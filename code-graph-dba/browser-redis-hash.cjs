const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(base+'/dba');
    await page.evaluate(async()=>{
      const {NativeWorkspace}=await import('/dba/native-workspace.js');
      const host=document.createElement('div');host.id='hash-fixture';host.style='position:fixed;inset:0;z-index:100;background:#101722';document.body.append(host);
      const f=window.hashFixture={commands:[],applies:0,releases:0,accounts:[],base64:'AP8=',phase:'complete',receipt:0};
      const api=async(path,method,input)=>{
        if(path==='/native/prepare'){f.command=input.command;f.commands.push(structuredClone(input));return{id:'hash-review',targetRevision:'hash-r1',mutation:!!input.command.transaction,expiresAt:Date.now()+60000,target:{connectionName:'Hash fixture',database:input.database},after:{nativeCommand:input.command},transactionNotice:'Exact field WATCH check; HSET preserves key TTL, rejects expiring fields.'};}
        if(path==='/native/apply'){f.applies++;if(f.lostReply)throw Error('Lost reply');return{id:'hash-job'};}
        if(path==='/native/execute')return{id:'hash-job'};
        if(method==='DELETE'){f.releases++;return{};}
        if(path.endsWith('/cancel')){f.cancelled=true;return{};}
        if(path==='/jobs/hash-job'){
          if(f.phase==='running')return{id:'hash-job',state:'running'};
          const write=!!f.command.transaction;
          const result=write?{kind:'transaction',outcome:f.error?'not_started':'acknowledged',entries:f.error?[]:[{index:0,state:'acknowledged',value:f.receipt}]}:{kind:'values',entries:[f.missing?{missing:true}:{base64:f.base64,truncated:!!f.truncated}]};
          return{id:'hash-job',state:write&&f.error?'failed':'complete',finished:Date.now(),result,error:f.error};
        }
        throw Error('Unexpected API '+path);
      };
      f.workspace=new NativeWorkspace({api,profile:{id:'hash-1',name:'Hash fixture',transport:'redis',readOnly:false},state:{database:'3',commandText:'["HGET",{"base64":"a2V5"},{"base64":"AP8="}]'},changed:()=>{},account:bytes=>f.accounts.push(bytes)});f.workspace.mount(host);
    });
    await page.getByRole('button',{name:'Edit Redis hash field',exact:true}).click();
    const editor=page.getByRole('region',{name:'Redis hash field value editor'}),value=editor.getByRole('textbox',{name:'Redis hash field value',exact:true});
    assert.equal(await editor.getByRole('textbox',{name:'Key',exact:true}).inputValue(),'a2V5');
    assert.equal(await editor.getByRole('textbox',{name:'Field',exact:true}).inputValue(),'AP8=');
    assert.equal(await editor.getByRole('combobox',{name:'Field encoding'}).inputValue(),'base64');
    assert.equal(await page.getByRole('button',{name:'Edit Redis string value',exact:true}).isDisabled(),true);
    assert.equal(await page.getByRole('textbox',{name:'Database',exact:true}).isDisabled(),true);
    await editor.getByRole('button',{name:'Load hash field',exact:true}).click();await editor.getByRole('status').filter({hasText:'Loaded 2 bytes'}).waitFor();
    assert.equal(await value.inputValue(),'AP8=');assert.equal(await editor.getByRole('textbox',{name:'Field',exact:true}).isDisabled(),true);
    await value.fill('AQI=');await editor.getByRole('button',{name:'Revert hash field draft'}).click();assert.equal(await value.inputValue(),'AP8=');
    await value.fill('AQI=');await editor.getByRole('button',{name:'Save hash field',exact:true}).click();
    const review=page.getByRole('dialog',{name:'Review native database change'});await review.waitFor();assert.match(await review.innerText(),/HSET/);assert.equal(await page.evaluate(()=>hashFixture.applies),0);
    await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();assert.equal(await value.inputValue(),'AQI=');
    await editor.getByRole('button',{name:'Save hash field',exact:true}).click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Saved · key TTL preserved'}).waitFor();
    assert.equal(await editor.getByRole('button',{name:'Save hash field',exact:true}).isDisabled(),true);
    const saved=await page.evaluate(()=>hashFixture.commands.at(-1));assert.equal(saved.expectedTargetRevision,'hash-r1');assert.equal(saved.database,'3');
    assert.deepEqual(saved.command,{transaction:[['HSET',{base64:'a2V5'},{base64:'AP8='},{base64:'AQI='}]],watch:[{key:{base64:'a2V5'},field:{base64:'AP8='},expected:{base64:'AP8='}}]});
    assert.equal(await page.locator('#hash-fixture .native-command').inputValue(),'["HGET",{"base64":"a2V5"},{"base64":"AP8="}]');
    await value.fill('AQM=');
    await page.evaluate(()=>{hashFixture.workspace.profile.readOnly=true;hashFixture.workspace.stringEditor.sync();});
    assert.equal(await value.getAttribute('readonly'),'');assert.equal(await editor.getByRole('button',{name:'Save hash field',exact:true}).isDisabled(),true);
    const before=await page.evaluate(()=>hashFixture.applies);await page.evaluate(()=>hashFixture.workspace.stringEditor.save());assert.equal(await page.evaluate(()=>hashFixture.applies),before);
    await page.evaluate(()=>{hashFixture.workspace.profile.readOnly=false;hashFixture.workspace.stringEditor.revert();});
    await editor.getByRole('combobox',{name:'Value encoding'}).selectOption('base64');
    await value.fill('AQM=');await page.evaluate(()=>hashFixture.receipt=1);await editor.getByRole('button',{name:'Save hash field',exact:true}).click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'outcome uncertain'}).waitFor();
    assert.equal(await editor.getByRole('button',{name:'Save hash field',exact:true}).isDisabled(),true,'An unexpected newly-created-field receipt requires reconciliation');
    page.once('dialog',d=>d.accept());await page.evaluate(()=>{hashFixture.receipt=0;hashFixture.base64='AQI=';});await editor.getByRole('button',{name:'Load hash field',exact:true}).click();await editor.getByRole('status').filter({hasText:'Loaded 2 bytes'}).waitFor();
    await editor.getByRole('combobox',{name:'Value encoding'}).selectOption('base64');
    await page.evaluate(()=>hashFixture.missing=true);await editor.getByRole('button',{name:'Load hash field',exact:true}).click();await editor.getByRole('status').filter({hasText:'missing fields'}).waitFor();assert.equal(await value.inputValue(),'AQI=');
    await page.evaluate(()=>{hashFixture.missing=false;hashFixture.truncated=true;});await editor.getByRole('button',{name:'Load hash field',exact:true}).click();await editor.getByRole('status').filter({hasText:'truncated values'}).waitFor();assert.equal(await value.inputValue(),'AQI=');
    await value.fill('');
    for(const error of ['Redis transaction conflict; no commands executed','Expiring hash fields cannot use this editor']){
      await page.evaluate(error=>hashFixture.error=error,error);await editor.getByRole('button',{name:'Save hash field',exact:true}).click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:error}).waitFor();assert.equal(await value.inputValue(),'');
    }
    await page.evaluate(()=>{hashFixture.error=null;hashFixture.lostReply=true;});await editor.getByRole('button',{name:'Save hash field',exact:true}).click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await editor.getByRole('button',{name:'Save hash field',exact:true}).isDisabled(),true);
    page.once('dialog',d=>d.dismiss());await editor.getByRole('button',{name:'Close value editor'}).click();assert.equal(await editor.isVisible(),true);
    await page.evaluate(()=>{hashFixture.workspace.unmount();hashFixture.workspace.mount(document.querySelector('#hash-fixture'));});assert.equal(await value.inputValue(),'');
    await page.setViewportSize({width:480,height:760});assert.equal(await editor.evaluate(e=>e.scrollWidth<=e.clientWidth+1),true);
    await editor.getByRole('button',{name:'Save hash field',exact:true}).scrollIntoViewIfNeeded();
    assert.equal(await editor.getByRole('button',{name:'Save hash field',exact:true}).evaluate(e=>{const r=e.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;}),true,'Narrow editor footer remains reachable by scrolling');
    await page.screenshot({path:'code-graph-dba/target/redis-hash-editor.png'});
    const rejected=await page.evaluate(async()=>{
      const {redisHashSnapshot}=await import('/dba/redis-string-editor.js');return [{},{kind:'values',entries:[]},{kind:'values',entries:[{base64:'AP8=',truncated:true}]},{kind:'values',entries:[{base64:btoa('x'.repeat(8193))}]}].map(result=>{try{redisHashSnapshot(result);return false;}catch{return true;}});
    });assert.deepEqual(rejected,[true,true,true,true]);
    page.once('dialog',d=>d.accept());await page.evaluate(()=>{hashFixture.lostReply=false;hashFixture.truncated=false;hashFixture.phase='running';});await editor.getByRole('button',{name:'Load hash field',exact:true}).click();await page.waitForFunction(()=>hashFixture.workspace.operation?.id==='hash-job');
    await page.evaluate(()=>{hashFixture.workspace.dispose();hashFixture.phase='complete';});await page.waitForFunction(()=>!hashFixture.workspace.operation);
    assert.equal(await page.evaluate(()=>hashFixture.cancelled),true);assert.equal(await page.evaluate(()=>hashFixture.accounts.at(-1)),0);assert.deepEqual(errors,[]);
    console.log('Redis hash editor: binary fields, exact review/revision, TTL errors, conflicts/uncertainty, draft preservation, cancellation and narrow layout passed.');
  }finally{await context.close();}
};
