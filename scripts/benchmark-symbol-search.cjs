#!/usr/bin/env node
'use strict';
// Real HTTP MCP name searches. Never mutate sources, restart a user server, or use user stores.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {host}=require('./run-efficiency-benchmark.cjs');
const {McpClient,unpack}=require('./mcp-benchmark-client.cjs');
const queries=[
  {id:'qualified-java-function',query:'NativeRedisArguments.bytes',kind:'function',lang:'java',limit:10},
  {id:'java-class',query:'SearchSymbolsTool',kind:'class',lang:'java',limit:20},
  {id:'storage-class',query:'PagedGraph',kind:'class',lang:'java',limit:20},
  {id:'indexer-method',query:'IncrementalIndexer.apply',kind:'function',lang:'java',limit:20},
  {id:'ambiguous-resolve',query:'resolve',kind:'function',lang:'java',limit:20},
  {id:'common-close',query:'close',kind:'function',lang:'java',limit:20},
  {id:'broad-get',query:'get',kind:'function',limit:100},
  {id:'broad-table',query:'Table',limit:100},
  {id:'javascript-grid',query:'DataGridView',limit:20},
  {id:'javascript-function',query:'openTableTab',kind:'function',lang:'js',limit:20},
  {id:'file-name',query:'Watcher',kind:'file',limit:20},
  {id:'missing-name',query:'__symbol_benchmark_absent_81f93d72__',limit:20}
];
function hash(value){return crypto.createHash('sha256').update(value).digest('hex');}
function percentile(values,p){const a=[...values].sort((a,b)=>a-b);return a[Math.min(a.length-1,Math.ceil(a.length*p)-1)];}
function stats(samples){
  const values=samples.map(s=>s.elapsedMs),bytes=samples.map(s=>s.responseBytes);
  return {count:values.length,p50Ms:percentile(values,.5),p95Ms:percentile(values,.95),p99Ms:percentile(values,.99),
    meanMs:values.reduce((a,b)=>a+b,0)/values.length,queriesPerSecond:1000*values.length/values.reduce((a,b)=>a+b,0),
    meanResponseBytes:bytes.reduce((a,b)=>a+b,0)/bytes.length};
}
function inventory(root){
  const rows=[];
  function walk(dir){
    for(const entry of fs.readdirSync(dir,{withFileTypes:true})){
      if(entry.isSymbolicLink())continue;
      const file=path.join(dir,entry.name),relative=path.relative(root,file).replaceAll('\\','/');
      if(entry.isDirectory()){
        if(!['target','.git','node_modules','.idea','.code-graph'].includes(entry.name))walk(file);
      }else if(entry.isFile())rows.push({path:relative,sha256:hash(fs.readFileSync(file))});
    }
  }
  walk(root);rows.sort((a,b)=>a.path.localeCompare(b.path,'en'));
  return {files:rows.length,sha256:hash(JSON.stringify(rows))};
}
function canonical(data){
  const copy={...data};delete copy.generation;delete copy.nextCursor;
  return copy;
}
async function main(baseline,candidate,dataset,output){
  if(!output)throw Error('Usage: node scripts/benchmark-symbol-search.cjs BASELINE_CLASSPATH CANDIDATE_CLASSPATH FROZEN_SOURCE OUTPUT');
  fs.mkdirSync(output,{recursive:true});
  const inputs=inventory(dataset),expected=new Map(),all=[];
  fs.writeFileSync(path.join(output,'manifest.json'),JSON.stringify({
    startedAt:new Date().toISOString(),baseline,candidate,dataset,inputInventory:inputs,queries,
    independentRunsPerBuild:3,warmupRequestsPerRun:24,mixedRequestsPerRun:120,repeatedRequestsPerRun:60,
    scope:'HTTP tools/call through response receipt and JSON decoding; no agent/model scheduling overhead',
    cache:'Fresh JVM/store per run; first query follows indexing. OS cache is NOT cleared. MCP search rescans; this is not the graph point-lookup cache.',
    heapCeilingMiB:768,sharedGraphAllowanceMiB:32
  },null,2)+'\n');
  for(let iteration=1;iteration<=3;iteration++){
    const order=iteration%2?[['baseline',baseline],['candidate',candidate]]:[['candidate',candidate],['baseline',baseline]];
    for(const [phase,classpath]of order){
      console.log(new Date().toISOString()+' Starting '+phase+' '+iteration);
      const server=await host(classpath,dataset),client=new McpClient(server.ready.endpoint);
      const report={phase,iteration,host:server.ready,samples:[],warmup:[]};
      let complete=false;
      try{
        report.initialize=await client.initialize();
        report.projects=unpack((await client.tool('list_projects',{})).result);
        assert.equal(report.projects.projects.length,1);
        assert.equal(report.projects.projects[0].state,'ready');
        const project=report.projects.projects[0].name;
        report.before=unpack((await client.tool('index_status',{project})).result);
        console.log(phase+' '+iteration+' indexed: '+JSON.stringify(report.before));
        let generation;
        async function search(query,category){
          const {id,...args}=query;
          const response=await client.tool('search_symbols',{project,...args});
          const data=unpack(response.result);
          assert.ok(Array.isArray(data.symbols),'Missing symbol payload');
          generation??=data.generation;
          assert.equal(data.generation,generation,'Generation changed during timing');
          const stable=canonical(data),resultHash=hash(JSON.stringify(stable));
          if(expected.has(id))assert.deepEqual(stable,expected.get(id),'Old/new or repeat result mismatch: '+id);
          else expected.set(id,stable);
          const sample={category,id,elapsedMs:response.elapsedMs,responseBytes:response.responseBytes,
            generation,returned:data.symbols.length,total:data.total,truncated:data.truncated,resultHash};
          (category==='warmup'?report.warmup:report.samples).push(sample);
        }
        await search(queries[0],'first');
        for(let i=0;i<24;i++)await search(queries[i%queries.length],'warmup');
        for(let round=0;round<10;round++)for(const query of queries)await search(query,'mixed');
        for(let i=0;i<60;i++)await search(queries[0],'repeated');
        report.after=unpack((await client.tool('index_status',{project})).result);
        assert.equal(report.after.generation,report.before.generation);
        report.summaries=Object.fromEntries(['first','mixed','repeated'].map(category=>[category,stats(report.samples.filter(s=>s.category===category))]));
        report.byQuery=Object.fromEntries(queries.map(q=>[q.id,stats(report.samples.filter(s=>s.category==='mixed'&&s.id===q.id))]));
        complete=true;
      }finally{
        try{await client.close();}finally{
          const ended=await server.close();report.metrics=ended.metrics;report.complete=complete;
          fs.writeFileSync(path.join(output,phase+'-'+iteration+'.json'),JSON.stringify(report,null,2)+'\n');
          fs.writeFileSync(path.join(output,phase+'-'+iteration+'.log'),ended.stderr);
        }
      }
      all.push(report);
      console.log(phase+' '+iteration+' finished '+JSON.stringify(report.summaries));
    }
  }
  assert.deepEqual(inventory(dataset),inputs,'Frozen dataset changed');
  const summary={completedAt:new Date().toISOString(),inputInventory:inputs,
    equivalentResults:true,resultWitnesses:Object.fromEntries(expected),builds:{}};
  for(const phase of ['baseline','candidate']){
    const runs=all.filter(r=>r.phase===phase);
    summary.builds[phase]={runs:runs.map(r=>r.summaries),
      pooled:Object.fromEntries(['first','mixed','repeated'].map(c=>[c,stats(runs.flatMap(r=>r.samples.filter(s=>s.category===c)))])),
      byQuery:Object.fromEntries(queries.map(q=>[q.id,stats(runs.flatMap(r=>r.samples.filter(s=>s.category==='mixed'&&s.id===q.id)))]))};
  }
  fs.writeFileSync(path.join(output,'summary.json'),JSON.stringify(summary,null,2)+'\n');
  console.log('ALL PASSED: equivalent results, stable generations, unchanged dataset, six isolated servers closed.');
}
if(require.main===module)main(...process.argv.slice(2)).catch(error=>{console.error(error);process.exitCode=1;});
module.exports={queries,stats,canonical,inventory};
