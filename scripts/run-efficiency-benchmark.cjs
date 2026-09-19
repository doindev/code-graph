#!/usr/bin/env node
'use strict';
const fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process');
const modules=require('./benchmark-module-navigation.cjs'),java=require('./benchmark-agent-navigation.cjs');
const {McpClient}=require('./mcp-benchmark-client.cjs');
async function host(jar,dataset) {
  const child=spawn('java',['-Xmx768m','--enable-native-access=ALL-UNNAMED','-cp',jar,path.join(__dirname,'EfficiencyServer.java'),dataset],{windowsHide:true,stdio:['pipe','pipe','pipe']});
  let stdout='',stderr='',ready;
  const exited=new Promise(resolve=>child.once('exit',(code,signal)=>resolve({code,signal})));
  child.stderr.on('data',data=>{stderr=(stderr+data).slice(-65536);});
  child.stdout.on('data',data=>{stdout+=data;});
  const deadline=Date.now()+180000;
  while(!ready) {
    if(child.exitCode!==null)throw Error('Isolated host exited: '+stderr);
    const match=stdout.match(/^READY=(.*)$/m);if(match)ready=JSON.parse(match[1]);
    if(Date.now()>deadline){child.stdin.end();throw Error('Isolated host startup deadline: '+stderr);}
    if(!ready)await new Promise(resolve=>setTimeout(resolve,100));
  }
  return {ready,child,async close(){
    child.stdin.end();
    let timeout;
    const result=await Promise.race([exited,new Promise(resolve=>{timeout=setTimeout(()=>resolve(null),15000);})]);
    clearTimeout(timeout);
    if(!result){child.kill();throw Error('Owned host did not close gracefully; inspect temporary store cleanup');}
    if(result.code!==0)throw Error('Owned host failed: '+stderr);
    const match=stdout.match(/^METRICS=(.*)$/m);return {metrics:match?JSON.parse(match[1]):null,stderr};
  }};
}
async function main(baseline,candidate,dataset,output) {
  fs.mkdirSync(output,{recursive:true});
  const manifest=modules.fixtures(dataset);fs.writeFileSync(path.join(output,'module-oracle.json'),JSON.stringify(manifest,null,2));
  for(let run=1;run<=3;run++)for(const [phase,jar]of (run%2?[['baseline',baseline],['candidate',candidate]]:[['candidate',candidate],['baseline',baseline]])) {
    const server=await host(jar,dataset);
    try {
      const warm=new McpClient(server.ready.endpoint);await warm.initialize();
      await warm.tool('search_symbols',{query:'NativeRedisArguments.bytes',limit:10});await warm.close();
      await java.run(server.ready.endpoint,dataset,phase,run,path.join(output,'workflow-'+phase+'-'+run+'.json'));
      await modules.run(server.ready.endpoint,dataset,phase,run,path.join(output,'modules-'+phase+'-'+run+'.json'));
      console.log(phase+' '+run+' complete');
    }finally{
      const ended=await server.close();
      fs.writeFileSync(path.join(output,'host-'+phase+'-'+run+'.json'),JSON.stringify({...server.ready,...ended.metrics},null,2));
      fs.writeFileSync(path.join(output,'host-'+phase+'-'+run+'.log'),ended.stderr);
    }
  }
}
if(require.main===module)main(...process.argv.slice(2)).catch(error=>{console.error(error);process.exitCode=1;});
module.exports={host};
