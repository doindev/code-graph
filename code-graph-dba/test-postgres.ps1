param([switch]$Browser, [string]$NodeModules)
$ErrorActionPreference = 'Stop'
$dbaTestName = 'code-graph-dba-test-' + [guid]::NewGuid().ToString('N')
$dbaPassword = [guid]::NewGuid().ToString('N')
$dbaWorkspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dbaCreated = $false
try {
    docker run --detach --rm --name $dbaTestName --label "code-graph.dba.test=$dbaTestName" --memory 512m --cpus 2 --tmpfs /var/lib/postgresql/data -e "POSTGRES_PASSWORD=$dbaPassword" -p '127.0.0.1::5432' postgres:16 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create disposable PostgreSQL container' }
    $dbaCreated = $true
    $dbaDeadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        docker exec $dbaTestName pg_isready -U postgres *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ([DateTime]::UtcNow -gt $dbaDeadline) { throw 'PostgreSQL readiness timeout' }
        Start-Sleep -Milliseconds 500
    } while ($true)
    $dbaBinding = docker port $dbaTestName 5432/tcp
    if ($dbaBinding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected PostgreSQL port binding' }
    $env:DBA_TEST_URL = 'jdbc:postgresql://127.0.0.1:' + $Matches[1] + '/postgres'
    $env:DBA_TEST_PASSWORD = $dbaPassword
    $env:DBA_TEST_DISPOSABLE = $dbaTestName
    Push-Location $dbaWorkspace
    try {
        mvn -pl code-graph-dba -am test -q; if ($LASTEXITCODE -ne 0) { throw 'DBA PostgreSQL tests failed' }
        if ($Browser) {
            mvn -pl code-graph-dba dependency:copy-dependencies '-DincludeScope=test' '-DoutputDirectory=target/test-lib' -q
            if ($LASTEXITCODE -ne 0) { throw 'Could not prepare browser dependencies' }
            & (Join-Path $PSScriptRoot 'test-browser.ps1') -NodeModules $NodeModules
        }
    }
    finally { Pop-Location }
} finally {
    Remove-Item Env:DBA_TEST_URL,Env:DBA_TEST_PASSWORD,Env:DBA_TEST_DISPOSABLE -ErrorAction SilentlyContinue
    if ($dbaCreated) {
        $dbaLabels = docker inspect --format '{{json .Config.Labels}}' $dbaTestName | ConvertFrom-Json
        $dbaOwner = $dbaLabels.'code-graph.dba.test'
        if ($dbaOwner -eq $dbaTestName) { docker rm --force $dbaTestName | Out-Null }
        else { Write-Warning 'Ownership mismatch: refusing container cleanup' }
    }
}
