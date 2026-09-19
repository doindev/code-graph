'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const {host}=require('./run-efficiency-benchmark.cjs');
const {McpClient,unpack}=require('./mcp-benchmark-client.cjs');
const {queries,canonical,stats}=require('./benchmark-symbol-search.cjs');

// Resource gate, not another three-run performance claim. Compare every query to retained old-build witnesses.
async function main(classpath,dataset,witnessFile,output){
  const witnesses=JSON.parse(fs.readFileSync(witnessFile)).resultWitnesses;
  const server=await host(classpath,dataset,{heapMiB:256}),client=new McpClient(server.ready.endpoint);
  const report={heapMiB:256,host:server.ready,samples:[]};
  try{
    await client.initialize();
    const projects=unpack((await client.tool('list_projects',{})).result).projects;
    assert.equal(projects.length,1);assert.equal(projects[0].state,'ready');
    const project=projects[0].name;
    report.before=unpack((await client.tool('index_status',{project})).result);
    for(let round=0;round<10;round++)for(const {id,...args}of queries){
      const response=await client.tool('search_symbols',{project,...args}),data=unpack(response.result);
      assert.equal(data.generation,report.before.generation);
      assert.deepEqual(canonical(data),witnesses[id],id);
      report.samples.push({id,elapsedMs:response.elapsedMs,responseBytes:response.responseBytes});
    }
    report.latency=stats(report.samples);report.passed=true;
  }finally{
    try{await client.close();}finally{
      const end=await server.close();report.metrics=end.metrics;
      fs.writeFileSync(output+'.log',end.stderr);
      fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
    }
  }
  assert.equal(report.metrics.storage.heapMaxBytes,256*1024*1024);
  console.log('256 MiB full-repository MCP search gate passed: '+JSON.stringify(report.latency));
}
main(...process.argv.slice(2)).catch(error=>{console.error(error);process.exitCode=1;});
