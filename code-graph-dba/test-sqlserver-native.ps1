param([Parameter(Mandatory=$true)][string]$DriverJar,[switch]$AcceptDeveloperEula)
$ErrorActionPreference='Stop'
if (-not $AcceptDeveloperEula) { throw 'Explicit -AcceptDeveloperEula is required for disposable development tests.' }
$driverPath=(Resolve-Path -LiteralPath $DriverJar).Path
$image='mcr.microsoft.com/mssql/server@sha256:4402d880dd4c34bfa7d8705e56a86cd6c88da80a1f6bbbe741f999e76264a090'
$prior=@(docker image ls --no-trunc --format '{{.ID}}')
$owner='cgraph-sqlserver-'+[guid]::NewGuid().ToString('N')
$container=$null
$imageId=$null
try {
    docker pull $image
    if ($LASTEXITCODE -ne 0) { throw 'SQL Server image download failed.' }
    $imageId=docker image inspect $image --format '{{.Id}}'
    $digest=docker image inspect $image --format '{{index .RepoDigests 0}}'
    $env:MSSQL_SA_PASSWORD='Cg!'+[guid]::NewGuid().ToString('N')+'9a'
    $container=docker run -d --name $owner --label "io.doindev.codegraph.test=$owner" --memory 3g --cpus 2 -p 127.0.0.1::1433 -e ACCEPT_EULA=Y -e MSSQL_PID=Developer -e MSSQL_MEMORY_LIMIT_MB=2048 -e MSSQL_SA_PASSWORD $digest
    if ($LASTEXITCODE -ne 0) { throw 'SQL Server container startup failed.' }
    $env:CG_SQLSERVER_OWNER=$owner
    $env:CG_SQLSERVER_PASSWORD=$env:MSSQL_SA_PASSWORD
    $env:CG_SQLSERVER_JAR=$driverPath
    $port=(docker port $container 1433/tcp).Trim().Split(':')[-1]
    $env:CG_SQLSERVER_URL="jdbc:sqlserver://127.0.0.1:$port;databaseName=master;encrypt=true;trustServerCertificate=true;loginTimeout=5"
    Write-Output "Owned SQL Server fixture: $owner; image $digest; loopback port $port"
    $deadline=[DateTime]::UtcNow.AddSeconds(120)
    do {
        $ready=(docker logs $container 2>&1 | Out-String).Contains('SQL Server is now ready for client connections')
        if($ready){break}
        if([DateTime]::UtcNow -gt $deadline){throw 'SQL Server readiness timed out.'}
        Start-Sleep -Milliseconds 500
    } while($true)
    mvn -q -pl code-graph-dba -am test '-Dtest=SqlServerNativeDeliveryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true'
    if($LASTEXITCODE -ne 0){throw 'SQL Server native delivery gate failed.'}
} finally {
    if($container){
        $label=docker inspect $container --format '{{index .Config.Labels "io.doindev.codegraph.test"}}'
        if($label -eq $owner){docker rm --force --volumes $container}
        else{Write-Warning 'Ownership mismatch: refusing container cleanup.'}
    }
    if($imageId -and $prior -notcontains $imageId){
        $users=@(docker ps -aq --filter "ancestor=$imageId")
        if($users.Count -eq 0){docker image rm $image}
        else{Write-Warning 'New image remains in use; preserving it.'}
    }
    foreach($key in @('MSSQL_SA_PASSWORD','CG_SQLSERVER_PASSWORD','CG_SQLSERVER_JAR','CG_SQLSERVER_OWNER','CG_SQLSERVER_URL')){
        Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
    }
}
