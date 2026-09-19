const {test}=require('node:test');
const assert=require('node:assert/strict');
const {normalized,changed,matches}=require('./src/main/resources/codegraph/viz/settings.js');
test('normalizes equivalent duration and memory units without rounding',()=>{
  assert.equal(normalized('projectTtl','60m'),normalized('projectTtl','1h'));
  assert.equal(normalized('graphMemory','1g'),normalized('graphMemory','1024MiB'));
  assert.equal(normalized('graphMemory','1536m'),'1610612736');
  for(const x of ['0s','-1h','1.5m','10','1e3s','9999999999999999999d'])
    assert.equal(normalized('projectTtl',x),null);
  for(const x of ['0m','31m','1.5g','garbage','9999999999999999999g'])
    assert.equal(normalized('graphMemory',x),null);
});
test('only enabled and meaningfully changed fields are submitted',()=>{
  const base={projectTtl:'3600s',graphMemory:'1536m'};
  assert.deepEqual(changed(base,{projectTtl:'1h',graphMemory:'1536MiB'},{projectTtl:true,graphMemory:true}),{});
  assert.deepEqual(changed(base,{projectTtl:'10m',graphMemory:'2g'},{projectTtl:true,graphMemory:false}),{projectTtl:'10m'});
  assert.deepEqual(changed(base,{projectTtl:'bad',graphMemory:'2g'},{projectTtl:true,graphMemory:true}),{projectTtl:'bad',graphMemory:'2g'});
});
test('searches categories and field descriptions, retaining relevant parents',()=>{
  assert.equal(matches('').length,3);
  assert.equal(matches('server').length,3);
  assert.deepEqual(matches('expire').map(d=>d.id),['ttl']);
  assert.deepEqual(matches('url').map(d=>d.id),['mcp']);
  assert.deepEqual(matches('residency').map(d=>d.id),['memory']);
  assert.equal(matches('nonesuch').length,0);
  assert.equal(matches('RAM')[0].fields.length,2);
});
