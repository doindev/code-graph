#!/usr/bin/env node
'use strict';
// Grade AFTER acquisition; benchmark-agent-navigation.cjs cannot access this oracle.
const fs=require('node:fs'),path=require('node:path');
const dir=path.resolve(process.argv[2]||'docs/validation/dependency-precision');
const oracle=JSON.parse(fs.readFileSync(path.join(dir,'workflow-oracle.json'),'utf8'));
if(oracle.errors.length)throw Error('Compiler oracle has errors');
const read=name=>JSON.parse(fs.readFileSync(path.join(dir,name),'utf8'));
const median=xs=>[...xs].sort((a,b)=>a-b)[Math.floor(xs.length/2)];
const expectedKey=x=>x.path+':'+x.identifierLine+':'+x.identifierColumn;
const actualKey=x=>x.path+':'+x.occurrence.span.startLine+':'+x.occurrence.span.startColumn;
const production='code-graph-dba/src/main/java/';
const runs={baseline:[],candidate:[]},grades=[];
for(const phase of Object.keys(runs))for(let i=1;i<=3;i++) {
  const report=read('workflow-'+phase+'-'+i+'.json');
  runs[phase].push(report);
  for(const t of report.results) {
    if(t.outcome==='failed')throw Error('Failed sample retained: '+phase+' '+i+' '+t.id+': '+t.error);
    if(t.kind!=='references')continue;
    const expected=oracle.references[t.query];
    const actual=t.references.filter(s=>s.path?.startsWith(production));
    const a=actual.map(actualKey).sort(),e=expected.map(expectedKey).sort();
    const match=JSON.stringify(a)===JSON.stringify(e);
    const source=t.events.filter(e=>e.category==='discovery_read').flatMap(e=>e.contents);
    const sourceCoversExpected=expected.every(ref=>source.some(c=>
      c.file===ref.path && c.start<=ref.identifierLine && c.end>=ref.identifierLine));
    const grade={phase,run:i,task:t.id,outcome:t.outcome,expected:expected.length,
      returnedProductionReferences:actual.length,mcpExactMatch:match,
      sourceEvidenceCoversExpected:t.outcome==='source_review'?sourceCoversExpected:null,
      interpretation:t.outcome==='source_review'?
        'All compiler-grounded locations are in acquired source; semantic human review is still required and its time is not measured':
        'Final MCP reference answer independently graded against javac'};
    grades.push(grade);
    if(t.outcome==='mcp_sufficient'&&!match)throw Error('Incorrect early stopping: '+JSON.stringify(grade));
    if(t.outcome==='source_review'&&!sourceCoversExpected)throw Error('Insufficient fallback evidence: '+JSON.stringify(grade));
  }
}
function metrics(reports,predicate) {
  const suites=reports.map(r=>{
    const tasks=r.results.filter(predicate),events=tasks.flatMap(t=>t.events);
    const counts=events.reduce((m,e)=>(m[e.category]=(m[e.category]||0)+1,m),{});
    const discovery=(counts.filesystem_search||0)+(counts.discovery_read||0);
    const filesystem=discovery+(counts.implementation_read||0);
    return {run:r.run,counts,filesystem,discovery,totalOperations:filesystem+(counts.mcp||0),
      acquiredSourceFiles:events.reduce((n,e)=>n+(e.filesRead||0),0),
      responseBytes:events.reduce((n,e)=>n+e.responseBytes,0),
      discoveryBytes:events.filter(e=>e.category==='filesystem_search'||e.category==='discovery_read').reduce((n,e)=>n+e.responseBytes,0),
      elapsedMs:tasks.reduce((n,t)=>n+t.elapsedMs,0),
      taskOutcomes:tasks.map(t=>({id:t.id,outcome:t.outcome}))};
  });
  return {suites,medianAcquisitionMs:median(suites.map(r=>r.elapsedMs)),
    medianResponseBytes:median(suites.map(r=>r.responseBytes)),
    totalCounts:suites.reduce((m,r)=>{for(const [k,v]of Object.entries(r.counts))m[k]=(m[k]||0)+v;return m;},{})};
}
const summary={
  methodology:'Eight independent evidence-acquisition tasks, three fresh MCP sessions per engine, identical frozen source. Fixed adaptive rule, no cross-task source cache, no oracle input to acquisition. Warm OS/application caches. Not fresh LLM agents and not model-inclusive coding time.',
  oracle:{method:oracle.method,compiler:oracle.compiler,errors:oracle.errors.length},
  references:{},allTasks:{},grades,
  caveats:[
    'Five reference tasks share two helper classes in one Java subsystem; not representative of all languages or full coding sessions.',
    'Filesystem counts are one rg invocation plus one bounded batched-source-read invocation per fallback, not one call per file.',
    'Baseline source acquisition is not the same as a completed semantic answer; unmeasured source-review reasoning remains. Candidate MCP-only answers are independently graded.',
    'References outside the explicit production-Java question scope still consume pagination calls; compare their counts as well as the scoped answers.',
    'Indexing, setup, compiler grading, model reasoning and tool orchestration round-trip latency are excluded from acquisition timing.',
    'Repeated tasks are fresh evidence acquisitions; real agents may reuse context and make fewer baseline calls.'
  ]
};
for(const phase of Object.keys(runs)) {
  summary.references[phase]=metrics(runs[phase],t=>t.kind==='references');
  summary.allTasks[phase]=metrics(runs[phase],()=>true);
}
fs.writeFileSync(path.join(dir,'workflow-summary.json'),JSON.stringify(summary,null,2)+'\n');
console.log(JSON.stringify({referenceTasks:Object.fromEntries(Object.entries(summary.references).map(([p,m])=>[p,{
  ms:m.medianAcquisitionMs,bytes:m.medianResponseBytes,counts:m.totalCounts,perRun:m.suites[0].totalOperations}])),
  allTasks:Object.fromEntries(Object.entries(summary.allTasks).map(([p,m])=>[p,{
    ms:m.medianAcquisitionMs,bytes:m.medianResponseBytes,counts:m.totalCounts,perRun:m.suites[0].totalOperations}])),
  candidateMcpAnswersCorrect:grades.filter(g=>g.phase==='candidate'&&g.mcpExactMatch).length,gradedTasks:grades.length},null,2));
