'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const {median}=require('./summarize-hybrid-freshness.cjs');
test('median preserves input and handles odd, even and singleton samples',()=>{
  const input=[3,1,2];assert.equal(median(input),2);assert.deepEqual(input,[3,1,2]);
  assert.equal(median([4,1,2,3]),2.5);assert.equal(median([9]),9);
});
test('empty measurements cannot become a misleading zero result',()=>{
  assert.throws(()=>median([]),/requires samples/);
});
