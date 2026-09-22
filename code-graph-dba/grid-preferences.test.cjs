const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const root=__dirname+'/src/main/resources/codegraph/dba/';
const moduleOf=text=>import('data:text/javascript;base64,'+Buffer.from(text).toString('base64'));
const schema=JSON.parse(fs.readFileSync(root+'grid-settings-schema.json','utf8'));
const modelPromise=moduleOf(fs.readFileSync(root+'grid-preferences.js','utf8').replace(/^import schema[^\n]+\n/,'const schema='+JSON.stringify(schema)+';\n'));
const valuesPromise=moduleOf(fs.readFileSync(root+'grid-values.js','utf8').replace(/^import [^\n]+\n/,'const preferences=()=>({}),preferenceState=()=>({local:{}});\n'));
const draftPromise=moduleOf(fs.readFileSync(root+'grid-state.js','utf8'));

test('scope precedence snapshots existing results and seeds future results',async()=>{
 const model=await modelPromise;let saved={revision:0,rowCeiling:10,global:{fontSize:14},connections:{a:{fontSize:16}}};
 await model.initializeGridPreferences(async(path,method,body)=>{if(method==='PUT'){assert.equal(body.expectedRevision,saved.revision);saved={...saved,revision:saved.revision+1,[body.scope==='global'?'global':'connections']:body.scope==='global'?body.settings:{...saved.connections,[body.connectionId]:body.settings}};}return structuredClone(saved);});
 const current={},sibling={};assert.equal(model.preferences(current,'a').fontSize,16);assert.equal(model.preferences(sibling,'a').fontSize,16);
 await model.savePreferences(current,'connection',{fontSize:18},0);assert.equal(model.preferences(current).fontSize,18);assert.equal(model.preferences(sibling).fontSize,16);assert.equal(model.preferences({},'a').fontSize,18);
 await model.savePreferences(current,'result',{fontSize:20},1);assert.equal(model.preferences(current).fontSize,20);assert.equal(model.preferences({},'a').fontSize,18);
 await model.savePreferences(current,'result',{},1);assert.equal(model.preferences(current).fontSize,18);assert.equal(model.pagePreference(current),10);
});
test('formatting and local comparison preserve exact decimal values',async()=>{
 const model=await modelPromise,values=await valuesPromise;
 assert.equal(model.formatNumber('9007199254740993.1250','en-US'),'9,007,199,254,740,993.1250');
 assert.equal(model.formatNumber('-0.0000','en-US'),'-0.0000');
 assert.equal(model.formatNumber('1e99','en-US'),'1e99');
 assert.ok(values.compareValues('9007199254740993.1251','9007199254740993.1250',3)>0);
 assert.ok(values.compareValues('-1.000000000000000000001','-1.000000000000000000002',3)>0);
 assert.notEqual(values.valueKey(null,12),values.valueKey('NULL',12));assert.notEqual(values.valueKey('',12),values.valueKey(null,12));
});
test('cached value discovery excludes draft overlays and preserves raw values',async()=>{
 const {GridDraft}=await draftPromise,{retainedValues}=await valuesPromise;
 const result={columns:[{id:'c1'}],rows:[['before'],['before'],[null],['']],grid:{rowIds:['a','b','c','d'],capabilities:{edit:true},columns:[{id:'c1',editable:true,nullable:true}]}};
 const draft=new GridDraft(result);draft.set(0,{id:'c1',source:0},{kind:'value',value:'after'});
 assert.deepEqual(retainedValues(result,{id:'c1',source:0,jdbcType:12}).find(x=>x.value==='before'),{value:'before',count:2});
});
test('read-only and saved-preview state prevent every draft mutation',async()=>{
 const {GridDraft}=await draftPromise;
 const result={columns:[{id:'c1'}],rows:[['raw']],grid:{rowIds:['a'],capabilities:{edit:true,insert:true,delete:true},columns:[{id:'c1',editable:true,nullable:true}]}};
 const draft=new GridDraft(result);draft.readOnly=()=>true;assert.equal(draft.canEdit(0,'c1'),false);assert.throws(()=>draft.add(),/read-only/);draft.select(0);assert.throws(()=>draft.deleteSelected(),/read-only/);
 draft.readOnly=()=>false;result.grid.refreshRequired=true;assert.throws(()=>draft.set(0,{id:'c1',source:0},{kind:'value',value:'again'}),/refresh/);
 draft.preview=[[{kind:'value',value:'saved'}]];assert.equal(draft.cell(0,{source:0}).value,'saved');assert.equal(result.rows[0][0],'raw');
});
test('datetime controls fall back for submillisecond precision and offsets',async()=>{
 const {dateInputType}=await modelPromise;
 assert.equal(dateInputType({jdbcType:91},'2026-09-21'),'date');
 assert.equal(dateInputType({jdbcType:93},'2026-09-21 12:34:56.123'),'datetime-local');
 assert.equal(dateInputType({jdbcType:93},'2026-09-21 12:34:56.123456789'),null);
 assert.equal(dateInputType({jdbcType:2014},'2026-09-21 12:34:56+03'),null);
});
test('schema validation rejects unsupported values and font metrics stay consistent',async()=>{
 const model=await modelPromise;
 for(const values of [{pageSize:0},{fontSize:21},{cancelTimeout:999},{readOnly:'true'},{dates:'utc'},{emptyText:'\n'},{sql:'SELECT 1'}])assert.throws(()=>model.validatePreferences(values));
 const result={};model.preferenceState(result).local={fontSize:20,rowHeight:24};assert.equal(model.rowHeight(result),30);
 assert.equal(model.fields.length,24);
});

test('loaded sorting compares timestamps with nanoseconds and timezone offsets',async()=>{
 const {compareValues}=await valuesPromise;
 assert.ok(compareValues('2026-09-21 12:00:00.000000002','2026-09-21 12:00:00.000000001',93)>0);
 assert.ok(compareValues('2026-09-21 12:00:00+03','2026-09-21 10:00:00Z',2014)<0);
 assert.equal(compareValues('12:00:00.123456789+03','09:00:00.123456789Z',2013),0);
 assert.ok(compareValues('Infinity','99999999999999999999999999999999',8)>0);
 assert.ok(compareValues('-Infinity','-99999999999999999999999999999999',8)<0);
 assert.ok(compareValues(null,'',12)<0);
});
