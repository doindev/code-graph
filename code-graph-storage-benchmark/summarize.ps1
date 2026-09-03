param(
    [Parameter(Mandatory)][string]$ResultsDirectory,
    [string]$BaselineDirectory
)
$ErrorActionPreference = 'Stop'
$benchResults = (Resolve-Path -LiteralPath $ResultsDirectory).Path
$benchManifest = @(Get-Content -LiteralPath (Join-Path $benchResults 'manifest.json') -Raw | ConvertFrom-Json)
function Median($values) {
    $sorted = @($values | Where-Object { $null -ne $_ } | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2
}
function MiB($value) { if ($null -eq $value) { return $null }; return [Math]::Round($value / 1MB, 3) }
$baseline = @{}
$scanRows = [Collections.Generic.List[object]]::new()
$baselineRoots = @($benchResults)
if ($BaselineDirectory) { $baselineRoots += (Resolve-Path -LiteralPath $BaselineDirectory).Path }
foreach ($baselineRoot in $baselineRoots) {
    foreach ($file in Get-ChildItem -LiteralPath $baselineRoot -Filter '*-memory-*.json') {
        $data = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
        if ($data.status -eq 'completed') { $baseline[$data.scenario] = $data }
    }
}
$rows = foreach ($entry in $benchManifest) {
    $file = Join-Path $benchResults "$($entry.name).json"
    $data = if (Test-Path -LiteralPath $file) { Get-Content -LiteralPath $file -Raw | ConvertFrom-Json } else { $null }
    $reference = $baseline[$entry.scenario]
    $equivalence = if (-not $data.queryFingerprint) { 'unavailable' }
        elseif (-not $reference) { 'no-baseline' }
        elseif ($data.queryFingerprint -eq $reference.queryFingerprint -and
            $data.finalGraphCounts.symbols -eq $reference.finalGraphCounts.symbols -and
            $data.finalGraphCounts.edges -eq $reference.finalGraphCounts.edges -and
            $data.finalGraphCounts.files -eq $reference.finalGraphCounts.files) { 'matched' } else { 'FAILED' }
    $stores = @($data.afterLoadStore, $data.beforeConcurrentStore, $data.finalStore, $data.afterRemovalStore,
        $data.resize.afterReduction, $data.resize.afterRestore)
    foreach ($scan in $data.scanRecovery) { $stores += @($scan.beforeScanStore, $scan.afterScanStore, $scan.recoveredStore) }
    $cycle=0
    foreach ($scan in $data.scanRecovery) {
        $scanRows.Add([pscustomobject][ordered]@{name=$entry.name; backend=$entry.backend; scenario=$entry.scenario; cycle=++$cycle;
            scanMs=$scan.scanMs; beforeP99Us=$scan.beforeScanHotWindow.p99Us;
            immediateP99Us=$scan.immediatelyAfterScan.p99Us; recoveryP99Us=$scan.nextHotWindow.p99Us;
            scanCacheMisses=if($entry.backend -eq 'rocksdb'){$scan.afterScanStore.cacheMisses-$scan.beforeScanStore.cacheMisses}else{$null};
            postScanHotCacheMisses=if($entry.backend -eq 'rocksdb'){$scan.recoveredStore.cacheMisses-$scan.afterScanStore.cacheMisses}else{$null};
            scanFileReads=if($entry.backend -eq 'mvstore'){$scan.afterScanStore.storeReadOperations-$scan.beforeScanStore.storeReadOperations}else{$null};
            postScanHotFileReads=if($entry.backend -eq 'mvstore'){$scan.recoveredStore.storeReadOperations-$scan.afterScanStore.storeReadOperations}else{$null}
        })
    }
    $maxAccounted = ($stores.accountedResidencyEstimateBytes | Measure-Object -Maximum).Maximum
    $maxOvershoot = ($stores.accountedEstimateOverBudgetBytes | Measure-Object -Maximum).Maximum
    $budgetGate = if ($entry.backend -eq 'memory') { 'not-enforced' }
        elseif ($maxOvershoot -gt 0) { 'FAILED-observed-overshoot' } else { 'unproven-incomplete-accounting' }
    $successful = $entry.exitCode -eq 0 -and $data.status -eq 'completed' -and -not $entry.terminationReason
    [pscustomobject][ordered]@{
        name=$entry.name; backend=$entry.backend; scenario=$entry.scenario; trial=$entry.trial
        completed=$successful; status=$data.status; error=$data.error; terminationReason=$entry.terminationReason
        equivalence=$equivalence; budgetGate=$budgetGate; resize=$data.resize.status; cleanup=$data.cleanup
        budgetMiB=(MiB $data.budgetBytes); heapLimitMiB=(MiB $data.beforeLoadMemory.heapMaxBytes)
        loadMs=$data.loadMs; removalMs=$data.removalMs; totalSeconds=$entry.elapsedSeconds
        hotP50Us=$data.timings.hotPoint.p50Us; hotP95Us=$data.timings.hotPoint.p95Us; hotP99Us=$data.timings.hotPoint.p99Us
        hotOpsSec=$data.timings.hotPoint.operationsPerSecond
        callerP99Us=$data.timings.callers.p99Us; impactP99Us=$data.timings.impact.p99Us; searchP50Us=$data.timings.symbolSearch.p50Us
        scanMs=(Median $data.scanRecovery.scanMs); preScanP99Us=(Median $data.scanRecovery.beforeScanHotWindow.p99Us)
        postScanP99Us=(Median $data.scanRecovery.immediatelyAfterScan.p99Us); recoveryP99Us=(Median $data.scanRecovery.nextHotWindow.p99Us)
        peakProcessMiB=(MiB $entry.peakWorkingSetBytes); peakPrivateMiB=(MiB $entry.peakPrivateBytes)
        peakHeapMiB=(MiB ((@($data.finalMemory.peakSampledHeapBytes,$data.retainedAfterRemoval.memory.peakSampledHeapBytes) | Measure-Object -Maximum).Maximum))
        retainedBeforeResizeMiB=(MiB $data.retainedBeforeResize.memory.heapUsedBytes)
        retainedAfterResizeMiB=(MiB $data.retainedAfterQueries.memory.heapUsedBytes)
        retainedAfterRemovalMiB=(MiB $data.retainedAfterRemoval.memory.heapUsedBytes)
        maxCheckpointAccountedMiB=(MiB $maxAccounted); maxCheckpointOvershootMiB=(MiB $maxOvershoot)
        peakDirtyEstimateMiB=(MiB $data.afterRemovalStore.peakDirtyBytesEstimate)
        gcMs=if($null -ne $data.retainedAfterRemoval.memory.gcTimeMs){$data.retainedAfterRemoval.memory.gcTimeMs}else{$data.finalMemory.gcTimeMs}; diskMiB=(MiB $data.diskBytes)
        concurrentReadUpdateMs=$data.concurrentReadUpdateMs
        graphCounts=$data.finalGraphCounts; fingerprint=$data.queryFingerprint
    }
}
$rows | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $benchResults 'summary-trials.json') -Encoding utf8
$rows | Export-Csv -LiteralPath (Join-Path $benchResults 'summary-trials.csv') -NoTypeInformation
$scanRows | Export-Csv -LiteralPath (Join-Path $benchResults 'scan-evidence.csv') -NoTypeInformation
$groups = foreach ($group in $rows | Group-Object scenario,backend) {
    $passed = @($group.Group | Where-Object completed)
    $item = [ordered]@{ scenario=$group.Group[0].scenario; backend=$group.Group[0].backend; trials=$group.Count;
        completed=$passed.Count; equivalence=@($group.Group.equivalence | Sort-Object -Unique);
        budgetGate=@($group.Group.budgetGate | Sort-Object -Unique); resize=@($group.Group.resize | Sort-Object -Unique);
        worstObservedOvershootMiB=($group.Group.maxCheckpointOvershootMiB | Measure-Object -Maximum).Maximum }
    foreach ($metric in @('loadMs','removalMs','hotP50Us','hotP95Us','hotP99Us','hotOpsSec','callerP99Us','impactP99Us','searchP50Us',
            'scanMs','preScanP99Us','postScanP99Us','recoveryP99Us','peakProcessMiB','peakPrivateMiB','peakHeapMiB',
            'retainedBeforeResizeMiB','retainedAfterResizeMiB','retainedAfterRemovalMiB','maxCheckpointAccountedMiB',
            'maxCheckpointOvershootMiB','peakDirtyEstimateMiB','gcMs','diskMiB','concurrentReadUpdateMs')) {
        $item[$metric] = Median $passed.$metric
    }
    [pscustomobject]$item
}
$groups | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $benchResults 'summary-groups.json') -Encoding utf8
$groups | Format-Table scenario,backend,completed,loadMs,hotP99Us,retainedBeforeResizeMiB,peakProcessMiB,budgetGate -AutoSize
if (@($rows | Where-Object equivalence -eq 'FAILED').Count -gt 0) { throw 'Query equivalence failed; see raw results.' }
