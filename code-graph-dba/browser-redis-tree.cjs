const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1000,height:700}}),page=await context.newPage(),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  try{
    // A component host needs no authenticated workspace; do not consume one of
    // the runtime's bounded sessions simply to mount an injected tree fixture.
    await page.route(base+'/dba/__redis-tree-fixture',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><html lang="en"><head><meta charset="utf-8"><link rel="stylesheet" href="/dba/style.css"><title>Redis tree fixture</title></head><body></body></html>'}));
    await page.goto(base+'/dba/__redis-tree-fixture');
    await page.evaluate(async()=>{
      const {MetadataTreeView}=await import('/dba/metadata-tree.js');
      const container=document.createElement('div');container.id='redis-tree-fixture';container.style.width='260px';document.body.prepend(container);
      const key=(name,id=name)=>({key:id,name,kind:'native_key',nativeObject:true,command:['TYPE',name]});
      const state=window.redisTreeTest={calls:[],notices:[],cancelled:[],replies:[],key,opened:[]};
      state.tree=new MetadataTreeView({api:async(path,method,body)=>{
        if(path.endsWith('/cancel')){state.cancelled.push(path);return {};}
        state.calls.push(body);const result=state.replies.shift();
        if(result==='pending')return new Promise(resolve=>state.submit=()=>resolve({id:'late',result:{nodes:[key('late')],scanComplete:true}}));
        return{id:'job-'+state.calls.length,result};
      },wait:async job=>{if(job.result?.error)throw new Error(job.result.error);return job.result;},menu(){},notice:text=>state.notices.push(text),openTable:(_profile,node)=>state.opened.push(node.key)});
      state.entry={key:'redis/keys',profile:{id:'redis',transport:'redis'},descriptor:{kind:'native_keys'},container,children:[],revision:0};
      state.tree.roots.set('redis',state.entry);
      state.replies.push({nodes:[],nextOffset:'1',scanComplete:false});await state.tree.reload(state.entry);
    });
    const fixture=page.locator('#redis-tree-fixture'),more=fixture.getByRole('button',{name:'Continue scan…',exact:true});
    assert.match(await fixture.innerText(),/0 distinct keys loaded.*Scan unfinished/s);assert.equal(await more.count(),1);
    await page.evaluate(()=>{const s=redisTreeTest;s.replies.push({nodes:[s.key('z'),s.key('a'),s.key('a')],nextOffset:'2'});});await more.click();
    assert.deepEqual(await fixture.locator('.metadata-name').allTextContents(),['a','z']);
    await page.evaluate(()=>{const s=redisTreeTest;s.replies.push({nodes:[s.key('z')],nextOffset:'3'});});await more.click();
    assert.equal(await fixture.locator('.metadata-name').count(),2);
    await page.evaluate(()=>{const s=redisTreeTest;s.replies.push({nodes:[s.key('a','binary-other')],scanComplete:true});});await more.click();
    assert.deepEqual(await fixture.locator('.metadata-name').allTextContents(),['a','a','z']);
    assert.equal(await fixture.evaluate(node=>node.scrollWidth<=node.clientWidth),true,'narrow tree controls must fit');
    assert.equal(await more.count(),0);assert.match(await fixture.innerText(),/Scan finished.*not a snapshot/s);
    await fixture.locator('.metadata-name').first().press('Enter');assert.equal(await page.evaluate(()=>redisTreeTest.opened.length),1);
    // Refresh restarts, even after arbitrarily many remembered pages; no scan replay.
    await page.evaluate(async()=>{const s=redisTreeTest;s.tree.pages.set(s.entry.key,9999);s.replies.push({nodes:[],nextOffset:'4'});await s.tree.reload(s.entry);});
    assert.equal(await page.evaluate(()=>redisTreeTest.calls.length),5);assert.equal(await page.evaluate(()=>redisTreeTest.calls.at(-1).offset),0);
    await fixture.getByRole('textbox',{name:'Redis key pattern'}).fill('user:*');
    await page.evaluate(()=>{redisTreeTest.replies.push({nodes:[],nextOffset:'5'});});await fixture.getByRole('textbox',{name:'Redis key pattern'}).press('Enter');
    assert.equal(await page.evaluate(()=>redisTreeTest.calls.at(-1).pattern),'user:*');
    // Capacity failures consume no cursor and preserve prior keys; retry uses same cursor.
    await page.evaluate(()=>{const s=redisTreeTest;s.replies.push({nodes:Array.from({length:2001},(_,i)=>s.key(String(i))),nextOffset:'wrong'});});await more.click();
    assert.match(await page.evaluate(()=>redisTreeTest.notices.at(-1)),/2,000 items/);assert.equal(await fixture.locator('.metadata-name').count(),0);
    await page.evaluate(()=>{const s=redisTreeTest;s.replies.push({nodes:Array.from({length:100},(_,i)=>({...s.key(String(i)),extra:'x'.repeat(22000)})),nextOffset:'wrong'});});await more.click();
    assert.match(await page.evaluate(()=>redisTreeTest.notices.at(-1)),/2 MiB/);
    await page.evaluate(()=>{const s=redisTreeTest;s.replies.push({nodes:[s.key('ok')],scanComplete:true});});await more.click();
    assert.equal(await page.evaluate(()=>redisTreeTest.calls.at(-1).offset),'5');
    assert.deepEqual(await fixture.locator('.metadata-name').allTextContents(),['ok']);
    // Failed refresh preserves the old tree and committed pattern.
    await fixture.getByRole('textbox',{name:'Redis key pattern'}).fill('bad:*');await page.evaluate(()=>redisTreeTest.replies.push({error:'scan failed'}));await fixture.getByRole('textbox',{name:'Redis key pattern'}).press('Enter');
    await page.waitForFunction(()=>redisTreeTest.entry.descriptor.pattern==='user:*');assert.deepEqual(await fixture.locator('.metadata-name').allTextContents(),['ok']);
    // Removal during submission cancels a late-arriving job and rejects its results.
    await page.evaluate(()=>{const s=redisTreeTest;s.replies.push('pending');s.pending=s.tree.reload(s.entry);});
    await page.evaluate(async()=>{const s=redisTreeTest;s.tree.retain([]);s.submit();await s.pending;});
    assert.deepEqual(await page.evaluate(()=>redisTreeTest.cancelled),['/jobs/late/cancel']);
    assert.deepEqual(await fixture.locator('.metadata-name').allTextContents(),['ok']);
    assert.equal(await page.evaluate(()=>redisTreeTest.entry.operations.size),0);
    // Cancellation also covers a job already being polled; completion is ignored.
    await page.evaluate(()=>{const s=redisTreeTest;s.tree.roots.set('redis',s.entry);s.tree.wait=job=>new Promise(resolve=>s.finish=()=>resolve(job.result));s.replies.push({nodes:[s.key('obsolete')],scanComplete:true});s.pending=s.tree.reload(s.entry);});
    await page.waitForFunction(()=>typeof redisTreeTest.finish==='function');
    await page.evaluate(async()=>{const s=redisTreeTest;s.tree.retain([]);s.finish();await s.pending;});
    assert.equal(await page.evaluate(()=>redisTreeTest.cancelled.length),2);assert.equal(await page.evaluate(()=>redisTreeTest.entry.operations.size),0);
    assert.deepEqual(await fixture.locator('.metadata-name').allTextContents(),['ok']);
    assert.deepEqual(errors,[]);console.log('PASS Redis key tree: bounded dedup, empty cursors, binary identity, pattern, refresh and cancellation');
  }finally{await context.close();}
};
