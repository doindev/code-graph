param([Parameter(Mandatory)][string]$BuildRoot,[string[]]$Vendors=@('postgresql','mysql','mariadb','sqlserver'),[switch]$AcceptSqlServerDeveloperEula)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'test-docker-resources.ps1')
$owner='cgraph-grid-qa-'+[guid]::NewGuid().ToString('N')
$keys=@('DBA_GRID_OWNER','DBA_GRID_VENDOR','DBA_GRID_URL','DBA_GRID_JAR','DBA_GRID_DRIVER','DBA_GRID_USER','DBA_GRID_PASSWORD')
$prior=@{};foreach($key in $keys){$prior[$key]=[Environment]::GetEnvironmentVariable($key)}
try {
  foreach($vendor in $Vendors){
    if($vendor -eq 'sqlserver' -and !$AcceptSqlServerDeveloperEula){throw 'SQL Server requires explicit -AcceptSqlServerDeveloperEula for disposable development tests.'}
    $scope=New-CgraphDockerScope -Owner $owner -Label 'codegraph.grid.owner'
    try {
      $recipe=switch($vendor){
        'postgresql'{@('postgres:16',5432,'org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar','org.postgresql.Driver','postgres')}
        'mysql'{@('mysql:8.4',3306,'com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar','com.mysql.cj.jdbc.Driver','root')}
        'mariadb'{@('mariadb:11.4',3306,'org/mariadb/jdbc/mariadb-java-client/3.5.7/mariadb-java-client-3.5.7.jar','org.mariadb.jdbc.Driver','root')}
        'sqlserver'{@('mcr.microsoft.com/mssql/server@sha256:4402d880dd4c34bfa7d8705e56a86cd6c88da80a1f6bbbe741f999e76264a090',1433,'com/microsoft/sqlserver/mssql-jdbc/13.4.0.jre11/mssql-jdbc-13.4.0.jre11.jar','com.microsoft.sqlserver.jdbc.SQLServerDriver','sa')}
        default{throw 'Unknown vendor'}
      }
      $env:DBA_GRID_JAR=Join-Path $env:USERPROFILE ('.m2/repository/'+$recipe[2])
      if(!(Test-Path -LiteralPath $env:DBA_GRID_JAR)){throw "Missing test JDBC JAR: $($recipe[2])"}
      $image=Get-CgraphDockerImage -Scope $scope -Reference $recipe[0];$name="$owner-$vendor";$scope.Containers+=$name
      $password='Grid!'+[guid]::NewGuid().ToString('N')+'8a'
      $dockerArgs=@('run','--detach','--name',$name,'--label',"codegraph.grid.owner=$owner",'--memory','3g','--cpus','2','-p',('127.0.0.1::'+$recipe[1]))
      switch($vendor){
        'postgresql'{$dockerArgs+=@('-e',"POSTGRES_PASSWORD=$password",'-e','POSTGRES_DB=grid_test')}
        'mysql'{$dockerArgs+=@('-e',"MYSQL_ROOT_PASSWORD=$password",'-e','MYSQL_DATABASE=grid_test')}
        'mariadb'{$dockerArgs+=@('-e',"MARIADB_ROOT_PASSWORD=$password",'-e','MARIADB_DATABASE=grid_test')}
        'sqlserver'{$dockerArgs+=@('-e',"MSSQL_SA_PASSWORD=$password",'-e','ACCEPT_EULA=Y','-e','MSSQL_PID=Developer','-e','MSSQL_MEMORY_LIMIT_MB=2048')}
      }
      $dockerArgs+=$image;docker @dockerArgs|Out-Host;if($LASTEXITCODE){throw 'Fixture launch failed'}
      $until=[DateTime]::UtcNow.AddMinutes(3)
      do {
        $logs=(docker logs $name 2>&1|Out-String)
        $ready=switch($vendor){'postgresql'{$logs -match 'database system is ready to accept connections' -and $logs -match 'init process complete'}'mysql'{$logs -match "port: 3306"}'mariadb'{$logs -match "port: 3306"}'sqlserver'{$logs.Contains('SQL Server is now ready for client connections')}}
        if(!$ready){Start-Sleep -Milliseconds 500}
      }while(!$ready -and [DateTime]::UtcNow -lt $until)
      if(!$ready){throw "Fixture readiness failed: $vendor"}
      $mapping=(docker port $name ($recipe[1].ToString()+'/tcp')).Trim();if($mapping -notmatch '^127\.0\.0\.1:(\d+)$'){throw 'Expected loopback port'}
      $port=$Matches[1];$env:DBA_GRID_OWNER=$owner;$env:DBA_GRID_VENDOR=$vendor;$env:DBA_GRID_DRIVER=$recipe[3];$env:DBA_GRID_USER=$recipe[4];$env:DBA_GRID_PASSWORD=$password
      $env:DBA_GRID_URL=if($vendor -eq 'sqlserver'){"jdbc:sqlserver://127.0.0.1:$port;databaseName=master;encrypt=true;trustServerCertificate=true;loginTimeout=5"}else{'jdbc:'+$vendor+'://127.0.0.1:'+$port+'/grid_test'}
      if($vendor -eq 'mysql'){$env:DBA_GRID_URL+='?allowPublicKeyRetrieval=true&useSSL=false'}
      & mvn -B -ntp -f (Join-Path $BuildRoot 'pom.xml') -pl code-graph-dba -am test '-Dtest=GridVendorTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true'
      if($LASTEXITCODE){throw "Grid vendor tests failed: $vendor"}
      $reports=Join-Path $BuildRoot 'code-graph-dba/target/surefire-reports'
      Copy-Item -LiteralPath (Join-Path $reports 'io.doindev.codegraph.dba.GridVendorTest.txt') -Destination (Join-Path $reports "grid-$vendor.txt")
      Write-Output "GRID_IMAGE $vendor $image"
    }finally{Remove-CgraphDockerResources -Scope $scope}
  }
}finally{foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$prior[$key])}}
