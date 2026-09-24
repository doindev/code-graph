param([string]$BuildRoot=(Split-Path $PSScriptRoot), [string[]]$Vendors=@('postgresql','mysql','mariadb'), [switch]$Yolo)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'test-docker-resources.ps1')
$ownedRun='cgraph-reusable-qa-'+[guid]::NewGuid().ToString('N')
$previousEnv=@{}
$envNames=@('DBA_REUSABLE_DISPOSABLE','DBA_REUSABLE_VENDOR','DBA_REUSABLE_URL','DBA_REUSABLE_REHEARSAL_URL','DBA_REUSABLE_JAR','DBA_REUSABLE_USER','DBA_REUSABLE_PASSWORD')
foreach($key in $envNames){$previousEnv[$key]=[Environment]::GetEnvironmentVariable($key)}
try {
    foreach($vendor in $Vendors){
        $image=switch($vendor){'postgresql'{'postgres:16'} 'mysql'{'mysql:8.4'} 'mariadb'{'mariadb:11.4'} default{throw "Unknown fixture vendor: $vendor"}}
        $fixtureScope=New-CgraphDockerScope -Owner $ownedRun -Label 'codegraph.reusable.owner'
        try {
        $imageId=Get-CgraphDockerImage -Scope $fixtureScope -Reference $image
        $port=if($vendor -eq 'postgresql'){5432}else{3306}
        $ports=@{}
        foreach($role in @('source','rehearsal')){
            $name="$ownedRun-$vendor-$role"
            $arguments=@('run','--detach','--name',$name,'--label',"codegraph.reusable.owner=$ownedRun",'-p',('127.0.0.1::'+$port))
            if($vendor -eq 'postgresql'){$arguments+=@('-e','POSTGRES_PASSWORD=reusable-fixture-only','-e','POSTGRES_DB=approval_test')}
            elseif($vendor -eq 'mysql'){$arguments+=@('-e','MYSQL_ROOT_PASSWORD=reusable-fixture-only','-e','MYSQL_DATABASE=approval_test')}
            else{$arguments+=@('-e','MARIADB_ROOT_PASSWORD=reusable-fixture-only','-e','MARIADB_DATABASE=approval_test')}
            $arguments+=$imageId
            if($vendor -eq 'mysql'){$arguments+='--log-bin-trust-function-creators=ON'}
            $fixtureScope.Containers+=$name
            docker @arguments
            if($LASTEXITCODE -ne 0){throw "Could not start $name"}
            $deadline=[DateTime]::UtcNow.AddMinutes(3)
            do{
                if($vendor -eq 'postgresql'){docker exec $name pg_isready -U postgres *> $null}
                elseif($vendor -eq 'mysql'){docker exec $name mysqladmin --connect-timeout=3 --protocol=TCP '--host=127.0.0.1' -uroot -preusable-fixture-only ping *> $null}
                else{docker exec $name mariadb-admin --connect-timeout=3 --protocol=TCP '--host=127.0.0.1' -uroot -preusable-fixture-only ping *> $null}
                $ready=$LASTEXITCODE -eq 0
                if(!$ready){Start-Sleep -Milliseconds 500}
            }while(!$ready -and [DateTime]::UtcNow -lt $deadline)
            if(!$ready){throw "Database startup failed: $name"}
            $mapping=(docker port $name ($port.ToString()+'/tcp')).Trim()
            if($mapping -notmatch '^127\.0\.0\.1:(\d+)$'){throw "Unexpected loopback port mapping: $mapping"}
            $ports[$role]=$Matches[1]
        }
        $env:DBA_REUSABLE_DISPOSABLE=$ownedRun
        $env:DBA_REUSABLE_VENDOR=$vendor
        $env:DBA_REUSABLE_PASSWORD='reusable-fixture-only'
        $env:DBA_REUSABLE_USER=if($vendor -eq 'postgresql'){'postgres'}else{'root'}
        $env:DBA_REUSABLE_URL='jdbc:'+$vendor+'://127.0.0.1:'+$ports.source+'/approval_test'
        $env:DBA_REUSABLE_REHEARSAL_URL='jdbc:'+$vendor+'://127.0.0.1:'+$ports.rehearsal+'/approval_test'
        if($vendor -eq 'mysql'){$env:DBA_REUSABLE_URL+='?allowPublicKeyRetrieval=true&useSSL=false';$env:DBA_REUSABLE_REHEARSAL_URL+='?allowPublicKeyRetrieval=true&useSSL=false'}
        $jar=switch($vendor){
            'postgresql'{'org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar'}
            'mysql'{'com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar'}
            'mariadb'{'org/mariadb/jdbc/mariadb-java-client/3.5.7/mariadb-java-client-3.5.7.jar'}
        }
        $env:DBA_REUSABLE_JAR=Join-Path $env:USERPROFILE ".m2/repository/$jar"
        if(!(Test-Path -LiteralPath $env:DBA_REUSABLE_JAR)){throw "Install the test-only JDBC dependency first: $jar"}
        $testNames=if($Yolo){'ReusableVendorIntegrationTest,WorkflowVendorIntegrationTest,YoloVendorIntegrationTest'}else{'ReusableVendorIntegrationTest,ReadPermissionVendorIntegrationTest,WorkflowVendorIntegrationTest'}
        & mvn -q -f (Join-Path $BuildRoot 'pom.xml') -pl code-graph-dba -am test ('-Dtest='+$testNames) '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true'
        if($LASTEXITCODE -ne 0){throw "Reusable integration tests failed for $vendor"}
        $reportDir=Join-Path $BuildRoot 'code-graph-dba/target/surefire-reports'
        Copy-Item -LiteralPath (Join-Path $reportDir 'io.doindev.codegraph.dba.ReusableVendorIntegrationTest.txt') -Destination (Join-Path $reportDir ("reusable-$vendor.txt"))
        Copy-Item -LiteralPath (Join-Path $reportDir 'io.doindev.codegraph.dba.WorkflowVendorIntegrationTest.txt') -Destination (Join-Path $reportDir ("workflow-$vendor.txt"))
        if(!$Yolo){Copy-Item -LiteralPath (Join-Path $reportDir 'io.doindev.codegraph.dba.ReadPermissionVendorIntegrationTest.txt') -Destination (Join-Path $reportDir ("read-permissions-$vendor.txt"))}
        if($Yolo){Copy-Item -LiteralPath (Join-Path $reportDir 'io.doindev.codegraph.dba.YoloVendorIntegrationTest.txt') -Destination (Join-Path $reportDir ("yolo-$vendor.txt"))}
        Write-Output "Reusable approval verification passed: $vendor"
        } finally { Remove-CgraphDockerResources -Scope $fixtureScope }
    }
} finally {
    foreach($key in $envNames){[Environment]::SetEnvironmentVariable($key,$previousEnv[$key])}
}
