param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$benchRoot = (Resolve-Path -LiteralPath "$PSScriptRoot/..").Path
$benchOutput = [IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $benchOutput -Force
$benchOs = Get-CimInstance Win32_OperatingSystem
$benchCpu = Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors
$benchDisks = Get-CimInstance Win32_DiskDrive | Select-Object Model,MediaType,Size,InterfaceType
$benchSources = @(rg --files --hidden -g '*.java' -g '*.js' -g '*.jsx' -g '*.ts' -g '*.tsx' -g '*.mjs' -g '*.cjs' -g 'pom.xml' -g '!**/target/**' -g '!**/.git/**' $benchRoot | Sort-Object | ForEach-Object {
    [pscustomobject]@{ path=[IO.Path]::GetRelativePath($benchRoot,$_); sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
})
$benchLibraries = @(Get-ChildItem -LiteralPath "$PSScriptRoot/target/lib" -Filter '*.jar' | ForEach-Object {
    [pscustomobject]@{ name=$_.Name; bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
})
[ordered]@{ capturedAt=(Get-Date -Format o); os=$benchOs.Caption; osVersion=$benchOs.Version; architecture=$benchOs.OSArchitecture
    totalPhysicalBytes=([long]$benchOs.TotalVisibleMemorySize * 1024); freePhysicalBytes=([long]$benchOs.FreePhysicalMemory * 1024)
    cpu=$benchCpu; disks=$benchDisks; java=(& java -version 2>&1 | Out-String); maven=(& mvn -version 2>&1 | Out-String)
    sourceRevision=(& git -C $benchRoot rev-parse HEAD); sources=$benchSources; libraries=$benchLibraries
    notes='Shared developer workstation; no OS file-cache flush, CPU pinning, or exclusive machine isolation. Source hashes identify the workload beyond the base Git revision.'
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $benchOutput 'environment.json') -Encoding utf8
