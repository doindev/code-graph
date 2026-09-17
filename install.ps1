#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Repository='https://github.com/doindev/code-graph.git',
    [string]$Ref='main',
    [string]$SourceDir,
    [string]$InstallDir=(Join-Path $env:LOCALAPPDATA 'CodeGraph'),
    [string]$Proxy,
    [string]$ProxyUser,
    [string]$NoProxy,
    [string]$GitCaFile,
    [string]$MavenSettings,
    [switch]$Check,
    [switch]$NonInteractive,
    [switch]$SkipTests,
    [switch]$NoPath,
    [switch]$KeepBuild,
    [switch]$BuildOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'

function Invoke-CgraphCapture([string]$Executable,[string[]]$Arguments) {
    $previous=$ErrorActionPreference
    try {
        # Windows PowerShell 5.1 otherwise treats native stderr (including java -version) as a terminating error.
        $ErrorActionPreference='Continue'
        $output=(& $Executable @Arguments 2>&1 | Out-String)
        [pscustomobject]@{Text=$output;Code=$LASTEXITCODE}
    } finally {$ErrorActionPreference=$previous}
}

function Find-CgraphJdk {
    $candidates=@($env:CGRAPH_JAVA_HOME,$env:JAVA_HOME)
    $java=Get-Command java.exe -ErrorAction SilentlyContinue
    if($java){
        $details=(Invoke-CgraphCapture $java.Source @('-XshowSettings:properties','-version')).Text
        if($details -match '(?m)^\s*java.home = (.+)$'){$candidates+=$Matches[1].Trim()}
    }
    foreach($vendor in @('Eclipse Adoptium','OpenJDK','Java')){
        $parent=Join-Path $env:ProgramFiles $vendor
        if(Test-Path -LiteralPath $parent){$candidates+=@(Get-ChildItem -LiteralPath $parent -Directory -Filter '*25*' | Select-Object -ExpandProperty FullName)}
    }
    foreach($candidate in $candidates){
        if(!$candidate){continue}
        $javaPath=Join-Path $candidate 'bin/java.exe'
        if(!(Test-Path -LiteralPath $javaPath)){continue}
        $version=(Invoke-CgraphCapture $javaPath @('-version')).Text
        if($version -match 'version "25(?:[.\-+" ])' -and (Test-Path (Join-Path $candidate 'bin/javac.exe')) -and (Test-Path (Join-Path $candidate 'bin/jpackage.exe')) -and (Test-Path (Join-Path $candidate 'bin/jlink.exe'))){return (Resolve-Path -LiteralPath $candidate).Path}
    }
    return $null
}
function Get-CgraphRequirements {
    $jdk=Find-CgraphJdk
    $git=Get-Command git.exe -ErrorAction SilentlyContinue
    $maven=Get-Command mvn.cmd -ErrorAction SilentlyContinue
    $mavenOk=$false
    if($maven -and $jdk){
        $previous=$env:JAVA_HOME
        try{$env:JAVA_HOME=$jdk;$result=Invoke-CgraphCapture $maven.Source @('--version');$mavenOk=$result.Code -eq 0 -and $result.Text -match 'Apache Maven 3\.(?:9|[1-9][0-9])\.'}finally{$env:JAVA_HOME=$previous}
    }
    [pscustomobject]@{Jdk=$jdk;Git=$git;Maven=$maven;MavenOk=$mavenOk}
}
function Confirm-CgraphInstall([string]$Question) {
    if($NonInteractive -or $Check -or [Console]::IsInputRedirected){return $false}
    return (Read-Host ($Question+' [y/N]')) -match '^(?i:y|yes)$'
}
function Offer-CgraphRequirement([string]$Name,[string]$Link,[string]$WingetId) {
    Write-Host ('Missing/incompatible: '+$Name+'; guidance: '+$Link)
    $winget=Get-Command winget.exe -ErrorAction SilentlyContinue
    if($WingetId -and $winget -and (Confirm-CgraphInstall "Run winget to install $Name? It may request Windows administrator approval")){
        $arguments=@('install','--id',$WingetId,'--exact','--source','winget')
        if($script:effectiveProxy){$arguments+=@('--proxy',$script:effectiveProxy)}
        & $winget.Source @arguments
        if($LASTEXITCODE -ne 0){Write-Warning 'Package installation failed. Configure the package manager proxy/certificates or install this prerequisite manually.'}
        $env:Path=[Environment]::GetEnvironmentVariable('Path','Machine')+';'+[Environment]::GetEnvironmentVariable('Path','User')+';'+$env:Path
    }
}
function Invoke-CgraphInstall {
    $saved=@{}
    foreach($name in @('JAVA_HOME','HTTPS_PROXY','HTTP_PROXY','NO_PROXY','GIT_SSL_CAINFO','GIT_CONFIG_COUNT','CGRAPH_PROXY_USER','CGRAPH_PROXY_PASSWORD')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
    $clone=$null;$success=$false;$gitKeys=@()
    try {
        $script:effectiveProxy=$Proxy
        if(!$script:effectiveProxy){$script:effectiveProxy=$env:HTTPS_PROXY}
        if(!$script:effectiveProxy){$script:effectiveProxy=$env:HTTP_PROXY}
        if($script:effectiveProxy){
            try{$uri=[Uri]$script:effectiveProxy}catch{throw 'Invalid proxy URL; use http(s)://host:port without credentials.'}
            if($uri.Scheme -notin @('http','https') -or !$uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/' -or $uri.Port -eq 0){throw 'Proxy must be http(s)://host:port without credentials. Use -ProxyUser or CGRAPH_PROXY_USER/CGRAPH_PROXY_PASSWORD.'}
            $env:HTTPS_PROXY=$script:effectiveProxy;$env:HTTP_PROXY=$script:effectiveProxy
            if($ProxyUser){$env:CGRAPH_PROXY_USER=$ProxyUser}
            if($env:CGRAPH_PROXY_USER -and !$env:CGRAPH_PROXY_PASSWORD -and !$Check){
                if($NonInteractive -or $Check -or [Console]::IsInputRedirected){throw 'Authenticated proxy needs CGRAPH_PROXY_PASSWORD in the process environment (not a command-line argument).'}
                $secret=Read-Host 'Proxy password (used only for this installation)' -AsSecureString
                $env:CGRAPH_PROXY_PASSWORD=[Net.NetworkCredential]::new('', $secret).Password
            }
            $gitProxy=$script:effectiveProxy
            if($env:CGRAPH_PROXY_USER){$builder=[UriBuilder]$uri;$builder.UserName=[Uri]::EscapeDataString($env:CGRAPH_PROXY_USER);$builder.Password=[Uri]::EscapeDataString([string]$env:CGRAPH_PROXY_PASSWORD);$gitProxy=$builder.Uri.AbsoluteUri}
            $count=0;if($env:GIT_CONFIG_COUNT){$count=[int]$env:GIT_CONFIG_COUNT}
            foreach($key in @("GIT_CONFIG_KEY_$count","GIT_CONFIG_VALUE_$count")){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process');$gitKeys+=$key}
            [Environment]::SetEnvironmentVariable("GIT_CONFIG_KEY_$count",'http.proxy','Process')
            [Environment]::SetEnvironmentVariable("GIT_CONFIG_VALUE_$count",$gitProxy,'Process')
            $env:GIT_CONFIG_COUNT=[string]($count+1)
            Write-Host 'Proxy configured for Git and Maven; credentials are not saved in the application.'
        }
        if($NoProxy){$env:NO_PROXY=$NoProxy}
        if($GitCaFile){$env:GIT_SSL_CAINFO=(Resolve-Path -LiteralPath $GitCaFile).Path}
        if($MavenSettings){$MavenSettings=(Resolve-Path -LiteralPath $MavenSettings).Path}
        $requirements=Get-CgraphRequirements
        Write-Host ('Git: '+[bool]$requirements.Git+'; full JDK 25: '+[bool]$requirements.Jdk+'; Maven 3.9+: '+$requirements.MavenOk)
        Write-Host 'Node.js/npm are not needed: cgraph is installed as a native launcher with a bundled runtime.'
        if(!$requirements.Git){Offer-CgraphRequirement 'Git' 'https://git-scm.com/downloads/win' 'Git.Git'}
        if(!$requirements.Jdk){Offer-CgraphRequirement 'JDK 25' 'https://adoptium.net/installation' 'EclipseAdoptium.Temurin.25.JDK'}
        if(!$requirements.MavenOk){
            Write-Host 'Maven 3.9+ is required: https://maven.apache.org/install.html. Add its bin directory to PATH.'
            $scoop=Get-Command scoop -ErrorAction SilentlyContinue
            if($scoop -and (Confirm-CgraphInstall 'Run scoop install maven using your existing Scoop installation?')){& $scoop.Source install maven}
        }
        $requirements=Get-CgraphRequirements
        if(!$requirements.Git -or !$requirements.Jdk -or !$requirements.MavenOk){throw 'Prerequisites are still unavailable. Open a new terminal after installing them, or set CGRAPH_JAVA_HOME to your full JDK 25, then rerun. No repository was cloned.'}
        if($Check){Write-Host 'Prerequisites passed. Check-only mode did not clone, build, install, or change PATH.';return}
        $env:JAVA_HOME=$requirements.Jdk
        if($SourceDir){$source=(Resolve-Path -LiteralPath $SourceDir).Path}
        else {
            if($Repository -match '^https?://[^/]*@'){throw 'Do not put repository credentials in the URL; configure a Git credential helper.'}
            $clone=Join-Path ([IO.Path]::GetTempPath()) ('cgraph-clone-'+[guid]::NewGuid().ToString('N'))
            $null=New-Item -ItemType Directory -Path $clone
            [IO.File]::WriteAllText((Join-Path $clone '.cgraph-clone-owner'),'cgraph-clone-v1')
            $source=Join-Path $clone 'source'
            Write-Host 'Cloning the selected repository/ref into a fresh temporary checkout...'
            $gitOutput=Invoke-CgraphCapture $requirements.Git.Source @('clone','--no-hardlinks','--branch',$Ref,'--',$Repository,$source)
            if($gitOutput.Code -ne 0){throw 'Git clone failed. Check repository/ref access, proxy authentication, NO_PROXY, and Git CA trust. Captured network output was suppressed to avoid revealing credentials.'}
        }
        $engine=Join-Path $source 'installer/CgraphInstaller.java'
        if(!(Test-Path -LiteralPath $engine)){throw 'Selected repository/ref does not include this installer yet. Use a published ref containing it, or -SourceDir with your development checkout.'}
        $arguments=@($engine,'--source',$source,'--install-dir',[IO.Path]::GetFullPath($InstallDir),'--maven',$requirements.Maven.Source)
        if($MavenSettings){$arguments+=@('--maven-settings',$MavenSettings)}
        if($script:effectiveProxy){$arguments+=@('--proxy',$script:effectiveProxy)}
        if($NoProxy){$arguments+=@('--no-proxy',$NoProxy)}
        if($SkipTests){$arguments+='--skip-tests'};if($NoPath){$arguments+='--no-path'}
        if($NonInteractive){$arguments+='--non-interactive'};if($KeepBuild){$arguments+='--keep-build'};if($BuildOnly){$arguments+='--build-only'}
        & (Join-Path $requirements.Jdk 'bin/java.exe') @arguments
        if($LASTEXITCODE -ne 0){throw 'The native installer failed; the existing server was not stopped or restarted.'}
        $success=$true
        if(!$NoPath -and !$BuildOnly){$env:Path=(Join-Path $InstallDir 'bin')+';'+$env:Path}
    } finally {
        foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
        if($clone){
            if($success -and !$KeepBuild){
                $actual=(Resolve-Path -LiteralPath $clone).Path;$tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
                if([IO.Path]::GetDirectoryName($actual) -ne $tempRoot -or [IO.Path]::GetFileName($actual) -notlike 'cgraph-clone-*' -or ((Get-Item -LiteralPath $actual).Attributes -band [IO.FileAttributes]::ReparsePoint) -or [IO.File]::ReadAllText((Join-Path $actual '.cgraph-clone-owner')) -ne 'cgraph-clone-v1'){throw 'Refusing cleanup: clone ownership/path validation failed.'}
                try{Remove-Item -LiteralPath $actual -Recurse -Force}catch{Write-Warning "Owned temporary clone remains at $actual"}
            } else {Write-Host "Temporary clone retained for diagnostics: $clone"}
        }
    }
}
if($MyInvocation.InvocationName -ne '.'){
    try{Invoke-CgraphInstall}catch{Write-Error $_ -ErrorAction Continue;exit 1}
}
