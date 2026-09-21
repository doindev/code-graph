import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';

const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const jdk=process.env.CGRAPH_JAVA_HOME||process.env.JAVA_HOME;
const bash=process.env.CGRAPH_TEST_BASH||(process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');
async function fixture(run){
  const root=await mkdtemp(path.join(os.tmpdir(),'cgraph-download-test-'));
  try{
    const source=path.join(root,'source with spaces');await mkdir(path.join(source,'installer'),{recursive:true});
    await writeFile(path.join(source,'installer/CgraphInstaller.java'),'import java.nio.file.*; class CgraphInstaller { public static void main(String[] args)throws Exception {Files.writeString(Path.of(System.getenv("CGRAPH_CAPTURE_ARGS")),String.join("\\n",args));}}');
    const env={...process.env,CGRAPH_REPOSITORY:repository,CGRAPH_TEST_JDK:jdk,CGRAPH_SOURCE:source,
      CGRAPH_CAPTURE_ARGS:path.join(root,'args.txt'),CGRAPH_TEST_PROFILE:path.join(root,'profile'),CGRAPH_TEST_ROOT:root};
    for(const key of ['HTTP_PROXY','HTTPS_PROXY','http_proxy','https_proxy','CGRAPH_PROXY_USER','CGRAPH_PROXY_PASSWORD'])delete env[key];
    await mkdir(path.join(env.CGRAPH_TEST_PROFILE,'.m2'),{recursive:true});
    await writeFile(path.join(env.CGRAPH_TEST_PROFILE,'.m2/settings.xml'),'<settings><!-- unchanged --></settings>');
    await writeFile(path.join(root,'alternate.xml'),'<settings><!-- explicit --></settings>');
    await writeFile(path.join(root,'corporate cert.pem'),'public certificate fixture');
    await run(env);
    assert.equal(await readFile(path.join(env.CGRAPH_TEST_PROFILE,'.m2/settings.xml'),'utf8'),'<settings><!-- unchanged --></settings>');
  }finally{await rm(root,{recursive:true,force:true});}
}
const ps=(command,env)=>spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',command],{env,encoding:'utf8',timeout:20000});
const sh=(command,env)=>spawnSync(bash,['-c',command],{env,encoding:'utf8',timeout:20000});
const passed=result=>assert.equal(result.status,0,result.stdout+result.stderr);

test('Windows default Maven settings, explicit override, absent default, and test opt-in',{skip:process.platform!=='win32'||!jdk},async()=>{
  await fixture(async env=>{
    const script=[
      ". (Join-Path $env:CGRAPH_REPOSITORY 'install.ps1') -SourceDir $env:CGRAPH_SOURCE -InstallDir (Join-Path $env:CGRAPH_TEST_ROOT 'unused install') -NoPath -NonInteractive",
      '$env:USERPROFILE=$env:CGRAPH_TEST_PROFILE',
      "function Get-CgraphRequirements {[pscustomobject]@{Jdk=$env:CGRAPH_TEST_JDK;Git=[pscustomobject]@{Source='unused'};Maven=[pscustomobject]@{Source='unused'};MavenOk=$true}}",
      "$MavenSettings=$env:CGRAPH_SETTINGS; $CertPem=$env:CGRAPH_CERT; $RunTests=$env:CGRAPH_TESTS -eq 'run'; $SkipTests=$env:CGRAPH_TESTS -eq 'skip'",
      "try {Invoke-CgraphInstall} catch {Write-Output $_.Exception.Message;exit 1}"
    ].join('\n');
    for(const mode of ['default','run','skip']){
      passed(ps(script,{...env,CGRAPH_TESTS:mode}));
      const args=(await readFile(env.CGRAPH_CAPTURE_ARGS,'utf8')).split('\n');
      assert.equal(args[args.indexOf('--maven-settings')+1],path.join(env.CGRAPH_TEST_PROFILE,'.m2/settings.xml'));
      assert.ok(args.includes(mode==='run'?'--run-tests':'--skip-tests'));
      assert.ok(!args.includes(mode==='run'?'--skip-tests':'--run-tests'));
    }
    const alternate=path.join(env.CGRAPH_TEST_ROOT,'alternate.xml');
    passed(ps(script,{...env,CGRAPH_SETTINGS:alternate}));
    let args=(await readFile(env.CGRAPH_CAPTURE_ARGS,'utf8')).split('\n');assert.equal(args[args.indexOf('--maven-settings')+1],alternate);
    const pem=path.join(env.CGRAPH_TEST_ROOT,'corporate cert.pem');
    passed(ps(script,{...env,CGRAPH_CERT:pem}));
    args=(await readFile(env.CGRAPH_CAPTURE_ARGS,'utf8')).split('\n');assert.equal(args[args.indexOf('--cert-pem')+1],pem);
    const invalidPem=ps(script,{...env,CGRAPH_CERT:path.join(env.CGRAPH_TEST_ROOT,'missing.pem')});assert.equal(invalidPem.status,1);assert.match(invalidPem.stdout,/-CertPem file does not exist/);
    const missing=ps(script,{...env,CGRAPH_SETTINGS:path.join(env.CGRAPH_TEST_ROOT,'absent.xml')});assert.equal(missing.status,1);assert.match(missing.stdout,/-MavenSettings file does not exist/);
    passed(ps(script,{...env,CGRAPH_TEST_PROFILE:path.join(env.CGRAPH_TEST_ROOT,'no-profile')}));
    args=(await readFile(env.CGRAPH_CAPTURE_ARGS,'utf8')).split('\n');assert.ok(!args.includes('--maven-settings'));
    const conflict=ps(". (Join-Path $env:CGRAPH_REPOSITORY 'install.ps1') -Check -RunTests -SkipTests; try{Invoke-CgraphInstall;exit 2}catch{Write-Output $_.Exception.Message;exit 0}",env);passed(conflict);assert.match(conflict.stdout,/not both/);
  });
});

test('Windows clone flags, disabled credential prompts, restored environment and useful redacted errors',{skip:process.platform!=='win32'},async()=>{
  const script=[
    ". (Join-Path $env:CGRAPH_REPOSITORY 'install.ps1') -Check",
    "$env:GIT_TERMINAL_PROMPT='original';$env:GIT_ASKPASS='original-ask';$env:SSH_ASKPASS='original-ssh';$env:CGRAPH_PROXY_PASSWORD='test+secret'",
    'function Invoke-CgraphCapture([string]$Executable,[string[]]$Arguments) {',
    "if($env:GIT_TERMINAL_PROMPT -ne '0' -or $env:GIT_ASKPASS -or $env:SSH_ASKPASS){throw 'Prompt not disabled'}",
    "foreach($arg in @('http.sslVerify=false','http.proxySSLVerify=false','credential.helper=','core.askPass=')){if($Arguments -notcontains $arg){throw ('Missing clone flag '+$arg)}}",
    "if($Arguments[-1] -ne 'destination with spaces' -or $Arguments[-2] -ne 'https://example.invalid/repo'){throw 'Arguments changed'}",
    "[pscustomobject]@{Code=128;Text=\"fatal: remote ref missing; https://user:private@host/repo test+secret test%2Bsecret`nAuthorization: Basic private-header\"}",
    '}',
    "$failure='';try{Invoke-CgraphClone 'fixture' 'main' 'https://example.invalid/repo' 'destination with spaces'}catch{$failure=$_.Exception.Message}",
    "if($failure -notmatch 'remote ref missing' -or $failure -notmatch 'exit 128' -or $failure -match 'test[+%]|private'){throw 'Diagnostics lost or secret disclosed'}",
    "if($env:GIT_TERMINAL_PROMPT -ne 'original' -or $env:GIT_ASKPASS -ne 'original-ask' -or $env:SSH_ASKPASS -ne 'original-ssh'){throw 'Environment leaked'}",
    "function Invoke-CgraphCapture {[pscustomobject]@{Code=0;Text='ok'}};Invoke-CgraphClone 'fixture' 'main' 'https://example.invalid/repo' 'destination'",
    "if($env:GIT_TERMINAL_PROMPT -ne 'original'){throw 'Successful clone leaked environment'}"
  ].join('\n');
  passed(ps(script,{...process.env,CGRAPH_REPOSITORY:repository}));
  passed(ps([
    ". (Join-Path $env:CGRAPH_REPOSITORY 'install.ps1') -Check -GitCaFile 'corporate cert.pem'",
    "if($CertPem -ne 'corporate cert.pem'){throw 'Legacy alias lost'}",
    "$env:GIT_SSL_NO_VERIFY='true';$env:GIT_SSL_CAINFO='original-ca'",
    'function Invoke-CgraphCapture([string]$Executable,[string[]]$Arguments){',
    "foreach($arg in @('http.sslVerify=true','http.proxySSLVerify=true','http.sslCAInfo=corporate cert.pem','http.schannelUseSSLCAInfo=true')){if($Arguments -notcontains $arg){throw ('Missing verified clone flag '+$arg)}}",
    "if($env:GIT_SSL_NO_VERIFY -or $env:GIT_SSL_CAINFO -ne 'corporate cert.pem'){throw 'Unverified TLS environment'}",
    "[pscustomobject]@{Code=0;Text='ok'}}",
    "Invoke-CgraphClone 'fixture' 'main' 'https://example.invalid/repo' 'destination' $CertPem",
    "if($env:GIT_SSL_NO_VERIFY -ne 'true' -or $env:GIT_SSL_CAINFO -ne 'original-ca'){throw 'TLS environment leaked'}"
  ].join('\n'),{...process.env,CGRAPH_REPOSITORY:repository}));
});

test('Bash clone diagnostics and authentication settings stay scoped to the clone',()=>{
  const script=[
    'source "$CGRAPH_REPOSITORY/install.sh"',
    'export GIT_TERMINAL_PROMPT=original GIT_ASKPASS=original-ask SSH_ASKPASS=original-ssh CGRAPH_PROXY_PASSWORD="test+secret"',
    'git(){ [[ "$GIT_TERMINAL_PROMPT" == 0 && -z "${GIT_ASKPASS:-}" && -z "${SSH_ASKPASS:-}" ]] || return 77;',
    '[[ "$*" == *http.sslVerify=false* && "$*" == *http.proxySSLVerify=false* && "$*" == *credential.helper=* && "$*" == *core.askPass=* ]] || return 78;',
    'printf "fatal: missing ref https://user:private@host/repo test+secret test%%2Bsecret\\nAuthorization: Basic private-header\\n" >&2; return 128; }',
    'status=0;result=$(cgraph_clone main https://example.invalid/repo "destination with spaces" 2>&1) || status=$?',
    '[[ $status == 128 && "$result" == *"missing ref"* && "$result" != *private* && "$result" != *test+secret* && "$result" != *test%2Bsecret* ]]',
    '[[ "$GIT_TERMINAL_PROMPT" == original && "$GIT_ASKPASS" == original-ask && "$SSH_ASKPASS" == original-ssh ]]'
  ].join('\n');
  passed(sh(script,{...process.env,CGRAPH_REPOSITORY:repository}));
  passed(sh([
    'source "$CGRAPH_REPOSITORY/install.sh"',
    'export GIT_SSL_NO_VERIFY=true GIT_SSL_CAINFO=original-ca',
    'git(){ [[ -z "${GIT_SSL_NO_VERIFY:-}" && "$GIT_SSL_CAINFO" == "corporate cert.pem" && "$*" == *http.sslVerify=true* && "$*" == *http.schannelUseSSLCAInfo=true* ]]; }',
    'cgraph_clone main https://example.invalid/repo destination "corporate cert.pem"',
    '[[ "$GIT_SSL_NO_VERIFY" == true && "$GIT_SSL_CAINFO" == original-ca ]]'
  ].join('\n'),{...process.env,CGRAPH_REPOSITORY:repository}));
  const failedVersion=sh('source "$CGRAPH_REPOSITORY/install.sh"; mvn(){ printf "JAVA_HOME fixture error\\n" >&2;return 9; }; cgraph_maven_version mvn',{...process.env,CGRAPH_REPOSITORY:repository});
  assert.equal(failedVersion.status,9);assert.match(failedVersion.stderr,/JAVA_HOME fixture error/);assert.match(failedVersion.stderr,/exit 9/);
});

test('Linux/macOS forwarding defaults to skipped tests and supports explicit opt-in',{skip:!jdk},async()=>{
  await fixture(async env=>{
    const script=[
      'source "$CGRAPH_REPOSITORY/install.sh"',
      'uname(){ printf "%s\\n" "$CGRAPH_TEST_OS"; };cgraph_jdk(){ printf "%s\\n" "$CGRAPH_TEST_JDK"; };mvn(){ printf "Apache Maven 3.9.11\\n"; }',
      'extra=();[[ -z "$CGRAPH_TEST_FLAG" ]] || extra+=("$CGRAPH_TEST_FLAG");[[ -z "${CGRAPH_CERT:-}" ]] || extra+=(--cert-pem "$CGRAPH_CERT")',
      'cgraph_main --source-dir "$CGRAPH_SOURCE" --install-dir "$CGRAPH_TEST_ROOT/unused" --no-path --non-interactive "${extra[@]}"'
    ].join('\n');
    for(const platform of ['Linux','Darwin'])for(const flag of ['','--run-tests','--skip-tests']){
      passed(sh(script,{...env,CGRAPH_TEST_OS:platform,CGRAPH_TEST_FLAG:flag}));
      const args=(await readFile(env.CGRAPH_CAPTURE_ARGS,'utf8')).split('\n');assert.ok(args.includes(flag==='--run-tests'?'--run-tests':'--skip-tests'));
    }
    const pem=path.join(env.CGRAPH_TEST_ROOT,'corporate cert.pem');
    passed(sh(script,{...env,CGRAPH_TEST_OS:'Linux',CGRAPH_TEST_FLAG:'',CGRAPH_CERT:pem}));
    const args=(await readFile(env.CGRAPH_CAPTURE_ARGS,'utf8')).split('\n');
    assert.equal(args[args.indexOf('--cert-pem')+1].replaceAll('\\','/').replace(/^\/([A-Za-z])\//,'$1:/'),pem.replaceAll('\\','/'));
    const conflict=sh('source "$CGRAPH_REPOSITORY/install.sh"; cgraph_main --run-tests --skip-tests',env);
    assert.equal(conflict.status,1);assert.match(conflict.stderr,/not both/);
  });
});
