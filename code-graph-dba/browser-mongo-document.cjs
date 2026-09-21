const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(base+'/dba');
    assert.deepEqual(await page.evaluate(async()=>{
      const {mongoDocumentJson}=await import('/dba/mongo-document-editor.js');
      const cases=[{_id:{$numberLong:'9223372036854775807'}},{_id:{$numberLong:'9223372036854775808'}},{_id:{$numberInt:'-2147483648'}},{_id:{$numberInt:'2147483648'}},{_id:9007199254740992},{_id:{}},{_id:'',nested:[{name:'one'},{name:'two'}]}];
      return cases.map(value=>{try{mongoDocumentJson(JSON.stringify(value));return true;}catch{return false;}});
    }),[true,false,true,false,false,false,true]);
    const compact=await page.evaluate(async()=>{
      const {NativeWorkspace}=await import('/dba/native-workspace.js');let allocation=0;
      const workspace=new NativeWorkspace({api:async()=>{},profile:{transport:'mongodb'},state:{},changed:()=>{},account:bytes=>{if(bytes>700000)throw Error('Full');allocation=bytes;}});
      workspace.showResult({kind:'transaction',atomic:true,documentGuard:true,outcome:'commit_acknowledged',entries:[{index:0,operation:'update',matchedOrInserted:1,modified:1,state:'committed',value:'x'.repeat(300000)}]});
      const result=JSON.parse(workspace.result.textContent);workspace.dispose();return{result,allocation};
    });
    assert.equal(compact.result.documentGuard,true);assert.equal(compact.result.outcome,'commit_acknowledged');assert.equal(compact.result.entries[0].matchedOrInserted,1);assert.equal(compact.allocation,0);
    await page.evaluate(async()=>{
      const {NativeWorkspace}=await import('/dba/native-workspace.js');
      const host=document.createElement('div');host.id='mongo-editor-fixture';host.style='position:fixed;inset:0;z-index:100;background:#101722';document.body.append(host);
      const f=window.mongoEditorFixture={requests:[],accounts:[],applies:0,phase:'complete',
        original:{_id:{$oid:'0123456789abcdef01234567'},large:{$numberLong:'9007199254740993'},price:{$numberDecimal:'1.2300'},binary:{$binary:{base64:'AP8=',subType:'00'}},nested:[null,{name:'original'}]}};
      const api=async(path,method,input)=>{
        if(path==='/native/prepare'){
          f.command=input.command;f.requests.push(structuredClone(input));
          return{id:'document-review',targetRevision:'document-r1',mutation:!!input.command.transaction,destructive:!!input.command.transaction?.[0]?.delete,expiresAt:Date.now()+60000,target:{connectionName:'Document fixture',database:input.database,collection:input.collection},after:{nativeCommand:input.command},transactionNotice:'Byte-exact BSON and collection UUID guard; complete replacement or deletion, no automatic retries.'};
        }
        if(path==='/native/apply'){f.applies++;if(f.lostReply)throw Error('Lost commit reply');return{id:'document-job'};}
        if(path==='/native/execute')return{id:'document-job'};
        if(path.endsWith('/cancel')){f.cancelled=true;return{};}
        if(method==='DELETE')return{};
        if(path==='/jobs/document-job'){
          if(f.phase==='running')return{id:'document-job',state:'running'};
          let result;
          if(f.command.transaction)result={kind:'transaction',atomic:true,documentGuard:!f.badReceipt,outcome:f.fail?'rollback_acknowledged':'commit_acknowledged',entries:[{index:0,operation:f.command.transaction[0].delete?'delete':'update',state:f.fail?'rolled_back':'committed',matchedOrInserted:1,modified:1}]};
          else if(f.command.listCollections)result={kind:'documents',entries:[{name:'items',type:f.view?'view':'collection',options:{},info:{uuid:{$binary:{base64:'AAAAAAAAAAAAAAAAAAAAAA==',subType:'04'}}}}]};
          else result={kind:'documents',truncated:!!f.truncated,entries:f.missing?[]:[structuredClone(f.original)]};
          return{id:'document-job',state:f.command.transaction&&f.fail?'failed':'complete',finished:Date.now(),error:f.fail?'Document changed concurrently':undefined,result};
        }
        throw Error('Unexpected API '+path);
      };
      f.workspace=new NativeWorkspace({api,profile:{id:'document-1',name:'Document fixture',transport:'mongodb',readOnly:false,nativeOptions:{topology:'replica_set'}},state:{database:'documents',collection:'items',commandText:'{"find":"items","filter":{"_id":{"$oid":"0123456789abcdef01234567"}},"limit":1}'},changed:()=>{},account:n=>f.accounts.push(n)});
      f.workspace.mount(host);
    });
    const open=page.getByRole('button',{name:'Edit MongoDB document',exact:true});
    await open.click();const editor=page.getByRole('region',{name:'MongoDB document editor'}),status=editor.getByRole('status'),value=editor.getByRole('textbox',{name:'Complete document as canonical Extended JSON'});
    const load=editor.getByRole('button',{name:'Load document',exact:true}),save=editor.getByRole('button',{name:'Save document',exact:true}),revert=editor.getByRole('button',{name:'Revert document draft'}),close=editor.getByRole('button',{name:'Close document editor'});
    assert.equal(await page.evaluate(()=>mongoEditorFixture.requests.length),0);assert.equal(await save.isDisabled(),true);assert.equal(await value.getAttribute('readonly'),'');
    assert.equal(await page.evaluate(()=>mongoEditorFixture.accounts.at(-1)),512*1024);
    assert.equal(await page.getByRole('textbox',{name:'Database',exact:true}).isDisabled(),true);assert.equal(await page.getByRole('textbox',{name:'Collection',exact:true}).isDisabled(),true);
    await load.click();await status.filter({hasText:'Loaded complete document'}).waitFor();
    const original=await value.inputValue();assert.match(original,/9007199254740993/);assert.match(original,/1.2300/);assert.equal(await save.isDisabled(),true);
    const changed=JSON.parse(original);changed.name='edited';await value.fill(JSON.stringify(changed,null,2));assert.equal(await save.isDisabled(),false);
    await page.evaluate(()=>{const w=mongoEditorFixture.workspace;w.unmount();w.mount(document.querySelector('#mongo-editor-fixture'));});
    assert.equal(JSON.parse(await value.inputValue()).name,'edited');
    page.once('dialog',d=>d.dismiss());await close.click();assert.equal(await editor.isVisible(),true);
    page.once('dialog',d=>d.dismiss());await load.click();assert.equal(JSON.parse(await value.inputValue()).name,'edited');
    await save.click();const review=page.getByRole('dialog',{name:'Review native database change'});await review.waitFor();assert.match(await review.innerText(),/documentGuard/);await review.getByRole('button',{name:'Cancel',exact:true}).click();await status.filter({hasText:'Cancelled before execution'}).waitFor();assert.equal(await page.evaluate(()=>mongoEditorFixture.applies),0);
    await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'Document saved'}).waitFor();
    const submitted=await page.evaluate(()=>mongoEditorFixture.requests.at(-1));assert.equal(submitted.database,'documents');assert.equal(submitted.collection,'items');assert.equal(submitted.expectedTargetRevision,'document-r1');
    assert.deepEqual(submitted.command.documentGuard.expected,JSON.parse(original));assert.deepEqual(submitted.command.transaction[0].updates[0].u,changed);assert.equal(await save.isDisabled(),true);assert.equal(await page.evaluate(()=>mongoEditorFixture.workspace.dirty),false);
    await load.click();await status.filter({hasText:'Loaded complete document'}).waitFor();
    const wrongId={...changed,_id:'different'};await value.fill(JSON.stringify(wrongId));await save.click();assert.match(await status.innerText(),/_id is immutable/);
    await value.fill('{"bad":');await save.click();assert.match(await status.innerText(),/JSON|Expected/);
    await value.fill('{"_id":"a","nested":{"same":"first","same":"second"}}');await save.click();assert.match(await status.innerText(),/Duplicate JSON field/);
    await revert.click();assert.equal(await value.inputValue(),original);
    const remove=editor.getByRole('button',{name:'Delete document',exact:true});
    const beforeDelete=await page.evaluate(()=>mongoEditorFixture.requests.length);
    await remove.click();assert.equal(await page.evaluate(()=>mongoEditorFixture.requests.length),beforeDelete);assert.equal(await value.getAttribute('readonly'),'');assert.equal(await remove.getAttribute('aria-pressed'),'true');assert.equal(await save.isDisabled(),false);
    await page.evaluate(()=>{const w=mongoEditorFixture.workspace;w.unmount();w.mount(document.querySelector('#mongo-editor-fixture'));});
    assert.equal(await remove.getAttribute('aria-pressed'),'true');page.once('dialog',d=>d.dismiss());await close.click();assert.equal(await editor.isVisible(),true);
    await revert.click();assert.equal(await remove.getAttribute('aria-pressed'),'false');assert.equal(await save.isDisabled(),true);assert.equal(await value.inputValue(),original);
    await remove.click();await save.click();await review.waitFor();assert.match(await review.innerText(),/delete/);await review.getByRole('button',{name:'Cancel',exact:true}).click();await status.filter({hasText:'Cancelled before execution'}).waitFor();assert.equal(await remove.getAttribute('aria-pressed'),'true');
    await page.evaluate(()=>mongoEditorFixture.fail=true);await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'Document changed concurrently'}).waitFor();assert.equal(await save.isDisabled(),false);assert.equal(await remove.getAttribute('aria-pressed'),'true');
    await page.evaluate(()=>{mongoEditorFixture.fail=false;mongoEditorFixture.badReceipt=true;});await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await save.isDisabled(),true);assert.equal(await remove.isDisabled(),true);assert.equal(await load.isDisabled(),true);
    page.once('dialog',d=>d.accept());await editor.getByRole('button',{name:'Discard reconciled document draft'}).click();await page.evaluate(()=>mongoEditorFixture.badReceipt=false);await load.click();await status.filter({hasText:'Loaded complete document'}).waitFor();
    await remove.click();await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'Document deleted; transaction commit confirmed'}).waitFor();
    const deleted=await page.evaluate(()=>mongoEditorFixture.requests.at(-1));assert.deepEqual(deleted.command.transaction,[{delete:'items',deletes:[{q:{_id:JSON.parse(original)._id},limit:1}]}]);assert.deepEqual(deleted.command.documentGuard.expected,JSON.parse(original));assert.equal(await value.inputValue(),'');assert.equal(await save.isDisabled(),true);assert.equal(await remove.isDisabled(),true);assert.equal(await page.evaluate(()=>mongoEditorFixture.workspace.dirty),false);
    await load.click();await status.filter({hasText:'Loaded complete document'}).waitFor();
    await value.fill(JSON.stringify(changed));await page.evaluate(()=>mongoEditorFixture.fail=true);await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'Document changed concurrently'}).waitFor();assert.equal(await save.isDisabled(),false);assert.equal(JSON.parse(await value.inputValue()).name,'edited');
    await page.evaluate(()=>{mongoEditorFixture.fail=false;mongoEditorFixture.badReceipt=true;});await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'outcome uncertain'}).waitFor();assert.equal(await load.isDisabled(),true);assert.equal(await revert.isDisabled(),true);
    const reconcile=editor.getByRole('button',{name:'Discard reconciled document draft'});
    page.once('dialog',d=>d.dismiss());await reconcile.click();assert.equal(await save.isDisabled(),true);
    page.once('dialog',d=>d.accept());await reconcile.click();assert.equal(await load.isDisabled(),false);
    await page.evaluate(()=>{mongoEditorFixture.badReceipt=false;mongoEditorFixture.missing=true;});await load.click();await status.filter({hasText:'Document not found'}).waitFor();assert.equal(await save.isDisabled(),true);
    await page.evaluate(()=>{mongoEditorFixture.missing=false;mongoEditorFixture.truncated=true;});await load.click();await status.filter({hasText:'Incomplete'}).waitFor();
    await page.evaluate(()=>{mongoEditorFixture.truncated=false;mongoEditorFixture.view=true;});await load.click();await status.filter({hasText:'inspection only'}).waitFor();assert.equal(await value.getAttribute('readonly'),'');
    await page.evaluate(()=>mongoEditorFixture.view=false);await load.click();await status.filter({hasText:'Loaded complete document'}).waitFor();
    await value.fill(JSON.stringify(changed));await page.evaluate(()=>mongoEditorFixture.lostReply=true);await save.click();await review.waitFor();await review.getByRole('button',{name:'Apply once'}).click();await status.filter({hasText:'outcome uncertain'}).waitFor();
    page.once('dialog',d=>d.accept());await reconcile.click();await page.evaluate(()=>mongoEditorFixture.lostReply=false);await load.click();await status.filter({hasText:'Loaded complete document'}).waitFor();
    await value.fill(JSON.stringify(changed));await page.evaluate(()=>{const v=mongoEditorFixture.workspace.stringEditor.value;v.value=JSON.stringify({_id:'a',x:'x'.repeat(32768)});v.dispatchEvent(new Event('input'));});assert.equal(JSON.parse(await value.inputValue()).name,'edited');
    await page.setViewportSize({width:420,height:900});await editor.scrollIntoViewIfNeeded();await page.screenshot({path:'code-graph-dba/target/mongo-document-editor.png'});
    assert.ok(await editor.evaluate(e=>e.scrollWidth<=e.clientWidth+2),'No horizontal control clipping');
    assert.ok(await value.evaluate(e=>e.getBoundingClientRect().height>=140),'Narrow editor retains a usable 140px border-box multi-line draft area');
    assert.ok(await editor.evaluate(e=>e.lastElementChild.getBoundingClientRect().top>=e.querySelector('textarea').getBoundingClientRect().bottom-1),'Footer stays below editor');
    await page.evaluate(()=>{mongoEditorFixture.workspace.profile.readOnly=true;mongoEditorFixture.workspace.stringEditor.sync();});assert.equal(await save.isDisabled(),true);assert.equal(await remove.isDisabled(),true);const count=await page.evaluate(()=>mongoEditorFixture.requests.length);await page.evaluate(()=>{mongoEditorFixture.workspace.stringEditor.stageDelete();mongoEditorFixture.workspace.stringEditor.save();});assert.equal(await page.evaluate(()=>mongoEditorFixture.requests.length),count);
    await page.evaluate(()=>{mongoEditorFixture.workspace.profile.readOnly=false;mongoEditorFixture.workspace.stringEditor.sync();});await save.click();await review.waitFor();await page.evaluate(()=>mongoEditorFixture.phase='running');await review.getByRole('button',{name:'Apply once'}).click();await page.waitForFunction(()=>mongoEditorFixture.workspace.operation?.id==='document-job');await page.getByRole('button',{name:'Cancel current operation'}).click();await page.evaluate(()=>mongoEditorFixture.phase='complete');await status.filter({hasText:'Document saved'}).waitFor();assert.equal(await page.evaluate(()=>mongoEditorFixture.cancelled),true);
    await close.click();assert.equal(await page.getByRole('textbox',{name:'Collection',exact:true}).isDisabled(),false);
    await page.evaluate(()=>mongoEditorFixture.workspace.profile.nativeOptions.topology='sharded');await open.click();await load.click();await status.filter({hasText:'inspection only'}).waitFor();assert.equal(await value.getAttribute('readonly'),'');await close.click();
    for(let i=0;i<4;i++){await open.click();await close.click();assert.equal(await page.evaluate(()=>mongoEditorFixture.accounts.at(-1)),await page.evaluate(()=>mongoEditorFixture.workspace.displayBytes));}
    await page.evaluate(()=>{const w=mongoEditorFixture.workspace,account=w.account;w.account=()=>{throw Error('Admission refused');};w.openDocumentEditor();w.account=account;});assert.equal(await editor.count(),0);
    await open.click();await page.evaluate(()=>mongoEditorFixture.workspace.invalidate('Connection removed'));assert.equal(await load.isDisabled(),true);await page.evaluate(()=>mongoEditorFixture.workspace.dispose());assert.equal(await page.evaluate(()=>mongoEditorFixture.accounts.at(-1)),0);assert.deepEqual(errors,[]);
    console.log('Mongo document editor passed: typed complete drafts, exact review, immutable ID, conflict/uncertainty, read-only topology, bounds, cancellation, lifecycle and narrow layout.');
  }finally{await context.close();}
};
