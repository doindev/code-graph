'use strict';
// Explicit, read-only MCP dogfooding. Logs acquisition evidence, not database payloads.
const fs = require('node:fs');
const path = require('node:path');
const {McpClient, unpack} = require('./mcp-benchmark-client.cjs');
const READS = new Set(['list_projects','index_status','search_symbols','get_symbol','get_file_outline',
  'get_symbol_context','resolve_symbol_at_position','find_references','find_implementations',
  'get_call_graph','get_impact_radius','get_blast_score','find_affected_tests','analyze_change']);

async function acquire(requests, {endpoint='http://localhost:3000/mcp', ledger, catalogPath}={}) {
  for (const request of requests) {
    if (!READS.has(request.tool) || typeof request.purpose !== 'string' || !request.purpose.trim())
      throw Error('Every operation must be an allowed code read with an explicit purpose');
  }
  const client = new McpClient(endpoint);
  try {
    const initialized = await client.initialize();
    const catalog = [];
    let cursor;
    do {
      const page = await client.rpc('tools/list', cursor ? {cursor} : {});
      catalog.push(...page.result.tools);
      cursor = page.result.nextCursor;
    } while (cursor);
    if (catalogPath) {
      fs.mkdirSync(path.dirname(catalogPath), {recursive:true});
      fs.writeFileSync(catalogPath, JSON.stringify({server:initialized.result.serverInfo, tools:catalog},null,2));
    }
    const results=[];
    for (const request of requests) {
      const definition=catalog.find(tool=>tool.name===request.tool);
      if (!definition) throw Error('Tool is not advertised: '+request.tool);
      for (const key of Object.keys(request.arguments || {}))
        if (!Object.hasOwn(definition.inputSchema.properties || {},key))
          throw Error('Unadvertised argument '+request.tool+'.'+key);
      const response = await client.tool(request.tool, request.arguments || {});
      let data, failure;
      try { data=unpack(response.result); } catch (error) { failure=error; data={}; }
      const entry={at:new Date().toISOString(),kind:'mcp',operation:request.tool,purpose:request.purpose,
        arguments:request.arguments || {},durationMs:response.elapsedMs,responseBytes:response.responseBytes,
        generation:data.generation ?? null,error:!!response.result.isError,
        nextCallReason:request.nextCallReason || null};
      if (ledger) {
        fs.mkdirSync(path.dirname(ledger), {recursive:true});
        fs.appendFileSync(ledger, JSON.stringify(entry)+'\n');
      }
      results.push({acquisition:entry,...(failure?{error:failure.message}:{data})});
    }
    return results;
  } finally { await client.close(); }
}

if (require.main === module) {
  const requests=JSON.parse(process.argv[2] || '[]');
  acquire(requests, {ledger:process.argv[3],catalogPath:process.argv[4]})
    .then(results=>console.log(JSON.stringify(results)))
    .catch(error=>{console.error(error.message);process.exitCode=1;});
}
module.exports={acquire};
