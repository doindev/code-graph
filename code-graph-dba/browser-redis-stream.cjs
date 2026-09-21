const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(base+'/dba');
    await page.evaluate(async()=>{
      const {NativeWorkspace}=await import('/dba/native-workspace.js');
      const host=document.createElement('div');host.id='stream-fixture';host.style='position:fixed;inset:0;z-index:100;background:#101722';document.body.append(host);
      const f=window.streamFixture={requests:[],applies:0,releases:0,accounts:[],phase:'complete',entryId:'18446744073709551615-18446744073709551615'};
      const api=async(path,method,input)=>{
        if(path==='/native/prepare'){f.command=input.command;f.requests.push(structuredClone(input));return{id:'stream-review',targetRevision:'stream-r1',mutation:Array.isArray(input.command),expiresAt:Date.now()+60000,target:{connectionName:'Stream fixture',database:input.database},after:{nativeCommand:input.command},transactionNotice:'Append only; NOMKSTREAM preserves current TTL and never creates a missing key. No automatic retries.'};}
        if(path==='/native/apply'){f.applies++;if(f.lostReply)throw Error('Lost append reply');return{id:'stream-job'};}
        if(path==='/native/execute')return{id:'stream-job'};
        if(path.endsWith('/cancel')){f.cancelled=true;return{};}
        if(method==='DELETE'){f.releases++;return{};}
        if(path==='/jobs/stream-job'){
          if(f.phase==='running')return{id:'stream-job',state:'running'};
          const write=Array.isArray(f.command),result=write?{kind:'stream',operation:'XADD',outcome:f.error?'rejected':'acknowledged',existingStreamOnly:true,applied:!f.missing,entryId:f.missing?null:f.entryId,value:f.missing?null:(f.badReceipt?'OK':f.entryId),entries:[]}:
            {kind:'pipeline',truncated:!!f.truncated,entries:[f.type??'stream',f.length??2,f.ttl??60000].map((value,index)=>({index,state:'acknowledged',value}))};
          return{id:'stream-job',state:write&&f.error?'failed':'complete',finished:Date.now(),result,error:f.error};
        }
        throw Error('Unexpected API '+path);
      };
      f.workspace=new NativeWorkspace({api,profile:{id:'stream-1',name:'Stream fixture',transport:'redis',readOnly:false},state:{database:'3',commandText:'["XLEN",{"base64":"AP8="}]'},changed:()=>{},account:bytes=>f.accounts.push(bytes)});
      f.workspace.mount(host);
    });
    const open=page.getByRole('button',{name:'Compose Redis stream entry',exact:true});
    await open.click();const editor=page.getByRole('region',{name:'Redis stream entry composer'}),status=editor.getByRole('status');
    const load=editor.getByRole('button',{name:'Load stream',exact:true}),stage=editor.getByRole('button',{name:'Stage new stream entry'}),add=editor.getByRole('button',{name:'Add field/value pair'}),save=editor.getByRole('button',{name:'Save stream entry',exact:true}),revert=editor.getByRole('button',{name:'Revert stream entry draft'});
    assert.equal(await page.evaluate(()=>streamFixture.requests.length),0);assert.equal(await stage.isDisabled(),true);
    assert.equal(await editor.getByRole('textbox',{name:'Key',exact:true}).inputValue(),'AP8=');assert.equal(await page.getByRole('textbox',{name:'Database',exact:true}).isDisabled(),true);
    assert.equal(await page.evaluate(()=>streamFixture.accounts.at(-1)),512*1024);
    await load.click();await status.filter({hasText:'Loaded stream'}).waitFor();
    assert.deepEqual((await page.evaluate(()=>streamFixture.requests[0])).command,{pipeline:[['TYPE',{base64:'AP8='}],['XLEN',{base64:'AP8='}],['PTTL',{base64:'AP8='}]]});
    await stage.focus();await stage.press('Space');assert.equal(await save.isDisabled(),false,'Explicitly staged empty field/value pair is valid');
    const pair=n=>editor.getByRole('group',{name:'Pair '+n,exact:true});
    await pair(1).getByRole('textbox',{name:'Field',exact:true}).fill('same');await pair(1).getByRole('combobox',{name:'Value encoding'}).selectOption('base64');await pair(1).getByRole('textbox',{name:'Value',exact:true}).fill('/wA=');
    await add.click();await pair(2).getByRole('textbox',{name:'Field',exact:true}).fill('same');await pair(2).getByRole('textbox',{name:'Value',exact:true}).fill('last');
    await page.evaluate(()=>{streamFixture.workspace.unmount();streamFixture.workspace.mount(document.querySelector('#stream-fixture'));});assert.equal(await pair(2).getByRole('textbox',{name:'Value',exact:true}).inputValue(),'last');
    page.once('dialog',d=>d.dismiss());await editor.getByRole('button',{name:'Close stream composer'}).click();assert.equal(await editor.isVisible(),true);
    await save.click();const review=page.getByRole('dialog',{name:'Review native database change'});await review.waitFor();assert.match(await review.innerText(),/NOMKSTREAM/);await review.getByRole('button',{name:'Cancel',exact:true}).click();await status.filter({hasText:'Cancelled before execution'}).waitFor();assert.equal(await page.evaluate(()=>streamFixture.applies),0);
    await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'Appended entry 18446744073709551615-18446744073709551615'}).waitFor();
    const request=await page.evaluate(()=>streamFixture.requests.at(-1));assert.equal(request.database,'3');assert.equal(request.expectedTargetRevision,'stream-r1');
    assert.deepEqual(request.command,['XADD',{base64:'AP8='},'NOMKSTREAM','*',{base64:'c2FtZQ=='},{base64:'/wA='},{base64:'c2FtZQ=='},{base64:'bGFzdA=='}]);
    assert.equal(await save.isDisabled(),true);assert.equal(await editor.getByRole('group').count(),0);assert.equal(await stage.isDisabled(),true);
    await load.click();await status.filter({hasText:'Loaded stream'}).waitFor();await stage.click();await save.click();await review.waitFor();await page.evaluate(()=>streamFixture.missing=true);await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'No entry added'}).waitFor();assert.equal(await save.isDisabled(),true);assert.equal(await page.evaluate(()=>streamFixture.workspace.dirty),true);await revert.click();assert.equal(await page.evaluate(()=>streamFixture.workspace.dirty),false);
    await page.evaluate(()=>{streamFixture.missing=false;streamFixture.badReceipt=true;});await load.click();await status.filter({hasText:'Loaded stream'}).waitFor();await stage.click();await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'incomplete or invalid'}).waitFor();assert.equal(await load.isDisabled(),true);assert.equal(await save.isDisabled(),true);assert.equal(await revert.isDisabled(),true);
    const reconciled=editor.getByRole('button',{name:'Discard reconciled stream draft'});page.once('dialog',d=>d.dismiss());await reconciled.click();assert.equal(await load.isDisabled(),true);
    page.once('dialog',d=>d.accept());await reconciled.click();assert.equal(await load.isDisabled(),false);assert.equal(await save.isDisabled(),true);
    await page.evaluate(()=>{streamFixture.badReceipt=false;});await load.click();await status.filter({hasText:'Loaded stream'}).waitFor();await stage.click();
    await pair(1).getByRole('combobox',{name:'Value encoding'}).selectOption('base64');await pair(1).getByRole('textbox',{name:'Value',exact:true}).fill('invalid!');
    const count=await page.evaluate(()=>streamFixture.requests.length);await save.click();assert.match(await status.innerText(),/canonical base64/);assert.equal(await page.evaluate(()=>streamFixture.requests.length),count);
    await revert.click();await stage.click();await pair(1).getByRole('textbox',{name:'Value',exact:true}).fill('x'.repeat(8193));await save.click();assert.match(await status.innerText(),/8 KiB/);
    await revert.click();await stage.click();
    for(let n=1;n<=5;n++){if(n>1)await add.click();await pair(n).getByRole('textbox',{name:'Value',exact:true}).fill('x'.repeat(8192));}
    await save.click();assert.match(await status.innerText(),/32 KiB/);assert.equal(await page.evaluate(()=>streamFixture.requests.length),count);
    await add.click();await pair(6).getByRole('textbox',{name:'Value',exact:true}).fill('x'.repeat(8192));assert.equal(await pair(6).getByRole('textbox',{name:'Value',exact:true}).inputValue(),'','Aggregate draft text bound includes the key');
    await revert.click();await stage.click();for(let n=1;n<32;n++)await add.click();assert.equal(await add.isDisabled(),true);
    await page.evaluate(()=>streamFixture.workspace.stringEditor.addPair());assert.equal(await editor.getByRole('group').count(),32);
    await pair(2).getByRole('button',{name:'Remove field/value pair'}).click();assert.equal(await editor.getByRole('group').count(),31);assert.equal(await add.isDisabled(),false);
    await revert.click();await stage.click();await editor.getByRole('button',{name:'Remove field/value pair'}).click();await save.click();assert.match(await status.innerText(),/1–32/);
    await add.click();await page.evaluate(()=>streamFixture.lostReply=true);await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await save.isDisabled(),true);
    page.once('dialog',d=>d.accept());await reconciled.click();await page.evaluate(()=>{streamFixture.lostReply=false;streamFixture.type='string';});await load.click();await status.filter({hasText:'existing stream'}).waitFor();assert.equal(await stage.isDisabled(),true);
    await page.evaluate(()=>{streamFixture.type='stream';streamFixture.truncated=true;});await load.click();await status.filter({hasText:'Incomplete stream'}).waitFor();
    await page.evaluate(()=>{streamFixture.truncated=false;});await load.click();await status.filter({hasText:'Loaded stream'}).waitFor();await stage.click();await pair(1).getByRole('textbox',{name:'Field',exact:true}).fill('event');await pair(1).getByRole('textbox',{name:'Value',exact:true}).fill('bounded draft');
    await page.setViewportSize({width:420,height:900});await page.screenshot({path:'code-graph-dba/target/redis-stream-editor.png'});
    assert.ok(await editor.evaluate(e=>e.scrollWidth<=e.clientWidth+2),'Narrow composer should not clip controls horizontally');
    assert.ok(await editor.evaluate(e=>e.lastElementChild.getBoundingClientRect().top>=e.querySelector('.redis-stream-fields').getBoundingClientRect().bottom-1),'Footer must remain below the fields, never overlap them');
    await pair(1).scrollIntoViewIfNeeded();await page.screenshot({path:'code-graph-dba/target/redis-stream-editor-fields.png'});
    await page.evaluate(()=>{streamFixture.workspace.profile.readOnly=true;streamFixture.workspace.stringEditor.sync();});assert.equal(await save.isDisabled(),true);assert.equal(await add.isDisabled(),true);
    const before=await page.evaluate(()=>streamFixture.requests.length);await page.evaluate(()=>streamFixture.workspace.stringEditor.save());assert.equal(await page.evaluate(()=>streamFixture.requests.length),before);
    await page.evaluate(()=>{streamFixture.workspace.profile.readOnly=false;streamFixture.workspace.stringEditor.sync();});await save.click();await review.waitFor();await page.evaluate(()=>streamFixture.phase='running');await review.getByRole('button',{name:'Apply once'}).click();
    await page.waitForFunction(()=>streamFixture.workspace.operation?.id==='stream-job');await page.getByRole('button',{name:'Cancel current operation'}).click();await page.evaluate(()=>{streamFixture.phase='complete';streamFixture.entryId='9-0';});await status.filter({hasText:'Appended entry 9-0'}).waitFor();assert.equal(await page.evaluate(()=>streamFixture.cancelled),true,'Confirmed append receipt survives late cancellation');
    await editor.getByRole('button',{name:'Close stream composer'}).click();assert.equal(await page.getByRole('textbox',{name:'Database',exact:true}).isDisabled(),false);
    for(let i=0;i<5;i++){
      await open.click();await load.click();await status.filter({hasText:'Loaded stream'}).waitFor();await stage.click();await revert.click();await editor.getByRole('button',{name:'Close stream composer'}).click();
      assert.equal(await page.evaluate(()=>streamFixture.accounts.at(-1)),await page.evaluate(()=>streamFixture.workspace.displayBytes),'Closing releases the editor reservation');
    }
    await page.evaluate(()=>{const w=streamFixture.workspace,account=w.account;w.account=()=>{throw Error('Browser memory admission refused');};w.openValueEditor('stream');w.account=account;});
    assert.equal(await editor.count(),0,'Admission failure cannot leave a composer mounted');
    await open.click();await load.click();await status.filter({hasText:'Loaded stream'}).waitFor();await stage.click();
    await page.evaluate(()=>streamFixture.workspace.invalidate('Connection removed'));assert.equal(await save.isDisabled(),true);assert.equal(await load.isDisabled(),true);
    await page.evaluate(()=>{streamFixture.workspace.dispose();});assert.equal(await page.evaluate(()=>streamFixture.accounts.at(-1)),0);assert.equal(await editor.count(),0);
    assert.deepEqual(errors,[]);
    console.log('Stream composer passed: existing-only ordered/binary drafts, duplicate/empty fields, bounds, review, typed receipts, missing-key and uncertain outcomes, explicit reconciliation, read-only/late cancellation, cleanup and narrow layout.');
  }finally{await context.close();}
};
