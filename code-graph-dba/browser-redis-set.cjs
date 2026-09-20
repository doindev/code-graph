const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(base+'/dba');
    await page.evaluate(async()=>{
      const {NativeWorkspace}=await import('/dba/native-workspace.js');
      const host=document.createElement('div');host.id='set-fixture';host.style='position:fixed;inset:0;z-index:100;background:#101722';document.body.append(host);
      const f=window.setFixture={commands:[],applies:0,accounts:[],present:false,phase:'complete'};
      const api=async(path,method,input)=>{
        if(path==='/native/prepare'){f.command=input.command;f.commands.push(structuredClone(input));return{id:'set-review',targetRevision:'set-r1',mutation:!!input.command.transaction,destructive:input.command.transaction?.[0]?.[0]==='SREM',expiresAt:Date.now()+60000,target:{connectionName:'Set fixture',database:input.database},after:{nativeCommand:input.command},transactionNotice:'Exact membership on an existing set; deleting the last member removes the key and TTL.'};}
        if(path==='/native/apply'){f.applies++;if(f.lostReply)throw Error('Lost reply');return{id:'set-job'};}
        if(path==='/native/execute')return{id:'set-job'};
        if(method==='DELETE'){f.releases=(f.releases??0)+1;return{};}
        if(path.endsWith('/cancel')){f.cancelled=true;f.phase='complete';return{};}
        if(path==='/jobs/set-job'){
          if(f.phase==='running')return{id:'set-job',state:'running'};
          const write=!!f.command.transaction;
          const result=write?{kind:'transaction',outcome:f.error?'conflict':'acknowledged',entries:f.error?[]:[{index:0,state:'acknowledged',value:f.receipt??1}]}:
            {kind:'pipeline',entries:[f.type??'set',f.present,f.count??2,f.ttl??60000].map((value,index)=>({index,state:'acknowledged',value}))};
          return{id:'set-job',state:write&&f.error?'failed':'complete',finished:Date.now(),result,error:f.error};
        }throw Error('Unexpected API '+path);
      };
      f.workspace=new NativeWorkspace({api,profile:{id:'set-1',name:'Set fixture',transport:'redis',readOnly:false},state:{database:'3',commandText:'["SISMEMBER",{"base64":"a2V5"},{"base64":"AP8="}]'},changed:()=>{},account:bytes=>f.accounts.push(bytes)});f.workspace.mount(host);
    });
    await page.getByRole('button',{name:'Edit Redis set member',exact:true}).click();
    const editor=page.getByRole('region',{name:'Redis set member editor'}),value=editor.getByRole('textbox',{name:'Redis set member',exact:true});
    const check=editor.getByRole('button',{name:'Check set membership'}),save=editor.getByRole('button',{name:'Save set member'}),revert=editor.getByRole('button',{name:'Revert membership draft'}),reset=editor.getByRole('button',{name:'Choose another member'});
    const review=page.getByRole('dialog',{name:'Review native database change'});
    assert.equal(await value.inputValue(),'AP8=');assert.equal(await editor.getByRole('combobox',{name:'Member encoding'}).inputValue(),'base64');
    assert.equal(await page.getByRole('button',{name:'Edit Redis list item'}).isDisabled(),true);assert.equal(await page.getByRole('textbox',{name:'Database',exact:true}).isDisabled(),true);
    await value.fill('!');assert.equal(await check.isDisabled(),true);await value.fill('AP8=');
    await check.click();await editor.getByRole('status').filter({hasText:'Member absent'}).waitFor();assert.equal(await save.isDisabled(),true);assert.equal(await value.getAttribute('readonly'),'');
    assert.deepEqual((await page.evaluate(()=>setFixture.commands.at(-1))).command,{pipeline:[['TYPE',{base64:'a2V5'}],['SISMEMBER',{base64:'a2V5'},{base64:'AP8='}],['SCARD',{base64:'a2V5'}],['PTTL',{base64:'a2V5'}]]});
    const add=editor.getByRole('button',{name:'Stage member insertion'});
    await add.focus();await page.keyboard.press('Space');assert.equal(await add.getAttribute('aria-pressed'),'true');assert.equal(await page.evaluate(()=>setFixture.applies),0);
    await revert.click();assert.equal(await save.isDisabled(),true);await add.click();
    page.once('dialog',d=>d.dismiss());await editor.getByRole('button',{name:'Close member editor'}).click();assert.equal(await editor.isVisible(),true);
    await page.evaluate(()=>{setFixture.workspace.unmount();setFixture.workspace.mount(document.querySelector('#set-fixture'));});assert.equal(await add.getAttribute('aria-pressed'),'true');
    await save.click();await review.waitFor();await review.getByRole('button',{name:'Cancel',exact:true}).click();await editor.getByRole('status').filter({hasText:'Cancelled before execution'}).waitFor();assert.equal(await page.evaluate(()=>setFixture.applies),0);
    await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Member added'}).waitFor();
    const added=await page.evaluate(()=>setFixture.commands.at(-1));assert.equal(added.database,'3');assert.equal(added.expectedTargetRevision,'set-r1');assert.deepEqual(added.command,{transaction:[['SADD',{base64:'a2V5'},{base64:'AP8='}]],watch:[{key:{base64:'a2V5'},member:{base64:'AP8='},expected:false}]});
    const del=editor.getByRole('button',{name:'Mark member for deletion'});
    await del.click();assert.equal(await editor.evaluate(e=>e.classList.contains('redis-pending-delete')),true);
    await page.evaluate(()=>setFixture.error='Redis transaction conflict; no commands executed');await save.click();await review.waitFor();assert.match(await review.innerText(),/last member/);await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'transaction conflict'}).waitFor();assert.equal(await del.getAttribute('aria-pressed'),'true');
    await page.evaluate(()=>{setFixture.error=null;setFixture.receipt=0;});await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await save.isDisabled(),true);assert.equal(await revert.isDisabled(),true);assert.equal(await reset.isDisabled(),true);
    await page.evaluate(()=>{setFixture.present=true;setFixture.receipt=1;});page.once('dialog',d=>d.accept());await check.click();await editor.getByRole('status').filter({hasText:'Member present'}).waitFor();await del.click();
    await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await editor.getByRole('status').filter({hasText:'Member deleted'}).waitFor();
    assert.deepEqual((await page.evaluate(()=>setFixture.commands.at(-1))).command,{transaction:[['SREM',{base64:'a2V5'},{base64:'AP8='}]],watch:[{key:{base64:'a2V5'},member:{base64:'AP8='},expected:true}]});
    assert.equal(await save.isDisabled(),true);assert.equal(await value.getAttribute('readonly'),null);
    await page.evaluate(()=>{setFixture.type='none';setFixture.present=false;});await check.click();await editor.getByRole('status').filter({hasText:'existing set'}).waitFor();assert.equal(await add.isDisabled(),true);
    await page.evaluate(()=>setFixture.type='set');await value.fill('');await check.click();await editor.getByRole('status').filter({hasText:'Member absent'}).waitFor();await add.click();
    assert.equal(await save.isDisabled(),false,'Empty member is a valid insert, not absence');
    page.once('dialog',d=>d.dismiss());await reset.click();assert.equal(await save.isDisabled(),false);await revert.click();await reset.click();assert.equal(await value.getAttribute('readonly'),null);
    const malformed=await page.evaluate(async()=>{
      const {redisSetSnapshot}=await import('/dba/redis-set-editor.js');
      const result=(type,present,count,ttl)=>({kind:'pipeline',entries:[type,present,count,ttl].map((value,index)=>({index,state:'acknowledged',value}))});
      return [{},{kind:'pipeline',entries:[]},result('string',false,1,-1),result('set','false',1,-1),result('set',false,0,-1),result('set',true,1,-2),{...result('set',false,1,-1),truncated:true}].map(r=>{try{redisSetSnapshot(r);return false;}catch{return true;}});
    });assert.equal(malformed.every(Boolean),true);
    await check.click();await editor.getByRole('status').filter({hasText:'Member absent'}).waitFor();await add.click();
    await page.setViewportSize({width:420,height:600});await save.scrollIntoViewIfNeeded();assert.equal(await save.evaluate(e=>{const r=e.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;}),true);await page.screenshot({path:'code-graph-dba/target/redis-set-editor.png'});
    const before=await page.evaluate(()=>setFixture.applies);await page.evaluate(async()=>{setFixture.workspace.profile.readOnly=true;setFixture.workspace.stringEditor.sync();await setFixture.workspace.stringEditor.save();});assert.equal(await save.isDisabled(),true);assert.equal(await add.isDisabled(),true);assert.equal(await page.evaluate(()=>setFixture.applies),before);
    await page.evaluate(()=>setFixture.workspace.invalidate('Connection removed. Draft retained.'));assert.equal(await check.isDisabled(),true);
    await page.evaluate(()=>setFixture.workspace.dispose());assert.equal(await editor.count(),0);assert.equal(await page.evaluate(()=>setFixture.accounts.at(-1)),0);
    assert.deepEqual(errors,[]);console.log('Redis set editor passed: typed membership, staged add/delete, exact review, empty/binary members, conflicts, uncertain receipts, lifecycle, read-only protection and narrow layout.');
  }finally{await context.close();}
};
