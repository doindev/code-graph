const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(base+'/dba');
    await page.evaluate(async()=>{
      const {NativeWorkspace}=await import('/dba/native-workspace.js');
      const host=document.createElement('div');host.id='list-fixture';host.style='position:fixed;inset:0;z-index:100;background:#101722';document.body.append(host);
      const f=window.listFixture={commands:[],applies:0,releases:0,accounts:[],base64:'AP8=',phase:'complete'};
      const api=async(path,method,input)=>{
        if(path==='/native/prepare'){f.command=input.command;f.commands.push(structuredClone(input));return{id:'list-review',targetRevision:'list-r1',mutation:!!input.command.transaction,destructive:input.command.transaction?.[0]?.[0]==='LTRIM',expiresAt:Date.now()+60000,target:{connectionName:'List fixture',database:input.database},after:{nativeCommand:input.command},transactionNotice:'List edits check current length and position bytes; positions are not stable identities. TTL preserved unless deleting the last item removes the key and TTL.'};}
        if(path==='/native/apply'){f.applies++;if(f.lostReply)throw Error('Lost reply');return{id:'list-job'};}
        if(path==='/native/execute')return{id:'list-job'};
        if(method==='DELETE'){f.releases++;return{};}
        if(path.endsWith('/cancel')){f.cancelled=true;return{};}
        if(path==='/jobs/list-job'){
          if(f.phase==='running')return{id:'list-job',state:'running'};
          const write=!!f.command.transaction;
          const result=write?{kind:'transaction',outcome:f.error?'conflict':'acknowledged',entries:f.error?[]:[{index:0,state:'acknowledged',value:f.receipt??'OK'}]}:
            {kind:'pipeline',entries:[f.type??'list',f.length??3,{base64:f.base64,truncated:!!f.truncated},f.ttl??60000].map((value,index)=>({index,state:'acknowledged',value}))};
          return{id:'list-job',state:write&&f.error?'failed':'complete',finished:Date.now(),result,error:f.error};
        }
        throw Error('Unexpected API '+path);
      };
      f.workspace=new NativeWorkspace({api,profile:{id:'list-1',name:'List fixture',transport:'redis',readOnly:false},state:{database:'3',commandText:'["LINDEX",{"base64":"a2V5"},"1"]'},changed:()=>{},account:bytes=>f.accounts.push(bytes)});f.workspace.mount(host);
    });
    await page.getByRole('button',{name:'Edit Redis list item',exact:true}).click();
    const editor=page.getByRole('region',{name:'Redis list item value editor'}),value=editor.getByRole('textbox',{name:'Redis list item value',exact:true});
    const load=editor.getByRole('button',{name:'Load list item',exact:true}),save=editor.getByRole('button',{name:'Save list item',exact:true}),revert=editor.getByRole('button',{name:'Revert list item draft'});
    const index=editor.getByRole('textbox',{name:'Index (zero-based)'});
    assert.equal(await index.inputValue(),'1');assert.equal(await editor.getByRole('textbox',{name:'Key',exact:true}).inputValue(),'a2V5');
    assert.equal(await page.getByRole('button',{name:'Edit Redis hash field',exact:true}).isDisabled(),true);
    assert.equal(await page.getByRole('textbox',{name:'Database',exact:true}).isDisabled(),true);
    await index.fill('-1');await load.click();assert.match(await editor.getByRole('status').innerText(),/zero-based/);assert.equal(await page.evaluate(()=>listFixture.commands.length),0);
    await index.fill('1');await load.click();await editor.getByRole('status').filter({hasText:'Loaded 2 bytes'}).waitFor();
    assert.equal(await index.isDisabled(),true);assert.equal(await value.inputValue(),'AP8=');assert.equal(await save.isDisabled(),true);
    const prepend=editor.getByRole('button',{name:'Stage prepend item'}),append=editor.getByRole('button',{name:'Stage append item'}),remove=editor.getByRole('button',{name:'Mark list end item for deletion'});
    assert.equal(await remove.isDisabled(),true,'Interior deletion is unavailable');assert.equal(await prepend.isDisabled(),false);
    assert.deepEqual((await page.evaluate(()=>listFixture.commands[0])).command,{pipeline:[['TYPE',{base64:'a2V5'}],['LLEN',{base64:'a2V5'}],['LINDEX',{base64:'a2V5'},'1'],['PTTL',{base64:'a2V5'}]]});
    await value.fill('AQI=');await revert.click();assert.equal(await value.inputValue(),'AP8=');
    await value.fill('AQI=');await page.evaluate(()=>{listFixture.workspace.unmount();listFixture.workspace.mount(document.querySelector('#list-fixture'));});assert.equal(await value.inputValue(),'AQI=');
    page.once('dialog',d=>d.dismiss());await editor.getByRole('button',{name:'Close value editor'}).click();assert.equal(await editor.isVisible(),true);
    await save.click();const review=page.getByRole('dialog',{name:'Review native database change'});await review.waitFor();
    assert.match(await review.innerText(),/not stable identities/);await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();assert.equal(await page.evaluate(()=>listFixture.applies),0);
    await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Saved'}).waitFor();
    const request=await page.evaluate(()=>listFixture.commands.at(-1));assert.equal(request.database,'3');assert.equal(request.expectedTargetRevision,'list-r1');
    assert.deepEqual(request.command,{transaction:[['LSET',{base64:'a2V5'},'1',{base64:'AQI='}]],watch:[{key:{base64:'a2V5'},index:1,length:3,expected:{base64:'AP8='}}]});
    await value.fill('AwQ=');await page.evaluate(()=>listFixture.error='Redis transaction conflict; no commands executed');
    await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'transaction conflict'}).waitFor();assert.equal(await value.inputValue(),'AwQ=');
    await page.evaluate(()=>{listFixture.error=null;listFixture.receipt=0;});await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await save.isDisabled(),true);assert.equal(await revert.isDisabled(),true);
    page.once('dialog',d=>d.accept());await load.click();await editor.getByRole('status').filter({hasText:'Loaded 2 bytes'}).waitFor();
    await value.fill('AQI=');page.once('dialog',d=>d.dismiss());await prepend.click();assert.equal(await value.inputValue(),'AQI=');assert.equal(await prepend.getAttribute('aria-pressed'),'false');
    page.once('dialog',d=>d.accept());await prepend.click();assert.equal(await value.inputValue(),'');assert.equal(await save.isDisabled(),false,'Empty list items are valid staged values');assert.equal(await remove.isDisabled(),true);
    await revert.click();assert.equal(await value.inputValue(),'AP8=');assert.equal(await save.isDisabled(),true);
    await prepend.focus();await prepend.press('Space');await value.fill('first');await save.click();await review.waitFor();await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();assert.equal(await prepend.getAttribute('aria-pressed'),'true');
    await page.evaluate(()=>listFixture.receipt=4);await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Added list item'}).waitFor();
    assert.deepEqual((await page.evaluate(()=>listFixture.commands.at(-1))).command,{transaction:[['LPUSH',{base64:'a2V5'},{base64:'Zmlyc3Q='}]],watch:[{key:{base64:'a2V5'},index:1,length:3,expected:{base64:'AP8='}}]});assert.equal(await index.inputValue(),'0');assert.equal(await save.isDisabled(),true);assert.equal(await prepend.isDisabled(),true,'Reload after positional changes');
    await page.evaluate(()=>{listFixture.length=4;listFixture.base64='Zmlyc3Q=';});await load.click();await editor.getByRole('status').filter({hasText:'Loaded 5 bytes'}).waitFor();
    await append.click();await page.evaluate(()=>listFixture.receipt=5);await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Added list item'}).waitFor();
    assert.deepEqual((await page.evaluate(()=>listFixture.commands.at(-1))).command,{transaction:[['RPUSH',{base64:'a2V5'},{base64:''}]],watch:[{key:{base64:'a2V5'},index:0,length:4,expected:{base64:'Zmlyc3Q='}}]});assert.equal(await index.inputValue(),'4');
    await page.evaluate(()=>{listFixture.length=5;listFixture.base64='';});await load.click();await editor.getByRole('status').filter({hasText:'Loaded 0 bytes'}).waitFor();assert.equal(await remove.isDisabled(),false);
    await remove.click();assert.equal(await value.getAttribute('readonly'),'');assert.equal(await remove.getAttribute('aria-pressed'),'true');await revert.click();assert.equal(await remove.getAttribute('aria-pressed'),'false');
    await remove.click();await save.click();await review.waitFor();assert.match(await review.innerText(),/Review destructive database change/);assert.match(await review.innerText(),/last item removes the key and TTL/);await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();
    await page.evaluate(()=>listFixture.receipt=1);await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await save.isDisabled(),true);assert.equal(await prepend.isDisabled(),true);assert.equal(await revert.isDisabled(),true);
    page.once('dialog',d=>d.accept());await load.click();await editor.getByRole('status').filter({hasText:'Loaded 0 bytes'}).waitFor();await remove.click();await page.evaluate(()=>listFixture.receipt='OK');await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Deleted list end item'}).waitFor();
    assert.deepEqual((await page.evaluate(()=>listFixture.commands.at(-1))).command,{transaction:[['LTRIM',{base64:'a2V5'},'0','-2']],watch:[{key:{base64:'a2V5'},index:4,length:5,expected:{base64:''}}]});assert.equal(await index.inputValue(),'3');assert.equal(await remove.isDisabled(),true);
    await index.fill('0');await page.evaluate(()=>listFixture.length=1);await load.click();await editor.getByRole('status').filter({hasText:'Loaded 0 bytes'}).waitFor();await remove.click();await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Deleted list end item'}).waitFor();
    assert.deepEqual((await page.evaluate(()=>listFixture.commands.at(-1))).command.transaction,[['LTRIM',{base64:'a2V5'},'1','-1']]);
    await page.evaluate(()=>{listFixture.type='none';listFixture.length=0;listFixture.ttl=-2;});await load.click();await editor.getByRole('status').filter({hasText:'Choose an existing list'}).waitFor();assert.equal(await prepend.isDisabled(),true);assert.equal(await save.isDisabled(),true);
    await page.evaluate(()=>{listFixture.type='list';listFixture.length=10000;listFixture.ttl=-1;listFixture.base64='AP8=';});await index.fill('9999');await load.click();await editor.getByRole('status').filter({hasText:'Loaded 2 bytes'}).waitFor();assert.equal(await prepend.isDisabled(),true);assert.equal(await append.isDisabled(),true);await page.evaluate(()=>listFixture.workspace.stringEditor.stageListEnd('append'));assert.equal(await save.isDisabled(),true);
    const rejected=await page.evaluate(async()=>{
      const {redisListSnapshot,redisListIndex}=await import('/dba/redis-string-editor.js');
      const result=(type,length,value,ttl)=>({kind:'pipeline',entries:[type,length,value,ttl].map((value,index)=>({index,state:'acknowledged',value}))});
      return [()=>redisListIndex('-1'),()=>redisListIndex('10000'),()=>redisListIndex(1),()=>redisListSnapshot({},1),
        ()=>redisListSnapshot(result('none',0,{base64:''},-2),1),()=>redisListSnapshot(result('list',1,{base64:''},-1),1),
        ()=>redisListSnapshot(result('list',10001,{base64:''},-1),1),()=>redisListSnapshot(result('list',3,{base64:'',truncated:true},-1),1),
        ()=>redisListSnapshot(result('list',3,{base64:btoa('x'.repeat(8193))},-1),1),()=>redisListSnapshot(result('list',3,{missing:true},-1),1),
        ()=>redisListSnapshot(result('list',3,{base64:''},-2),1)].map(fn=>{try{fn();return false;}catch{return true;}});
    });assert.equal(rejected.every(Boolean),true);
    await page.setViewportSize({width:420,height:600});await save.scrollIntoViewIfNeeded();
    const reset=editor.getByRole('button',{name:'Choose another position'});await value.fill('AQI=');page.once('dialog',d=>d.dismiss());await reset.click();assert.equal(await index.isDisabled(),true);
    page.once('dialog',d=>d.accept());await reset.click();assert.equal(await index.isDisabled(),false);assert.equal(await save.isDisabled(),true);assert.equal(await remove.isDisabled(),true);await index.fill('0');await page.evaluate(()=>listFixture.length=1);await load.click();await editor.getByRole('status').filter({hasText:'Loaded 2 bytes'}).waitFor();await value.fill('AQI=');await save.scrollIntoViewIfNeeded();
    assert.equal(await save.evaluate(e=>{const r=e.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;}),true);
    await page.screenshot({path:'code-graph-dba/target/redis-list-editor.png'});
    await value.fill('AQI=');const before=await page.evaluate(()=>listFixture.applies);
    await page.evaluate(async()=>{listFixture.workspace.profile.readOnly=true;listFixture.workspace.stringEditor.sync();await listFixture.workspace.stringEditor.save();});assert.equal(await save.isDisabled(),true);assert.equal(await page.evaluate(()=>listFixture.applies),before);
    await page.evaluate(()=>listFixture.workspace.invalidate('Connection removed. Draft retained.'));assert.equal(await load.isDisabled(),true);assert.equal(await value.inputValue(),'AQI=');
    await page.evaluate(()=>listFixture.workspace.dispose());assert.equal(await editor.count(),0);assert.equal(await page.evaluate(()=>listFixture.accounts.at(-1)),0);
    assert.deepEqual(errors,[]);
    console.log('List-end editor passed: binary/empty prepend/append, guarded end deletion, interior/max-length restrictions, exact review, TTL warnings, cancellation/uncertainty, Revert, lifecycle and narrow layout.');
  }finally{await context.close();}
};
