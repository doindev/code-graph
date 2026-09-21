const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const loaded=import('data:text/javascript;base64,'+fs.readFileSync(__dirname+'/src/main/resources/codegraph/dba/mongo-pipeline-state.js').toString('base64'));
test('new builder does not reinterpret a find command',async()=>{
  const {MongoPipelineState}=await loaded,m=new MongoPipelineState('items','{"find":"items","filter":{"a":1}}');
  assert.equal(m.imported,false);assert.deepEqual(m.command(),{aggregate:'items',pipeline:[],allowDiskUse:false});
});
test('canonical BSON survives import, edit, reorder and serialization',async()=>{
  const {MongoPipelineState}=await loaded,command={aggregate:'items',pipeline:[{$match:{n:{$numberLong:'9007199254740993'},d:{$numberDecimal:'1.00000000000000000001'}}},{$sort:{n:-1}}],allowDiskUse:true};
  const m=new MongoPipelineState('items',JSON.stringify(command)),id=m.stages[0].id;
  assert.deepEqual(m.command(),command);assert.equal(m.move(id,1),true);assert.equal(m.stages[1].id,id);
  assert.deepEqual(m.command().pipeline[1],command.pipeline[0]);assert.equal(m.move(id,3),false);
});
test('disabled drafts are excluded without losing editor text',async()=>{
  const {MongoPipelineState}=await loaded,m=new MongoPipelineState('items'),id=m.add('$match','{"name":"雪"}');
  m.stage(id).enabled=false;assert.equal(m.command().pipeline.length,0);m.stage(id).enabled=true;
  assert.deepEqual(m.command().pipeline,[{$match:{name:'雪'}}]);m.remove(id);assert.throws(()=>m.stage(id),/no longer/);
});
test('only implemented stages and options can be imported; no silent stripping',async()=>{
  const {MongoPipelineState}=await loaded;
  for(const stage of ['$out','$merge','$lookup','$unionWith','$unknown'])assert.throws(()=>new MongoPipelineState('items',JSON.stringify({aggregate:'items',pipeline:[{[stage]:{}}]})),/Unsupported builder stage/);
  for(const command of [{aggregate:'other',pipeline:[]},{aggregate:'items',pipeline:[],hint:'a'},{aggregate:'items',pipeline:[],$db:'other'},{aggregate:'items',pipeline:[{$match:{},$sort:{a:1}}]},{aggregate:'items',pipeline:{}}])assert.throws(()=>new MongoPipelineState('items',JSON.stringify(command)));
  assert.throws(()=>new MongoPipelineState(''),/exact collection/);
});
test('source validation rejects duplicate keys and unsafe numeric tokens',async()=>{
  const {parsePipelineJson}=await loaded;
  for(const text of ['{"a":1,"a":2}','{"x":{"a":1,"\\u0061":2}}','{"x":9007199254740993}','{"x":1e400}','{"x":-0}'])assert.throws(()=>parsePipelineJson(text));
  assert.deepEqual(parsePipelineJson('{"s":"9007199254740993","n":{"$numberLong":"9007199254740993"},"x":[true,false,null,1.5]}'),{s:'9007199254740993',n:{$numberLong:'9007199254740993'},x:[true,false,null,1.5]});
  assert.throws(()=>parsePipelineJson('{$match:{}}'),/valid Extended JSON/);
});
test('nesting, value count and UTF8 limits apply even to disabled stages',async()=>{
  const {MongoPipelineState,parsePipelineJson}=await loaded;
  assert.throws(()=>parsePipelineJson('['.repeat(34)+'0'+']'.repeat(34)),/nesting/);
  assert.throws(()=>parsePipelineJson(JSON.stringify(Array(16385).fill(0))),/values/);
  assert.throws(()=>parsePipelineJson(JSON.stringify('雪'.repeat(50000))),/128 KiB/);
  const m=new MongoPipelineState('items'),id=m.add('$match','{"a":"'+ 'x'.repeat(100000)+'"}');m.stage(id).enabled=false;
  assert.throws(()=>m.add('$match','"'+ 'x'.repeat(40000)+'"'),/128 KiB/);assert.equal(m.stages.length,1);
  assert.throws(()=>m.edit(id,'雪'.repeat(50000)),/128 KiB/);assert.equal(m.stage(id).text.length,100008);
});
test('64 stage bound, stable IDs and all supported stage defaults',async()=>{
  const {MongoPipelineState,PIPELINE_STAGES}=await loaded,m=new MongoPipelineState('items');
  for(const name of Object.keys(PIPELINE_STAGES))m.add(name);assert.equal(m.command().pipeline.length,17);
  while(m.stages.length<64)m.add('$limit');assert.throws(()=>m.add('$limit'),/64 stages/);
  const ids=new Set(m.stages.map(s=>s.id));m.remove(m.stages[0].id);const id=m.add('$limit');assert.equal(ids.has(id),false);
});
test('invalid stage text is retained for correction, not executable',async()=>{
  const {MongoPipelineState}=await loaded,m=new MongoPipelineState('items'),id=m.add('$match');m.edit(id,'{"bad":');
  assert.throws(()=>m.command(),/valid Extended JSON/);assert.equal(m.stage(id).text,'{"bad":');m.edit(id,'{}');assert.equal(m.command().pipeline.length,1);
});
test('object-field ordering must round-trip, including numeric field names',async()=>{
  const {parsePipelineJson}=await loaded;
  assert.throws(()=>parsePipelineJson('{"$sort":{"2":1,"1":-1}}'),/reordered/);
  assert.throws(()=>parsePipelineJson('{"document":{"name":"x","0":true}}'),/reordered/);
  assert.deepEqual(Object.keys(parsePipelineJson('{"1":-1,"2":1,"name":1}')),['1','2','name']);
});
