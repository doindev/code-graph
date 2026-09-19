'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {inventory}=require('./benchmark-symbol-search.cjs');
function median(values){
  assert.ok(values.length,'median requires samples');
  const sorted=[...values].sort((a,b)=>a-b),middle=Math.floor(sorted.length/2);
  return sorted.length%2?sorted[middle]:(sorted[middle-1]+sorted[middle])/2;
}
function summarize(root){
  const rows=[];
  for(const variant of ['baseline','candidate'])for(let run=1;run<=3;run++){
    const dir=path.join(root,variant+'-'+run),p=JSON.parse(fs.readFileSync(path.join(dir,'profile.json')));
    const m=JSON.parse(fs.readFileSync(path.join(dir,'process-memory.json')));
    assert.equal(p.initialFiles,507);assert.equal(p.initialSymbols,6568);assert.equal(p.initialEdges,26708);
    assert.equal(p.updates.length,3);assert.ok(p.ownedCopyRemoved&&p.ownedStoreRemoved);
    for(const update of p.updates){assert.equal(update.work.parsedFiles,1);assert.equal(update.work.resolvedFiles,507);}
    rows.push({variant,run,source:p.source,initialMs:p.initialMillis,updatesMs:p.updates.map(u=>u.elapsedMillis),
      initialDiskBytes:p.afterInitial.storage.diskBytes,finalDiskBytes:p.final.storage.diskBytes,
      sampledPeakHeapBytes:p.final.sampledPeakHeapBytes,
      sampledPeakResidentBytes:Math.max(...m.samples.map(s=>s.workingSetBytes)),
      osReportedPeakResidentBytes:Math.max(...m.samples.map(s=>s.peakWorkingSetBytes)),
      sampledPeakPrivateCommittedBytes:Math.max(...m.samples.map(s=>s.privateCommittedBytes))});
  }
  const groups=Object.fromEntries(['baseline','candidate'].map(variant=>{
    const group=rows.filter(r=>r.variant===variant),result={};
    for(const key of ['initialMs','initialDiskBytes','finalDiskBytes','sampledPeakHeapBytes','sampledPeakResidentBytes','osReportedPeakResidentBytes','sampledPeakPrivateCommittedBytes']){
      const values=group.map(r=>r[key]);result[key]={median:median(values),min:Math.min(...values),max:Math.max(...values)};
    }
    result.updateMs={median:median(group.flatMap(r=>r.updatesMs))};return [variant,result];
  }));
  assert.ok(rows.every(r=>r.source===rows[0].source));
  return {scope:'Three independent JVMs per build; three direct declaration updates each; 768 MiB heap, 32 MiB graph allowance; JFR/NMT enabled. No concurrent search workload. Peaks sampled at 100 ms (heap), ~1 s (process); OS peak counter separate.',
    inputInventory:inventory(rows[0].source),rows,groups,
    declarationMedianReductionPercent:100*(1-groups.candidate.updateMs.median/groups.baseline.updateMs.median)};
}
if(require.main===module)process.stdout.write(JSON.stringify(summarize(process.argv[2]),null,2)+'\n');
module.exports={summarize,median};
