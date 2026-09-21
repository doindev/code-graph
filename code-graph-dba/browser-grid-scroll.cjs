const assert=require('node:assert/strict');
module.exports=async(browser,base,jar)=>{
  const context=await browser.newContext({viewport:{width:1400,height:940}}),page=await context.newPage(),errors=[],windows=[];
  page.on('pageerror',error=>errors.push(error.message));
  page.on('request',request=>{if(request.url().match(/\/grids\/[^/]+\/page$/)&&request.postDataJSON()?.direction==='window')windows.push(request.postDataJSON());});
  try{
    await page.goto(base+'/dba');await page.waitForFunction(()=>document.querySelector('#workspace')?.inert===false&&/^\d+ connection/.test(document.querySelector('#connection-count')?.textContent??''));
    const profile=await page.evaluate(async jar=>{
      const session=await(await fetch('/api/dba/session')).json();
      const api=async(path,method='GET',body)=>{const response=await fetch('/api/dba'+path,{method,headers:{'Content-Type':'application/json','X-Dba-CSRF':session.csrf},body:body?JSON.stringify(body):undefined});const result=await response.json();if(!response.ok)throw Error(result.error);return result;};
      await api('/settings','PUT',{uiRows:1000});
      const profile=await api('/connections','POST',{name:'Scroll window fixture',url:'jdbc:h2:mem:scroll_window_browser;DB_CLOSE_DELAY=-1',jar,driverClass:'org.h2.Driver',username:'sa',saveUntested:true});
      const job=await api('/query/execute','POST',{connectionId:profile.id,sql:"CREATE TABLE SCROLL_ROWS(ID INT PRIMARY KEY,LABEL VARCHAR(200));INSERT INTO SCROLL_ROWS SELECT X,'row-'||X FROM SYSTEM_RANGE(1,1601)",parameters:[]});
      for(;;){const done=await api('/jobs/'+job.id);if(done.finished){if(done.state!=='complete')throw Error(done.error);await api('/jobs/'+job.id,'DELETE');break;}await new Promise(r=>setTimeout(r,30));}return profile;
    },jar);
    await page.reload();await page.locator('.connection-row[data-connection="'+profile.id+'"] .connection-select').click();await page.locator('#new-tab').click();
    await page.locator('#sql').fill('SELECT * FROM PUBLIC.SCROLL_ROWS');await page.locator('#run').click();
    const status=page.locator('#grid .grid-row-status'),scroll=page.locator('#grid .data-grid-scroll'),footer=page.locator('#grid .data-grid-footer');
    const wait=async offset=>{await page.waitForFunction(offset=>document.querySelector('#grid .grid-row-status')?.textContent.startsWith((offset+1)+'–')&&document.querySelector('#grid')?.getAttribute('aria-busy')==='false',offset);};
    await wait(0);assert.equal(windows.length,0,'Opening a grid does not drain the query');
    // Resize two columns by dragging, then assert widths across virtual and server page boundaries.
    const widths={};
    for(const [id,delta] of [['c1',130],['c2',220]]){
      const header=page.locator('#grid .data-column-header[data-column-id='+id+']'),handle=header.locator('.column-resize'),bounds=await handle.boundingBox();
      await page.mouse.move(bounds.x+5,bounds.y+10);await page.mouse.down();await page.mouse.move(bounds.x+5+delta,bounds.y+10);await page.mouse.up();
      widths[id]=await header.evaluate(e=>e.getBoundingClientRect().width);
    }
    async function aligned(){
      assert.equal(await scroll.evaluate((host,widths)=>{
        const header=host.querySelector('.grid-row.header'),layout=getComputedStyle(header).gridTemplateColumns;
        return [...host.querySelectorAll('.grid-body .grid-row')].every(row=>getComputedStyle(row).gridTemplateColumns===layout)&&
          Object.entries(widths).every(([id,width])=>header.querySelector('[data-column-id='+id+']').getBoundingClientRect().width===width);
      },widths),true,'Resized data columns remain aligned with the header across virtual/server windows');
    }
    await aligned();
    const runBefore=await page.locator('#run').isDisabled(),cancelBefore=await page.locator('#stop').isDisabled();
    let pause;
    await page.route('**/api/dba/grids/*/page',async route=>{
      if(route.request().postDataJSON()?.direction==='window'&&pause)await pause;
      await route.continue();
    });
    let resume;pause=new Promise(resolve=>resume=resolve);
    const anchor=await scroll.evaluate(element=>{
      element.scrollTop=element.scrollHeight-element.clientHeight-120;
      return element.scrollTop;
    });
    await page.locator('.grid-scroll-progress').waitFor();
    assert.equal(await scroll.evaluate(e=>e.closest('[inert]')!==null),false);
    assert.equal(await page.locator('#run').isDisabled(),runBefore);assert.equal(await page.locator('#stop').isDisabled(),cancelBefore);
    assert.equal(await footer.evaluate(e=>e.inert),true);
    resume();pause=null;await wait(100);
    await aligned();
    assert.ok(Math.abs(await scroll.evaluate(e=>e.scrollTop)+100*28-anchor)<2,'Overlapping window preserves visible row/pixel');
    assert.ok(await page.locator('#grid .grid-body .grid-row').count()<45,'DOM remains virtualized');
    const downCalls=windows.length;await page.waitForTimeout(450);assert.equal(windows.length,downCalls,'Publication does not trigger a paging loop');
    await scroll.evaluate(e=>e.scrollTop=80);await wait(0);
    assert.equal(windows.at(-1).offset,0);
    await aligned();
    assert.ok(Math.abs(await scroll.evaluate(e=>e.scrollTop)-(80+100*28))<2);
    // Layout preferences survive window changes.
    const header=page.locator('#grid .data-column-header').first();await header.focus();await header.press('Alt+ArrowRight');
    assert.equal(await page.locator('#grid .data-column-header').last().getAttribute('data-column-id'),'c1');
    await scroll.evaluate(e=>e.scrollTop=e.scrollHeight-e.clientHeight-80);await wait(100);
    assert.equal(await page.locator('#grid .data-column-header').last().getAttribute('data-column-id'),'c1');
    // Editing pauses automatic paging; scrolling must not open Save/Discard dialogs.
    await aligned();
    await footer.getByRole('button',{name:'First row',exact:true}).click();await wait(0);
    await page.locator('#grid .grid-body .grid-row').first().locator('[data-column-id=c2]').dblclick();
    await page.locator('.grid-cell-editor textarea').fill('pending edit');await page.locator('.grid-cell-editor textarea').press('Enter');
    const dirtyCalls=windows.length;await scroll.evaluate(e=>e.scrollTop=e.scrollHeight);await page.waitForTimeout(450);
    assert.equal(windows.length,dirtyCalls);assert.equal(await page.locator('dialog[open]').count(),0);assert.match(await status.innerText(),/pending/);
    await footer.getByRole('button',{name:'Cancel',exact:true}).click();await footer.getByRole('button',{name:'First row',exact:true}).click();await wait(0);
    // Find is intentionally loaded-window-only, so its scope stays stable.
    await page.locator('#grid .data-grid-command').getByRole('button',{name:'Find and Replace',exact:true}).click();
    const findCalls=windows.length;await scroll.evaluate(e=>e.scrollTop=e.scrollHeight);await page.waitForTimeout(350);assert.equal(windows.length,findCalls);
    await page.locator('#grid .grid-find').getByRole('button',{name:/Close/}).click();
    // Explicit tiny page sizes remain honored; wheel navigation works without a scrollbar.
    const limit=footer.getByRole('textbox',{name:'Rows per page'});
    await limit.fill('1');await limit.press('Enter');await wait(0);assert.match(await status.innerText(),/^1–1/);
    await scroll.hover();await page.mouse.wheel(0,120);await wait(1);assert.match(await status.innerText(),/^2–2/);
    await page.mouse.wheel(0,-120);await wait(0);
    // No stale DOM/timers after view unmount; controller state survives tab switching.
    await limit.fill('200');await limit.press('Enter');await wait(0);
    await aligned();
    await page.locator('#new-tab').click();const beforeHidden=windows.length;await page.waitForTimeout(450);assert.equal(windows.length,beforeHidden);
    assert.deepEqual(errors,[]);
    console.log('PASS grid-scroll: forward/backward overlapping windows, anchored viewport, bounded DOM, dirty/find pause, tiny pages, lifecycle');
  }catch(error){console.log('Scroll diagnostic',await page.evaluate(()=>({error:document.querySelector('.grid-query-error')?.textContent,status:document.querySelector('.grid-row-status')?.textContent})),errors,windows);throw error;}
  finally{await context.close();}
};
