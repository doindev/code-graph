'use strict';
// Independent grading happens after acquisition; the acquisition code never reads this oracle.
const fs=require('node:fs'),path=require('node:path');
const {verify}=require('./benchmark-context-navigation.cjs');
const directory=path.resolve(process.argv[2]);
const oracle=JSON.parse(fs.readFileSync(path.join(directory,'oracle.json'),'utf8'));
const percentile=(values,p)=>[...values].sort((a,b)=>a-b)[Math.min(values.length-1,Math.ceil(values.length*p)-1)];
const out={methodology:'Three independent hybrid JVMs per build, alternating order, one warmup and twelve measured acquisitions each, identical frozen repository plus four explicit Java method fixtures. Warm OS caches; shared Windows host. Not an LLM coding-time benchmark.',
  baseline:'deffa3fd9e2749577b87403d0cdb61beb85fe202',
  limits:{heapMiB:768,sharedGraphAllowanceMiB:32},
  caveats:['Initialization, tools/list and list_projects are equal bootstrap calls excluded from navigation counts/timing/bytes.',
    'Acquisition timing sums navigation and conditional filesystem evidence operations, not model reasoning or user task completion.',
    'The baseline falls back only when advertised method evidence is absent; local source review is bounded to the discovered explicit fixture declarations.',
    'Candidate transports preserve text and structuredContent; richer implementation evidence can increase bytes despite fewer calls.',
    'p99 uses only 36 acquisitions per build and is exploratory. Shared-host tests/indexing can affect tails.',
    'Heap, process memory and accounted cache estimates are separate measurements; none is a hard process-RAM guarantee.',
    'Fixture correctness and filesystem savings are not claims of compiler completeness or every coding task.'],phases:{}};
for(const phase of ['baseline','candidate']){
  const runs=[1,2,3].map(i=>JSON.parse(fs.readFileSync(path.join(directory,phase+'-'+i+'.json'),'utf8')));
  for(const run of runs){if(run.failure||run.samples.length!==12)throw Error('Failed/incomplete run cannot be omitted');for(const sample of run.samples)verify(sample,oracle);}
  const samples=runs.flatMap(r=>r.samples),times=samples.map(s=>s.acquisitionMs);
  out.phases[phase]={correctAcquisitions:samples.length,correctMethodAnswers:samples.length*Object.keys(oracle.methods).length,
    falsePositives:0,falseNegatives:0,p50Ms:percentile(times,.5),p95Ms:percentile(times,.95),p99Ms:percentile(times,.99),
    acquisitionsPerSecond:samples.length*1000/times.reduce((a,b)=>a+b,0),medianResponseBytes:percentile(samples.map(s=>s.responseBytes),.5),
    perAcquisition:{mcpNavigationCalls:samples[0].mcpCalls,filesystemSearches:samples[0].filesystemSearches,discoveryReads:samples[0].sourceReads},
    runs:runs.map(r=>({run:r.run,indexAndStartupMs:r.startup.indexAndStartupMs,p50Ms:percentile(r.samples.map(s=>s.acquisitionMs),.5),
      heapUsedBytes:r.metrics.heapUsedBytes,heapPoolPeaksBytes:r.metrics.heapPoolPeaksBytes,gcCount:r.metrics.gcCount,gcTimeMs:r.metrics.gcTimeMs,
      processMemory:r.processMemory,storage:r.metrics.storage}))};
}
fs.writeFileSync(path.join(directory,'summary.json'),JSON.stringify(out,null,2)+'\n');
console.log(JSON.stringify(out,null,2));
