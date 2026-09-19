'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {compare,fingerprint}=require('./diagnose-mcp-catalog.cjs');
test('catalog identity ignores key/registration order, not descriptions or schemas',()=>{
  const a=[{name:'b',inputSchema:{type:'object',properties:{x:{type:'string'}}}},{name:'a',description:'first'}];
  const b=[{description:'first',name:'a'},{inputSchema:{properties:{x:{type:'string'}},type:'object'},name:'b'}];
  assert.equal(fingerprint(a),fingerprint(b));
  b[0].description='changed';assert.notEqual(fingerprint(a),fingerprint(b));
  assert.deepEqual(compare(a,b).differentDefinitions,['a']);
});
test('client filtering and server registration differences remain separate',()=>{
  assert.deepEqual(compare([{name:'a'},{name:'new'}],[{name:'a'},{name:'old'}]),{
    missingFromClient:['new'],missingFromServer:['old'],differentDefinitions:[]
  });
});
test('name-only client inventory does not pretend to compare absent schemas',()=>{
  assert.deepEqual(compare([{name:'a',description:'server',inputSchema:{type:'object'}}],['a']),{
    missingFromClient:[],missingFromServer:[],differentDefinitions:[]
  });
});
