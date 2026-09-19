#Requires -Version 5.1
# cgraph-managed-uninstaller-v1
[CmdletBinding()]
param([string]$InstallDir, [switch]$Yes, [switch]$Check, [switch]$KeepPath)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'

function Get-CgraphUninstallTarget([string]$Directory) {
    if(!$Directory){
        if(Test-Path -LiteralPath (Join-Path $PSScriptRoot '.cgraph-install')){$Directory=$PSScriptRoot}
        else{$Directory=Join-Path $env:LOCALAPPDATA 'CodeGraph'}
    }
    $absolute=[IO.Path]::GetFullPath($Directory).TrimEnd('\','/')
    $userHome=[IO.Path]::GetFullPath($env:USERPROFILE).TrimEnd('\','/')
    if(!$absolute -or $absolute -eq [IO.Path]::GetPathRoot($absolute).TrimEnd('\','/') -or $absolute -eq $userHome){throw 'Refusing a root or home directory as the uninstall target'}
    if(!(Test-Path -LiteralPath $absolute)){return $null}
    $at=Get-Item -LiteralPath $absolute -Force
    while($at){
        if($at.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing a linked installation path'}
        $at=$at.Parent
    }
    $marker=Join-Path $absolute '.cgraph-install'
    if(!(Test-Path -LiteralPath $marker -PathType Leaf) -or [IO.File]::ReadAllText($marker) -ne 'code-graph-native-install-v1'){throw 'This directory is not a verified code-graph installation; nothing removed'}
    $allowed=@('.cgraph-install','bin','releases','uninstall.ps1','uninstall.sh')
    foreach($entry in Get-ChildItem -LiteralPath $absolute -Force){
        if($entry.Name -notin $allowed){throw "Unrecognized item in the installation: $($entry.Name). Move personal data out or review manually; nothing removed"}
    }
    # Do not traverse junctions or symlinks during recursive Windows deletion.
    $pending=[Collections.Generic.Stack[string]]::new();$pending.Push($absolute)
    while($pending.Count){
        foreach($entry in Get-ChildItem -LiteralPath $pending.Pop() -Force){
            if($entry.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Installation contains a linked item; review and remove manually without following links'}
            if($entry.PSIsContainer){$pending.Push($entry.FullName)}
        }
    }
    return $absolute
}
function Assert-CgraphStopped([string]$Directory) {
    $prefix=$Directory.TrimEnd('\','/')+'\'
    foreach($process in Get-CimInstance Win32_Process){
        if($process.ProcessId -eq $PID){continue}
        if($process.ExecutablePath -and ([string]$process.ExecutablePath).StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){
            throw "Installed code-graph process $($process.ProcessId) is running. Stop it first; nothing removed"
        }
        if($process.Name -match '^(java|javaw|cgraph)\.exe$' -and $process.CommandLine -and
           ([string]$process.CommandLine).Replace('/','\').IndexOf($prefix,[StringComparison]::OrdinalIgnoreCase) -ge 0){
            throw "Installed code-graph process $($process.ProcessId) is running. Stop it first; nothing removed"
        }
    }
}
function Get-CgraphPathWithoutInstallation([string]$Original,[string]$Directory) {
    $bin=Join-Path $Directory 'bin'
    $kept=@($Original.Split(';') | Where-Object {
        $entry=[Environment]::ExpandEnvironmentVariables($_.Trim().Trim('"')).TrimEnd('\','/')
        $entry -ine $bin
    })
    return ($kept -join ';')
}
function Remove-CgraphUserPath([string]$Directory) {
    $original=[string][Environment]::GetEnvironmentVariable('Path','User')
    $updated=Get-CgraphPathWithoutInstallation $original $Directory
    if($updated -ne $original){[Environment]::SetEnvironmentVariable('Path',$updated,'User');Write-Host 'Removed the exact code-graph user PATH entry. Open a new terminal.'}
}
function Invoke-CgraphUninstall {
    $target=Get-CgraphUninstallTarget $InstallDir
    if(!$target){Write-Host 'No installation found at this location; nothing changed.';return}
    Assert-CgraphStopped $target
    Write-Host "Verified installation: $target"
    Write-Host 'Removes the application, retained releases, and command shim. Preserves DBA profiles/vault credentials, projects, MCP configuration, skills, and caches outside this directory.'
    if($Check){Write-Host 'Check only: no files or PATH entries changed.';return}
    if(!$Yes){
        if([Console]::IsInputRedirected){throw 'Noninteractive uninstall requires explicit -Yes'}
        if((Read-Host 'Uninstall this application? [y/N]') -notmatch '^(?i:y|yes)$'){Write-Host 'Cancelled; nothing changed.';return}
    }
    # Repeat ownership and process checks immediately before the exact recursive removal.
    if((Get-CgraphUninstallTarget $target) -ne $target){throw 'Installation changed; refusing removal'}
    Assert-CgraphStopped $target
    Remove-Item -LiteralPath $target -Recurse -Force
    if(!$KeepPath){Remove-CgraphUserPath $target}
    Write-Host 'Application removed; reinstall to recover its binaries. User database data, MCP connections and skills were not removed. Disable/remove the code-graph connection in each client if it is no longer needed.'
}
if($MyInvocation.InvocationName -ne '.'){
    try{Invoke-CgraphUninstall}catch{Write-Error $_ -ErrorAction Continue;exit 1}
}
