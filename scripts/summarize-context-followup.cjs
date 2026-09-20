'use strict';
const fs=require('node:fs'),path=require('node:path');
const {verify}=require('./benchmark-context-navigation.cjs');
const directory=path.resolve(process.argv[2]),oracle=JSON.parse(fs.readFileSync(path.join(directory,'oracle.json'),'utf8'));
const percentile=(values,p)=>[...values].sort((a,b)=>a-b)[Math.min(values.length-1,Math.ceil(values.length*p)-1)];
const metrics=samples=>({p50Ms:percentile(samples.map(s=>s.acquisitionMs),.5),p95Ms:percentile(samples.map(s=>s.acquisitionMs),.95),
  p99Ms:percentile(samples.map(s=>s.acquisitionMs),.99),medianResponseBytes:percentile(samples.map(s=>s.responseBytes),.5),
  acquisitionsPerSecond:samples.length*1000/samples.reduce((n,s)=>n+s.acquisitionMs,0)});
const out={methodology:'Three independent JVMs per build, alternating order, 10 warmup + 100 measured acquisitions. Identical frozen dataset and independent oracle; prefer locations only when advertised. Warm OS cache, shared Windows host; bootstrap excluded; deterministic navigation, not total agent time.',
  limits:{heapMiB:768,sharedGraphAllowanceMiB:32},phases:{}};
for(const phase of ['baseline','candidate']){
  const runs=[1,2,3].map(n=>JSON.parse(fs.readFileSync(path.join(directory,phase+'-'+n+'.json'),'utf8')));
  for(const run of runs){
    if(run.failure||run.samples.length!==100)throw Error('Failed/incomplete run cannot be omitted');
    for(const sample of run.samples)verify(sample,oracle);
  }
  const samples=runs.flatMap(r=>r.samples);
  const operations={};
  for(const name of [...new Set(samples.flatMap(s=>s.events.map(e=>e.operation)).filter(Boolean))]){
    const events=samples.flatMap(s=>s.events.filter(e=>e.operation===name));
    operations[name]={calls:events.length,p50Ms:percentile(events.map(e=>e.durationMs),.5),
      p95Ms:percentile(events.map(e=>e.durationMs),.95),p99Ms:percentile(events.map(e=>e.durationMs),.99),
      medianResponseBytes:percentile(events.map(e=>e.responseBytes),.5)};
  }
  out.phases[phase]={...metrics(samples),correctAcquisitions:samples.length,correctMethodAnswers:samples.length*Object.keys(oracle.methods).length,falsePositives:0,falseNegatives:0,
    operations,perAcquisition:{mcpCalls:samples[0].mcpCalls,filesystemSearches:samples[0].filesystemSearches,sourceReads:samples[0].sourceReads},
    runs:runs.map(r=>({run:r.run,...metrics(r.samples),indexAndStartupMs:r.startup.indexAndStartupMs,metrics:r.metrics,processMemory:r.processMemory}))};
}
fs.writeFileSync(path.join(directory,'summary.json'),JSON.stringify(out,null,2)+'\n');
console.log(JSON.stringify(out,null,2));
