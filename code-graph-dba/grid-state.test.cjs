const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const modulePromise=import('data:text/javascript;base64,'+fs.readFileSync(__dirname+'/src/main/resources/codegraph/dba/grid-state.js').toString('base64'));
function fixture(){return{columns:[{id:'c1'}],rows:[['one'],['two'],[null]],grid:{revision:1,rowIds:['a','b','c'],capabilities:{edit:true,insert:true,delete:true},columns:[{id:'c1',editable:true,nullable:true,hasDefault:true}]}};}
test('page-local range, toggle and active selection',async()=>{const {GridDraft}=await modulePromise,d=new GridDraft(fixture());d.select(0);d.select(2,{shiftKey:true});assert.deepEqual([...d.selection],[0,1,2]);d.select(1,{ctrlKey:true});assert.deepEqual([...d.selection],[0,2]);assert.equal(d.active,1);d.clearSelection();assert.equal(d.anchor,null);});
test('stages changes without rewriting originals; unchanged edits clear dirty',async()=>{const {GridDraft}=await modulePromise,r=fixture(),d=new GridDraft(r),c={id:'c1',source:0};d.set(0,c,{kind:'value',value:'changed'});assert.equal(r.rows[0][0],'one');assert.equal(d.dirty,true);d.set(0,c,{kind:'value',value:'one'});assert.equal(d.dirty,false);d.set(0,c,{kind:'null'});assert.equal(d.payload().changes[0].values.c1.kind,'null');d.cancel();assert.equal(d.dirty,false);});
test('deletion and insertion remain local and cancel restores snapshot',async()=>{const {GridDraft}=await modulePromise,r=fixture(),d=new GridDraft(r);d.select(1);d.deleteSelected();assert.equal(d.deleted(1),true);assert.equal(r.rows.length,3);d.add();assert.equal(d.length,4);d.deleteSelected();assert.equal(d.length,3);d.cancel();assert.equal(d.deleted(1),false);});
test('caps and read-only capabilities fail closed',async()=>{const {GridDraft,pageSize}=await modulePromise,d=new GridDraft(fixture(),{maxRows:1});d.add();assert.throws(()=>d.add(),/Too many/);const ro=new GridDraft({rows:[['x']]});assert.throws(()=>ro.set(0,{id:'c1',source:0},{kind:'value',value:'y'}),/read-only/);for(const v of ['','0','-1','2.0','1e2','１２','12345678','1001'])assert.throws(()=>pageSize(v));assert.equal(pageSize('200'),200);});
test('typed drafts reject invalid values without losing edits or precision',async()=>{const {GridDraft,validateCell}=await modulePromise;
  for(const [type,bad] of [[4,'1.1'],[16,'yes'],[91,'yesterday'],[93,'no date']])assert.throws(()=>validateCell({name:'field',jdbcType:type},{kind:'value',value:bad}));
  validateCell({name:'big',jdbcType:-5},{kind:'value',value:'9007199254740993'});
  assert.throws(()=>validateCell({name:'big',jdbcType:-5},{kind:'value',value:'999999999999999999999999'}));
  const r=fixture();r.grid.columns[0].jdbcType=4;const d=new GridDraft(r);d.set(0,{id:'c1',source:0},{kind:'value',value:'not a number'});assert.throws(()=>d.payload());assert.equal(d.dirty,true);d.cancel();
});
test('shared draft accounting is released on cancel',async()=>{const {GridDraft}=await modulePromise;
  const a=new GridDraft(fixture()),b=new GridDraft(fixture()),c={id:'c1',source:0};
  a.set(0,c,{kind:'value',value:'x'.repeat(750000)});assert.throws(()=>b.set(0,c,{kind:'value',value:'y'.repeat(750000)}),/allowance/);a.cancel();b.set(0,c,{kind:'value',value:'y'.repeat(750000)});b.cancel();
});
