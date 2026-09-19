'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const {probe,stats}=require('./benchmark-mixed-search.cjs');

test('statistics preserve maxima, empty cases and nearest-rank percentiles',()=>{
  assert.deepEqual(stats([]),{count:0,p50Ms:null,p95Ms:null,p99Ms:null,maxMs:null,meanMs:null});
  assert.deepEqual(stats([9,1,5,3,7]),{count:5,p50Ms:5,p95Ms:9,p99Ms:9,maxMs:9,meanMs:5});
  assert.equal(stats(Array.from({length:100},(_,i)=>100-i)).p95Ms,95);
});

test('body workload retains declarations and changes the bounded call evidence',()=>{
  const declarations=text=>[...text.matchAll(/static void (\w+)\(/g)].map(m=>m[1]);
  for(let sequence=1;sequence<=180;sequence++) {
    const text=probe(sequence,0);
    assert.deepEqual(declarations(text),declarations(probe(0,0)));
    assert.equal([...text.matchAll(/(?:alpha|beta)\(\);/g)].length,sequence%3+1);
    assert.ok(text.includes(sequence%2?'alpha();':'beta();'));
    assert.ok(text.length<4096);
  }
});

test('declaration workload changes only the designated revision name beyond body edits',()=>{
  assert.equal(probe(7,1).replace('revision1','revision2'),probe(7,2));
  assert.notEqual(probe(1,0),probe(7,0),'Save identity is retained even when call shapes repeat');
});
