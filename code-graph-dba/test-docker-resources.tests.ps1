$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'test-docker-resources.ps1')
$script:fixtureId='sha256:'+('a'*64)
$script:baselineId='sha256:'+('b'*64)
$script:present=$false
$script:users=@()
$script:label='run-one'
$script:calls=[Collections.Generic.List[string]]::new()
# This test never invokes Docker; the fake exercises exact cleanup commands and refusals.
function docker {
    $command=$args -join ' '
    $script:calls.Add($command)
    $global:LASTEXITCODE=0
    if($command -eq 'image ls --no-trunc --quiet'){return $script:baselineId}
    if($command.StartsWith('image inspect ')){if(!$script:present){$global:LASTEXITCODE=1;return};return $script:fixtureId}
    if($command.StartsWith('pull ')){$script:present=$true;return}
    if($command.StartsWith('inspect ')){return (@{Id='owned-container-id';Config=@{Labels=@{'test.owner'=$script:label}}}|ConvertTo-Json -Compress -Depth 5)}
    if($command.StartsWith('ps ')){return $script:users}
    if($command.StartsWith('rm ') -or $command.StartsWith('image rm ')){return}
    throw "Unexpected fake command: $command"
}
function Check([bool]$Condition,[string]$Message){if(!$Condition){throw $Message}}
$scope=New-CgraphDockerScope -Owner 'run-one' -Label 'test.owner'
$scope.Containers+= 'unique-fixture'
$id=Get-CgraphDockerImage $scope 'fixture:1'
Check ($id -eq $script:fixtureId) 'Image identity must be exact'
Remove-CgraphDockerResources $scope
Check ($script:calls.Contains('rm --force --volumes owned-container-id')) 'Owned container and volumes should be removed'
Check ($script:calls.Contains('image rm '+$script:fixtureId)) 'New unused image should be removed without force'

$script:calls.Clear();$script:present=$false;$script:fixtureId=$script:baselineId
$scope=New-CgraphDockerScope -Owner 'run-one' -Label 'test.owner'
$null=Get-CgraphDockerImage $scope 'new-tag-for-existing-id:1'
Remove-CgraphDockerResources $scope
Check (!@($script:calls|Where-Object { $_.StartsWith('image rm ') }).Count) 'Existing image ID must be preserved even when a new tag was pulled'

$script:calls.Clear();$script:fixtureId='sha256:'+('c'*64);$script:present=$false;$script:users=@('someone-elses-container');$script:label='different-owner'
$scope=New-CgraphDockerScope -Owner 'run-one' -Label 'test.owner';$scope.Containers+='unique-fixture'
$null=Get-CgraphDockerImage $scope 'fixture:2'
Remove-CgraphDockerResources $scope
Check (!@($script:calls|Where-Object { $_.StartsWith('rm ') -or $_.StartsWith('image rm ') }).Count) 'Unowned containers and used images must be preserved'
Write-Output 'Docker ownership cleanup tests passed (no real Docker operations).'
