# Dot-source from test harnesses. Never remove pre-existing images or unowned containers.
function New-CgraphDockerScope {
    param([Parameter(Mandatory)][string]$Owner,[Parameter(Mandatory)][string]$Label)
    $baseline = @(docker image ls --no-trunc --quiet)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot record Docker image baseline; refusing to start fixtures' }
    return @{ Owner=$Owner; Label=$Label; Baseline=$baseline; Images=@(); Containers=@() }
}
function Get-CgraphDockerImage {
    param([Parameter(Mandatory)][hashtable]$Scope,[Parameter(Mandatory)][string]$Reference)
    $imageId = docker image inspect --format '{{.Id}}' $Reference 2>$null
    if ($LASTEXITCODE -ne 0) {
        docker pull $Reference | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Could not pull fixture image: $Reference" }
        $imageId = docker image inspect --format '{{.Id}}' $Reference
        if ($LASTEXITCODE -ne 0) { throw 'Cannot identify pulled fixture image' }
        $imageId = $imageId.Trim()
        if ($Scope.Baseline -notcontains $imageId) { $Scope.Images += $imageId }
    }
    $imageId = $imageId.Trim()
    if ($imageId -notmatch '^sha256:[a-f0-9]{64}$') { throw 'Unexpected Docker image identity' }
    return $imageId
}
function Remove-CgraphDockerResources {
    param([Parameter(Mandatory)][hashtable]$Scope)
    foreach ($name in $Scope.Containers) {
        $details = docker inspect --format '{{json .}}' $name 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        $container = $details | ConvertFrom-Json
        if ($container.Config.Labels.($Scope.Label) -ne $Scope.Owner) {
            Write-Warning "Ownership mismatch; preserved container $name"
            continue
        }
        docker rm --force --volumes $container.Id | Out-Host
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove owned container $name; its image will be retained if still used" }
    }
    foreach ($imageId in @($Scope.Images | Select-Object -Unique)) {
        if ($Scope.Baseline -contains $imageId) { continue }
        $users = @(docker ps --all --quiet --filter "ancestor=$imageId")
        if ($LASTEXITCODE -ne 0) { Write-Warning "Cannot verify image usage; preserved $imageId"; continue }
        if ($users.Count -gt 0) { Write-Warning "New fixture image is now used by a container; preserved $imageId"; continue }
        # Do not force: concurrent retagging or use must prevent deletion.
        docker image rm $imageId | Out-Host
        if ($LASTEXITCODE -ne 0) { Write-Warning "Docker retained fixture image $imageId; inspect its tags and users" }
    }
}
