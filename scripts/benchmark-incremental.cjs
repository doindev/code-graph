#!/usr/bin/env node
'use strict';
const fs=require('node:fs'),path=require('node:path'),{spawn,execFile}=require('node:child_process');
const {promisify}=require('node:util'),run=promisify(execFile);

async function main(baselineClasspath,candidateClasspath,frozenRoot,output) {
  if(!output)throw Error('Usage: node scripts/benchmark-incremental.cjs BASELINE_CLASSPATH CANDIDATE_CLASSPATH FROZEN_SOURCE OUTPUT');
  fs.mkdirSync(output,{recursive:true});
  for(let iteration=1;iteration<=3;iteration++) {
    const order=iteration%2?[['baseline',baselineClasspath],['candidate',candidateClasspath]]:[['candidate',candidateClasspath],['baseline',baselineClasspath]];
    for(const [phase,classpath]of order) {
      const java=spawn('java',['-Xmx768m','--enable-native-access=ALL-UNNAMED','-cp',classpath,
        path.join(__dirname,'IncrementalBenchmark.java'),frozenRoot,phase],{windowsHide:true,stdio:['ignore','pipe','pipe']});
      let stdout='',stderr='',sampling=false;const memory=[];
      const timer=setInterval(async()=>{
        if(sampling||process.platform!=='win32'||java.exitCode!==null)return;
        sampling=true;
        try{
          const result=await run('powershell.exe',['-NoProfile','-Command',
            'Get-Process -Id '+java.pid+' -ErrorAction Stop | Select-Object WorkingSet64,PeakWorkingSet64,PrivateMemorySize64 | ConvertTo-Json -Compress'],
            {windowsHide:true,timeout:5000});
          memory.push({time:Date.now(),...JSON.parse(result.stdout)});
        }catch{}finally{sampling=false;}
      },2000);
      java.stdout.on('data',data=>{stdout+=data;if(stdout.length>4*1024*1024)java.kill();});
      java.stderr.on('data',data=>{stderr=(stderr+data).slice(-256*1024);process.stderr.write(phase+' '+iteration+': '+data);});
      let watchdog;
      const exit=await new Promise((resolve,reject)=>{
        watchdog=setTimeout(()=>{java.kill();reject(Error('Owned benchmark exceeded 15 minutes'));},900000);
        java.once('error',reject);java.once('exit',(code,signal)=>resolve({code,signal}));
      }).finally(()=>{clearInterval(timer);clearTimeout(watchdog);});
      fs.writeFileSync(path.join(output,phase+'-'+iteration+'.log'),stderr);
      if(exit.code!==0)throw Error(phase+' failed '+JSON.stringify(exit)+'\n'+stderr);
      const result=JSON.parse(stdout.trim().split(/\r?\n/).at(-1));
      result.iteration=iteration;
      result.processMemory={scope:'whole JVM including source launcher compilation and final correctness oracle; 2s OS sampling',samples:memory};
      fs.writeFileSync(path.join(output,phase+'-'+iteration+'.json'),JSON.stringify(result,null,2)+'\n');
      console.log(phase+' '+iteration+' passed');
    }
  }
}
if(require.main===module)main(...process.argv.slice(2)).catch(error=>{console.error(error);process.exitCode=1;});
module.exports={main};
