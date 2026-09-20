'use strict';
// Deterministic adaptive acquisition harness, not an LLM or end-user wall-clock claim.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const {host}=require('./run-efficiency-benchmark.cjs');
const {McpClient,unpack}=require('./mcp-benchmark-client.cjs');
function fixture(root){
  const folder=path.join(root,'context-fixture');if(fs.existsSync(folder))throw Error('Fixture directory already exists; use a fresh frozen dataset');
  fs.mkdirSync(folder);const oracle={methods:{},files:{}};
  for(let i=0;i<4;i++){
    const name='Workflow'+i,file='context-fixture/'+name+'.java';
    const text=`package benchprobe; interface ${name} { int run(int value); }\nclass Worker${i} implements ${name} { public int run(int value) { return value; } }\n`;
    fs.writeFileSync(path.join(root,file),text);oracle.files[file]=crypto.createHash('sha256').update(text).digest('hex');
    oracle.methods[`java:${file}#benchprobe.${name}.run/1`]=[`java:${file}#benchprobe.Worker${i}.run/1`];
  }
  return oracle;
}
async function acquire(endpoint,root,{preferLocations=false}={}){
  const client=new McpClient(endpoint),events=[],generations=new Set();
  async function call(name,args,reason){const r=await client.tool(name,args);events.push({kind:'mcp',operation:name,purpose:reason,durationMs:r.elapsedMs,responseBytes:r.responseBytes,error:!!r.result.isError});const d=r.result.isError&&name==='find_implementations'?{symbols:[],coverage:'unsupported_method_target'}:unpack(r.result);if(d.generation!==undefined)generations.add(d.generation);return d;}
  try{
    await client.initialize();const catalog=(await client.rpc('tools/list',{})).result.tools;
    const project=unpack((await client.tool('list_projects',{})).result).projects[0].name;
    const found=await call('search_symbols',{project,query:'benchprobe.Workflow',kind:'function',limit:20},'Discover exact interface method identities once');
    const ids=found.symbols.map(s=>s.id).sort();if(ids.length!==4)throw Error('Expected four frozen fixture methods');
    const answer={};let cursor;
    if(catalog.some(t=>t.name==='get_symbol_context')){
      do{
        const detail=preferLocations&&catalog.find(t=>t.name==='get_symbol_context').inputSchema.properties.detail?{detail:'locations'}:{};
        const data=await call('get_symbol_context',{project,symbol_ids:ids,include:['declaration','implementations'],limit:100,...detail,...(cursor?{cursor}:{})},'Acquire declarations and verified implementation evidence in one generation');
        for(const row of data.items){if(row.section==='declaration')answer[row.targetId]??={declaration:row.id,implementations:[]};if(row.section==='implementations'){answer[row.targetId]??={declaration:row.targetId,implementations:[]};answer[row.targetId].implementations.push(row.id);}}
        if(data.workLimitReached)throw Error('Fixture acquisition exhausted inspection budget');cursor=data.nextCursor;
      }while(cursor);
    }else{
      for(const id of ids){
        await call('get_symbol',{project,symbol_id:id},'Get declaration for a known method; bulk context is unavailable');
        const evidence=await call('find_implementations',{project,symbol_id:id,limit:100},'Check advertised implementation evidence before choosing a fallback');
        answer[id]={declaration:id,implementations:(evidence.symbols??[]).map(s=>s.id)};
      }
    }
    for(const id of ids){
      answer[id]??={declaration:id,implementations:[]};if(answer[id].implementations.length)continue;
      const owner=id.split('#')[1].split('.').at(-2),tick=performance.now();
      const r=spawnSync('rg',['-l','--glob','*.java','implements\\s+'+owner+'\\b','context-fixture'],{cwd:root,encoding:'utf8'});
      if(r.status>1||r.error)throw r.error??Error(r.stderr);
      events.push({kind:'filesystem_search',purpose:'No method implementations in indexed evidence; inspect explicit nominal declarations',durationMs:performance.now()-tick,responseBytes:Buffer.byteLength(r.stdout)});
      for(const relative of r.stdout.trim().split(/\r?\n/).filter(Boolean)){
        const file=relative.replaceAll('\\','/'),read=performance.now(),text=fs.readFileSync(path.join(root,file),'utf8');
        events.push({kind:'discovery_source_read',purpose:'Resolve the explicit method implementation after incomplete indexed evidence',durationMs:performance.now()-read,responseBytes:Buffer.byteLength(text),path:file});
        for(const match of text.matchAll(new RegExp('class\\s+(\\w+)\\s+implements\\s+'+owner+'\\s*\\{\\s*public\\s+int\\s+run\\(int\\s+\\w+\\)','g')))
          answer[id].implementations.push(`java:${file}#benchprobe.${match[1]}.run/1`);
      }
    }
    if(generations.size!==1)throw Error('Generation changed during acquisition');
    return{answer,events,generation:[...generations][0],mcpCalls:events.filter(e=>e.kind==='mcp').length,filesystemSearches:events.filter(e=>e.kind==='filesystem_search').length,sourceReads:events.filter(e=>e.kind==='discovery_source_read').length,responseBytes:events.reduce((n,e)=>n+e.responseBytes,0),acquisitionMs:events.reduce((n,e)=>n+e.durationMs,0)};
  }finally{await client.close();}
}
function verify(run,oracle){for(const [id,expected]of Object.entries(oracle.methods)){const actual=[...new Set(run.answer[id]?.implementations??[])].sort();if(JSON.stringify(actual)!==JSON.stringify(expected))throw Error('Independent fixture oracle mismatch: '+id+' actual='+JSON.stringify(actual));}return true;}
function processMemory(pid){
  if(process.platform!=='win32')return{available:false,reason:'This harness currently measures Windows process working set only'};
  const r=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',`Get-Process -Id ${pid} | Select-Object WorkingSet64,PeakWorkingSet64,PrivateMemorySize64 | ConvertTo-Json -Compress`],{encoding:'utf8',windowsHide:true});
  if(r.status!==0)return{available:false,reason:'Process memory probe failed'};
  return{available:true,...JSON.parse(r.stdout)};
}
async function main(baseline,candidate,root,output){
  fs.mkdirSync(output,{recursive:true});const oracle=fixture(root);fs.writeFileSync(path.join(output,'oracle.json'),JSON.stringify(oracle,null,2));
  const results=[];
  for(let run=1;run<=3;run++)for(const [phase,jar]of(run%2?[['baseline',baseline],['candidate',candidate]]:[['candidate',candidate],['baseline',baseline]])){
    const server=await host(jar,root),samples=[];let failure;
    try{
      await acquire(server.ready.endpoint,root); // identical bounded warmup, not measured
      for(let i=0;i<12;i++){const sample=await acquire(server.ready.endpoint,root);verify(sample,oracle);samples.push(sample);}
    }catch(error){failure=error.message;}finally{
      const memory=processMemory(server.child.pid);const closed=await server.close();const entry={phase,run,environment:{node:process.version,platform:process.platform,arch:process.arch},startup:server.ready,metrics:closed.metrics,processMemory:memory,failure,samples};
      results.push(entry);fs.writeFileSync(path.join(output,phase+'-'+run+'.json'),JSON.stringify(entry,null,2));console.log(phase+' '+run+' '+(failure?'FAILED '+failure:'passed'));
    }
    if(failure)throw Error(failure);
  }
  fs.writeFileSync(path.join(output,'results.json'),JSON.stringify(results,null,2));
}
if(require.main===module)main(...process.argv.slice(2)).catch(e=>{console.error(e);process.exitCode=1;});
module.exports={fixture,acquire,verify,processMemory};
