'use strict';
// Read-only benchmark transport. Owning harness controls any project/server setup separately.
class McpClient {
  constructor(endpoint) { this.endpoint=endpoint; this.sequence=0; this.session=null; }
  async rpc(method, params) {
    const started=performance.now();
    const response=await fetch(this.endpoint,{method:'POST',signal:AbortSignal.timeout(120000),headers:{
      'Content-Type':'application/json','Accept':'application/json, text/event-stream',
      ...(this.session?{'Mcp-Session-Id':this.session}:{})
    },body:JSON.stringify({jsonrpc:'2.0',id:++this.sequence,method,params})});
    this.session ||= response.headers.get('Mcp-Session-Id');
    const text=await response.text();
    if(!response.ok)throw Error('MCP HTTP '+response.status+': '+text.slice(0,400));
    const data=text.split('\n').find(line=>line.startsWith('data:'));
    const payload=JSON.parse(data?data.slice(5):text);
    if(payload.error)throw Error(JSON.stringify(payload.error));
    return {elapsedMs:performance.now()-started,responseBytes:Buffer.byteLength(text),result:payload.result};
  }
  async initialize() {
    return this.rpc('initialize',{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'adaptive-navigation-benchmark',version:'1'}});
  }
  async tool(name,args) { return this.rpc('tools/call',{name,arguments:args}); }
  async close() {
    if(this.session)await fetch(this.endpoint,{method:'DELETE',signal:AbortSignal.timeout(10000),headers:{'Mcp-Session-Id':this.session}});
  }
}
function unpack(result) {
  if(result.isError)throw Error(result.content?.find(c=>c.type==='text')?.text || 'MCP tool failed');
  return result.structuredContent?.data || JSON.parse(result.content.find(c=>c.type==='text').text);
}
module.exports={McpClient,unpack};
