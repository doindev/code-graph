param(
    [Parameter(Mandatory=$true)][string]$BaselineLib,
    [Parameter(Mandatory=$true)][string]$CandidateLib,
    [Parameter(Mandatory=$true)][string]$Dataset,
    [string]$OutputDirectory = 'docs/validation/dependency-precision',
    [int[]]$RunNumbers = @(1,2,3),
    [ValidateSet('baseline','candidate')][string[]]$Engines = @('baseline','candidate')
)
$ErrorActionPreference = 'Stop'
$benchmarkSource = (Resolve-Path (Join-Path $PSScriptRoot 'NavigationBenchmark.java')).Path
$benchmarkDataset = (Resolve-Path -LiteralPath $Dataset).Path
$benchmarkOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $benchmarkOutput -Force | Out-Null
# Alternate old/new engines to reduce ordering bias. Each process has a new application cache,
# but the operating-system file cache is neither cleared nor claimed to be cold.
foreach ($run in $RunNumbers) {
    $engineOrder = @($Engines)
    if ($run % 2 -eq 0) { [array]::Reverse($engineOrder) }
    foreach ($engine in $engineOrder) {
        $library = if ($engine -eq 'baseline') { $BaselineLib } else { $CandidateLib }
        $library = (Resolve-Path -LiteralPath $library).Path
        $stdout = Join-Path $benchmarkOutput ("engine-$engine-$run.json")
        $stderr = Join-Path $benchmarkOutput ("engine-$engine-$run.stderr.log")
        $arguments = @('-Xmx768m','--enable-native-access=ALL-UNNAMED','-cp',('"' + $library + '\*"'),('"' + $benchmarkSource + '"'),('"' + $benchmarkDataset + '"'),'hybrid')
        $process = Start-Process java -WindowStyle Hidden -PassThru -ArgumentList $arguments -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $peakWorkingSet = 0L
        $deadline = [DateTime]::UtcNow.AddMinutes(10)
        try {
            while (-not $process.HasExited) {
                $process.Refresh()
                $peakWorkingSet = [Math]::Max($peakWorkingSet, $process.WorkingSet64)
                if ([DateTime]::UtcNow -gt $deadline) { throw 'Benchmark exceeded ten-minute deadline' }
                Start-Sleep -Milliseconds 250
            }
            $process.WaitForExit()
            if ($process.ExitCode -ne 0) { throw "Benchmark failed: $engine run $run; inspect $stderr" }
            $result = Get-Content -LiteralPath $stdout -Raw | ConvertFrom-Json
            $result | Add-Member -NotePropertyName sampledPeakWorkingSetBytes -NotePropertyValue $peakWorkingSet
            $result | Add-Member -NotePropertyName engineLabel -NotePropertyValue $engine
            $result | Add-Member -NotePropertyName run -NotePropertyValue $run
            $result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $stdout -Encoding utf8
            Write-Output ("{0} run {1}: index {2} ms; {3} caller occurrences; peak working set {4} bytes" -f $engine,$run,[Math]::Round($result.indexMs),$result.callerOccurrences,$peakWorkingSet)
        } finally {
            if (-not $process.HasExited) { Stop-Process -Id $process.Id }
            $process.Dispose()
        }
    }
}
