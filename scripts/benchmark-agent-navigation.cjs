#!/usr/bin/env node
'use strict';
// Adaptive evidence acquisition, NOT an LLM wall-clock benchmark.
// Same questions and evidence gate for both engines; no expected answers are read here.
// Usage: node scripts/benchmark-agent-navigation.cjs ENDPOINT ROOT PHASE RUN [OUTPUT]
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const {McpClient,unpack}=require('./mcp-benchmark-client.cjs');
const production='code-graph-dba/src/main/java/';
const questions=[
  ...['NativeRedisArguments.bytes','NativeRedisArguments.validate','NativeResults.add',
      'NativeResults.finish','NativeResults.binary'].map(query=>({
    id:'references:'+query,kind:'references',query,
    question:'Locate the directly bound production Java call sites of '+query+
      ', including source locations. Report static indexed coverage, not runtime completeness.'
  })),
  {id:'declaration',kind:'declaration',query:'NativeReadExecutor.key',
    question:'Locate the declaration and parameter signature of NativeReadExecutor.key.'},
  {id:'implementation',kind:'implementation',query:'NativeRedisArguments.bytes',
    question:'Locate NativeRedisArguments.bytes and obtain its implementation for an upcoming edit. Do not edit it.'},
  {id:'exhaustiveness',kind:'exhaustive',query:'NativeRedisArguments.bytes',
    question:'Can this be treated as an exhaustive reference list for a safe deletion? Check source, retain unresolved coverage caveats; do not delete anything.'}
];
function evidenceGap(data) {
  if(!data.symbols?.length)return 'No reference evidence returned; absence is not proof of non-use';
  for(const s of data.symbols) {
    const e=s.resolutionEvidence;
    if(s.confidence<1)return 'Ambiguous/heuristic reference bindings; confidence '+s.confidence;
    if(!e || e.resolutionStatus!=='resolved' || e.candidateCount!=='1' || e.omittedCandidates!=='0')
      return 'Missing conclusive binding/candidate evidence';
    if(!s.occurrence?.span || s.locationPrecision!=='identifier_token')
      return 'Exact occurrence location is unavailable';
  }
  return null;
}
function safePath(root,relative) {
  const p=path.resolve(root,relative),r=path.resolve(root);
  if(!p.startsWith(r+path.sep))throw Error('Path outside benchmark root: '+relative);
  return p;
}
async function run(endpoint,root,phase,runNumber,output) {
  const client=new McpClient(endpoint),results=[];
  const setup=[];
  try {
    setup.push(await client.initialize());
    const listing=unpack((await client.tool('list_projects',{})).result);
    if(listing.projects.length!==1 || listing.projects[0].state!=='ready')throw Error('Expected one ready isolated project');
    const project=listing.projects[0].name;
    for(const question of questions) {
      const task={...question,events:[],decisions:[]},started=performance.now();
      const generations=new Set();
      const decision=(action,reason)=>task.decisions.push({action,reason,atMs:performance.now()-started});
      async function query(name,args,reason) {
        const r=await client.tool(name,{project,...args});
        task.events.push({category:'mcp',name,args,reason,...r});
        const d=unpack(r.result);
        if(d.generation!==undefined)generations.add(d.generation);
        if(generations.size>1)throw Error('Generation changed during task');
        return d;
      }
      function batchRead(files,reason,category='discovery_read',ranges={}) {
        const tick=performance.now();
        let bytes=0;
        const contents=files.map(file=>{
          const p=safePath(root,file),stat=fs.statSync(p);
          if(stat.size>128*1024 || bytes+stat.size>512*1024)throw Error('Source-review read budget exceeded');
          const text=fs.readFileSync(p,'utf8');bytes+=stat.size;
          const lines=text.split(/\r?\n/),range=ranges[file]||{start:1,end:lines.length};
          return {file,start:range.start,end:range.end,
            sha256:crypto.createHash('sha256').update(text).digest('hex'),
            text:lines.slice(range.start-1,range.end).join('\n')};
        });
        task.events.push({category,reason,elapsedMs:performance.now()-tick,filesRead:files.length,
          responseBytes:Buffer.byteLength(JSON.stringify(contents)),contents});
        return contents;
      }
      function sourceFallback(reason) {
        decision('filesystem_search',reason);
        const tick=performance.now(),owner=question.query.split('.')[0];
        // Search type uses, not the common member name across the whole repository.
        // One batched source-reading tool invocation follows; file reads are counted separately.
        const args=['-l','--glob','*.java','\\b'+owner+'\\b',production];
        const search=spawnSync('rg',args,{cwd:root,encoding:'utf8',maxBuffer:1024*1024});
        if(search.error||search.status>1)throw search.error||Error(search.stderr);
        const files=search.stdout.trim().split(/\r?\n/).filter(Boolean).map(p=>p.replaceAll('\\','/')).sort();
        task.events.push({category:'filesystem_search',reason,args,elapsedMs:performance.now()-tick,
          responseBytes:Buffer.byteLength(search.stdout),files});
        if(!files.length||files.length>32)throw Error('Source-review file bound exceeded or no source found');
        batchRead(files,'Inspect receiver declarations, qualified calls and internal unqualified calls to resolve the knowledge gap');
        task.outcome='source_review';
        task.coverage='Bounded source evidence acquired; no claim of compiler-complete or dynamic reference coverage';
      }
      try {
        const discovery=await query('search_symbols',{query:question.query,kind:'function',lang:'java',limit:10},
          'Find stable identity and declaration location without a filesystem search');
        if(discovery.truncated||discovery.symbols.length!==1)throw Error('Declaration identity is ambiguous');
        const symbol=discovery.symbols[0];task.symbol=symbol;
        if(question.kind==='declaration') {
          task.answer={symbolId:symbol.id,signature:symbol.sig,line:symbol.line};
          decision('stop','Unique declaration, path in stable ID, signature and line answer the navigation question');
          task.outcome='mcp_sufficient';
        } else if(question.kind==='implementation') {
          const file=symbol.id.slice(symbol.id.indexOf(':')+1,symbol.id.indexOf('#'));
          const outline=await query('get_file_outline',{file,limit:100},'Obtain exact method bounds for a targeted implementation read');
          const node=outline.symbols.find(s=>s.id===symbol.id);
          if(!node?.declarationSpan)throw Error('No exact method declaration span');
          decision('implementation_read','MCP provides locations, but the edit task needs the actual implementation body');
          batchRead([file],'Implementation content required for editing, not a discovery fallback','implementation_read',
            {[file]:{start:node.declarationSpan.startLine,end:node.declarationSpan.endLine}});
          task.outcome='implementation_read_required';
        } else {
          let cursor,page=0;task.references=[];
          do {
            const data=await query('find_references',{symbol_id:symbol.id,limit:20,...(cursor?{cursor}:{})},
              cursor?'Finish precise reference inventory by following continuation':'Get caller identities, binding evidence and exact occurrence spans');
            task.references.push(...data.symbols);
            task.inventoryComplete=data.inventoryComplete;task.coverage=data.coverage;
            // The question is production Java, so unrelated JS/test references do not
            // justify reading Java source. Still consume every page before stopping.
            const scoped=data.symbols.filter(s=>s.path?.startsWith(production));
            const gap=scoped.length?evidenceGap({...data,symbols:scoped}):null;
            if(question.kind==='exhaustive' && data.inventoryComplete!==true) {
              sourceFallback('Task asks for exhaustive deletion safety, but the server explicitly does not guarantee reference completeness');
              task.answer='Cannot establish safe deletion: source review does not eliminate dynamic/unindexed-use uncertainty';
              break;
            }
            if(data.total>80) {sourceFallback('Inventory exceeds the four-page evidence budget; narrow by owning type in source instead of paging speculative references');break;}
            if(gap) {sourceFallback(gap);break;}
            cursor=data.nextCursor;
            if(cursor)decision('mcp_next_page','Bindings are precise, but the inventory is paginated; finish pagination before stopping');
            if(++page>=4 && cursor) {sourceFallback('Precise inventory exceeds bounded MCP page budget');break;}
          } while(cursor);
          if(!task.outcome && !task.references.some(s=>s.path?.startsWith(production)))
            sourceFallback('No production Java references returned; absence is not proof of non-use');
          if(!task.outcome) {
            decision('stop','All returned bindings have one resolved candidate and exact spans; no pages remain. Sufficient for bounded static navigation, not exhaustive deletion safety');
            task.outcome='mcp_sufficient';
          }
        }
      } catch(error) {
        task.outcome='failed';task.error=error.message;
      }
      task.elapsedMs=performance.now()-started;
      task.counts=task.events.reduce((m,e)=>(m[e.category]=(m[e.category]||0)+1,m),{});
      task.generation=[...generations];results.push(task);
    }
    const report={phase,run:runNumber,endpoint,root,project,createdAt:new Date().toISOString(),
      methodology:'Scripted adaptive evidence acquisition; fresh MCP session per run, same rule for both engines. Not an independent LLM trial; no model reasoning time is measured. Oracle is never read by this script.',
      cacheState:'Warm application and OS file caches; isolated servers index identical source. Setup excluded.',
      setup,results};
    fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify({phase,run:runNumber,output,tasks:results.map(t=>({id:t.id,outcome:t.outcome,counts:t.counts,ms:t.elapsedMs,error:t.error}))}));
    return report;
  } finally {await client.close();}
}
module.exports={evidenceGap,questions,run};
if(require.main===module) {
  const [endpoint,root,phase,number,out]=process.argv.slice(2);
  if(!endpoint||!root||!phase||!number)throw Error('Required: ENDPOINT ROOT PHASE RUN [OUTPUT]');
  const output=out||path.join(process.cwd(),'docs/validation/dependency-precision/workflow-'+phase+'-'+number+'.json');
  run(endpoint,root,phase,Number(number),output).catch(e=>{console.error(e);process.exitCode=1;});
}
