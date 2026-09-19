import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm,access,copyFile,readdir} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';

// Actual removals are confined to fresh, owned installations. Never change the
// real user's PATH or client profiles. Bash OS/process probes are fixture-driven
// so macOS/Linux code paths can also be checked from Windows Git Bash.
const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const bash=process.env.CGRAPH_TEST_BASH||(process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');
async function fixture(run){
  const root=await mkdtemp(path.join(os.tmpdir(),'cgraph-uninstall-test-'));
  try{
    const home=path.join(root,'home'),install=path.join(root,"install with 'quote"),outside=path.join(root,'user-data');
    await mkdir(home);await mkdir(outside);
    await writeFile(path.join(outside,'profile.json'),'preserve credentials and data');
    await mkdir(path.join(install,'bin'),{recursive:true});await mkdir(path.join(install,'releases','one'),{recursive:true});
    await writeFile(path.join(install,'.cgraph-install'),'code-graph-native-install-v1');
    await writeFile(path.join(install,'bin','cgraph'),'owned shim');
    await writeFile(path.join(install,'releases','one','runtime'),'owned runtime');
    for(const name of ['uninstall.ps1','uninstall.sh'])await copyFile(path.join(repository,name),path.join(install,name));
    const env={...process.env,CGRAPH_REPOSITORY:repository,CGRAPH_TEST_INSTALL:install,CGRAPH_TEST_HOME:home,
      CGRAPH_TEST_OUTSIDE:outside,HOME:home,USERPROFILE:home};
    await run({root,home,install,outside,env});
    assert.equal(await readFile(path.join(outside,'profile.json'),'utf8'),'preserve credentials and data');
  }finally{
    assert.ok(path.basename(root).startsWith('cgraph-uninstall-test-')&&path.dirname(root)===os.tmpdir());
    await rm(root,{recursive:true,force:true});
  }
}
function ps(env,code){return spawnSync('powershell.exe',['-NoProfile','-Command',code],{env,encoding:'utf8',timeout:20000});}
function sh(env,code){return spawnSync(bash,['-c',code],{env,encoding:'utf8',timeout:20000});}
function ok(result){assert.equal(result.status,0,result.stdout+result.stderr);}
function failed(result,pattern){assert.notEqual(result.status,0,result.stdout+result.stderr);assert.match(result.stdout+result.stderr,pattern);}
const psLoad=". (Join-Path $env:CGRAPH_REPOSITORY 'uninstall.ps1') -InstallDir $env:CGRAPH_TEST_INSTALL -KeepPath";
const shLoad='source "$CGRAPH_REPOSITORY/uninstall.sh"\nps(){ printf "1 init\\n"; }\n';

test('Windows uninstall validates ownership, running processes, links and exact PATH matching',{skip:process.platform!=='win32'},async()=>{
  await fixture(async({install,outside,env})=>{
    ok(ps(env,`${psLoad}
      $Check=$true; Invoke-CgraphUninstall
      $bin=Join-Path $env:CGRAPH_TEST_INSTALL 'bin'
      $original='C:\\unrelated;'+$bin+';'+$bin+'-other'
      $expected='C:\\unrelated;'+$bin+'-other'
      if((Get-CgraphPathWithoutInstallation $original $env:CGRAPH_TEST_INSTALL) -ne $expected){throw 'PATH filtering mismatch'}
      function Get-CimInstance { [pscustomobject]@{ProcessId=12345;ExecutablePath=(Join-Path $env:CGRAPH_TEST_INSTALL 'bin/cgraph.exe');Name='cgraph.exe';CommandLine=''} }
      $blocked=$false;try{Assert-CgraphStopped $env:CGRAPH_TEST_INSTALL}catch{$blocked=$true}
      if(!$blocked){throw 'Running app allowed'}
    `));
    failed(ps(env,`${psLoad}\nInvoke-CgraphUninstall`),/Noninteractive uninstall requires/);
    await writeFile(path.join(install,'personal.txt'),'keep');
    failed(ps(env,`${psLoad}\n$Yes=$true; Invoke-CgraphUninstall`),/Unrecognized item/);
    await rm(path.join(install,'personal.txt'));
    ok(ps(env,`${psLoad}
      $link=Join-Path $env:CGRAPH_TEST_INSTALL 'releases/link'
      try {
        New-Item -ItemType Junction -Path $link -Target $env:CGRAPH_TEST_OUTSIDE | Out-Null
        $blocked=$false;try{Get-CgraphUninstallTarget $env:CGRAPH_TEST_INSTALL}catch{$blocked=$true}
        if(!$blocked){throw 'Linked data accepted'}
      }finally{if(Test-Path -LiteralPath $link){[IO.Directory]::Delete($link)}}
      foreach($bad in @($env:CGRAPH_TEST_HOME,[IO.Path]::GetPathRoot($env:CGRAPH_TEST_INSTALL),$env:CGRAPH_TEST_OUTSIDE)){
        $blocked=$false;try{Get-CgraphUninstallTarget $bad}catch{$blocked=$true};if(!$blocked){throw 'Unsafe target accepted'}
      }
    `));
    assert.equal(await readFile(path.join(outside,'profile.json'),'utf8'),'preserve credentials and data');
    // Run the installed copy, verifying it can safely remove its own file.
    ok(ps(env,"& (Join-Path $env:CGRAPH_TEST_INSTALL 'uninstall.ps1') -InstallDir $env:CGRAPH_TEST_INSTALL -Yes -KeepPath"));
    await assert.rejects(access(install));
    ok(ps(env,`${psLoad}\n$Yes=$true; Invoke-CgraphUninstall`));
  });
});

test('macOS/Linux uninstall validates ownership, preserves external data and removes only managed PATH blocks',async()=>{
  for(const platform of ['Darwin','Linux'])await fixture(async({home,install,env})=>{
    env.CGRAPH_TEST_OS=platform;
    const setup=shLoad+'uname(){ printf "%s\\n" "$CGRAPH_TEST_OS"; }\n';
    // Use the platform-native spelling (Git Bash maps Windows temp paths).
    const normalized=sh(env,setup+'cd "$CGRAPH_TEST_INSTALL" && pwd -P');ok(normalized);
    const actual=normalized.stdout.trim(),quoted="'"+actual.replaceAll("'","'\"'\"'")+"/bin'";
    const managed='# cgraph native launcher\nexport PATH='+quoted+':"$PATH"\n';
    const original='export PRESERVED=yes\n'+managed+'# unrelated ending\n';
    await writeFile(path.join(home,'.bashrc'),original);
    await writeFile(path.join(home,'.zshrc'),'unrelated no final newline');
    await writeFile(path.join(home,'.profile'),'# cgraph native launcher\nexport PATH=/some/other/bin:"$PATH"\n');
    ok(sh(env,setup+'cgraph_uninstall_main --install-dir "$CGRAPH_TEST_INSTALL" --check'));
    assert.equal(await readFile(path.join(home,'.bashrc'),'utf8'),original);
    failed(sh(env,setup+'cgraph_uninstall_main --install-dir "$CGRAPH_TEST_INSTALL"'),/Noninteractive uninstall requires/);
    failed(sh(env,setup+'ps(){ printf "444 fake-process %s/bin/cgraph\\n" "$(cd "$CGRAPH_TEST_INSTALL" && pwd -P)"; }\ncgraph_uninstall_main --install-dir "$CGRAPH_TEST_INSTALL" --yes'),/Stop it first/);
    await writeFile(path.join(install,'personal.txt'),'keep');
    failed(sh(env,setup+'cgraph_uninstall_main --install-dir "$CGRAPH_TEST_INSTALL" --yes'),/Unrecognized installation content/);
    await rm(path.join(install,'personal.txt'));
    for(const target of ['$CGRAPH_TEST_HOME','$CGRAPH_TEST_OUTSIDE','/'])failed(sh(env,setup+`cgraph_uninstall_main --install-dir "${target}" --yes`),/Refusing root or home|Not a verified/);
    // Installed script, default directory selection; no root or real profile modifications.
    ok(sh(env,'source "$CGRAPH_TEST_INSTALL/uninstall.sh"\nps(){ printf "1 init\\n"; }\ncgraph_uninstall_main --yes'));
    await assert.rejects(access(install));
    assert.equal(await readFile(path.join(home,'.bashrc'),'utf8'),'export PRESERVED=yes\n# unrelated ending\n');
    assert.equal(await readFile(path.join(home,'.zshrc'),'utf8'),'unrelated no final newline');
    assert.equal(await readFile(path.join(home,'.profile'),'utf8'),'# cgraph native launcher\nexport PATH=/some/other/bin:"$PATH"\n');
    const backups=(await readdir(home)).filter(n=>n.startsWith('.bashrc.cgraph-backup.'));
    assert.equal(backups.length,1);assert.equal(await readFile(path.join(home,backups[0]),'utf8'),original);
    ok(sh(env,setup+'cgraph_uninstall_main --install-dir "$CGRAPH_TEST_INSTALL" --yes'));
  });
});
