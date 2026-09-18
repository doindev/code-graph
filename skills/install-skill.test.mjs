import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,mkdir,rm,symlink,access} from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {clients,installSkill} from './install-skill.mjs';

test('all selected clients receive skill and references; unchanged installs are idempotent',async()=>{
  const project=await mkdtemp(path.join(os.tmpdir(),'cgraph-skill-test-'));
  try {
    for(const client of Object.keys(clients)) {
      const result=await installSkill({project,client});
      assert.equal(result.status,'installed');
      assert.match(await readFile(path.join(result.destination,'SKILL.md'),'utf8'),/name: code-graph/);
      await access(path.join(result.destination,'references','approvals-and-jobs.md'));
      assert.equal((await installSkill({project,client})).status,'already-installed');
      await assert.rejects(access(path.join(project,clients[client],'mcp.json')));
    }
  } finally { await rm(project,{recursive:true,force:true}); }
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
