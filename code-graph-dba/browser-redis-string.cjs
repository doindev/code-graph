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
          const values=f.command.pipeline?.length===1?[f.type??'string']:[f.type??'string',f.length??atob(f.base64).length,{base64:f.base64,truncated:!!f.truncated},f.ttl];
          const result=f.command.pipeline?{kind:'pipeline',entries:values.map((value,index)=>({index,state:'acknowledged',value}))}:{kind:'transaction',outcome:f.outcome,entries:[{index:0,state:'acknowledged',value:f.command.transaction?.[0]?.[0]==='RENAMENX'?(f.renameReceipt??true):f.command.transaction?.[0]?.[0]==='DEL'?1:'OK'}]};
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
    // Expiry-only drafts use one reviewed SET; no implicit write, separate EXPIRE, or stale TTL reset.
    const expiry=page.getByRole('combobox',{name:'Expiry',exact:true}),seconds=page.getByRole('textbox',{name:'Expiry seconds',exact:true}),expirySave=page.getByRole('button',{name:'Save string',exact:true});
    assert.equal(await expiry.inputValue(),'preserve');assert.equal(await seconds.isVisible(),false);
    const beforeExpiry=await page.evaluate(()=>stringFixture.applies);
    await expiry.selectOption('duration');assert.equal(await expirySave.isDisabled(),true);
    for(const invalid of ['','0','-1','1.5','1e3','2147483648','abc']){
      await seconds.fill(invalid);assert.equal(await seconds.getAttribute('aria-invalid'),'true');assert.equal(await expirySave.isDisabled(),true);
    }
    assert.equal(await page.evaluate(()=>stringFixture.applies),beforeExpiry);
    await seconds.fill('120');assert.equal(await expirySave.isEnabled(),true);assert.equal(await value.inputValue(),'new value');
    await page.evaluate(()=>{stringFixture.workspace.unmount();stringFixture.workspace.mount(document.querySelector('#string-fixture'));});
    assert.equal(await seconds.inputValue(),'120');await expirySave.click();await review.waitFor();
    assert.deepEqual((await page.evaluate(()=>stringFixture.commands.at(-1))).command,{transaction:[['SET',{base64:'AP8='},{base64:btoa('new value')},'XX','EX','120']],watch:[{key:{base64:'AP8='},expected:{base64:btoa('new value')}}]});
    await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();
    assert.equal(await page.evaluate(()=>stringFixture.applies),beforeExpiry);assert.equal(await seconds.inputValue(),'120');
    await page.getByRole('button',{name:'Revert string draft'}).click();assert.equal(await expiry.inputValue(),'preserve');assert.equal(await expirySave.isDisabled(),true);
    await expiry.selectOption('duration');await seconds.fill('120');await expirySave.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();
    await editor.getByRole('status').filter({hasText:'Saved string with expiry 120 seconds'}).waitFor();assert.equal(await expiry.isDisabled(),true);assert.equal(await expirySave.isDisabled(),true);
    await page.evaluate(()=>{stringFixture.base64=btoa('new value');});await page.getByRole('button',{name:'Load string',exact:true}).click();await editor.getByRole('status').filter({hasText:'Loaded 9 bytes'}).waitFor();
    await expiry.selectOption('none');await expirySave.click();await review.waitFor();
    assert.deepEqual((await page.evaluate(()=>stringFixture.commands.at(-1))).command.transaction,[['SET',{base64:'AP8='},{base64:btoa('new value')},'XX']]);
    await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Saved string with no expiry'}).waitFor();
    // Creation is explicit, absence-checked, dirty even for an empty value, and never sent by preparation.
    const newButton=page.getByRole('button',{name:'Prepare new string',exact:true}),save=page.getByRole('button',{name:'Save string',exact:true}),deletion=page.getByRole('button',{name:'Mark string key for deletion',exact:true});
    await newButton.click();await editor.getByRole('status').filter({hasText:'Key already exists'}).waitFor();
    await page.evaluate(()=>{stringFixture.type='none';});const beforeCreate=await page.evaluate(()=>stringFixture.applies);
    await newButton.click();await editor.getByRole('status').filter({hasText:'New string draft'}).waitFor();
    assert.equal(await expiry.inputValue(),'none');assert.equal(await expiry.locator('option[value="preserve"]').isDisabled(),true);
    assert.equal(await value.inputValue(),'');assert.equal(await save.isEnabled(),true);assert.equal(await deletion.isDisabled(),true);assert.equal(await page.evaluate(()=>stringFixture.applies),beforeCreate);
    assert.deepEqual((await page.evaluate(()=>stringFixture.commands.at(-1))).command,{pipeline:[['TYPE',{base64:'AP8='}]]});
    await save.click();await review.waitFor();assert.match(await review.innerText(),/NX/);await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();
    await page.getByRole('button',{name:'Revert string draft'}).click();await editor.getByRole('status').filter({hasText:'New-string draft discarded'}).waitFor();assert.equal(await save.isDisabled(),true);
    await newButton.click();await editor.getByRole('status').filter({hasText:'New string draft'}).waitFor();
    await page.evaluate(()=>{stringFixture.workspace.unmount();stringFixture.workspace.mount(document.querySelector('#string-fixture'));});assert.equal(await save.isEnabled(),true);
    await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Created string with no expiry'}).waitFor();
    assert.deepEqual((await page.evaluate(()=>stringFixture.commands.at(-1))).command,{transaction:[['SET',{base64:'AP8='},{base64:''},'NX']],watch:[{key:{base64:'AP8='},expected:null}]});
    assert.equal(await value.getAttribute('readonly'),'');assert.equal(await save.isDisabled(),true);
    // Delete stages the loaded exact bytes. Revert and review Cancel issue no DEL.
    await page.evaluate(()=>{stringFixture.type='string';stringFixture.base64='';});await page.getByRole('button',{name:'Load string',exact:true}).click();await editor.getByRole('status').filter({hasText:'Loaded 0 bytes'}).waitFor();
    await deletion.click();assert.equal(await deletion.getAttribute('aria-pressed'),'true');assert.equal(await value.getAttribute('readonly'),'');
    await page.getByRole('button',{name:'Revert string draft'}).click();assert.equal(await deletion.getAttribute('aria-pressed'),'false');
    await deletion.click();await save.click();await review.waitFor();assert.match(await review.innerText(),/DEL/);await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();
    assert.equal(await deletion.getAttribute('aria-pressed'),'true');await page.evaluate(()=>{stringFixture.workspace.unmount();stringFixture.workspace.mount(document.querySelector('#string-fixture'));});assert.equal(await deletion.getAttribute('aria-pressed'),'true');
    await page.evaluate(()=>stringFixture.outcome='conflict');await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Original value changed'}).waitFor();assert.equal(await deletion.getAttribute('aria-pressed'),'true');
    await page.evaluate(()=>stringFixture.outcome='acknowledged');await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Deleted string key and its TTL'}).waitFor();
    assert.deepEqual((await page.evaluate(()=>stringFixture.commands.at(-1))).command,{transaction:[['DEL',{base64:'AP8='}]],watch:[{key:{base64:'AP8='},expected:{base64:''}}]});assert.equal(await deletion.isDisabled(),true);
    await page.evaluate(()=>{stringFixture.type='none';});await newButton.click();await editor.getByRole('status').filter({hasText:'New string draft'}).waitFor();
    await expiry.selectOption('duration');await seconds.fill('300');await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();
    await editor.getByRole('status').filter({hasText:'Created string with expiry 300 seconds'}).waitFor();
    assert.deepEqual((await page.evaluate(()=>stringFixture.commands.at(-1))).command,{transaction:[['SET',{base64:'AP8='},{base64:''},'NX','EX','300']],watch:[{key:{base64:'AP8='},expected:null}]});
    await page.evaluate(()=>{stringFixture.type='none';stringFixture.submissionFailure=true;});await newButton.click();await editor.getByRole('status').filter({hasText:'New string draft'}).waitFor();
    await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await newButton.isDisabled(),true);assert.equal(await save.isDisabled(),true);
    assert.equal(await expiry.isDisabled(),true);
    await page.evaluate(()=>{stringFixture.submissionFailure=false;stringFixture.type='string';});page.once('dialog',d=>d.accept());
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
      const {redisBytes,redisStringSnapshot,redisNewStringSnapshot}=await import('/dba/redis-string-editor.js');const rejected=[];
      for(const [text,mode] of [['AR==','base64'],['a===','base64'],['a '.repeat(9000),'text'],['\ud800','text']])try{redisBytes(text,mode);rejected.push(false);}catch{rejected.push(true);}
      const result={kind:'pipeline',entries:['string',2,{base64:'AP8=',truncated:true},-1].map((value,index)=>({index,state:'acknowledged',value}))};try{redisStringSnapshot(result);rejected.push(false);}catch{rejected.push(true);}
      for(const input of [{kind:'pipeline',entries:[]},{kind:'pipeline',entries:[{index:0,state:'acknowledged',value:'hash'}]},{kind:'pipeline',truncated:true,entries:[{index:0,state:'acknowledged',value:'none'}]},{kind:'pipeline',entries:[{index:0,state:'acknowledged',value:'none',valueOmitted:true}]}])try{redisNewStringSnapshot(input);rejected.push(false);}catch{rejected.push(true);}return rejected;
    });assert.deepEqual(cases,Array(9).fill(true));
    const expiryCases=await page.evaluate(async()=>{
      const {redisStringExpiry}=await import('/dba/redis-string-editor.js');
      const rejected=[];for(const args of [['preserve','',true],['unknown',''],['duration',''],['duration','0'],['duration','-1'],['duration','1.1'],['duration','1e3'],['duration','2147483648'],['duration','١'],['duration',' 1'],['duration',1]]){
        try{redisStringExpiry(...args);rejected.push(false);}catch{rejected.push(true);}
      }
      return{rejected,valid:[redisStringExpiry('preserve',''),redisStringExpiry('none','',true),redisStringExpiry('duration','0001'),redisStringExpiry('duration','2147483647')]};
    });assert.deepEqual(expiryCases,{rejected:Array(11).fill(true),valid:[['KEEPTTL'],[],['EX','1'],['EX','2147483647']]});
    // Unmount retains draft. Dispose cancels an in-flight job and releases memory.
    await value.fill('AQI=');await page.evaluate(()=>{stringFixture.workspace.unmount();stringFixture.workspace.mount(document.querySelector('#string-fixture'));});assert.equal(await value.inputValue(),'AQI=');
    const rename=page.getByRole('button',{name:'Rename string key',exact:true}),newKey=page.getByRole('textbox',{name:'New key name',exact:true}),newMode=page.getByRole('combobox',{name:'New key encoding',exact:true});
    page.once('dialog',d=>d.dismiss());await rename.click();assert.equal(await newKey.isVisible(),false);assert.equal(await value.inputValue(),'AQI=');
    page.once('dialog',d=>d.accept());await rename.click();assert.equal(await value.inputValue(),btoa('a\r\nb'));assert.equal(await save.isDisabled(),true);assert.equal(await newKey.evaluate(n=>document.activeElement===n),true);
    assert.equal(await expiry.isDisabled(),true);assert.equal(await deletion.isDisabled(),true);assert.equal(await value.getAttribute('readonly'),'');
    await newMode.selectOption('base64');await newKey.fill('AP8=');assert.equal(await newKey.getAttribute('aria-invalid'),'true');
    await newKey.fill('!');assert.equal(await save.isDisabled(),true);await newKey.fill('AQE=');assert.equal(await save.isEnabled(),true);
    const beforeRename=await page.evaluate(()=>stringFixture.applies);
    await save.click();await review.waitFor();assert.match(await review.innerText(),/RENAMENX/);
    assert.deepEqual((await page.evaluate(()=>stringFixture.commands.at(-1))).command,{transaction:[['RENAMENX',{base64:'AP8='},{base64:'AQE='}]],watch:[{key:{base64:'AP8='},expected:{base64:btoa('a\r\nb')}},{key:{base64:'AQE='},expected:null}]});
    await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();assert.equal(await page.evaluate(()=>stringFixture.applies),beforeRename);
    await page.evaluate(()=>{stringFixture.workspace.unmount();stringFixture.workspace.mount(document.querySelector('#string-fixture'));});assert.equal(await newKey.inputValue(),'AQE=');
    await page.getByRole('button',{name:'Revert string draft'}).click();assert.equal(await newKey.isVisible(),false);assert.equal(await save.isDisabled(),true);
    await rename.click();await newMode.selectOption('base64');await newKey.fill('AQE=');
    await page.evaluate(()=>{stringFixture.outcome='conflict';});await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Original value changed'}).waitFor();assert.equal(await newKey.inputValue(),'AQE=');
    await page.evaluate(()=>{stringFixture.outcome='acknowledged';stringFixture.renameReceipt=false;});await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Rename was not applied'}).waitFor();
    assert.equal(await page.getByRole('textbox',{name:'Key',exact:true}).inputValue(),'AP8=');
    await page.evaluate(()=>{delete stringFixture.renameReceipt;});await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Renamed string key'}).waitFor();
    assert.equal(await page.getByRole('textbox',{name:'Key',exact:true}).inputValue(),'AQE=');assert.equal(await newKey.isVisible(),false);assert.equal(await rename.isDisabled(),true);assert.equal(await save.isDisabled(),true);
    assert.equal(await page.locator('#string-fixture .native-command').inputValue(),'["GET",{"base64":"AP8="}]');
    await page.evaluate(()=>{stringFixture.type='string';});await page.getByRole('button',{name:'Load string',exact:true}).click();await editor.getByRole('status').filter({hasText:'Loaded 4 bytes'}).waitFor();
    await rename.click();await newKey.fill('another name');await page.evaluate(()=>{stringFixture.renameReceipt=1;});await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'reconcile both source and destination keys'}).waitFor();
    assert.equal(await save.isDisabled(),true);assert.equal(await newKey.isDisabled(),true);assert.equal(await rename.isDisabled(),true);
    page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'Load string',exact:true}).click();await editor.getByRole('status').filter({hasText:'Loaded 4 bytes'}).waitFor();assert.equal(await newKey.isVisible(),false);
    await rename.click();await newKey.fill('recovered draft');await page.setViewportSize({width:480,height:760});assert.equal(await editor.evaluate(n=>n.scrollWidth<=n.clientWidth+1),true);await page.setViewportSize({width:1280,height:900});
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
    assert.equal(await newButton.isDisabled(),true);assert.equal(await deletion.isDisabled(),true);
    assert.equal(await rename.isDisabled(),true);await page.evaluate(()=>stringFixture.direct.stageRename());assert.equal(await newKey.isVisible(),false);
    assert.equal(await expiry.isDisabled(),true);
    await page.evaluate(async()=>{stringFixture.direct.toggleDelete();await stringFixture.direct.load(true);});assert.equal(await page.evaluate(()=>stringFixture.directCalls),1);
    await page.evaluate(async()=>{stringFixture.direct.value.value='blocked';await stringFixture.direct.save();});assert.equal(await page.evaluate(()=>stringFixture.directCalls),1);
    await page.setViewportSize({width:480,height:760});
    assert.equal(await editor.evaluate(n=>n.scrollWidth<=n.clientWidth+1),true,'Narrow editor has no horizontal control clipping');
    await page.evaluate(()=>{stringFixture.readOnly=false;stringFixture.direct.invalidate('Connection removed');});assert.equal(await page.getByRole('button',{name:'Save string',exact:true}).isDisabled(),true);
    await page.evaluate(()=>stringFixture.direct.dispose());assert.deepEqual(errors,[]);
    console.log('Redis string editor passed: staged rename/expiry/create/delete, exact two-key guards and typed rename receipts, binary/empty values, duration limits, review/revision, Revert, conflicts, uncertainty, cancellation, read-only profiles, remount and narrow layout.');
  }finally{await context.close();}
};
