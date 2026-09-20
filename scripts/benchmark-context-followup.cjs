'use strict';
// Reuses the frozen independent oracle/dataset; never edits or installs anything in it.
const fs=require('node:fs'),path=require('node:path');
const {host}=require('./run-efficiency-benchmark.cjs');
const {acquire,verify,processMemory}=require('./benchmark-context-navigation.cjs');
async function main(baseline,candidate,dataset,oraclePath,output){
  const oracle=JSON.parse(fs.readFileSync(oraclePath,'utf8'));
  fs.mkdirSync(output,{recursive:true});
  fs.copyFileSync(oraclePath,path.join(output,'oracle.json'));
  for(let run=1;run<=3;run++)for(const [phase,jar]of(run%2?[['baseline',baseline],['candidate',candidate]]:[['candidate',candidate],['baseline',baseline]])){
    const server=await host(jar,dataset),samples=[];let failure;
    try{
      for(let i=0;i<10;i++)verify(await acquire(server.ready.endpoint,dataset,{preferLocations:true}),oracle);
      for(let i=0;i<100;i++){const sample=await acquire(server.ready.endpoint,dataset,{preferLocations:true});verify(sample,oracle);samples.push(sample);}
    }catch(error){failure=error.message;}finally{
      const memory=processMemory(server.child.pid),closed=await server.close();
      const result={phase,run,buildJar:path.resolve(jar),warmup:10,measured:100,preferLocations:true,startup:server.ready,metrics:closed.metrics,processMemory:memory,
        environment:{node:process.version,platform:process.platform,arch:process.arch},failure,samples};
      fs.writeFileSync(path.join(output,phase+'-'+run+'.json'),JSON.stringify(result,null,2));
      console.log(phase+' '+run+' '+(failure?'FAILED '+failure:'passed'));
    }
    if(failure)throw Error(failure);
  }
}
if(require.main===module)main(...process.argv.slice(2)).catch(error=>{console.error(error);process.exitCode=1;});
module.exports={main};
