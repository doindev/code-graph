param([string]$NodeModules)
$ErrorActionPreference = 'Stop'
if (-not $env:DBA_BROWSER_SUITE) {
    # Independent suites get independent runtimes, profiles and browser sessions.
    # A closed browser context must not require weakening production session limits.
    try {
        foreach ($dbaSuite in @('native','yolo','tree-context','workspace-toolbar','script-selection','grid','editable-grid','table-designer','view-query','query-builder','object-creation','object-designer','grid-edit','project-context','catalog','editor-pairing','approvals','approval-review','core')) {
            $env:DBA_BROWSER_SUITE = $dbaSuite
            & $PSCommandPath -NodeModules $NodeModules
        }
    } finally { Remove-Item Env:DBA_BROWSER_SUITE -ErrorAction SilentlyContinue }
    return
}
if ($NodeModules) { $env:NODE_PATH = $NodeModules }
$dbaRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dbaRun = New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot ('target/browser-' + [guid]::NewGuid().ToString('N')))
$dbaFixture = $null
Push-Location $dbaRoot
try {
    $dbaFixture = Start-Process java -WindowStyle Hidden -PassThru -ArgumentList @('--enable-native-access=ALL-UNNAMED','-cp','"code-graph-dba/target/test-classes;code-graph-dba/target/classes;code-graph-core/target/classes;code-graph-dba/target/test-lib/*"','io.doindev.codegraph.dba.BrowserFixture') -RedirectStandardOutput (Join-Path $dbaRun.FullName 'stdout.log') -RedirectStandardError (Join-Path $dbaRun.FullName 'stderr.log')
    $dbaDeadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $dbaLine = Get-Content (Join-Path $dbaRun.FullName 'stdout.log') -ErrorAction SilentlyContinue | Where-Object { $_ -like 'DBA_FIXTURE=*' } | Select-Object -First 1
        if ($dbaLine) { break }
        if ($dbaFixture.HasExited -or [DateTime]::UtcNow -gt $dbaDeadline) { throw 'Browser fixture failed; inspect target/browser-*/stderr.log' }
        Start-Sleep -Milliseconds 200
    } while ($true)
    $dbaInfo = $dbaLine.Substring(12) | ConvertFrom-Json
    node code-graph-dba/browser-smoke.cjs $dbaInfo.base $dbaInfo.jar $dbaInfo.schema
    if ($LASTEXITCODE -ne 0) { throw 'Browser checks failed' }
} finally {
    if ($dbaInfo) { try { Invoke-WebRequest -UseBasicParsing ($dbaInfo.base + '/__test/stop') | Out-Null } catch {} }
    if ($dbaFixture -and -not $dbaFixture.HasExited) { if (-not $dbaFixture.WaitForExit(10000)) { Stop-Process -Id $dbaFixture.Id } }
    Pop-Location
}
