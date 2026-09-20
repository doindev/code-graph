const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  try{
    await page.goto(base+'/dba');
    await page.evaluate(async()=>{
      const {NativeWorkspace}=await import('/dba/native-workspace.js');
      const host=document.createElement('div');host.id='string-fixture';host.style='position:fixed;inset:0;z-index:100;background:#101722';document.body.append(host);
      window.stringFixture={commands:[],applies:0,releases:0,accounts:[],base64:btoa('old value'),outcome:'acknowledged',ttl:60000,phase:'complete'};
      const f=window.stringFixture;
      const api=async(path,method,input)=>{
        if(path==='/native/prepare'){f.command=input.command;f.commands.push(structuredClone(input));return {id:'review-key',targetRevision:'revision-1',mutation:!!input.command.transaction,expiresAt:Date.now()+60000,target:{connectionName:'Redis fixture',database:input.database},after:{nativeCommand:input.command},transactionNotice:'WATCH checks exact original bytes; SET XX KEEPTTL preserves TTL. No automatic retry.'};}
        if(path==='/native/apply'){f.applies++;if(f.submissionFailure)throw new Error('Lost submission reply');return{id:'key-job'};}
        if(path==='/native/execute')return{id:'key-job'};
        if(method==='DELETE'){f.releases++;return{};}
        if(path.endsWith('/cancel')){f.cancelled=true;return{};}
        if(path==='/jobs/key-job'){
          if(f.phase==='running')return{id:'key-job',state:'running'};
          const result=f.command.pipeline?{kind:'pipeline',entries:[f.type??'string',f.length??atob(f.base64).length,{base64:f.base64,truncated:!!f.truncated},f.ttl].map((value,index)=>({index,state:'acknowledged',value}))}:{kind:'transaction',outcome:f.outcome,entries:[{index:0,state:'acknowledged',value:'OK'}]};
          return{id:'key-job',state:f.outcome==='conflict'&&f.command.transaction?'failed':'complete',error:f.outcome==='conflict'?'Original value changed':undefined,finished:Date.now(),result};
        }
        throw new Error('Unexpected API '+path);
      };
      f.workspace=new NativeWorkspace({api,profile:{id:'redis-1',name:'Redis fixture',transport:'redis',readOnly:false},state:{database:'3',commandText:'["GET",{"base64":"AP8="}]'},changed:()=>{},account:bytes=>f.accounts.push(bytes)});
      f.workspace.mount(host);
    });
    const editor=page.getByRole('region',{name:'Redis string value editor'}),value=page.getByRole('textbox',{name:'Redis string value',exact:true});
    await page.getByRole('button',{name:'Edit Redis string value',exact:true}).click();await editor.waitFor();
    assert.equal(await page.getByRole('textbox',{name:'Key',exact:true}).inputValue(),'AP8=');
    assert.equal(await page.getByRole('combobox',{name:'Key encoding'}).inputValue(),'base64');
    assert.equal(await page.getByRole('button',{name:'Run native command',exact:true}).isDisabled(),true);
    assert.equal(await page.getByRole('textbox',{name:'Database',exact:true}).isDisabled(),true);
    await page.getByRole('button',{name:'Load string',exact:true}).click();await editor.getByRole('status').filter({hasText:'Loaded 9 bytes'}).waitFor();
    assert.equal(await value.inputValue(),'old value');assert.equal(await value.getAttribute('spellcheck'),'false');
    await value.fill('new value');assert.equal(await page.getByRole('button',{name:'Save string',exact:true}).isEnabled(),true);
    await page.getByRole('button',{name:'Revert string draft'}).click();assert.equal(await value.inputValue(),'old value');
    await value.fill('new value');await page.getByRole('button',{name:'Save string',exact:true}).click();
    const review=page.getByRole('dialog',{name:'Review native database change'});await review.waitFor();
    assert.match(await review.innerText(),/KEEPTTL/);assert.match(await review.innerText(),/database.*3/s);
    assert.equal(await page.evaluate(()=>stringFixture.applies),0);
    await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();
    assert.equal(await value.inputValue(),'new value');assert.equal(await page.evaluate(()=>stringFixture.applies),0);
    await page.getByRole('button',{name:'Save string',exact:true}).click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();
    await editor.getByRole('status').filter({hasText:'Saved · TTL preserved'}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Save string',exact:true}).isDisabled(),true);
    const request=await page.evaluate(()=>stringFixture.commands.at(-1));assert.equal(request.database,'3');assert.equal(request.connectionId,'redis-1');assert.equal(request.expectedTargetRevision,'revision-1');
    assert.deepEqual(request.command,{transaction:[['SET',{base64:'AP8='},{base64:btoa('new value')},'XX','KEEPTTL']],watch:[{key:{base64:'AP8='},expected:{base64:btoa('old value')}}]});
    assert.equal(await page.locator('#string-fixture .native-command').inputValue(),'["GET",{"base64":"AP8="}]','Value saves never rewrite the command draft');
    // Binary and CRLF values never undergo lossy text normalization.
    await page.evaluate(()=>{stringFixture.base64='AP8=';});await page.getByRole('button',{name:'Load string',exact:true}).click();await editor.getByRole('status').filter({hasText:'Loaded 2 bytes'}).waitFor();
    assert.equal(await page.getByRole('combobox',{name:'Value encoding'}).inputValue(),'base64');assert.equal(await value.inputValue(),'AP8=');
    await value.fill('AQA=');await page.evaluate(()=>stringFixture.outcome='conflict');await page.getByRole('button',{name:'Save string',exact:true}).click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();
    await editor.getByRole('status').filter({hasText:'Original value changed'}).waitFor();assert.equal(await value.inputValue(),'AQA=');
    // No uncertain write retry; explicit reload is required.
    await page.evaluate(()=>{stringFixture.outcome='acknowledged';stringFixture.submissionFailure=true;});await page.getByRole('button',{name:'Save string',exact:true}).click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();
    await editor.getByRole('status').filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await page.getByRole('button',{name:'Save string',exact:true}).isDisabled(),true);
    const count=await page.evaluate(()=>stringFixture.applies);await page.waitForTimeout(300);assert.equal(await page.evaluate(()=>stringFixture.applies),count);
    page.once('dialog',d=>d.dismiss());await page.getByRole('button',{name:'Close value editor',exact:true}).click();assert.equal(await editor.isVisible(),true);
    page.once('dialog',d=>d.accept());await page.evaluate(()=>{stringFixture.submissionFailure=false;stringFixture.base64=btoa('a\r\nb');});await page.getByRole('button',{name:'Load string',exact:true}).click();await editor.getByRole('status').filter({hasText:'Loaded 4 bytes'}).waitFor();
    assert.equal(await page.getByRole('combobox',{name:'Value encoding'}).inputValue(),'base64');
    // Oversized/non-string/incomplete reads cannot replace a valid draft.
    await page.evaluate(()=>stringFixture.length=9000);await page.getByRole('button',{name:'Load string',exact:true}).click();await editor.getByRole('status').filter({hasText:'complete string values'}).waitFor();
    assert.equal(await value.inputValue(),btoa('a\r\nb'));
    await page.evaluate(()=>{stringFixture.length=4;stringFixture.type='hash';});await page.getByRole('button',{name:'Load string',exact:true}).click();await editor.getByRole('status').filter({hasText:'Only existing Redis strings'}).waitFor();
    const cases=await page.evaluate(async()=>{
      const {redisBytes,redisStringSnapshot}=await import('/dba/redis-string-editor.js');const rejected=[];
      for(const [text,mode] of [['AR==','base64'],['a===','base64'],['a '.repeat(9000),'text'],['\ud800','text']])try{redisBytes(text,mode);rejected.push(false);}catch{rejected.push(true);}
      const result={kind:'pipeline',entries:['string',2,{base64:'AP8=',truncated:true},-1].map((value,index)=>({index,state:'acknowledged',value}))};try{redisStringSnapshot(result);rejected.push(false);}catch{rejected.push(true);}return rejected;
    });assert.deepEqual(cases,[true,true,true,true,true]);
    // Unmount retains draft. Dispose cancels an in-flight job and releases memory.
    await value.fill('AQI=');await page.evaluate(()=>{stringFixture.workspace.unmount();stringFixture.workspace.mount(document.querySelector('#string-fixture'));});assert.equal(await value.inputValue(),'AQI=');
    await page.screenshot({path:'code-graph-dba/target/redis-string-editor.png'});
    page.once('dialog',d=>d.accept());await page.evaluate(()=>{stringFixture.type='string';stringFixture.phase='running';});await page.getByRole('button',{name:'Load string',exact:true}).click();
    await page.waitForFunction(()=>stringFixture.workspace.operation?.id==='key-job');await page.evaluate(()=>{stringFixture.workspace.dispose();stringFixture.phase='complete';});await page.waitForFunction(()=>!stringFixture.workspace.operation);
    assert.equal(await page.evaluate(()=>stringFixture.cancelled),true);assert.equal(await page.evaluate(()=>stringFixture.accounts.at(-1)),0);
    assert.equal(await page.locator('#string-fixture .redis-string-editor').count(),0);
    await page.evaluate(async()=>{
      const {RedisStringEditor}=await import('/dba/redis-string-editor.js');
      const f=stringFixture;f.readOnly=true;f.directCalls=0;
      f.direct=new RedisStringEditor({run:async()=>{f.directCalls++;return{ok:true,targetRevision:'r1',result:{kind:'pipeline',entries:['string',0,{base64:'',truncated:false},-1].map((value,index)=>({index,state:'acknowledged',value}))}};},readOnly:()=>f.readOnly,changed:()=>{},close:()=>{},key:'empty'});
      document.querySelector('#string-fixture').append(f.direct.root);await f.direct.load();
    });
    assert.equal(await value.inputValue(),'');assert.equal(await value.getAttribute('readonly'),'');
    assert.equal(await page.getByRole('button',{name:'Save string',exact:true}).isDisabled(),true);
    await page.evaluate(async()=>{stringFixture.direct.value.value='blocked';await stringFixture.direct.save();});assert.equal(await page.evaluate(()=>stringFixture.directCalls),1);
    await page.setViewportSize({width:480,height:760});
    assert.equal(await editor.evaluate(n=>n.scrollWidth<=n.clientWidth+1),true,'Narrow editor has no horizontal control clipping');
    await page.evaluate(()=>{stringFixture.readOnly=false;stringFixture.direct.invalidate('Connection removed');});assert.equal(await page.getByRole('button',{name:'Save string',exact:true}).isDisabled(),true);
    await page.evaluate(()=>stringFixture.direct.dispose());assert.deepEqual(errors,[]);
    console.log('Redis string editor checks passed: bounded binary drafts, exact review/revision, TTL command, conflicts, unknown outcomes, cancellation, read-only profiles and narrow layout.');
  }finally{await context.close();}
};
