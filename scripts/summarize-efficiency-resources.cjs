#!/usr/bin/env node
'use strict';
// Summarize retained samples; no servers, source changes, or cache flushing.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const median=values=>values.toSorted((a,b)=>a-b)[Math.floor(values.length/2)];
async function hash(file) {
  const digest=crypto.createHash('sha256');
  for await(const chunk of fs.createReadStream(file))digest.update(chunk);
  return digest.digest('hex');
}
async function main(results,baselineJar,candidateJar,dataset) {
  if(![results,baselineJar,candidateJar,dataset].every(Boolean))
    throw Error('Usage: node scripts/summarize-efficiency-resources.cjs RESULTS BASELINE_JAR CANDIDATE_JAR FROZEN_DATASET');
  const summary={method:'Median of three independent fresh-JVM/application-store runs per build. OS file cache was not cleared.',
    units:{timing:'milliseconds',memory:'bytes',throughput:'measured in-process query sequences/second'},builds:{}};
  for(const label of ['baseline','candidate']) {
    const runs=[1,2,3].map(run=>JSON.parse(fs.readFileSync(path.join(results,'engine-'+label+'-'+run+'.json'),'utf8')));
    const fields=['indexMs','queryP50Ms','queryP95Ms','queryP99Ms','heapUsedBytes','heapPoolPeaksBytes',
      'sampledPeakWorkingSetBytes','gcCount','gcTimeMs'];
    const storage=['budgetBytes','cacheCapacityBytes','cacheUsedBytesEstimate','peakCacheUsedBytesEstimate',
      'cacheHits','cacheMisses','cacheEvictions','enginePageCacheBytes','metadataReserveBytes','temporaryReserveBytes',
      'peakUnsavedBytesEstimate','cacheOverCapacityBytesEstimate','peakDirtyThresholdOvershootBytesEstimate','diskBytes'];
    summary.builds[label]={...Object.fromEntries(fields.map(key=>[key,median(runs.map(r=>r[key]))])),
      querySequencesPerSecond:median(runs.map(r=>r.querySequenceMs.length*1000/r.querySequenceMs.reduce((a,b)=>a+b,0))),
      storage:Object.fromEntries(storage.map(key=>[key,median(runs.map(r=>r.storage[key]))])),
      index:runs[0].index,environment:{java:runs[0].java,os:runs[0].os,processors:runs[0].processors,
        maxHeapBytes:runs[0].maxHeapBytes,cacheAllowanceBytes:runs[0].cacheAllowanceBytes},
      jarSha256:await hash(label==='baseline'?baselineJar:candidateJar)};
  }
  summary.limitations=['Heap is a post-workload sample, not measured retained size. Pool peaks need not occur simultaneously.',
    'Sampled process working set is not a hard total-memory ceiling. Driver/native/OS allocations are not fully accounted.',
    'Query sequence throughput is not MCP/network throughput. Three runs and 30 timed sequences per run are a small sample.',
    'Hybrid staged rebuilds have dirty-buffer overshoot estimates; zero cache overshoot does not mean total RAM is capped.'];
  fs.writeFileSync(path.join(results,'resource-summary.json'),JSON.stringify(summary,null,2)+'\n');
  const files=[],root=path.resolve(dataset);
  async function visit(directory) {
    for(const entry of fs.readdirSync(directory,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))) {
      const file=path.join(directory,entry.name);
      if(entry.isSymbolicLink())throw Error('Frozen dataset must not contain symlinks: '+file);
      if(entry.isDirectory())await visit(file);
      else if(entry.isFile())files.push({path:path.relative(root,file).replaceAll('\\','/'),bytes:fs.statSync(file).size,sha256:await hash(file)});
    }
  }
  await visit(root);files.sort((a,b)=>a.path.localeCompare(b.path));
  const inventorySha256=crypto.createHash('sha256').update(JSON.stringify(files)).digest('hex');
  fs.writeFileSync(path.join(results,'dataset-manifest.json'),JSON.stringify({inventorySha256,fileCount:files.length,files},null,2)+'\n');
  console.log(JSON.stringify({summary,inventorySha256,fileCount:files.length},null,2));
}
if(require.main===module)main(...process.argv.slice(2)).catch(error=>{console.error(error);process.exitCode=1;});
