#!/usr/bin/env node
'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const {McpClient,unpack}=require('./mcp-benchmark-client.cjs');
const cases=[
  {name:'aliasTarget',module:'alias/right.js'},
  {name:'defaultTarget',module:'default/right.js'},
  {name:'namespaceTarget',module:'namespace/right.js'},
  {name:'barrelTarget',module:'barrel/right.js'},
  {name:'commonTarget',module:'common/right.cjs'}
];
function fixtures(root) {
  const files={
    'alias/right.js':'export function aliasTarget(){}',
    'alias/wrong.js':'export function aliasCall(){}',
    'alias/use.js':"import {aliasTarget as aliasCall} from './right.js';\naliasCall();",
    'default/right.js':'export default function defaultTarget(){}',
    'default/wrong.js':'export function defaultCall(){}',
    'default/use.js':"import defaultCall from './right.js';\ndefaultCall();",
    'namespace/right.js':'export function namespaceTarget(){}',
    'namespace/wrong.js':'export function namespaceTarget(){}',
    'namespace/use.js':"import * as api from './right.js';\napi.namespaceTarget();",
    'barrel/right.js':'export function barrelTarget(){}',
    'barrel/wrong.js':'export function barrelCall(){}',
    'barrel/barrel.js':"export {barrelTarget as execute} from './right.js';",
    'barrel/use.js':"import {execute as barrelCall} from './barrel.js';\nbarrelCall();",
    'common/right.cjs':'function commonTarget(){} exports.commonTarget=commonTarget;',
    'common/wrong.cjs':'function commonCall(){} exports.commonCall=commonCall;',
    'common/use.cjs':"const {commonTarget:commonCall}=require('./right.cjs');\ncommonCall();"
  };
  for(const [name,text]of Object.entries(files)){
    const file=path.join(root,'efficiency-fixtures',name);fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,text+'\n');
  }
  // Independent, authored oracle, never consulted by the acquisition stopping rule.
  const oracle=cases.map(c=>({name:c.name,target:'efficiency-fixtures/'+c.module,
    caller:'efficiency-fixtures/'+c.module.replace(/right\.(js|cjs)$/,'use.$1'),line:2}));
  return {files:Object.fromEntries(Object.entries(files).map(([n,t])=>[n,crypto.createHash('sha256').update(t+'\n').digest('hex')])),oracle};
}
async function run(endpoint,root,phase,number,output) {
  const client=new McpClient(endpoint),results=[];
  try {
    await client.initialize();const project=unpack((await client.tool('list_projects',{})).result).projects[0].name;
    for(const task of cases) {
      const events=[],start=performance.now();
      async function query(name,args,purpose) {
        const response=await client.tool(name,{project,...args});
        const data=unpack(response.result);events.push({category:'mcp',operation:name,purpose,
          durationMs:response.elapsedMs,responseBytes:response.responseBytes,generation:data.generation});return data;
      }
      const declaration=await query('search_symbols',{query:task.name,kind:'function',lang:'js',limit:20},'Find exact module-qualified definition');
      const target=declaration.symbols.find(s=>s.id.includes('efficiency-fixtures/'+task.module+'#'));
      if(!target)throw Error('Missing fixture definition: '+task.name);
      let cursor,generation;const references=[];let pages=0;
      do {
        if(++pages>32)throw Error('Benchmark page bound exceeded');
        const page=await query('find_references',{symbol_id:target.id,limit:100,...(cursor?{cursor}:{})},'Obtain reference locations and binding evidence');
        if(generation!==undefined&&generation!==page.generation)throw Error('Mixed benchmark generations');
        generation=page.generation;
        references.push(...page.symbols);cursor=page.nextCursor;
      }while(cursor);
      const precise=references.length>0&&references.every(r=>r.confidence===1&&r.resolutionEvidence?.resolutionStatus==='resolved'
        &&r.resolutionEvidence?.candidateCount==='1'&&r.resolutionEvidence?.omittedCandidates==='0'&&r.occurrence?.span);
      let outcome='mcp_sufficient';
      if(!precise) {
        outcome='source_review';const tick=performance.now();
        const folder='efficiency-fixtures/'+task.module.split('/')[0];
        const search=spawnSync('rg',['--files',folder],{cwd:root,encoding:'utf8',maxBuffer:1024*1024});
        if(search.status!==0)throw Error(search.stderr||'Fixture search failed');
        events.push({category:'filesystem_search',operation:'rg --files',purpose:'Missing or uncertain module binding requires checking import/export source',
          durationMs:performance.now()-tick,responseBytes:Buffer.byteLength(search.stdout)});
        const readStart=performance.now(),files=search.stdout.trim().split(/\r?\n/);
        const contents=files.map(file=>({file,text:fs.readFileSync(path.join(root,file),'utf8')}));
        events.push({category:'discovery_read',operation:'bounded source read',purpose:'Review module specifiers and exported/local names',
          durationMs:performance.now()-readStart,responseBytes:Buffer.byteLength(JSON.stringify(contents)),files});
      }
      results.push({task:task.name,target:target.id,outcome,references,events,acquisitionMs:performance.now()-start});
    }
    const report={phase,run:number,methodology:'Adaptive deterministic harness, not an LLM timing trial. Source fallback only for missing/uncertain evidence; authored oracle is graded afterward.',results};
    fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');return report;
  }finally{await client.close();}
}
module.exports={fixtures,run};
