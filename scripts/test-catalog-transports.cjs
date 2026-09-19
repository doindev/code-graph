'use strict';
const assert=require('node:assert/strict'),{spawn}=require('node:child_process');
const {host}=require('./run-efficiency-benchmark.cjs');
const {McpClient,unpack}=require('./mcp-benchmark-client.cjs');
const {fingerprint,diagnose}=require('./diagnose-mcp-catalog.cjs');
async function main(jar) {
  const http=await host(jar,'--empty');
  const stdio=spawn('java',['-Djava.awt.headless=true','--enable-native-access=ALL-UNNAMED','-cp',jar,'io.doindev.codegraph.mcp.Main'],
    {windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,CODE_GRAPH_ROOT:''}});
  const ended=new Promise(resolve=>stdio.once('exit',resolve));let line='',next=0,err='';
  const waiting=new Map();stdio.stderr.on('data',data=>{err=(err+data).slice(-4096);});
  stdio.stdout.on('data',data=>{
    line+=data;
    while(line.includes('\n')) {
      const at=line.indexOf('\n'),message=JSON.parse(line.slice(0,at));line=line.slice(at+1);
      if(message.id&&waiting.has(message.id)){waiting.get(message.id)(message);waiting.delete(message.id);}
    }
  });
  async function rpc(method,params) {
    const id=++next;let timeout;
    try {
      const reply=await new Promise((resolve,reject)=>{
        waiting.set(id,resolve);timeout=setTimeout(()=>reject(Error('stdio timeout: '+err)),20000);
        stdio.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');
      });
      if(reply.error)throw Error(JSON.stringify(reply.error));return reply.result;
    }finally{clearTimeout(timeout);waiting.delete(id);}
  }
  const client=new McpClient(http.ready.endpoint);
  try {
    const init=await client.initialize(),other=await rpc('initialize',{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'catalog-regression',version:'1'}});
    stdio.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
    assert.equal(init.result.serverInfo.version,other.serverInfo.version);assert.notEqual(other.serverInfo.version,'0.0.1');
    const a=(await client.rpc('tools/list',{})).result.tools,b=(await rpc('tools/list',{})).tools;
    assert.equal(fingerprint(a),fingerprint(b),'HTTP and stdio catalog mismatch');
    const ctx=unpack((await client.tool('get_workspace_context',{})).result);
    const stdctx=unpack(await rpc('tools/call',{name:'get_workspace_context',arguments:{}}));
    assert.deepEqual(ctx.server,stdctx.server);
    assert.equal(ctx.server.toolCatalogFingerprint,fingerprint(a));
    process.env.CGRAPH_DIAGNOSTIC_SESSION=client.session;
    const fresh=await diagnose(http.ready.endpoint);assert.deepEqual(fresh.comparisons.existingSession.missingFromClient,[]);
    await client.close();const stale=await diagnose(http.ready.endpoint);assert.equal(stale.comparisons.existingSession.unavailable,true);
    delete process.env.CGRAPH_DIAGNOSTIC_SESSION;
    console.log(JSON.stringify({passed:true,count:a.length,server:ctx.server,freshExistingParity:true,staleSessionRejected:true}));
  }finally {
    await client.close().catch(()=>{});stdio.stdin.end();
    let timer;await Promise.race([ended,new Promise(resolve=>{timer=setTimeout(()=>{stdio.kill();resolve();},10000);})]);clearTimeout(timer);
    await http.close();
  }
}
main(process.argv[2]).catch(error=>{console.error(error);process.exitCode=1;});
