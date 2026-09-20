param(
    [string]$Name = 'validation',
    [string]$Modules = 'code-graph-mcp-http',
    [string]$Tests = '',
    [switch]$Package,
    [switch]$All,
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$ownedRoot = Join-Path $repository 'target/mcp-efficiency-coverage'
if ($Name -notmatch '^[a-z0-9-]+$') { throw 'Name must use lowercase letters, digits and hyphens' }
$run = Join-Path $ownedRoot ($Name + '-' + [Guid]::NewGuid().ToString('N'))
$source = Join-Path $run 'source'
New-Item -ItemType Directory -Path $source -Force | Out-Null
$paths = @(& git -C $repository ls-files --cached --others --exclude-standard -- . ':(exclude)**/target/**' ':(exclude)target/**' ':(exclude)**/node_modules/**') | Sort-Object -Unique
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate isolated build inputs' }
foreach ($relative in $paths) {
    $original = Join-Path $repository $relative
    if (-not (Test-Path -LiteralPath $original -PathType Leaf)) { continue }
    $destination = Join-Path $source $relative
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
    Copy-Item -LiteralPath $original -Destination $destination
}
$arguments = @('-B', '-ntp', '-Djava.awt.headless=true', '-Dsurefire.failIfNoSpecifiedTests=false')
if (-not $All) { $arguments += @('-pl', $Modules, '-am') }
if ($Tests) { $arguments += "-Dtest=$Tests" }
if ($SkipTests) { $arguments += '-DskipTests' }
$arguments += $(if ($Package) { 'package' } else { 'test' })
Write-Output "Isolated source: $source"
Write-Output "Build log: $(Join-Path $run 'maven.log')"
Push-Location $source
try {
    & mvn @arguments *> (Join-Path $run 'maven.log')
    $buildExit = $LASTEXITCODE
} finally { Pop-Location }
Get-Content -LiteralPath (Join-Path $run 'maven.log') -Tail 65
exit $buildExit
