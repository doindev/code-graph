const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const load=name=>import('data:text/javascript;base64,'+fs.readFileSync(__dirname+'/src/main/resources/codegraph/dba/'+name+'.js').toString('base64'));
const engine=load('grid-search-worker'),drafts=load('grid-state');
const cell=(text,row=0,column='c1',editable=true)=>({text,row,column,editable});
test('literal, case, regex and Unicode whole words',async()=>{
 const {searchCells:find}=await engine,cells=[cell('Alpha alpha alphabet'),cell('éclair éclairage αλφα',1)];
 assert.equal(find({query:'alpha',cells}).matches.length,3);
 assert.equal(find({query:'alpha',caseSensitive:true,wholeWord:true,cells}).matches.length,1);
 assert.equal(find({query:'éclair',wholeWord:true,cells}).matches.length,1);
 assert.equal(find({query:'^Alpha',regex:true,cells}).matches.length,1);
 assert.equal(find({query:'.*',cells:[cell('a.*b')]}).matches.length,1);
 assert.throws(()=>find({query:'[',regex:true,cells}),/Invalid regular/);
});
test('regex replacement groups, current occurrence and literal dollars',async()=>{
 const {searchCells:find}=await engine,cells=[cell('cat-12 cat-34')];
 const found=find({query:'cat-(\\d+)',regex:true,cells,replace:true,replacement:'dog-$1'});
 assert.equal(found.changes[0].text,'dog-12 dog-34');
 assert.equal(find({query:'cat',cells,replace:true,replacement:'$&'}).changes[0].text,'$&-12 $&-34');
 assert.equal(find({query:'cat',cells,replace:true,replacement:'dog',current:{row:0,column:'c1',start:7,end:10}}).changes[0].text,'cat-12 dog-34');
 assert.equal(find({query:'(?<pet>cat)',regex:true,cells,replace:true,replacement:'${pet} $$ $<pet>'}).changes[0].text,'${pet} $ cat-12 ${pet} $ cat-34');
});
test('read-only matches and zero-length Unicode matches are safe',async()=>{
 const {searchCells:find}=await engine;
 const result=find({query:'a',cells:[cell('a'),cell('a',1,'c1',false)],replace:true,replacement:'b'});
 assert.equal(result.changes.length,1);assert.equal(result.skipped,1);
 const empty=find({query:'(?=.)',regex:true,cells:[cell('😀x')],replace:true,replacement:'!'});
 assert.deepEqual(empty.matches.map(m=>m.start),[0,2]);assert.equal(empty.changes[0].text,'!😀!x');
});
test('search and replacement caps fail rather than produce partial results',async()=>{
 const {searchCells:find}=await engine;
 assert.throws(()=>find({query:'x'.repeat(513),cells:[]}),/512/);
 assert.throws(()=>find({query:'a',cells:[cell('a'.repeat(20001))]}),/20,000/);
 assert.throws(()=>find({query:'a',cells:[cell('aa')],replace:true,replacement:'b'.repeat(8192)}),/cell limit/);
});
test('batch staging preserves originals and is atomic for invalid values and budget failures',async()=>{
 const {GridDraft}=await drafts;
 const result={rows:[['one',12],['two',13]],grid:{rowIds:['r1','r2'],capabilities:{edit:true},columns:[{id:'text',name:'Text',jdbcType:12,editable:true,size:20},{id:'number',name:'Number',jdbcType:4,editable:true}]}};
 const d=new GridDraft(result),text={id:'text',source:0},number={id:'number',source:1};
 d.set(1,text,{kind:'value',value:'previous'});
 const edits=[{index:0,column:text,value:{kind:'value',value:'changed'}},{index:0,column:number,value:{kind:'value',value:'invalid'}}];
 assert.throws(()=>d.setMany(edits),/whole number/);assert.equal(d.changes.size,1);assert.equal(d.cell(0,text).value,'one');
 edits[1].value.value='24';d.setMany(edits);assert.equal(d.cell(0,text).value,'changed');assert.equal(result.rows[0][1],12);
 const before=d.changes;d.maxBytes=1;assert.throws(()=>d.setMany([{index:1,column:text,value:{kind:'value',value:'another'}}]),/allowance/);assert.equal(d.changes,before);d.cancel();
});
