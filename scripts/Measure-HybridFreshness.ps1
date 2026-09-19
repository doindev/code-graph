param(
    [Parameter(Mandatory=$true)][string]$Classpath,
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Output
)
$ErrorActionPreference = 'Stop'
$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$outputPath = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $outputPath) { throw 'Use a new output directory for each independent run' }
New-Item -ItemType Directory -Path $outputPath | Out-Null
$arguments = @('-Xmx768m','-XX:NativeMemoryTracking=summary','--enable-native-access=ALL-UNNAMED',
    '-cp',('"' + $Classpath + '"'),('"' + (Join-Path $PSScriptRoot 'ProfileHybridFreshness.java') + '"'),
    ('"' + $sourcePath + '"'),('"' + $outputPath + '"'))
$child = Start-Process -FilePath (Get-Command java).Source -ArgumentList $arguments -WindowStyle Hidden -RedirectStandardOutput (Join-Path $outputPath 'stdout.log') -RedirectStandardError (Join-Path $outputPath 'stderr.log') -PassThru
$clock = [Diagnostics.Stopwatch]::StartNew()
$samples = [Collections.Generic.List[object]]::new()
$nativeCaptured = $false
try {
    while (-not $child.HasExited) {
        $child.Refresh()
        $samples.Add([pscustomobject]@{elapsedMs=$clock.ElapsedMilliseconds;workingSetBytes=$child.WorkingSet64;
            privateCommittedBytes=$child.PrivateMemorySize64;peakWorkingSetBytes=$child.PeakWorkingSet64})
        $tail = Get-Content -LiteralPath (Join-Path $outputPath 'stdout.log') -Tail 5
        if (-not $nativeCaptured -and ($tail -match '^INDEXED=')) {
            & jcmd $child.Id VM.native_memory summary | Out-File -LiteralPath (Join-Path $outputPath 'native-memory.log')
            $nativeCaptured = $true
        }
        if ($clock.Elapsed.TotalMinutes -gt 15) { throw 'Owned profiling JVM exceeded its 15-minute deadline' }
        Start-Sleep -Milliseconds 1000
    }
    $child.WaitForExit()
    if ($child.ExitCode -ne 0) {
        Get-Content -LiteralPath (Join-Path $outputPath 'stderr.log')
        throw "Owned profiling JVM exited with code $($child.ExitCode)"
    }
} finally {
    if (-not $child.HasExited) { Stop-Process -Id $child.Id; $child.WaitForExit() }
    [pscustomobject]@{pid=$child.Id;classpath=$Classpath;source=$sourcePath;sampleIntervalMs=1000;note='Windows working set is resident process memory; private committed bytes are not RSS. Includes compilation, initial indexing, JFR and NMT overhead. Heap sampled separately in profile.json.';
        samples=$samples.ToArray()} | ConvertTo-Json -Depth 6 | Out-File -LiteralPath (Join-Path $outputPath 'process-memory.json')
}
Get-Content -LiteralPath (Join-Path $outputPath 'stdout.log') -Tail 4
