param([Parameter(Mandatory)][string]$BuildRoot,[string[]]$Vendors=@('postgresql','mysql','mariadb'))
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'test-docker-resources.ps1')
$ownedRun='cgraph-compare-qa-'+[guid]::NewGuid().ToString('N')
$scope=New-CgraphDockerScope -Owner $ownedRun -Label 'codegraph.compare.owner'
$previous=@{}
$keys=@('DBA_COMPARE_DISPOSABLE','DBA_COMPARE_VENDOR','DBA_COMPARE_SOURCE','DBA_COMPARE_DESTINATION','DBA_COMPARE_USER','DBA_COMPARE_JAR','DBA_COMPARE_EVIDENCE')
foreach($key in $keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key)}
try {
 foreach($vendor in $Vendors){
  $image=switch($vendor){'postgresql'{'postgres:16'} 'mysql'{'mysql:8.4'} 'mariadb'{'mariadb:11.4'} default{throw 'Unknown vendor'}}
  $imageId=Get-CgraphDockerImage -Scope $scope -Reference $image
  $port=if($vendor -eq 'postgresql'){5432}else{3306}
  $urls=@{}
  foreach($side in @('source','destination')){
   $name="$ownedRun-$vendor-$side"
   $args=@('run','--detach','--name',$name,'--label',"codegraph.compare.owner=$ownedRun",'-p',('127.0.0.1::'+$port))
   if($vendor -eq 'postgresql'){$args+=@('-e','POSTGRES_PASSWORD=compare-fixture-only','-e','POSTGRES_DB=compare_test')}
   elseif($vendor -eq 'mysql'){$args+=@('-e','MYSQL_ROOT_PASSWORD=compare-fixture-only','-e','MYSQL_DATABASE=compare_test')}
   else{$args+=@('-e','MARIADB_ROOT_PASSWORD=compare-fixture-only','-e','MARIADB_DATABASE=compare_test')}
   $args+=$imageId;$scope.Containers+=$name
   docker @args | Out-Host
   if($LASTEXITCODE -ne 0){throw "Could not start $name"}
   $until=[DateTime]::UtcNow.AddMinutes(3)
   do {
    if($vendor -eq 'postgresql'){docker exec $name pg_isready -U postgres *> $null}
    elseif($vendor -eq 'mysql'){docker exec $name mysqladmin --connect-timeout=3 --protocol=TCP --host=127.0.0.1 -uroot -pcompare-fixture-only ping *> $null}
    else{docker exec $name mariadb-admin --connect-timeout=3 --protocol=TCP --host=127.0.0.1 -uroot -pcompare-fixture-only ping *> $null}
    $ready=$LASTEXITCODE -eq 0
    if(!$ready){Start-Sleep -Milliseconds 500}
   }while(!$ready -and [DateTime]::UtcNow -lt $until)
   if(!$ready){throw "Startup timed out for $name"}
   $mapping=(docker port $name ($port.ToString()+'/tcp')).Trim()
   if($mapping -notmatch '^127\.0\.0\.1:(\d+)$'){throw 'Unexpected Docker port mapping'}
   $urls[$side]='jdbc:'+$vendor+'://127.0.0.1:'+$Matches[1]+'/compare_test'
   if($vendor -eq 'mysql'){$urls[$side]+='?allowPublicKeyRetrieval=true&useSSL=false'}
  }
  $env:DBA_COMPARE_DISPOSABLE=$ownedRun;$env:DBA_COMPARE_VENDOR=$vendor
  $env:DBA_COMPARE_SOURCE=$urls.source;$env:DBA_COMPARE_DESTINATION=$urls.destination
  $env:DBA_COMPARE_USER=if($vendor -eq 'postgresql'){'postgres'}else{'root'}
  $jar=switch($vendor){'postgresql'{'org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar'} 'mysql'{'com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar'} 'mariadb'{'org/mariadb/jdbc/mariadb-java-client/3.5.7/mariadb-java-client-3.5.7.jar'}}
  $env:DBA_COMPARE_JAR=Join-Path $env:USERPROFILE ".m2/repository/$jar"
  if(!(Test-Path -LiteralPath $env:DBA_COMPARE_JAR)){throw "Missing test driver $jar"}
  $env:DBA_COMPARE_EVIDENCE=Join-Path $BuildRoot 'compare-evidence'
  mvn -o -B -f (Join-Path $BuildRoot 'pom.xml') -pl code-graph-dba -am test '-Dtest=CompareVendorIntegrationTest,CatalogScanVendorIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
  if($LASTEXITCODE -ne 0){throw "Comparison/catalog tests failed for $vendor"}
  Copy-Item -LiteralPath (Join-Path $BuildRoot 'code-graph-dba/target/surefire-reports/io.doindev.codegraph.dba.CompareVendorIntegrationTest.txt') -Destination (Join-Path $env:DBA_COMPARE_EVIDENCE "$vendor-results.txt")
 }
}finally{
 Remove-CgraphDockerResources -Scope $scope
 foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$previous[$key])}
 $current=@(docker image ls --no-trunc --quiet)
 foreach($id in $scope.Baseline){if($current -notcontains $id){Write-Warning "A pre-existing Docker image is no longer present: $id"}}
}
