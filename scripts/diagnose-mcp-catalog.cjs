#!/usr/bin/env node
'use strict';
// READ ONLY: initialize/list catalog. Never calls application tools or retries operations.
const fs=require('node:fs');
const crypto=require('node:crypto');
const {McpClient}=require('./mcp-benchmark-client.cjs');
function canonical(value) {
  if(Array.isArray(value))return value.map(canonical);
  if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().map(k=>[k,canonical(value[k])]));
  return value;
}
function fingerprint(tools) {
  return crypto.createHash('sha256').update(JSON.stringify(canonical([...tools].sort((a,b)=>a.name<b.name?-1:a.name>b.name?1:0)))).digest('hex');
}
function compare(server,observed) {
  const actual=new Map(server.map(t=>[t.name,t])),client=new Map(observed.map(t=>typeof t==='string'?[t,{name:t}]:[t.name,t]));
  return {
    missingFromClient:[...actual.keys()].filter(n=>!client.has(n)),
    missingFromServer:[...client.keys()].filter(n=>!actual.has(n)),
    differentDefinitions:[...actual.keys()].filter(n=>client.has(n)&&Object.keys(client.get(n)).length>1&&
      JSON.stringify(canonical(actual.get(n)))!==JSON.stringify(canonical(client.get(n))))
  };
}
async function list(client) {
  let cursor;const tools=[];let pages=0;
  do {
    const response=await client.rpc('tools/list',cursor?{cursor}:{});
    if(++pages>32||tools.length+response.result.tools.length>4096)throw Error('Catalog exceeds diagnostic bounds');
    tools.push(...response.result.tools);cursor=response.result.nextCursor;
  }while(cursor);
  return tools;
}
async function diagnose(endpoint,observedFile) {
  const url=new URL(endpoint);
  if(!['localhost','127.0.0.1','[::1]'].includes(url.hostname)||url.protocol!=='http:')throw Error('Use a loopback HTTP MCP endpoint');
  const fresh=new McpClient(endpoint);
  try {
    const init=await fresh.initialize(),tools=await list(fresh);
    const report={server:init.result.serverInfo,freshCount:tools.length,fingerprint:fingerprint(tools),comparisons:{}};
    if(process.env.CGRAPH_DIAGNOSTIC_SESSION) {
      const existing=new McpClient(endpoint);existing.session=process.env.CGRAPH_DIAGNOSTIC_SESSION;
      try {report.comparisons.existingSession=compare(tools,await list(existing));}
      catch(error){report.comparisons.existingSession={unavailable:true,guidance:'Existing session is stale or unavailable; initialize a new session. Never retry writes automatically.'};}
      // The supplied session belongs to its client. Do not terminate it.
    }
    if(observedFile) {
      if(fs.statSync(observedFile).size>4*1024*1024)throw Error('Observed catalog exceeds 4 MiB');
      const observed=JSON.parse(fs.readFileSync(observedFile,'utf8'));
      const items=Array.isArray(observed)?observed:observed.tools;
      if(!Array.isArray(items))throw Error('Expected tools/list JSON or a tools array');
      report.comparisons.client=compare(tools,items);
    }
    report.guidance='If fresh sessions agree but the client differs, refresh/reconnect the client. Check client filtering separately from missing server registration. This diagnostic does not modify client configuration.';
    return report;
  }finally {await fresh.close();}
}
if(require.main===module)diagnose(process.argv[2]||'http://localhost:3000/mcp',process.argv[3])
  .then(report=>console.log(JSON.stringify(report,null,2))).catch(error=>{console.error(error.message);process.exitCode=1;});
module.exports={canonical,fingerprint,compare,diagnose};
