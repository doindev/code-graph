# No packages, clones, builds, user PATH or active servers are changed by these checks.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../install.ps1') -Check -NonInteractive
$beforePath=[Environment]::GetEnvironmentVariable('Path','User')
$saved=@{}
foreach($name in @('HTTPS_PROXY','HTTP_PROXY','NO_PROXY','CGRAPH_PROXY_USER','CGRAPH_PROXY_PASSWORD','GIT_CONFIG_COUNT','GIT_CONFIG_KEY_0','GIT_CONFIG_VALUE_0')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Get-CgraphRequirements {[pscustomobject]@{Jdk='test JDK path';Git=[pscustomobject]@{Source='git.exe'};Maven=[pscustomobject]@{Source='mvn.cmd'};MavenOk=$true}}
function Offer-CgraphRequirement {throw 'Unexpected prerequisite installation attempt'}
try{
    $env:HTTPS_PROXY=$null;$env:HTTP_PROXY=$null;$env:CGRAPH_PROXY_USER=$null;$env:CGRAPH_PROXY_PASSWORD=$null;$env:GIT_CONFIG_COUNT=$null
    $Proxy='http://proxy.example:8080';$ProxyUser='domain\tester'
    Invoke-CgraphInstall
    if($env:HTTPS_PROXY -or $env:CGRAPH_PROXY_USER -or $env:GIT_CONFIG_COUNT){throw 'Installer leaked proxy process configuration'}
    $Proxy='http://user:private_value@proxy.example:8080';$failed=$false
    try{Invoke-CgraphInstall}catch{$failed=$true;if($_.ToString().Contains('private_value')){throw 'Proxy error disclosed credential'}}
    if(!$failed){throw 'Credential URL was accepted'}
    $Proxy='http://bad proxy:8080';$failed=$false
    try{Invoke-CgraphInstall}catch{$failed=$true}
    if(!$failed){throw 'Malformed proxy accepted'}
    $Proxy=$null;$ProxyUser=$null
    function Get-CgraphRequirements {[pscustomobject]@{Jdk=$null;Git=$null;Maven=$null;MavenOk=$false}}
    function Offer-CgraphRequirement {Write-Host 'Missing prerequisite correctly reported without installation'}
    $failed=$false
    try{Invoke-CgraphInstall}catch{$failed=$true;if($_.ToString() -notmatch 'Prerequisites are still unavailable'){throw}}
    if(!$failed){throw 'Missing requirements accepted'}
    if([Environment]::GetEnvironmentVariable('Path','User') -ne $beforePath){throw 'User PATH changed'}
    Write-Host 'PowerShell bootstrap checks passed: check-only, hidden-auth deferral, environment restoration, redaction, invalid proxies, missing tools, unchanged PATH.'
}finally{foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
