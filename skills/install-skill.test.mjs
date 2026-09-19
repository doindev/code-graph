import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,mkdir,rm,symlink,access,readdir,realpath} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import os from 'node:os';
import {clients,installSkill,installSkills,parseOptions,selectClients} from './install-skill.mjs';
import {spawnSync} from 'node:child_process';

const source=path.join(path.dirname(fileURLToPath(import.meta.url)),'code-graph');
async function inventory(root,relative='') {
  const result={};
  for(const entry of await readdir(path.join(root,relative),{withFileTypes:true})) {
    const name=path.join(relative,entry.name);
    if(entry.isDirectory())Object.assign(result,await inventory(root,name));
    else result[name]=await readFile(path.join(root,name),'utf8');
  }
  return result;
}

test('all selected clients receive skill and references; unchanged installs are idempotent',async()=>{
  const project=await mkdtemp(path.join(os.tmpdir(),'cgraph-skill-test-'));
  try {
    for(const client of Object.keys(clients)) {
      const result=await installSkill({project,client});
      assert.equal(result.status,'installed');
      assert.match(await readFile(path.join(result.destination,'SKILL.md'),'utf8'),/name: code-graph/);
      await access(path.join(result.destination,'references','approvals-and-jobs.md'));
      assert.deepEqual(await inventory(result.destination),await inventory(source));
      assert.equal((await installSkill({project,client})).status,'already-installed');
      await assert.rejects(access(path.join(project,clients[client],'mcp.json')));
    }
  } finally { await rm(project,{recursive:true,force:true}); }
});

test('all local Markdown reference links resolve inside the maintained skill',async()=>{
  const files=await inventory(source);
  for(const [name,body] of Object.entries(files)) {
    if(!name.endsWith('.md'))continue;
    for(const match of body.matchAll(/\]\(([^)]+)\)/g)) {
      const target=match[1];
      if(/^(https?:|#)/.test(target))continue;
      const resolved=path.resolve(source,path.dirname(name),target.split('#')[0]);
      assert.ok(resolved.startsWith(path.resolve(source)+path.sep),'reference escapes skill: '+target);
      await access(resolved);
    }
  }
});

test('dry run does not create directories and customized skills are never overwritten',async()=>{
  const project=await mkdtemp(path.join(os.tmpdir(),'cgraph-skill-test-'));
  try {
    assert.equal((await installSkill({project,client:'codex',dryRun:true})).status,'would-install');
    await assert.rejects(access(path.join(project,'.agents')));
    const {destination}=await installSkill({project,client:'codex'});
    const file=path.join(destination,'SKILL.md');
    await writeFile(file,'custom instructions');
    await assert.rejects(installSkill({project,client:'codex'}),/Nothing was overwritten/);
    assert.equal(await readFile(file,'utf8'),'custom instructions');
    await assert.rejects(installSkill({project,client:'unknown'}));
    await assert.rejects(installSkill({client:'codex'}));
  } finally { await rm(project,{recursive:true,force:true}); }
});

test('destination junction/symlink cannot redirect installation outside the chosen project',async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'cgraph-skill-test-'));
  try {
    const project=path.join(root,'project'),other=path.join(root,'other');
    await mkdir(project);await mkdir(other);
    await symlink(other,path.join(project,'.agents'),process.platform==='win32'?'junction':'dir');
    await assert.rejects(installSkill({project,client:'codex'}),/linked/);
    await assert.rejects(access(path.join(other,'skills')));
  } finally { await rm(root,{recursive:true,force:true}); }
});

test('global all/subset/none copies complete skills only to selected current-user destinations',async()=>{
  const userHome=await mkdtemp(path.join(os.tmpdir(),'cgraph-skill-test-'));
  try {
    const options={userHome,environment:{},global:true,client:'none'};
    assert.deepEqual(await installSkills(options),[]);
    assert.deepEqual(await readdir(userHome),[]);
    const subset=await installSkills({...options,client:'codex,claude'});
    assert.deepEqual(subset.map(item=>item.status),['installed','installed']);
    await assert.rejects(access(path.join(userHome,'.copilot')));
    const results=await installSkills({...options,client:'all'});
    const globalRoots={codex:'.agents',copilot:'.copilot',claude:'.claude',windsurf:'.codeium/windsurf'};
    for(const result of results) {
      assert.equal(result.destination,path.join(await realpath(userHome),globalRoots[result.client],'skills/code-graph'));
      assert.deepEqual(await inventory(result.destination),await inventory(source));
      const base=path.join(userHome,globalRoots[result.client]);
      assert.deepEqual(await readdir(base),['skills'],'no client settings/MCP configuration written');
    }
    assert.ok((await installSkills({...options,client:'all'})).every(item=>item.status==='already-installed'));
  } finally {await rm(userHome,{recursive:true,force:true});}
});

