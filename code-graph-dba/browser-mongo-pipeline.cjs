const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  try{
    await page.route(base+'/dba/__pipeline-fixture',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><html lang="en"><head><meta charset="utf-8"><link rel="stylesheet" href="/dba/style.css"><title>Pipeline fixture</title></head><body></body></html>'}));
    await page.goto(base+'/dba/__pipeline-fixture');
    await page.evaluate(async()=>{
      const {NativeWorkspace}=await import('/dba/native-workspace.js');window.pipelineTest={calls:[],bytes:[],changes:0};
      const s=pipelineTest;s.workspace=new NativeWorkspace({
        profile:{id:'mongo',name:'Pipeline fixture',transport:'mongodb',nativeOptions:{database:'app'},readOnly:true},
        state:{database:'app',collection:'items',commandText:'{"find":"items","filter":{},"limit":100}'},
        changed:()=>s.changes++,account:bytes=>{if(s.reject&&bytes>0)throw Error('Browser allowance full');s.bytes.push(bytes);},
        api:async(path,method,body)=>{s.calls.push({path,method,body});if(path==='/native/prepare')return{id:'review',targetRevision:'revision',mutation:false};if(path==='/native/execute')return{id:'job'};if(path==='/jobs/job'&&!method)return{state:'complete',finished:Date.now(),result:{kind:'documents',entries:[{name:'beta'}],truncated:false}};return {};}
      });s.workspace.mount(document.body);
    });
    const open=page.getByRole('button',{name:'Build aggregation pipeline',exact:true}),dialog=page.getByRole('dialog',{name:'MongoDB aggregation pipeline'});
    const value=dialog.getByRole('textbox',{name:'Stage value as Extended JSON'}),palette=dialog.getByRole('combobox',{name:'Stage to add'});
    const add=dialog.getByRole('button',{name:'Add stage',exact:true}),use=dialog.getByRole('button',{name:'Use pipeline',exact:true});
    const original=await page.locator('.native-command').inputValue();
    assert.equal(await open.locator('svg[data-lucide=workflow]').count(),1);
    await page.evaluate(()=>pipelineTest.reject=true);await open.click();assert.equal(await dialog.count(),0);
    assert.match(await page.locator('.native-status').innerText(),/Browser allowance full/);
    assert.equal(await page.evaluate(()=>pipelineTest.bytes.at(-1)),0);
    await page.evaluate(()=>pipelineTest.reject=false);
    await open.click();assert.equal(await page.getByRole('button',{name:'Run native command',exact:true}).isDisabled(),true);
    await add.click();await value.fill('{"name":"beta","n":{"$numberLong":"9007199254740993"}}');
    await palette.selectOption('$sort');await add.click();await value.fill('{"name":1}');
    await dialog.getByRole('button',{name:'Move stage 2 up',exact:true}).click();
    assert.equal(await value.inputValue(),'{"name":1}');assert.equal(await dialog.locator('.mongo-stage-select').first().innerText(),'1. $sort');
    await dialog.getByRole('button',{name:'Move stage 1 down',exact:true}).press('Enter');
    await dialog.getByRole('checkbox',{name:'Enable stage 1',exact:true}).uncheck();
    assert.match(await dialog.getByRole('status').innerText(),/1 disabled/);
    await dialog.getByRole('checkbox',{name:'Enable stage 1',exact:true}).check();
    await dialog.locator('.mongo-stage-select').first().dragTo(dialog.locator('.mongo-pipeline-stages li').nth(1));
    assert.equal(await dialog.locator('.mongo-stage-select').first().innerText(),'1. $sort');
    await dialog.getByRole('button',{name:'Move stage 2 up',exact:true}).click();
    await dialog.locator('summary').click();assert.equal(await dialog.getByRole('textbox',{name:'Generated aggregation command'}).getAttribute('readonly'),'');
    await page.screenshot({path:'code-graph-dba/target/mongo-pipeline.png'});
    assert.equal(await page.locator('.native-command').inputValue(),original);assert.equal(await page.evaluate(()=>pipelineTest.calls.length),0);
    await use.click();await dialog.waitFor({state:'hidden'});assert.equal(await page.evaluate(()=>pipelineTest.calls.length),0);
    const generated=JSON.parse(await page.locator('.native-command').inputValue());
    assert.deepEqual(generated,{aggregate:'items',pipeline:[{$match:{name:'beta',n:{$numberLong:'9007199254740993'}}},{$sort:{name:1}}],allowDiskUse:false});
    await page.getByRole('button',{name:'Run native command',exact:true}).click();await page.waitForFunction(()=>!pipelineTest.workspace.operation);
    const execution=await page.evaluate(()=>pipelineTest.calls.find(c=>c.path==='/native/execute').body);
    assert.equal(execution.collection,'items');assert.equal(execution.database,'app');assert.equal(execution.connectionId,'mongo');assert.deepEqual(execution.command,generated);
    assert.equal(await page.evaluate(()=>pipelineTest.bytes.at(-1)<2*1024*1024),true);
    // Invalid drafts never replace command text. Cancel/Escape are read-only.
    await open.click();await value.fill('{"n":9007199254740993}');assert.equal(await use.isDisabled(),true);
    await dialog.getByRole('button',{name:'Cancel',exact:true}).click();assert.deepEqual(JSON.parse(await page.locator('.native-command').inputValue()),generated);
    await open.click();await page.keyboard.press('Escape');await dialog.waitFor({state:'hidden'});
    // Target changes, ownership loss, and late editor updates cannot overwrite state.
    await open.click();await page.evaluate(()=>pipelineTest.workspace.editor.value='{"find":"items"}');await use.click();
    assert.match(await dialog.getByRole('status').innerText(),/Workspace or connection changed/);assert.equal(await dialog.isVisible(),true);
    await dialog.getByRole('button',{name:'Close pipeline builder',exact:true}).click();
    await open.click();await page.evaluate(()=>pipelineTest.workspace.updateProfile({...pipelineTest.workspace.profile,name:'Renamed'}));
    assert.equal(await use.isDisabled(),true);assert.match(await dialog.getByRole('status').innerText(),/configuration changed/);
    await dialog.getByRole('button',{name:'Cancel',exact:true}).click();
    // No background execution while the modal owns the draft; teardown releases it.
    await page.evaluate(()=>{const w=pipelineTest.workspace;w.unavailable=null;w.lockStringTarget();});
    await open.click();const count=await page.evaluate(()=>pipelineTest.calls.length);
    await page.evaluate(()=>pipelineTest.workspace.run());assert.equal(await page.evaluate(()=>pipelineTest.calls.length),count);
    await page.setViewportSize({width:480,height:720});
    assert.equal(await dialog.evaluate(d=>d.scrollWidth<=d.clientWidth+1),true);
    assert.equal(await dialog.locator('.mongo-pipeline-stages').evaluate(d=>d.scrollWidth<=d.clientWidth+1),true);
    page.once('dialog',d=>d.dismiss());assert.equal(await page.evaluate(()=>pipelineTest.workspace.canClose()),false);
    await page.evaluate(()=>pipelineTest.workspace.dispose());assert.equal(await dialog.count(),0);assert.equal(await page.evaluate(()=>pipelineTest.bytes.at(-1)),0);
    assert.deepEqual(errors,[]);
    console.log('Mongo pipeline browser: stage editing/order, canonical BSON, no auto-run, exact target, stale drafts, accessibility, narrow layout and cleanup passed');
  }finally{await context.close();}
};
