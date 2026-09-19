#!/usr/bin/env node
'use strict';
// Explicit, read-only navigation workload. No project onboarding, mutation, or server launch.
// Usage: node scripts/benchmark-navigation.cjs http://localhost:3000/mcp code-graph before
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const endpoint = process.argv[2] || 'http://localhost:3000/mcp';
const project = process.argv[3] || 'code-graph';
const phase = process.argv[4] || 'sample';
const root = process.cwd();
let session, sequence = 0;
const events = [];
async function rpc(method, params) {
  const start = performance.now();
  const response = await fetch(endpoint, {method:'POST', headers:{
    'Content-Type':'application/json', 'Accept':'application/json, text/event-stream',
    ...(session ? {'Mcp-Session-Id':session} : {})
  }, body:JSON.stringify({jsonrpc:'2.0',id:++sequence,method,params})});
  session ||= response.headers.get('Mcp-Session-Id');
  const text = await response.text();
  if (!response.ok) throw Error('MCP HTTP '+response.status+': '+text.slice(0,300));
  const data = text.split('\n').find(line=>line.startsWith('data:'));
  const body = data ? JSON.parse(data.slice(5)) : JSON.parse(text);
  if(body.error) throw Error(JSON.stringify(body.error));
  events.push({category:method==='tools/call'?'mcp_symbol_query':'mcp_protocol',method,tool:params?.name,
    elapsedMs:performance.now()-start,responseBytes:Buffer.byteLength(text)});
  return body.result;
}
function search(pattern, paths) {
  const start = performance.now();
  const result = spawnSync('rg',['-n',pattern,...paths],{cwd:root,encoding:'utf8',maxBuffer:4*1024*1024});
  if(result.error || result.status>1)throw result.error || Error(result.stderr);
  events.push({category:'filesystem_search',pattern,paths,elapsedMs:performance.now()-start,
    responseBytes:Buffer.byteLength(result.stdout),matchingLines:result.stdout.trim()?result.stdout.trim().split('\n').length:0});
  return result.stdout;
}
(async()=>{
  await rpc('initialize',{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'precision-navigation-benchmark',version:'1'}});
  // Each fixed task uses one indexed query and one read-only text-search baseline.
  // These are different instruments, not claims that grep alone resolves semantic references.
  const results = [];
  for(let run=1;run<=3;run++) {
    const discovery = await rpc('tools/call',{name:'search_symbols',arguments:{project,query:'NativeRedisArguments.bytes',limit:10}});
    const symbol = discovery.structuredContent?.data?.symbols?.[0]?.id ||
      JSON.parse(discovery.content.find(c=>c.type==='text').text).symbols[0].id;
    const callers = await rpc('tools/call',{name:'find_references',arguments:{project,symbol_id:symbol,limit:100}});
    const textMatches = search('NativeRedisArguments\\.bytes\\(',['code-graph-dba/src/main/java','code-graph-dba/src/test/java']);
    results.push({run,symbol,callers,textMatches});
  }
  const report={phase,createdAt:new Date().toISOString(),root,endpoint,project,
    measurements:'Warm server samples; OS file cache not cleared. No productivity baseline is inferred.',
    counts:events.reduce((a,e)=>(a[e.category]=(a[e.category]||0)+1,a),{}),events,results};
  const destination=path.join(root,'docs/validation/dependency-precision/navigation-'+phase+'.json');
  fs.mkdirSync(path.dirname(destination),{recursive:true});fs.writeFileSync(destination,JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({destination,counts:report.counts,events}));
  if(session)await fetch(endpoint,{method:'DELETE',headers:{'Mcp-Session-Id':session}});
})().catch(error=>{console.error(error.message);process.exitCode=1;});
