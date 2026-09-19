import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm,access} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';

// Exercise actual bootstrap argument forwarding, using a harmless JDK source-launcher
// fixture instead of Maven/jpackage. No application, PATH, skill, or MCP settings are installed.
const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const jdk=process.env.CGRAPH_JAVA_HOME||process.env.JAVA_HOME;
const bash=process.env.CGRAPH_TEST_BASH||(process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');
const fixtureSource=[
  'import java.nio.file.*;',
  'class CgraphInstaller { public static void main(String[] args) throws Exception {',
  'Files.writeString(Path.of(System.getenv("CGRAPH_CAPTURE_ARGS")),String.join("\\n",args));',
  'System.exit(Integer.parseInt(System.getenv().getOrDefault("CGRAPH_FIXTURE_EXIT","0")));',
  '}}'
].join('\n');

async function fixture(run) {
  const root=await mkdtemp(path.join(os.tmpdir(),'cgraph-bootstrap-skill-test-'));
  try {
    const source=path.join(root,'source with spaces');
    await mkdir(path.join(source,'installer'),{recursive:true});
    await writeFile(path.join(source,'installer/CgraphInstaller.java'),fixtureSource);
    const env={...process.env,CGRAPH_SKILL_FIXTURE:source,CGRAPH_INSTALL_FIXTURE:path.join(root,'unused install'),
      CGRAPH_CAPTURE_ARGS:path.join(root,'arguments.txt'),CGRAPH_TEST_JDK:jdk,CGRAPH_REPOSITORY:repository};
    for(const name of ['HTTPS_PROXY','HTTP_PROXY','https_proxy','http_proxy','CGRAPH_PROXY_USER','CGRAPH_PROXY_PASSWORD'])
      delete env[name];
    await run(env);
    await assert.rejects(access(env.CGRAPH_INSTALL_FIXTURE),'fixture must not create an application install');
  } finally {await rm(root,{recursive:true,force:true});}
}
async function forwarded(env) {return (await readFile(env.CGRAPH_CAPTURE_ARGS,'utf8')).split('\n');}
function assertSelection(args,selection) {
  const at=args.indexOf('--skills');
  if(selection===null)assert.equal(at,-1,'omission delegates optional prompting to shared installer');
  else {assert.ok(at>=0);assert.equal(args[at+1],selection);}
  assert.ok(args.includes('--non-interactive'));
  assert.ok(args.includes('--no-path'));
}

test('PowerShell bootstrap forwards all/subset/none/omitted skills and reports partial success',
  {skip:process.platform!=='win32'||!jdk},async()=>{
    await fixture(async env=>{
      const command=[
        ". (Join-Path $env:CGRAPH_REPOSITORY 'install.ps1') -SourceDir $env:CGRAPH_SKILL_FIXTURE -InstallDir $env:CGRAPH_INSTALL_FIXTURE -NoPath -NonInteractive",
        "function Get-CgraphRequirements { [pscustomobject]@{Jdk=$env:CGRAPH_TEST_JDK;Git=[pscustomobject]@{Source='unused-git'};Maven=[pscustomobject]@{Source='unused-maven'};MavenOk=$true} }",
        "$Skills=$env:CGRAPH_SELECTED_SKILLS",
        "try { Invoke-CgraphInstall; exit 0 } catch { Write-Output $_.Exception.Message; exit 1 }"
      ].join('\n');
      for(const selection of ['all','codex,claude','none',null]) {
        const result=spawnSync('powershell.exe',['-NoProfile','-Command',command],
          {env:{...env,CGRAPH_SELECTED_SKILLS:selection||''},encoding:'utf8',timeout:20000});
        assert.equal(result.status,0,result.stdout+result.stderr);
        assertSelection(await forwarded(env),selection);
      }
      const partial=spawnSync('powershell.exe',['-NoProfile','-Command',command],
        {env:{...env,CGRAPH_SELECTED_SKILLS:'all',CGRAPH_FIXTURE_EXIT:'2'},encoding:'utf8',timeout:20000});
      assert.equal(partial.status,1);assert.match(partial.stdout,/Application installed, but optional skill installation was incomplete/);
    });
  });

test('Bash bootstrap forwards skill choices for Linux/macOS and preserves partial-success status',
  {skip:!jdk},async()=>{
    await fixture(async env=>{
      const command=[
        'source "$CGRAPH_REPOSITORY/install.sh"',
        'uname(){ printf "%s\\n" "$CGRAPH_TEST_OS"; }',
        'cgraph_jdk(){ printf "%s\\n" "$CGRAPH_TEST_JDK"; }',
        'mvn(){ printf "Apache Maven 3.9.11\\n"; }',
        'git(){ printf "Unexpected Git call\\n" >&2; return 1; }',
        'extra=()',
        '[[ -z "$CGRAPH_SELECTED_SKILLS" ]] || extra+=(--skills "$CGRAPH_SELECTED_SKILLS")',
        'cgraph_main --source-dir "$CGRAPH_SKILL_FIXTURE" --install-dir "$CGRAPH_INSTALL_FIXTURE" --no-path --non-interactive "${extra[@]}"'
      ].join('\n');
      for(const platform of ['Linux','Darwin'])for(const selection of ['all','codex,claude','none',null]) {
        const result=spawnSync(bash,['-c',command],
          {env:{...env,CGRAPH_SELECTED_SKILLS:selection||'',CGRAPH_TEST_OS:platform},encoding:'utf8',timeout:20000});
        assert.equal(result.status,0,result.stdout+result.stderr);
        assertSelection(await forwarded(env),selection);
      }
      const partial=spawnSync(bash,['-c',command],
        {env:{...env,CGRAPH_SELECTED_SKILLS:'all',CGRAPH_TEST_OS:'Linux',CGRAPH_FIXTURE_EXIT:'2'},encoding:'utf8',timeout:20000});
      assert.equal(partial.status,2);assert.match(partial.stderr,/Application installed, but optional skills were incomplete/);
    });
  });
