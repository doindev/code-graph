param(
    [string]$OutputDirectory = "$PSScriptRoot/results",
    [int]$Runs = 3,
    [int]$Queries = 1000,
    [int]$TimeoutSeconds = 300,
    [string[]]$Backends = @('memory', 'mvstore', 'rocksdb'),
    [string[]]$Scenarios = @('fit', 'pressure', 'multi', 'repository'),
    [int]$Nodes = 100000,
    [int]$Edges = 1000000,
    [ValidateRange(128,1536)][int]$HeapMiB = 1536
)
$ErrorActionPreference = 'Stop'
if ($Runs -lt 3) { throw 'At least three independent trials are required.' }
$benchRoot = (Resolve-Path -LiteralPath "$PSScriptRoot/..").Path
$benchOutput = [IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $benchOutput -Force
$benchJava = (Get-Command java).Source
$benchClassPath = "$PSScriptRoot/target/classes;$PSScriptRoot/target/lib/*"
$benchManifest = [Collections.Generic.List[object]]::new()
foreach ($scenario in $Scenarios) {
    if ($scenario -notin @('fit','pressure','multi','repository')) { throw "Unknown scenario $scenario" }
    foreach ($backend in $Backends) {
        if ($backend -notin @('memory','mvstore','rocksdb')) { throw "Unknown backend $backend" }
        for ($trial = 1; $trial -le $Runs; $trial++) {
            $benchName = "$scenario-$backend-$trial"
            $benchJson = Join-Path $benchOutput "$benchName.json"
            if (Test-Path -LiteralPath $benchJson) { throw "Refusing to overwrite existing trial $benchJson" }
            $benchBudget = if ($scenario -in @('pressure','multi')) { 32 } else { 1024 }
            $benchProjects = if ($scenario -eq 'multi') { 4 } else { 1 }
            $benchNodeCount = [int]($Nodes / $benchProjects)
            $benchEdgeCount = [int]($Edges / $benchProjects)
            $benchArgs = @("-Xmx${HeapMiB}m",'-XX:NativeMemoryTracking=summary','--enable-native-access=ALL-UNNAMED',
                '-cp', "`"$benchClassPath`"", 'io.doindev.codegraph.bench.BenchMain',
                '--backend',$backend,'--scenario',$scenario,'--budget-mib',$benchBudget,
                '--nodes',$benchNodeCount,'--edges',$benchEdgeCount,'--projects',$benchProjects,
                '--queries',$Queries,'--root',"`"$benchRoot`"",'--output',"`"$benchJson`"")
            Write-Output "Running $benchName (cache $benchBudget MiB, projects $benchProjects)"
            $benchProcess = Start-Process -FilePath $benchJava -ArgumentList $benchArgs -WorkingDirectory $benchRoot -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput (Join-Path $benchOutput "$benchName.stdout.log") -RedirectStandardError (Join-Path $benchOutput "$benchName.stderr.log")
            $benchTimer = [Diagnostics.Stopwatch]::StartNew()
            $benchPeakWorkingSet = 0L
            $benchPeakPrivate = 0L
            $benchReason = $null
            while (-not $benchProcess.HasExited) {
                $benchProcess.Refresh()
                $benchPeakWorkingSet = [Math]::Max($benchPeakWorkingSet, $benchProcess.WorkingSet64)
                $benchPeakPrivate = [Math]::Max($benchPeakPrivate, $benchProcess.PrivateMemorySize64)
                if ($benchTimer.Elapsed.TotalSeconds -gt $TimeoutSeconds -or $benchPeakWorkingSet -gt 3GB) {
                    $benchReason = 'Trial exceeded time or 3 GiB working-set safety limit'
                    # Only terminate the exact process launched above; never enumerate/kill unrelated Java processes.
                    $benchProcess.Kill()
                    break
                }
                $null = $benchProcess.WaitForExit(100)
            }
            $benchProcess.WaitForExit()
            $benchProcess.Refresh()
            $benchExit = $benchProcess.ExitCode
            $benchEntry = [ordered]@{ name=$benchName; backend=$backend; scenario=$scenario; trial=$trial;
                exitCode=$benchExit; elapsedSeconds=$benchTimer.Elapsed.TotalSeconds; peakWorkingSetBytes=$benchPeakWorkingSet;
                peakPrivateBytes=$benchPeakPrivate; terminationReason=$benchReason; resultAvailable=(Test-Path -LiteralPath $benchJson);
                java=$benchJava; arguments=$benchArgs; processMemorySampleIntervalMs=100 }
            $benchManifest.Add($benchEntry)
            $benchManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $benchOutput 'manifest.json') -Encoding utf8
            if ($null -ne $benchReason) { Write-Warning "$benchName failed: $benchReason; scratch location, if any, is preserved in its logs." }
            $benchProcess.Dispose()
        }
    }
}
Write-Output "Trials finished. Raw measurements and failure manifest: $benchOutput"
