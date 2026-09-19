import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,readFile,rm} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';

// Optional real-client config parser checks. Never uses the real user config,
// starts an agent, executes a tool, connects a database or logs into a client.
const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const jdk=process.env.CGRAPH_JAVA_HOME||process.env.JAVA_HOME;
for(const [client,key,args] of [
  ['codex','CGRAPH_CODEX_ENTRY',['mcp','list','--json']],
  ['copilot','CGRAPH_COPILOT_ENTRY',['mcp','get','code-graph','--json']]
]) test(`real ${client} CLI reads generated user MCP entry`,{skip:!jdk||!process.env[key]},async()=>{
  const home=await mkdtemp(path.join(os.tmpdir(),'cgraph-mcp-client-smoke-'));
  try {
    const env={...process.env,HOME:home,USERPROFILE:home,CODEX_HOME:path.join(home,'.codex'),
      COPILOT_HOME:path.join(home,'.copilot'),CLAUDE_CONFIG_DIR:path.join(home,'.claude'),
      APPDATA:path.join(home,'AppData/Roaming'),LOCALAPPDATA:path.join(home,'AppData/Local'),
      XDG_CONFIG_HOME:path.join(home,'.config'),XDG_CACHE_HOME:path.join(home,'.cache'),
      DO_NOT_TRACK:'1'};
    for(const dir of [env.CODEX_HOME,env.COPILOT_HOME,env.APPDATA,env.LOCALAPPDATA])await mkdir(dir,{recursive:true});
    const install=spawnSync(path.join(jdk,'bin',process.platform==='win32'?'java.exe':'java'),
      [`-Duser.home=${home}`,path.join(repository,'installer/McpInstaller.java'),'--clients',client],
      {cwd:home,env,encoding:'utf8',timeout:30000});
    assert.equal(install.status,0,install.stdout+install.stderr);
    const config=client==='codex'?path.join(env.CODEX_HOME,'config.toml'):path.join(env.COPILOT_HOME,'mcp-config.json');
    const before=await readFile(config,'utf8');
    const result=spawnSync(process.execPath,[process.env[key],...args],{cwd:home,env,encoding:'utf8',timeout:30000});
    assert.equal(result.status,0,result.stdout+result.stderr);
    assert.match(result.stdout,/code-graph/);assert.match(result.stdout,/http:\/\/localhost:3000\/mcp/);
    assert.equal(await readFile(config,'utf8'),before,'Read-only CLI check must preserve configuration');
  } finally {await rm(home,{recursive:true,force:true});}
});
