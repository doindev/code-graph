param([string]$BuildRoot=(Split-Path $PSScriptRoot),[string[]]$Vendors=@('postgresql','mysql','mariadb'),[string]$NodeModules,[switch]$Browser)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'test-docker-resources.ps1')
$ownedRun='cgraph-scheduler-qa-'+[guid]::NewGuid().ToString('N')
$names=@('DBA_SCHEDULER_DISPOSABLE','DBA_SCHEDULER_VENDOR','DBA_SCHEDULER_URL','DBA_SCHEDULER_JAR','DBA_SCHEDULER_USER','DBA_BROWSER_SUITE')
$previous=@{};foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name)}
try {
 foreach($vendor in $Vendors){
  $scope=New-CgraphDockerScope -Owner $ownedRun -Label 'codegraph.scheduler.owner'
  try {
   $image=Get-CgraphDockerImage -Scope $scope -Reference $(if($vendor -eq 'postgresql'){'postgres:16'}elseif($vendor -eq 'mysql'){'mysql:8.4'}else{'mariadb:11.4'})
   if($vendor -eq 'postgresql'){
    $context=New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot ('target/'+$ownedRun))
    @('FROM postgres:16','RUN apt-get update && apt-get install -y --no-install-recommends postgresql-16-cron && rm -rf /var/lib/apt/lists/*') | Set-Content (Join-Path $context.FullName 'Dockerfile')
    $tag=$ownedRun+':postgresql'
    docker build --label "codegraph.scheduler.owner=$ownedRun" -t $tag $context.FullName | Out-Host
    if($LASTEXITCODE -ne 0){throw 'Could not build disposable pg_cron fixture'}
    $image=(docker image inspect --format '{{.Id}}' $tag).Trim();$scope.Images+=$image
   }
   $port=if($vendor -eq 'postgresql'){5432}else{3306};$name=$ownedRun+'-'+$vendor;$scope.Containers+=$name
   $schedulerDockerArgs=@('run','--detach','--name',$name,'--label',"codegraph.scheduler.owner=$ownedRun",'-p',('127.0.0.1::'+$port))
   if($vendor -eq 'postgresql'){$schedulerDockerArgs+=@('-e','POSTGRES_PASSWORD=scheduler-fixture-only','-e','POSTGRES_DB=scheduler_test')}
   elseif($vendor -eq 'mysql'){$schedulerDockerArgs+=@('-e','MYSQL_ROOT_PASSWORD=scheduler-fixture-only','-e','MYSQL_DATABASE=scheduler_test')}
   else{$schedulerDockerArgs+=@('-e','MARIADB_ROOT_PASSWORD=scheduler-fixture-only','-e','MARIADB_DATABASE=scheduler_test')}
   $schedulerDockerArgs+=$image
   if($vendor -eq 'postgresql'){$schedulerDockerArgs+=@('-c','shared_preload_libraries=pg_cron','-c','cron.database_name=scheduler_test')}
   docker @schedulerDockerArgs | Out-Host;if($LASTEXITCODE -ne 0){throw 'Fixture startup failed'}
   $deadline=[DateTime]::UtcNow.AddMinutes(3)
   do{if($vendor -eq 'postgresql'){docker exec $name pg_isready -U postgres *> $null}elseif($vendor -eq 'mysql'){docker exec $name mysqladmin --protocol=TCP '--host=127.0.0.1' -uroot -pscheduler-fixture-only ping *> $null}else{docker exec $name mariadb-admin --protocol=TCP '--host=127.0.0.1' -uroot -pscheduler-fixture-only ping *> $null};$ready=$LASTEXITCODE -eq 0;if(!$ready){Start-Sleep -Milliseconds 500}}while(!$ready -and [DateTime]::UtcNow -lt $deadline)
   if(!$ready){throw 'Fixture did not become ready'}
   $mapping=(docker port $name ($port.ToString()+'/tcp')).Trim();if($mapping -notmatch '^127\.0\.0\.1:(\d+)$'){throw 'Invalid fixture port'}
   $env:DBA_SCHEDULER_DISPOSABLE=$ownedRun;$env:DBA_SCHEDULER_VENDOR=$vendor;$env:DBA_SCHEDULER_USER=if($vendor -eq 'postgresql'){'postgres'}else{'root'}
   $env:DBA_SCHEDULER_URL='jdbc:'+$vendor+'://127.0.0.1:'+$Matches[1]+'/scheduler_test'+$(if($vendor -eq 'mysql'){'?allowPublicKeyRetrieval=true&useSSL=false'}else{''})
   $driver=switch($vendor){'postgresql'{'org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar'}'mysql'{'com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar'}'mariadb'{'org/mariadb/jdbc/mariadb-java-client/3.5.7/mariadb-java-client-3.5.7.jar'}}
   $env:DBA_SCHEDULER_JAR=Join-Path $env:USERPROFILE ".m2/repository/$driver"
   & mvn -o -q -f (Join-Path $BuildRoot 'pom.xml') -pl code-graph-dba -am test '-Dtest=ScheduledJobsIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true'
   if($LASTEXITCODE -ne 0){throw "Scheduler integration failed: $vendor"}
   Copy-Item -LiteralPath (Join-Path $BuildRoot 'code-graph-dba/target/surefire-reports/io.doindev.codegraph.dba.ScheduledJobsIntegrationTest.txt') -Destination (Join-Path $BuildRoot "code-graph-dba/target/scheduler-$vendor.txt")
   Write-Output "Scheduled jobs live verification passed: $vendor"
   if($Browser){$env:DBA_BROWSER_SUITE='scheduled-jobs';& (Join-Path $PSScriptRoot 'test-browser.ps1') -NodeModules $NodeModules}
  }finally{Remove-CgraphDockerResources -Scope $scope}
 }
}finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
