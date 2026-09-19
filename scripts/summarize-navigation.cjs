#!/usr/bin/env node
'use strict';
const fs=require('node:fs'), path=require('node:path');
const directory=path.resolve('docs/validation/dependency-precision');
const read=name=>JSON.parse(fs.readFileSync(path.join(directory,name),'utf8').replace(/^\uFEFF/,''));
function percentile(values,p) {const sorted=[...values].sort((a,b)=>a-b);return sorted[Math.ceil(sorted.length*p)-1];}
const engines={};
for(const engine of ['baseline','candidate']) {
  const runs=[1,2,3].map(run=>read('engine-'+engine+'-'+run+'.json'));
  if(runs.some(r=>!r.engineLabel))throw Error('Incomplete benchmark: '+engine);
  const samples=runs.flatMap(r=>r.querySequenceMs);
  engines[engine]={
    indexMedianMs:percentile(runs.map(r=>r.indexMs),.5),
    queryP50Ms:percentile(samples,.5),queryP95Ms:percentile(samples,.95),queryP99Ms:percentile(samples,.99),
    sequenceThroughputPerSecond:samples.length*1000/samples.reduce((a,b)=>a+b,0),
    workingSetPeakMedianBytes:percentile(runs.map(r=>r.sampledPeakWorkingSetBytes),.5),
    heapAfterQueriesMedianBytes:percentile(runs.map(r=>r.heapUsedBytes),.5),
    diskMedianBytes:percentile(runs.map(r=>r.storage.diskBytes),.5),
    callerOccurrences:runs.map(r=>r.callerOccurrences),distinctCallers:runs.map(r=>r.distinctCallers.length),
    fileCounts:runs.map(r=>r.index.files),symbolCounts:runs.map(r=>r.index.symbols),edgeCounts:runs.map(r=>r.index.edges)
  };
}
const navigation={};
for(const phase of ['before','after']) {
  const report=read('navigation-'+phase+'.json');
  navigation[phase]={counts:report.counts,events:report.events,
    results:report.results.map(r=>{
      const data=r.callers.structuredContent?.data || JSON.parse(r.callers.content.find(c=>c.type==='text').text);
      return {run:r.run,occurrences:data.total,distinctCallers:new Set(data.symbols.map(s=>s.id)).size,
        inventoryComplete:data.inventoryComplete,coverage:data.coverage};
    })};
}
const report={createdAt:new Date().toISOString(),engines,navigation,tests:read('test-summary.json'),
  limitations:[
    'Thirty warmed query sequences per fresh JVM; three JVMs per engine. OS file cache not cleared.',
    'No unrelated database or UI workload included. Source-launch compiler overhead is included in process memory.',
    'Heap-after-query values include collectible garbage and are not retained-heap measurements.',
    'The fixed navigation workload deliberately uses equal tool counts; it does not prove an overall reduction in agent filesystem calls.',
    'Correctness oracle is the reviewed six-call-site/five-caller helper and synthetic/unit fixtures, not a complete repository reference inventory.'
  ]};
fs.writeFileSync(path.join(directory,'summary.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
