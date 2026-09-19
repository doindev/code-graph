#!/usr/bin/env node
'use strict';
// Production binaries are read-only. Only uniquely owned temporary sources/stores are modified.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto');
const {spawn}=require('node:child_process'),assert=require('node:assert/strict');
const {McpClient,unpack}=require('./mcp-benchmark-client.cjs');
const {queries,canonical,inventory}=require('./benchmark-symbol-search.cjs');
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const hash=v=>crypto.createHash('sha256').update(v).digest('hex');
const PROBE='__mixed_latency__/LatencyProbe.java';
function probe(sequence,revision) {
  const target=sequence%2?'alpha':'beta',count=sequence%3+1;
  return `package latencyprobe;\nclass LatencyProbe {\n static void entry(){ ${`${target}(); `.repeat(count)} }\n static void alpha(){}\n static void beta(){}\n static void revision${revision}(){}\n}\n// edit ${sequence}\n`;
}
function stats(values) {
  const sorted=[...values].sort((a,b)=>a-b),p=q=>sorted[Math.min(sorted.length-1,Math.ceil(sorted.length*q)-1)]??null;
  return {count:values.length,p50Ms:p(.5),p95Ms:p(.95),p99Ms:p(.99),maxMs:p(1),meanMs:values.length?values.reduce((a,b)=>a+b,0)/values.length:null};
}
function copy(source,target) {
  fs.mkdirSync(target,{recursive:true});
  for(const entry of fs.readdirSync(source,{withFileTypes:true})) {
    if(entry.isSymbolicLink())continue;
    if(entry.isDirectory()) {if(!['target','.git','node_modules','.idea','.code-graph'].includes(entry.name))copy(path.join(source,entry.name),path.join(target,entry.name));}
    else if(entry.isFile())fs.copyFileSync(path.join(source,entry.name),path.join(target,entry.name));
  }
}
async function host(classpath,root,token,report) {
  const runtime=path.join(path.dirname(root),'runtime');fs.mkdirSync(runtime);
  const child=spawn('java',['-Xmx768m','-Djava.io.tmpdir='+runtime,'--enable-native-access=ALL-UNNAMED','-cp',classpath,path.join(__dirname,'MixedLoadServer.java'),root,token],{windowsHide:true,stdio:['pipe','pipe','pipe']});
  const pending=new Map();let buffer='',stderr='',ready,sequence=0,exit,metrics;
  const exited=new Promise(resolve=>child.once('exit',(code,signal)=>{exit={code,signal};resolve(exit);}));
  child.once('error',error=>{exit={error:error.message};});
  child.stderr.on('data',chunk=>{stderr=(stderr+chunk).slice(-1024*1024);});
  child.stdout.on('data',chunk=>{
    buffer+=chunk;let newline;
    while((newline=buffer.indexOf('\n'))>=0) {
      const line=buffer.slice(0,newline).trim();buffer=buffer.slice(newline+1);const equal=line.indexOf('=');
      if(equal<0)continue;
      const type=line.slice(0,equal),value=JSON.parse(line.slice(equal+1));
      if(type==='READY')ready=value;
      if(type==='ACK') {pending.get(value.id)?.(value);pending.delete(value.id);}
      if(type==='PUBLICATION')report.publications.push(value);
      if(type==='OBSERVER_ERROR')report.errors.push({observer:value});
      if(type==='METRICS')metrics=value;
    }
  });
  const deadline=performance.now()+240000;
  try { while(!ready) {
    if(exit)throw Error('Host startup failed '+JSON.stringify(exit)+'\n'+stderr);
    if(performance.now()>deadline){child.stdin.end();throw Error('Host startup deadline');}
    await wait(100);
  }} catch(error) {child.stdin.end();child.kill();await exited;report.stderr=stderr;throw error;}
  return {ready,async command(value) {
    const id=++sequence;let timer;
    try{return await Promise.race([new Promise(resolve=>{pending.set(id,resolve);child.stdin.write(JSON.stringify({id,...value})+'\n');}),
      new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Control command deadline')),120000);}),
      exited.then(()=>{throw Error('Owned host exited during control command: '+stderr);})]);}
    finally{clearTimeout(timer);pending.delete(id);}
  },async close() {
    child.stdin.end();let timer;
    const end=await Promise.race([exited,new Promise(resolve=>{timer=setTimeout(()=>resolve(null),30000);})]);clearTimeout(timer);
    if(!end){child.kill();await exited;throw Error('Owned host required forced stop; check store cleanup');}
    report.stderr=stderr;report.metrics=metrics;assert.equal(end.code,0,stderr);
    report.residualStores=fs.readdirSync(runtime).filter(name=>name.startsWith('code-graph-session-'));
    assert.deepEqual(report.residualStores,[],'Owned session store was not removed');
  }};
}
async function main(classpath,source,witnessFile,output,seconds='60',runs='3') {
  if(!output)throw Error('Usage: node scripts/benchmark-mixed-search.cjs CLASSPATH FROZEN_SOURCE WITNESSES OUTPUT [SECONDS=60] [RUNS=3]');
  seconds=Number(seconds);runs=Number(runs);assert.ok(seconds>=2&&seconds<=300&&Number.isInteger(runs)&&runs>=1&&runs<=3);
  source=fs.realpathSync(source);fs.mkdirSync(output,{recursive:true});
  const input=inventory(source),witnesses=JSON.parse(fs.readFileSync(witnessFile)).resultWitnesses,all=[];
  const manifest={startedAt:new Date().toISOString(),classpath,source,input,seconds,runs,heapMiB:768,graphCacheMiB:32,
    concurrency:1,queries,editIntervalsMs:{body:1000,declaration:10000},warmupQueries:24,
    scope:'Real watcher; sequential closed-loop MCP searches; independent JVM per run; rotated idle/body/declaration phases; no OS cache flushing',
    freshness:'File-write completion to first verified published probe hash, observed at 10 ms intervals plus read admission; upper-bound visibility, not exact commit timestamp',
    observation:'One tiny compound graph read per changed generation validates fragment hash and call edges. No polling full inventories.'};
  fs.writeFileSync(path.join(output,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
  for(let run=1;run<=runs;run++) {
    const scratch=fs.mkdtempSync(path.join(os.tmpdir(),'code-graph-mixed-search-')),root=path.join(scratch,'source'),token=crypto.randomUUID();
    fs.mkdirSync(root);fs.writeFileSync(path.join(root,'.mixed-load-owner'),token);
    const report={run,root,phases:[],publications:[],edits:[],errors:[],complete:false};let server,client;
    try {
      copy(source,root);fs.writeFileSync(path.join(root,'.mixed-load-owner'),token);
      fs.mkdirSync(path.join(root,path.dirname(PROBE)));fs.writeFileSync(path.join(root,PROBE),probe(0,0));
      console.log(new Date().toISOString()+` Starting mixed run ${run}`);
      server=await host(classpath,root,token,report);report.host=server.ready;
      console.log(new Date().toISOString()+` Run ${run} indexed in ${Math.round(server.ready.indexAndStartupMs)}ms`);
      client=new McpClient(server.ready.endpoint);await client.initialize();
      const project=unpack((await client.tool('list_projects',{})).result).projects[0].name;
      report.before=unpack((await client.tool('index_status',{project})).result);
      let ordinal=0,editSequence=0,revision=0,lastGeneration=report.before.generation;
      async function search() {
        const {id,...args}=queries[ordinal++%queries.length],started=performance.now();
        const response=await client.tool('search_symbols',{project,...args}),data=unpack(response.result);
        assert.deepEqual(canonical(data),witnesses[id],'Stable symbol inventory changed: '+id);
        assert.ok(data.generation>=lastGeneration,'Generation moved backwards');lastGeneration=data.generation;
        return {id,startedMs:started,elapsedMs:response.elapsedMs,responseBytes:response.responseBytes,generation:data.generation};
      }
      for(let i=0;i<24;i++)await search();
      const phases=['idle','body','declaration'];
      for(const name of [...phases.slice(run-1),...phases.slice(0,run-1)]) {
        const phase={name,samples:[],before:await server.command({action:'mark'})};report.phases.push(phase);
        const started=performance.now(),deadline=started+seconds*1000;let done=false;
        console.log(new Date().toISOString()+` Run ${run}: ${name} (${seconds}s)`);
        // Open-loop file writes independent of query completion. Backlogged saves may coalesce.
        const writer=(async()=>{
          if(name==='idle')return;
          const interval=name==='body'?1000:10000;let next=started;
          while(!done&&performance.now()<deadline) {
            await wait(Math.max(0,next-performance.now()));if(done||performance.now()>=deadline)break;
            const seq=++editSequence;if(name==='declaration')revision++;
            const content=probe(seq,revision),ack=await server.command({action:'edit',content});
            report.edits.push({phase:name,sequence:seq,revision,hash:hash(content),...ack});next+=interval;
          }
        })();
        try {while(performance.now()<deadline)phase.samples.push(await search());}
        finally{done=true;await writer;}
        phase.durationMs=performance.now()-started;
        const latest=report.edits.at(-1),drainStart=performance.now();
        while(latest&&!report.publications.some(p=>p.hash===latest.hash)) {
          if(performance.now()-drainStart>180000)throw Error('Latest edit did not become visible');await wait(50);
        }
        // Do not overlap unfinished indexing with the following phase.
        let status;
        do {status=unpack((await client.tool('index_status',{project})).result);if(status.dirtyPending)await wait(100);
          if(performance.now()-drainStart>180000)throw Error('Watcher failed to drain');}while(status.dirtyPending);
        phase.drainMs=performance.now()-drainStart;
        phase.after=await server.command({action:'mark'});phase.latency=stats(phase.samples.map(s=>s.elapsedMs));
        phase.throughputQps=phase.samples.length/(phase.durationMs/1000);
        console.log(`Run ${run}: ${name} ${JSON.stringify(phase.latency)}, drain=${Math.round(phase.drainMs)}ms`);
      }
      for(const publication of report.publications) {
        const edit=report.edits.find(e=>e.hash===publication.hash);assert.ok(edit,'Published unknown probe hash');
        publication.phase=edit.phase;publication.sequence=edit.sequence;
        publication.visibilityMs=publication.observedAtMs-edit.writeFinishedMs;assert.ok(publication.visibilityMs>=0);
        const target=edit.sequence%2?'alpha':'beta',expected=Array(edit.sequence%3+1).fill(`java:${PROBE}#latencyprobe.LatencyProbe.${target}/0`);
        assert.deepEqual(publication.calls.sort(),expected,'Published fragment and edges disagree');
        assert.equal(publication.work.mode,edit.phase==='body'?'incremental':'incremental-reresolve');
        assert.equal(publication.work.parsedFiles,1);
        assert.equal(publication.work.resolvedFiles,edit.phase==='body'?1:report.before.files);
      }
      for(const phase of report.phases) {
        const published=report.publications.filter(p=>p.phase===phase.name),edits=report.edits.filter(e=>e.phase===phase.name);
        phase.writes=edits.length;phase.publishedEdits=published.length;
        phase.coalescedEdits=edits.filter(e=>!published.some(p=>p.hash===e.hash)).length;
        phase.visibility=stats(published.map(p=>p.visibilityMs));phase.indexing=stats(published.map(p=>p.work.elapsedMillis));
      }
      assert.equal(report.errors.length,0);report.complete=true;
    } catch(error) {report.errors.push({message:error.message,stack:error.stack});throw error;}
    finally {
      try{if(client)await client.close();}finally{
        try{if(server)await server.close();}finally{
          // Exact mkdtemp directory with ownership token; never delete the supplied source or build.
          assert.equal(fs.readFileSync(path.join(root,'.mixed-load-owner'),'utf8'),token);
          assert.equal(path.dirname(scratch),fs.realpathSync(os.tmpdir()));assert.ok(path.basename(scratch).startsWith('code-graph-mixed-search-'));
          assert.equal(path.dirname(root),scratch);
          fs.rmSync(scratch,{recursive:true});report.ownedSourceRemoved=!fs.existsSync(scratch);
          fs.writeFileSync(path.join(output,`run-${run}.log`),report.stderr||'');delete report.stderr;
          fs.writeFileSync(path.join(output,`run-${run}.json`),JSON.stringify(report,null,2)+'\n');
        }
      }
    }
    all.push(report);
  }
  assert.deepEqual(inventory(source),input,'Frozen source changed');
  const summary={completedAt:new Date().toISOString(),allPassed:all.every(r=>r.complete),scenarios:{}};
  for(const name of ['idle','body','declaration']) {
    const phases=all.flatMap(r=>r.phases.filter(p=>p.name===name)),publications=all.flatMap(r=>r.publications.filter(p=>p.phase===name));
    summary.scenarios[name]={latency:stats(phases.flatMap(p=>p.samples.map(s=>s.elapsedMs))),
      throughputQps:phases.reduce((n,p)=>n+p.samples.length,0)/(phases.reduce((n,p)=>n+p.durationMs,0)/1000),
      visibility:stats(publications.map(p=>p.visibilityMs)),indexing:stats(publications.map(p=>p.work.elapsedMillis)),
      writes:phases.reduce((n,p)=>n+p.writes,0),publishedEdits:publications.length,coalescedEdits:phases.reduce((n,p)=>n+p.coalescedEdits,0),
      runs:phases.map(p=>({latency:p.latency,throughputQps:p.throughputQps,visibility:p.visibility,drainMs:p.drainMs}))};
  }
  fs.writeFileSync(path.join(output,'summary.json'),JSON.stringify(summary,null,2)+'\n');console.log(JSON.stringify(summary));
}
if(require.main===module)main(...process.argv.slice(2)).catch(error=>{console.error(error);process.exitCode=1;});
module.exports={probe,stats};
