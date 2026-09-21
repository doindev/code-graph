const assert=require('node:assert/strict');
module.exports=async(browser,base,jar)=>{
  const context=await browser.newContext({viewport:{width:1450,height:980},acceptDownloads:true}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(base+'/dba');await page.locator('#agent-approvals').waitFor({state:'attached'});await page.waitForFunction(()=>document.querySelector('#connection-count')?.textContent.includes('connection'));
    const profile=await page.evaluate(async jar=>{
      const s=await(await fetch('/api/dba/session')).json(),headers={'Content-Type':'application/json','X-Dba-CSRF':s.csrf};
      async function api(path,method='GET',body){const r=await fetch('/api/dba'+path,{method,headers,body:body?JSON.stringify(body):undefined});const data=await r.json();if(!r.ok)throw Error(data.error);return data;}
      window.gridApi=api;
      await api('/settings','PUT',{uiRows:1000});
      const p=await api('/connections','POST',{name:'Editable grid fixture',url:'jdbc:h2:mem:editable_browser;DB_CLOSE_DELAY=-1',jar,driverClass:'org.h2.Driver',username:'sa',saveUntested:true});
      const job=await api('/query/execute','POST',{connectionId:p.id,sql:"CREATE TABLE PUBLIC.GRID_DATA(ID INT PRIMARY KEY, LABEL VARCHAR(80), AMOUNT DECIMAL(18,3) DEFAULT 4, REQUIRED_VALUE VARCHAR(80) NOT NULL DEFAULT 'defaulted'); INSERT INTO PUBLIC.GRID_DATA SELECT X, 'row-'||X, X, 'required' FROM SYSTEM_RANGE(1,451)",parameters:[]});
      for(;;){const current=await api('/jobs/'+job.id);if(current.finished){if(current.state!=='complete')throw Error(current.error);await api('/jobs/'+job.id,'DELETE');break;}await new Promise(r=>setTimeout(r,30));}return p;
    },jar);
    await page.reload();await page.locator('.connection-row[data-connection="'+profile.id+'"] .connection-select').click();await page.locator('#new-tab').click();await page.locator('#sql').fill('SELECT * FROM PUBLIC.GRID_DATA');await page.locator('#run').click();
    await page.waitForFunction(()=>document.querySelector('.grid-row-status')?.textContent.includes('1–200'));
    const footer=page.locator('#grid .data-grid-footer'),rows=page.locator('#grid .grid-body .grid-row');
    assert.equal(await footer.getByRole('textbox',{name:'Rows per page'}).inputValue(),'200');
    // Only render a mode selector when NULL or DEFAULT offers a real alternative.
    await rows.first().locator('[data-column-id=c1]').dblclick();
    const editor=page.locator('.grid-cell-editor'),input=editor.locator('textarea');
    assert.equal(await editor.getByRole('combobox',{name:'Value mode'}).count(),0);
    assert.equal(await input.evaluate(e=>e===document.activeElement),true);
    assert.equal(await editor.evaluate(e=>Math.abs(e.querySelector('textarea').getBoundingClientRect().right-e.getBoundingClientRect().right)<=2),true);
    await input.fill('999');await input.press('Escape');assert.equal(await rows.first().locator('[data-column-id=c1]').innerText(),'1');
    for(const [column,nullable,hasDefault] of [['c2',true,false],['c3',true,true],['c4',false,true]]){
      await rows.first().locator('[data-column-id='+column+']').dblclick();
      const mode=editor.getByRole('combobox',{name:'Value mode'});assert.equal(await mode.isVisible(),true);
      assert.equal(await mode.locator('option[value=null]').isEnabled(),nullable);
      assert.equal(await mode.locator('option[value=default]').isEnabled(),hasDefault);
      await mode.focus();
      for(const alternative of [...(nullable?['null']:[]),...(hasDefault?['default']:[])]){
        await mode.selectOption(alternative);assert.equal(await input.isDisabled(),true);
        await mode.selectOption('value');assert.equal(await input.isEnabled(),true);
      }
      await input.press('Escape');
    }
    assert.equal(await footer.getByRole('button',{name:'Save',exact:true}).isDisabled(),true);
    await rows.nth(0).locator('.row-number').click();await rows.nth(3).locator('.row-number').click({modifiers:['Shift']});assert.match(await page.locator('.grid-row-status').innerText(),/4 selected/);
    await rows.nth(1).locator('.row-number').click({modifiers:['Control']});assert.match(await page.locator('.grid-row-status').innerText(),/3 selected/);
    await rows.nth(0).locator('[data-column-id=c2]').dblclick();await page.locator('.grid-cell-editor textarea').fill('edited');await page.locator('.grid-cell-editor textarea').press('Enter');
    assert.equal(await footer.getByRole('button',{name:'Save',exact:true}).isEnabled(),true);assert.match(await rows.nth(0).innerText(),/edited/);
    await footer.getByRole('button',{name:'Refresh',exact:true}).click();await page.getByRole('dialog',{name:'Unsaved row changes'}).waitFor();await page.getByRole('button',{name:'Stay',exact:true}).click();assert.match(await rows.nth(0).innerText(),/edited/);
    await footer.getByRole('button',{name:'Save',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#grid')?.getAttribute('aria-busy')==='false'&&document.querySelector('.grid-row-status')?.textContent.includes('pending')===false);
    assert.match(await rows.nth(0).innerText(),/edited/);
    // Simulate a lost commit acknowledgement, after the server actually commits.
    let uncertainJob=null;
    await page.route('**/api/dba/grids/*/apply',async route=>{const response=await route.fetch();uncertainJob=(await response.json()).id;await route.fulfill({response});});
    await page.route('**/api/dba/jobs/*',async route=>{
      if(route.request().method()==='GET'&&route.request().url().endsWith('/'+uncertainJob)){
        const response=await route.fetch(),body=await response.json();
        if(body.finished&&body.outcome==='commit_acknowledged'){body.state='failed';body.outcome='unknown';body.error='Simulated lost acknowledgement';delete body.result;}
        await route.fulfill({response,json:body});
      }else await route.continue();
    });
    await rows.nth(0).locator('[data-column-id=c2]').dblclick();await page.locator('.grid-cell-editor textarea').fill('saved before disconnect');await page.locator('.grid-cell-editor textarea').press('Enter');
    await footer.getByRole('button',{name:'Save',exact:true}).click();
    await page.getByRole('button',{name:'Reconcile with database',exact:true}).waitFor();
    assert.equal(await footer.getByRole('button',{name:'Save',exact:true}).isDisabled(),true);
    await page.getByRole('button',{name:'Reconcile with database',exact:true}).click();await page.getByRole('button',{name:'Reload actual data',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('#grid')?.getAttribute('aria-busy')==='false'&&!document.querySelector('.grid-row-status')?.textContent.includes('pending'));
    assert.match(await rows.nth(0).innerText(),/saved before disconnect/);
    await page.unroute('**/api/dba/grids/*/apply');await page.unroute('**/api/dba/jobs/*');
    await footer.getByRole('button',{name:'Last row',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.grid-row-status')?.textContent.includes('252–451'));assert.match(await rows.last().innerText(),/451/);
    await footer.getByRole('button',{name:'First row',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.grid-row-status')?.textContent.includes('1–200'));
    await rows.nth(0).locator('.row-number').click();await footer.getByRole('button',{name:'Delete',exact:true}).click();assert.equal(await page.locator('.grid-row-deleted').count(),1);await footer.getByRole('button',{name:'Cancel',exact:true}).click();assert.equal(await page.locator('.grid-row-deleted').count(),0);
    await rows.nth(0).locator('[data-column-id=c2]').dblclick();await page.locator('.grid-cell-editor textarea').fill('not saved');await page.locator('.grid-cell-editor textarea').press('Escape');assert.match(await rows.nth(0).innerText(),/saved before disconnect/);
    await footer.getByRole('button',{name:'Grid settings',exact:true}).click();const settings=page.getByRole('dialog',{name:'Grid settings'});await settings.locator('input[type=checkbox]').last().uncheck();await settings.getByRole('button',{name:'Apply',exact:true}).click();assert.equal(await page.locator('.data-column-header').count(),3);
    await footer.getByRole('textbox',{name:'Rows per page'}).fill('20');await footer.getByRole('textbox',{name:'Rows per page'}).press('Enter');await page.waitForFunction(()=>document.querySelector('.grid-row-status')?.textContent.includes('1–20'));
    for(const format of ['csv','xlsx','txt','sql']){
      await footer.getByRole('button',{name:'Export data',exact:true}).click();const dialog=page.getByRole('dialog',{name:'Export data'});await dialog.getByRole('combobox',{name:'File format'}).selectOption(format);
      const promise=page.waitForEvent('download');await dialog.getByRole('button',{name:'Export',exact:true}).click();const download=await promise;assert.equal(download.suggestedFilename(),'result.'+format);assert.equal(await download.failure(),null);
    }
    await rows.first().focus();await rows.first().press('F2');await page.locator('.grid-cell-editor textarea').press('Tab');await page.locator('.grid-cell-editor textarea').press('Shift+Tab');await page.locator('.grid-cell-editor textarea').press('Escape');
    await footer.getByRole('button',{name:'Add row',exact:true}).click();
    if(await page.locator('.grid-cell-editor').count())await page.locator('.grid-cell-editor textarea').press('Enter');
    const added=page.locator('.grid-row-added');
    await added.locator('[data-column-id=c1]').dblclick();await page.locator('.grid-cell-editor textarea').fill('999');await page.locator('.grid-cell-editor textarea').press('Enter');
    await added.locator('[data-column-id=c2]').dblclick();await page.locator('.grid-cell-editor select').selectOption('value');await page.locator('.grid-cell-editor textarea').fill('inserted through grid');await page.locator('.grid-cell-editor textarea').press('Enter');
    await footer.getByRole('button',{name:'Save',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#grid')?.getAttribute('aria-busy')==='false'&&document.querySelector('.grid-row-status')?.textContent.includes('of 452'));
    await footer.getByRole('button',{name:'Last row',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.grid-row-status')?.textContent.includes('433–452'));
    await rows.last().locator('.row-number').click();await footer.getByRole('button',{name:'Delete',exact:true}).click();await footer.getByRole('button',{name:'Save',exact:true}).click();
    const deletion=page.getByRole('dialog',{name:'Save row deletions?',exact:true});await deletion.waitFor();assert.match(await deletion.innerText(),/1 deletions/);await deletion.getByRole('button',{name:'Save',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('#grid')?.getAttribute('aria-busy')==='false'&&document.querySelector('.grid-row-status')?.textContent.includes('of 451'));
    await footer.getByRole('button',{name:'First row',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.grid-row-status')?.textContent.includes('1–20'));
    await page.setViewportSize({width:760,height:720});assert.equal(await footer.evaluate(e=>e.scrollWidth<=e.clientWidth+1),true);
    await page.screenshot({path:'code-graph-dba/target/editable-grid.png'});
    // Row limiting must not require an editable relation or deterministic paging.
    await page.setViewportSize({width:1450,height:980});
    const readonlySql='SELECT ID+1 AS NEXT_ID, LABEL FROM PUBLIC.GRID_DATA ORDER BY ID';
    await page.locator('#sql').fill(readonlySql);await page.locator('#run').click();
    await page.waitForFunction(()=>document.querySelector('.grid-row-status')?.textContent.includes('1–200')&&document.querySelector('#grid')?.getAttribute('aria-busy')==='false');
    const limit=footer.getByRole('textbox',{name:'Rows per page'});assert.equal(await limit.isEnabled(),true);
    assert.equal(await footer.getByRole('button',{name:'Add row',exact:true}).isDisabled(),true);
    const submitted=[];await page.route(/\/api\/dba\/(?:grids\/[^/]+\/(?:reload|page)|query\/execute)$/,async route=>{submitted.push({url:route.request().url(),body:route.request().postDataJSON()});await route.continue();});
    await limit.fill('abc17');assert.equal(await limit.inputValue(),'17');assert.equal(submitted.length,0,'Typing alone does not execute SQL');
    for(const invalid of ['','0','1001']){await limit.fill(invalid);await limit.press('Enter');assert.equal(await limit.evaluate(e=>e.checkValidity()),false);assert.equal(submitted.length,0);}
    await limit.fill('17');await limit.press('Enter');await page.waitForFunction(()=>document.querySelector('.grid-row-status')?.textContent.includes('1–17')&&document.querySelector('#grid')?.getAttribute('aria-busy')==='false');
    assert.equal(submitted.at(-1).body.limit,17);assert.ok(submitted.at(-1).url.endsWith('/reload'));assert.equal(await rows.count(),17);assert.equal(await limit.inputValue(),'17');
    await footer.getByRole('button',{name:'Refresh',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#grid')?.getAttribute('aria-busy')==='false');
    assert.equal(submitted.at(-1).body.limit,17);assert.equal(await rows.count(),17);
    await page.locator('#grid .grid-filter-input').fill('ID > 20');await page.getByRole('button',{name:'Apply SQL filter expression',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('#grid')?.getAttribute('aria-busy')==='false'&&document.querySelector('.grid-row-status')?.textContent.includes('1–17'));
    assert.ok(submitted.at(-1).url.endsWith('/query/execute'));assert.equal(submitted.at(-1).body.rowLimit,17);assert.equal(await rows.count(),17);assert.equal(await rows.first().locator('[data-column-id=c1]').innerText(),'22');assert.equal(await limit.inputValue(),'17');assert.equal(await page.locator('#sql').inputValue(),readonlySql);
    await limit.fill('300');await limit.press('Enter');await page.waitForFunction(()=>document.querySelector('.grid-row-status')?.textContent.includes('1–300')&&document.querySelector('#grid')?.getAttribute('aria-busy')==='false');
    assert.equal(submitted.at(-1).body.limit,300);assert.equal(await limit.inputValue(),'300');
    await page.screenshot({path:'code-graph-dba/target/grid-row-limit.png'});
    await page.unroute(/\/api\/dba\/(?:grids\/[^/]+\/(?:reload|page)|query\/execute)$/);
    assert.deepEqual(errors,[]);console.log('Editable grids: conditional Value/NULL/DEFAULT selector, staging, Save/Stay/Cancel, row selection, first/last paging, settings, all export downloads and narrow footer passed.');
  }catch(error){console.error('Grid browser diagnostics',JSON.stringify({errors,status:await page.locator('#status').textContent(),error:await page.locator('#error').textContent(),grid:await page.locator('#grid').innerText()}));throw error;}finally{await context.close();}
};
