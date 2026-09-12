$ErrorActionPreference = 'Stop'
$dbaTestName = 'code-graph-dba-vault-' + [guid]::NewGuid().ToString('N')
$dbaBuilt = $false
try {
    docker build --label "code-graph.dba.test=$dbaTestName" -f (Join-Path $PSScriptRoot 'Dockerfile.vault') -t $dbaTestName $PSScriptRoot
    if ($LASTEXITCODE -ne 0) { throw 'Linux vault fixture build failed' }
    $dbaBuilt = $true
    docker run --rm --name $dbaTestName --label "code-graph.dba.test=$dbaTestName" --memory 512m --cpus 2 $dbaTestName
    if ($LASTEXITCODE -ne 0) { throw 'Linux vault fixture test failed' }
} finally {
    if ($dbaBuilt) {
        $dbaLabels = docker image inspect --format '{{json .Config.Labels}}' $dbaTestName | ConvertFrom-Json
        $dbaOwner = $dbaLabels.'code-graph.dba.test'
        if ($dbaOwner -eq $dbaTestName) { docker image rm $dbaTestName | Out-Null }
        else { Write-Warning 'Ownership mismatch: refusing image cleanup' }
    }
}
