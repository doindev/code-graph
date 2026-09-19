'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {evidenceGap,questions}=require('./benchmark-agent-navigation.cjs');
const precise=()=>({symbols:[{confidence:1,locationPrecision:'identifier_token',occurrence:{span:{startLine:1}},
  resolutionEvidence:{resolutionStatus:'resolved',candidateCount:'1',omittedCandidates:'0'}}]});
test('precise bindings permit bounded navigation, not a claim of inventory completeness',()=>{
  assert.equal(evidenceGap({...precise(),inventoryComplete:false}),null);
  assert.ok(questions.some(q=>q.kind==='exhaustive'));
});
test('old high heuristic confidence does not masquerade as resolved binding',()=>{
  assert.match(evidenceGap({symbols:[{confidence:.95}]}),/Ambiguous/);
  assert.match(evidenceGap({symbols:[{confidence:1}]}),/Missing/);
});
test('ambiguous, omitted, absent and containing-only references require fallback',()=>{
  for(const change of [{resolutionStatus:'candidate'},{candidateCount:'2'},{omittedCandidates:'1'}]) {
    const d=precise();Object.assign(d.symbols[0].resolutionEvidence,change);
    assert.ok(evidenceGap(d));
  }
  assert.ok(evidenceGap({symbols:[]}));
  const d=precise();d.symbols[0].locationPrecision='containing_symbol';
  assert.ok(evidenceGap(d));
});
test('implementation task remains a distinct unavoidable source-content control',()=>{
  assert.equal(questions.filter(q=>q.kind==='implementation').length,1);
  assert.equal(questions.filter(q=>q.kind==='references').length,5);
});
