'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const dir=process.argv[2],read=name=>JSON.parse(fs.readFileSync(path.join(dir,name),'utf8'));
const oracle=read('module-oracle.json').oracle,grades=[],summary={};
const median=xs=>xs.toSorted((a,b)=>a-b)[Math.floor(xs.length/2)];
for(const phase of ['baseline','candidate']) {
  const suites=[];
  for(let run=1;run<=3;run++) {
    const report=read('modules-'+phase+'-'+run+'.json'),events=report.results.flatMap(t=>t.events);
    for(const task of report.results) {
      const expected=oracle.find(o=>o.name===task.task);
      const matches=task.references.filter(r=>r.path===expected.caller&&r.occurrence?.span.startLine===expected.line);
      const correct=matches.length===1&&task.references.length===1;
      const sourceCovers=task.events.some(e=>e.category==='discovery_read'&&e.files.some(f=>f.replaceAll('\\','/')===expected.caller));
      if(task.outcome==='mcp_sufficient')assert.ok(correct,'Incorrect early stopping: '+task.task);
      else assert.ok(sourceCovers,'Fallback omitted caller: '+task.task);
      grades.push({phase,run,task:task.task,outcome:task.outcome,correctMcpAnswer:correct,
        falsePositiveReferences:task.references.length-matches.length,falseNegativeReferences:matches.length?0:1,
        fallbackAcquiredCaller:sourceCovers});
    }
    suites.push({run,acquisitionMs:report.results.reduce((s,t)=>s+t.acquisitionMs,0),responseBytes:events.reduce((s,e)=>s+e.responseBytes,0),
      counts:events.reduce((s,e)=>(s[e.category]=(s[e.category]||0)+1,s),{})});
  }
  summary[phase]={suites,medianAcquisitionMs:median(suites.map(s=>s.acquisitionMs)),medianResponseBytes:median(suites.map(s=>s.responseBytes))};
}
const report={methodology:'Five authored module-binding questions; independent expected references are graded after adaptive acquisition. Three fresh hosts per build. No model/reasoning or semantic source-review time is measured.',summary,grades};
fs.writeFileSync(path.join(dir,'module-summary.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(summary,null,2));