test('global dry-run and documented config overrides are explicit; customized clients do not block others',async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'cgraph-skill-test-'));
  try {
    const userHome=path.join(root,'user');await mkdir(userHome);
    const options={userHome,environment:{},global:true,client:'all'};
    assert.ok((await installSkills({...options,dryRun:true})).every(item=>item.status==='would-install'));
    assert.deepEqual(await readdir(userHome),[]);
    const first=await installSkill({...options,client:'codex'});
    await writeFile(path.join(first.destination,'references','approvals-and-jobs.md'),'customized policy');
    const results=await installSkills(options);
    assert.equal(results[0].status,'failed');assert.match(results[0].error,/Nothing was overwritten/);
    assert.ok(results.slice(1).every(item=>item.status==='installed'));
    assert.equal(await readFile(path.join(first.destination,'references','approvals-and-jobs.md'),'utf8'),'customized policy');
    const environment={CLAUDE_CONFIG_DIR:path.join(root,'claude config'),COPILOT_HOME:path.join(root,'copilot config')};
    const redirected=await installSkills({...options,client:'claude,copilot',environment});
    assert.ok(redirected.every(item=>item.status==='installed'));
    assert.equal(redirected[0].destination,path.join(environment.CLAUDE_CONFIG_DIR,'skills/code-graph'));
    assert.equal(redirected[1].destination,path.join(environment.COPILOT_HOME,'skills/code-graph'));
    await assert.rejects(installSkill({...options,client:'claude',environment:{CLAUDE_CONFIG_DIR:'relative'}}),/absolute/);
    const codex=await installSkill({...options,client:'codex',userHome:root,environment:{CODEX_HOME:path.join(root,'codex config')},dryRun:true});
    assert.equal(codex.destination,path.join(root,'.agents/skills/code-graph'));
  } finally {await rm(root,{recursive:true,force:true});}
});

test('global installation refuses linked configuration roots and does not touch the redirect target',async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'cgraph-skill-test-'));
  try {
    const userHome=path.join(root,'user'),outside=path.join(root,'outside');
    await mkdir(userHome);await mkdir(outside);
    await symlink(outside,path.join(userHome,'.copilot'),process.platform==='win32'?'junction':'dir');
    await assert.rejects(installSkill({client:'copilot',global:true,userHome,environment:{}}),/linked/);
    assert.deepEqual(await readdir(outside),[]);
  } finally {await rm(root,{recursive:true,force:true});}
});

test('CLI choices preserve project mode and require explicit scope',()=>{
  assert.deepEqual(selectClients('Codex, Claude'),['codex','claude']);
  for(const selection of [undefined,'','none,claude','all,codex','codex,codex','codex,','unknown'])
    assert.throws(()=>selectClients(selection));
  assert.deepEqual(parseOptions(['--client','all','--global','--dry-run']),{client:'all',global:true,dryRun:true});
  assert.deepEqual(parseOptions(['--client','claude','--project','project with spaces']),{client:'claude',project:'project with spaces'});
  for(const args of [[],['--client','all'],['--client','none','--global','--project','x'],['--client','--global'],
    ['--client','all','--global','--global'],['--unknown']])assert.throws(()=>parseOptions(args));
});

test('real CLI global installs honor a disposable user home without requiring a running MCP',async()=>{
  const root=await mkdtemp(path.join(os.tmpdir(),'cgraph-skill-test-'));
  try {
    const env={...process.env,HOME:root,USERPROFILE:root};
    delete env.COPILOT_HOME;delete env.CLAUDE_CONFIG_DIR;
    const helper=fileURLToPath(new URL('./install-skill.mjs',import.meta.url));
    const run=(...args)=>spawnSync(process.execPath,[helper,...args],{env,encoding:'utf8'});
    const installed=run('--client','all','--global');
    assert.equal(installed.status,0,installed.stderr);
    assert.equal(JSON.parse(installed.stdout).length,4);
    for(const result of JSON.parse(installed.stdout))assert.ok(result.destination.startsWith(root+path.sep));
    const help=run('--help');assert.equal(help.status,0);assert.match(help.stdout,/Optional guidance only/);
    const repeat=run('--client','codex','--global');assert.equal(JSON.parse(repeat.stdout).status,'already-installed');
    await writeFile(path.join(root,'.agents/skills/code-graph/SKILL.md'),'local override');
    const conflict=run('--client','all','--global');
    assert.equal(conflict.status,1);assert.equal(JSON.parse(conflict.stdout)[0].status,'failed');
  } finally {await rm(root,{recursive:true,force:true});}
});
