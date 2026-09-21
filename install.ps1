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
    # Optional corporate CA bundle for Git AND Maven. Omit to bypass TLS validation during installation.
    [Alias('GitCaFile')][string]$CertPem,
    [string]$MavenSettings,
    # Optional current-user skills: all, none, or comma-separated codex,copilot,claude,windsurf.
    # Differing copies prompt before replacement; No/non-interactive keeps them. Approved updates keep a backup.
    [string]$Skills,
    # Optional current-user MCP connections; skill choices are offered afterward.
    [string]$McpClients,
    [string]$McpUrl='http://localhost:3000/mcp',
    [switch]$Check,
    [switch]$NonInteractive,
    # Tests are skipped by default; opt in explicitly for installation builds.
    [switch]$RunTests,
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

function Resolve-CgraphMavenSettings([string]$Override,[string]$ProfileDirectory=$env:USERPROFILE) {
    if($Override){
        if(!(Test-Path -LiteralPath $Override -PathType Leaf)){throw 'The -MavenSettings file does not exist or is not a file.'}
        return (Resolve-Path -LiteralPath $Override).Path
    }
    if($ProfileDirectory){
        $candidate=Join-Path $ProfileDirectory '.m2/settings.xml'
        if(Test-Path -LiteralPath $candidate -PathType Leaf){return (Resolve-Path -LiteralPath $candidate).Path}
    }
}
function Protect-CgraphDiagnostic([string]$Text) {
    foreach($secret in @($env:CGRAPH_PROXY_PASSWORD)){
        if($secret){$Text=$Text.Replace($secret,'[REDACTED]').Replace([Uri]::EscapeDataString($secret),'[REDACTED]')}
    }
    $Text=[regex]::Replace($Text,'(?i)(https?://)[^/\s@]+@','$1[REDACTED]@')
    return [regex]::Replace($Text,'(?im)((?:proxy-)?authorization:\s*)[^\r\n]+','$1[REDACTED]')
}
function Invoke-CgraphClone([string]$Executable,[string]$Revision,[string]$Url,[string]$Destination,[string]$CertificatePem) {
    $prior=@{}
    foreach($name in @('GIT_TERMINAL_PROMPT','GIT_ASKPASS','SSH_ASKPASS','GIT_SSL_NO_VERIFY','GIT_SSL_CAINFO')){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
    try {
        $env:GIT_TERMINAL_PROMPT='0';$env:GIT_ASKPASS=$null;$env:SSH_ASKPASS=$null
        $env:GIT_SSL_NO_VERIFY=$null
        $arguments=@('-c','credential.helper=','-c','core.askPass=')
        if($CertificatePem){
            $env:GIT_SSL_CAINFO=$CertificatePem
            $arguments+=@('-c','http.sslVerify=true','-c','http.proxySSLVerify=true','-c',('http.sslCAInfo='+$CertificatePem),'-c',('http.proxySSLCAInfo='+$CertificatePem),'-c','http.schannelUseSSLCAInfo=true')
            Write-Host 'Git certificate verification enabled using the supplied PEM bundle.'
        } else {
            $arguments+=@('-c','http.sslVerify=false','-c','http.proxySSLVerify=false')
            Write-Warning 'INSTALLATION ONLY: Git TLS certificate verification is disabled. Intercepted downloads can contain untrusted code.'
        }
        $arguments+=@('clone','--no-hardlinks','--branch',$Revision,'--',$Url,$Destination)
        $result=Invoke-CgraphCapture $Executable $arguments
        if($result.Code -ne 0){
            $diagnostic=Protect-CgraphDiagnostic $result.Text
            throw "Git clone failed (exit $($result.Code)). Check the repository/ref, proxy and access permissions. Interactive authentication is disabled.`n$diagnostic"
        }
    } finally {foreach($name in $prior.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}}
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
        try{
            $env:JAVA_HOME=$jdk;$result=Invoke-CgraphCapture $maven.Source @('--version')
            $mavenOk=$result.Code -eq 0 -and $result.Text -match 'Apache Maven 3\.(?:9|[1-9][0-9])\.'
            if($result.Code -ne 0){Write-Warning ('Maven prerequisite check failed (exit '+$result.Code+'): '+(Protect-CgraphDiagnostic $result.Text))}
        }finally{$env:JAVA_HOME=$previous}
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
function Get-CgraphSkillArguments([string]$Selection,[bool]$OnlyBuild) {
    if(!$Selection){return}
    $normalized=$Selection.Trim().ToLowerInvariant()
    $items=@($normalized.Split(',') | ForEach-Object {$_.Trim()})
    if($normalized -notin @('all','none')){
        if(@($items | Where-Object {$_ -notin @('codex','copilot','claude','windsurf')}).Count -gt 0 -or
           @($items | Select-Object -Unique).Count -ne $items.Count){throw 'Skills must be all, none, or unique comma-separated clients: codex,copilot,claude,windsurf'}
    }
    if($OnlyBuild -and $normalized -ne 'none'){throw '-BuildOnly cannot install skills; use -Skills none or omit -Skills'}
    return @('--skills',($items -join ','))
}
function Get-CgraphMcpArguments([string]$Selection,[string]$Endpoint,[bool]$OnlyBuild) {
    $items=@()
    if($Selection){
        $items=@($Selection.Trim().ToLowerInvariant().Split(',') | ForEach-Object {$_.Trim()})
        if(($items -join ',') -notin @('all','none')){
            if(@($items | Where-Object {$_ -notin @('codex','copilot','copilot-vscode','claude','windsurf')}).Count -gt 0 -or
               @($items | Select-Object -Unique).Count -ne $items.Count){throw 'McpClients must be all, none, or unique comma-separated codex,copilot,copilot-vscode,claude,windsurf'}
        }
        if($OnlyBuild -and ($items -join ',') -ne 'none'){throw '-BuildOnly cannot configure MCP; use -McpClients none or omit it'}
    }
    try{$endpointUri=[Uri]$Endpoint}catch{throw 'McpUrl must be http://localhost:PORT/mcp without credentials, query, or fragment'}
    if(!$endpointUri.IsAbsoluteUri -or $endpointUri.Scheme -ne 'http' -or $endpointUri.Host -notin @('localhost','127.0.0.1','[::1]','::1') -or
       $endpointUri.UserInfo -or $endpointUri.Query -or $endpointUri.Fragment -or $endpointUri.Port -le 0 -or $endpointUri.AbsolutePath -notin @('/mcp','/mcp/')){
        throw 'McpUrl must be http://localhost:PORT/mcp (or 127.0.0.1 / [::1]), without credentials, query, or fragment'
    }
    if($items.Count){'--mcp-clients';($items -join ',')}
    '--mcp-url';$Endpoint
}
function Invoke-CgraphInstall {
    if($RunTests -and $SkipTests){throw 'Choose -RunTests or -SkipTests, not both. Tests are skipped by default.'}
    $skillArguments=@(Get-CgraphSkillArguments $Skills ([bool]$BuildOnly))
    $mcpArguments=@(Get-CgraphMcpArguments $McpClients $McpUrl ([bool]$BuildOnly))
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
        $resolvedPem=$null
        if($CertPem){
            if(!(Test-Path -LiteralPath $CertPem -PathType Leaf)){throw 'The -CertPem file does not exist or is not a file.'}
            $resolvedPem=(Resolve-Path -LiteralPath $CertPem).Path
        }
        $resolvedSettings=Resolve-CgraphMavenSettings $MavenSettings
        if($resolvedSettings){Write-Host ('Using Maven settings: '+$resolvedSettings+' (unchanged).')}
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
            if($Repository -match '^https?://[^/]*@'){throw 'Do not put repository credentials in the URL. Installer clones disable credential helpers/prompts; use -SourceDir with a separately authenticated checkout.'}
            $clone=Join-Path ([IO.Path]::GetTempPath()) ('cgraph-clone-'+[guid]::NewGuid().ToString('N'))
            $null=New-Item -ItemType Directory -Path $clone
            [IO.File]::WriteAllText((Join-Path $clone '.cgraph-clone-owner'),'cgraph-clone-v1')
            $source=Join-Path $clone 'source'
            Write-Host 'Cloning the selected repository/ref into a fresh temporary checkout...'
            Invoke-CgraphClone $requirements.Git.Source $Ref $Repository $source $resolvedPem
        }
        $engine=Join-Path $source 'installer/CgraphInstaller.java'
        if(!(Test-Path -LiteralPath $engine)){throw 'Selected repository/ref does not include this installer yet. Use a published ref containing it, or -SourceDir with your development checkout.'}
        $arguments=@($engine,'--source',$source,'--install-dir',[IO.Path]::GetFullPath($InstallDir),'--maven',$requirements.Maven.Source)
        $arguments+=$skillArguments
        $arguments+=$mcpArguments
        if($resolvedSettings){$arguments+=@('--maven-settings',$resolvedSettings)}
        if($resolvedPem){$arguments+=@('--cert-pem',$resolvedPem)}
        if($script:effectiveProxy){$arguments+=@('--proxy',$script:effectiveProxy)}
        if($NoProxy){$arguments+=@('--no-proxy',$NoProxy)}
        if($RunTests){$arguments+='--run-tests'}else{$arguments+='--skip-tests'};if($NoPath){$arguments+='--no-path'}
        if($NonInteractive){$arguments+='--non-interactive'};if($KeepBuild){$arguments+='--keep-build'};if($BuildOnly){$arguments+='--build-only'}
        & (Join-Path $requirements.Jdk 'bin/java.exe') @arguments
        if($LASTEXITCODE -eq 2){
            $success=$true
            throw 'Application installed, but optional MCP/skill setup was incomplete. Inspect the per-client results and any backup paths. No server was restarted.'
        }
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
