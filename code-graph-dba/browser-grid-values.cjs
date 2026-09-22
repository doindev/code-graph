const assert=require('node:assert/strict');

module.exports=async function(browser,base,jar){
  const context=await browser.newContext({viewport:{width:1150,height:900}}),page=await context.newPage(),errors=[],valueRequests=[];
  page.on('pageerror',error=>errors.push(error.message));
  page.on('request',request=>{if(/\/grids\/[^/]+\/values$/.test(request.url()))valueRequests.push(request.postDataJSON());});
  try{
    await page.goto(base+'/dba');await page.locator('#agent-approvals').waitFor({state:'attached'});
    const profile=await page.evaluate(async jar=>{
      const session=await(await fetch('/api/dba/session')).json();
      async function api(path,method='GET',body){const response=await fetch('/api/dba'+path,{method,headers:{'Content-Type':'application/json','X-Dba-CSRF':session.csrf},...(body===undefined?{}:{body:JSON.stringify(body)})});const value=await response.json();if(!response.ok)throw Error(value.error);return value;}
      const profile=await api('/connections','POST',{name:'Value picker fixture',url:'jdbc:h2:mem:value_picker;DB_CLOSE_DELAY=-1',jar,driverClass:'org.h2.Driver',username:'sa',saveUntested:true});
      const job=await api('/query/execute','POST',{connectionId:profile.id,autoCommit:true,parameters:[],sql:"CREATE TABLE PICKER_ITEMS(ID INT PRIMARY KEY, NAME VARCHAR(80), AMOUNT DECIMAL(30,3)); INSERT INTO PICKER_ITEMS SELECT X, 'value-'||LPAD(CAST(X AS VARCHAR),3,'0'), 9007199254740993.125 FROM SYSTEM_RANGE(1,451); INSERT INTO PICKER_ITEMS VALUES(452,NULL,1),(453,NULL,2),(454,'',3),(455,'NULL',4),(456,'value-001',5);"});
      for(let i=0;i<150;i++){const current=await api('/jobs/'+job.id);if(current.finished){if(current.state!=='complete')throw Error(current.error);await api('/jobs/'+job.id,'DELETE');return profile;}await new Promise(resolve=>setTimeout(resolve,100));}throw Error('Fixture timed out');
    },jar);
    await page.reload();await page.locator('.connection-row[data-connection="'+profile.id+'"] .connection-select').click();await page.locator('#new-tab').click();
    await page.locator('#sql').fill('SELECT ID, NAME, AMOUNT FROM PUBLIC.PICKER_ITEMS WHERE ID < 4 ORDER BY ID');
    await page.locator('#run').click();await page.waitForFunction(()=>document.querySelectorAll('#grid .grid-body .grid-row').length===3);
    const picker=page.locator('.grid-values-dialog'),search=picker.getByRole('searchbox',{name:'Filter values'}),apply=picker.getByRole('button',{name:'Apply',exact:true});
    async function ready(){await page.waitForFunction(()=>{const dialog=document.querySelector('.grid-values-dialog');return dialog&&!dialog.querySelector('.grid-values-summary').textContent.includes('Loading');});}
    async function open(host=page.locator('#grid'),index=1){await host.locator('.grid-column-toggle').nth(index).click();await page.getByRole('menuitem',{name:'Filter by value',exact:true}).click();await picker.waitFor();await ready();}
    async function choose(value){await search.fill(value);await ready();await picker.getByRole('checkbox',{name:'Select '+value,exact:true}).check();}
    await open();
    assert.equal(await picker.getByRole('checkbox',{name:'Read from server',exact:true}).isChecked(),true);
    assert.equal(await picker.getByRole('checkbox',{name:'Show row count',exact:true}).isChecked(),false);
    assert.equal(await picker.getByRole('checkbox',{name:'Show distinct values count',exact:true}).isChecked(),false);
    assert.equal(await apply.isDisabled(),true);assert.ok(await picker.locator('.grid-values-list .grid-values-row').count()<50,'Value rows are virtualized');
    assert.ok((await picker.locator('.grid-values-count').allTextContents()).every(text=>text===''));
    const positions=await picker.evaluate(d=>({input:d.querySelector('input[type=search]').getBoundingClientRect().width,list:d.querySelector('.grid-values-list').getBoundingClientRect().width,clear:d.querySelector('.grid-values-actions>button').getBoundingClientRect().x,apply:d.querySelector('.grid-values-actions>div').getBoundingClientRect().x,options:[...d.querySelectorAll('.grid-values-options>label')].map(e=>e.getBoundingClientRect().width)}));
    assert.ok(Math.abs(positions.input-positions.list)<2);assert.ok(positions.clear<positions.apply);assert.ok(Math.max(...positions.options)-Math.min(...positions.options)<2);
    await picker.getByRole('checkbox',{name:'Show row count',exact:true}).check();await ready();
    await picker.getByRole('checkbox',{name:'Show distinct values count',exact:true}).check();await ready();
    assert.match(await picker.locator('.grid-values-summary').innerText(),/454 distinct values/);
    assert.equal(await picker.getByRole('checkbox',{name:'Select NULL',exact:true}).locator('..').locator('.grid-values-count').innerText(),'2');
    assert.equal(await picker.getByRole('checkbox',{name:'Select (empty string)',exact:true}).count(),1);
    assert.equal(await picker.getByRole('checkbox',{name:'Select "NULL"',exact:true}).count(),1);
    const previousHeight=await picker.locator('.grid-values-list').evaluate(e=>e.scrollHeight);await picker.locator('.grid-values-list').evaluate(e=>e.scrollTop=e.scrollHeight);await page.waitForFunction(height=>document.querySelector('.grid-values-list')?.scrollHeight>height,previousHeight);
    assert.ok(valueRequests.some(request=>request.offset>0),'Paging respects the fixture row ceiling');
    await search.fill('value-451');await ready();assert.equal(await picker.getByRole('checkbox',{name:'Select value-451',exact:true}).count(),1,'Server search covers unloaded rows');
    assert.match(await picker.locator('.grid-values-summary').innerText(),/1 matching/);
    await choose('value-001');await search.fill('value-002');await ready();assert.equal(await apply.isEnabled(),true,'Search does not clear hidden selections');
    await picker.getByRole('button',{name:'Clear All',exact:true}).click();assert.equal(await apply.isDisabled(),true);
    await choose('value-001');await search.fill('value-002');await ready();await picker.getByRole('checkbox',{name:'Select value-002',exact:true}).check();
    await search.fill('');await ready();await page.screenshot({path:'code-graph-dba/target/grid-value-picker.png'});
    await apply.click();await picker.waitFor({state:'detached'});assert.equal(await page.locator('#grid .grid-body .grid-row').count(),2);
    assert.match(await page.locator('.grid-source-preview').inputValue(),/ID < 4/i);assert.match(await page.locator('.grid-filter-input').inputValue(),/NAME IN \('value-001', 'value-002'\)/i);
    await open();assert.match(await picker.locator('.grid-values-summary').innerText(),/2 selected/);
    await picker.getByRole('button',{name:'Clear All',exact:true}).click();await choose('value-003');await apply.click();await picker.waitFor({state:'detached'});
    assert.equal(await page.locator('#grid .grid-body .grid-row').count(),1);assert.doesNotMatch(await page.locator('.grid-filter-input').inputValue(),/value-001/);
    await open();await picker.getByRole('checkbox',{name:'Read from server',exact:true}).uncheck();await ready();
    const beforeCached=valueRequests.length;
    assert.match(await picker.locator('.grid-values-summary').innerText(),/Retained grid rows only/);
    assert.match(await picker.locator('.grid-values-summary').innerText(),/1 distinct values/);
    await search.fill('value');await ready();await picker.getByRole('checkbox',{name:'Show distinct values count',exact:true}).uncheck();await ready();
    assert.equal(valueRequests.length,beforeCached,'Cached search/count changes never query the server');
    await picker.getByRole('button',{name:'Clear All',exact:true}).click();await picker.getByRole('button',{name:'Cancel',exact:true}).click();await open();assert.match(await picker.locator('.grid-values-summary').innerText(),/1 selected/,'Cancel preserves applied choices');
    await page.keyboard.press('Escape');await picker.waitFor({state:'detached'});
    assert.equal(await page.locator('#grid .grid-column-toggle').nth(1).evaluate(e=>e===document.activeElement),true);
    await open();await picker.getByRole('checkbox',{name:'Read from server',exact:true}).check();await ready();await picker.getByRole('button',{name:'Clear All',exact:true}).click();await choose('value-001');
    const beforeFailure=await page.locator('.grid-source-preview').inputValue();
    await page.route('**/api/dba/query/grid-edit',route=>route.fulfill({status:400,contentType:'application/json',body:JSON.stringify({error:'Fixture filter validation failure'})}),{times:1});
    await apply.click();await picker.getByRole('alert').filter({hasText:'Fixture filter validation failure'}).waitFor();assert.equal(await page.locator('.grid-source-preview').inputValue(),beforeFailure);assert.equal(await picker.getByRole('checkbox',{name:'Select value-001',exact:true}).isChecked(),true);await picker.getByRole('button',{name:'Cancel',exact:true}).click();
    await open();await picker.getByRole('button',{name:'Clear All',exact:true}).click();await choose('value-002');
    let heldEdit,signalEdit;const editArrived=new Promise(resolve=>signalEdit=resolve);
    await page.route('**/api/dba/query/grid-edit',route=>{heldEdit=route;signalEdit();},{times:1});
    await apply.click();await editArrived;await picker.getByRole('button',{name:'Cancel',exact:true}).click();await heldEdit.continue();
    await picker.waitFor({state:'detached'});assert.equal(await page.locator('.grid-source-preview').inputValue(),beforeFailure,'Cancelling Apply preserves the prior grid and filter');
    // Unknown read outcomes do not force a mode switch; the user can choose cached rows explicitly.
    await page.route(/\/api\/dba\/grids\/[^/]+\/values$/,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({error:'Fixture values unavailable'})}),{times:1});
    await open();assert.match(await picker.getByRole('alert').innerText(),/Fixture values unavailable/);await picker.getByRole('checkbox',{name:'Read from server',exact:true}).uncheck();await ready();assert.equal(await picker.getByRole('checkbox',{name:'Select value-003',exact:true}).count(),1);await picker.getByRole('button',{name:'Cancel',exact:true}).click();
    await open(page.locator('#grid'),2);await choose('9007199254740993.125');await apply.click();await picker.waitFor({state:'detached'});
    assert.equal(await page.locator('#grid .grid-body .grid-row').count(),1,'Exact decimals survive filtering through the browser');
    assert.match(await page.locator('#grid .grid-body').innerText(),/9007199254740993.125/);
    // Actual Table Data and query-builder hosts use the same picker.
    await page.getByRole('button',{name:'Expand Value picker fixture',exact:true}).click();
    await page.getByRole('button',{name:'Expand Schemas',exact:true}).click();await page.getByRole('button',{name:'Expand PUBLIC',exact:true}).click();await page.getByRole('button',{name:'Expand Tables',exact:true}).click();
    await page.locator('.metadata-node[data-name="PICKER_ITEMS"] > .metadata-title > .metadata-name').dblclick();
    const tableGrid=page.locator('#table-document .data-grid-host');await tableGrid.locator('.grid-column-toggle').nth(1).waitFor();await page.waitForFunction(()=>document.querySelector('#table-document .data-grid-host')?.getAttribute('aria-busy')==='false');
    await open(tableGrid);await choose('value-001');await apply.click();await picker.waitFor({state:'detached'});assert.equal(await tableGrid.locator('.grid-body .grid-row').count(),2);
    await page.getByRole('tab',{name:'Diagram',exact:true}).click();await page.getByRole('button',{name:'New query from source',exact:true}).waitFor();await page.getByRole('button',{name:'New query from source',exact:true}).click();
    const builder=page.locator('.query-builder');await page.waitForFunction(()=>document.querySelector('.qb-toolbar [aria-label="Run query (Ctrl+Enter)"]')?.disabled===false);
    await builder.getByRole('button',{name:'Run query (Ctrl+Enter)',exact:true}).click();await page.waitForFunction(()=>document.querySelectorAll('.qb-results .grid-body .grid-row').length>0&&!document.querySelector('.grid-query-overlay'));
    await open(builder);await choose('value-002');await apply.click();await picker.waitFor({state:'detached'});assert.equal(await builder.locator('.grid-body .grid-row').count(),1);
    await open(builder);await picker.getByRole('button',{name:'Clear All',exact:true}).click();await choose('value-003');await apply.click();await picker.waitFor({state:'detached'});assert.equal(await builder.locator('.grid-body .grid-row').count(),1);assert.match(await builder.locator('.grid-body').innerText(),/value-003/);
    await open(builder);await page.setViewportSize({width:390,height:550});const bounds=await picker.boundingBox();assert.ok(bounds.x>=0&&bounds.x+bounds.width<=390&&bounds.y>=0&&bounds.y+bounds.height<=550);await page.keyboard.press('Escape');
    // Cached numeric comparisons never round through JavaScript Number.
    const exact=await page.evaluate(async()=>{const {retainedValues,decimalKey}=await import('/dba/grid-values.js');return {key:decimalKey('9007199254740993.125'),values:retainedValues({rows:[['9007199254740994.126'],['9007199254740993.125'],['1.0'],['1.00'],[null]]},{source:0,jdbcType:3})};});
    assert.equal(exact.key,'9007199254740993.125');assert.equal(exact.values.length,4);assert.equal(exact.values[1].count,2);assert.equal(exact.values[2].value,'9007199254740993.125');
    await page.evaluate(async()=>{
      const {VisualQueryBuilder}=await import('/dba/query-builder.js');
      const builder=Object.create(VisualQueryBuilder.prototype),expression={kind:'output',output:'name'},authored={kind:'in',arg:expression,args:[{kind:'literal',type:'text',value:'first'}],not:false};
      Object.assign(builder,{model:{mode:'detail',where:authored,summary:{having:null},detail:{outputs:[{id:'name',expression:{kind:'column',source:'t',name:'NAME'}}]}},history:[],future:[],revision:0,guardRows:async()=>true,gridOutput:()=>expression,edit:fn=>{fn();builder.revision++;},selectTab:()=>{},controller:{execute:async()=>{builder.resultRevision=builder.revision;}}});
      await builder.applyGridValues({columnIndex:0,jdbcType:12,values:['first']});
      await builder.applyGridValues({columnIndex:0,jdbcType:12,values:['second']});
      if(builder.model.where.args[0]!==authored||builder.model.where.args[1].args[0].value!=='second')throw Error('Picker removed an identical authored predicate');
    });
    const race=await context.newPage();race.on('pageerror',error=>errors.push(error.message));
    await race.route('**/dba/app.js',route=>route.fulfill({status:200,contentType:'application/javascript',body:''}));await race.goto(base+'/dba');
    await race.evaluate(async()=>{
      const {DataGridView,GridQueryController}=await import('/dba/data-grid.js');document.body.innerHTML='<div id="race-grid" style="height:400px"></div>';
      const state=window.valueRace={events:[],jobs:new Map(),next:0,hold:false};
      const api=async(path,method='GET',body)=>{
        state.events.push({path,method});
        if(path.endsWith('/values')){const id=String(++state.next);state.jobs.set(id,{cancelled:false});if(state.hold)await new Promise(resolve=>state.submit=resolve);return {id,state:'queued'};}
        const id=path.split('/')[2],job=state.jobs.get(id);
        if(path.endsWith('/cancel')){job.cancelled=true;return {};}
        if(method==='DELETE'){state.jobs.delete(id);return {};}
        return job.cancelled?{id,state:'complete',finished:1,result:{values:[{value:'STALE'}],hasMore:false,nextOffset:1}}:{id,state:'running',finished:0};
      };
      const result={columns:[{id:'c1',label:'VALUE',jdbcType:12}],rows:[['cached'],['other']],grid:{id:'fixture',revision:1,rowCeiling:1000,capabilities:{}}};
      const controller=new GridQueryController({api,run:async()=>true});
      state.view=new DataGridView(document.querySelector('#race-grid'),result,{sourceSql:'SELECT VALUE FROM T',controller,transform:change=>controller.execute(change)});
    });
    async function raceOpen(){await race.locator('.grid-column-toggle').click();await race.getByRole('menuitem',{name:'Filter by value',exact:true}).click();await race.locator('.grid-values-dialog').waitFor();}
    await raceOpen();await race.waitForFunction(()=>valueRace.jobs.size===1);
    await race.getByRole('checkbox',{name:'Read from server',exact:true}).uncheck();await race.getByRole('checkbox',{name:'Select cached',exact:true}).waitFor();
    await race.waitForFunction(()=>valueRace.jobs.size===0);assert.equal(await race.getByRole('checkbox',{name:'Select STALE',exact:true}).count(),0,'Cancelled server response cannot replace cached choices');
    await race.getByRole('checkbox',{name:'Read from server',exact:true}).check();await race.waitForFunction(()=>valueRace.jobs.size===1);
    await race.evaluate(()=>{valueRace.view.result.grid={...valueRace.view.result.grid,revision:2};valueRace.view.updateData('SELECT VALUE FROM T');});
    await race.locator('.grid-values-dialog').waitFor({state:'detached'});await race.waitForFunction(()=>valueRace.jobs.size===0);
    await race.evaluate(()=>valueRace.hold=true);await raceOpen();await race.waitForFunction(()=>typeof valueRace.submit==='function');
    await race.locator('.grid-values-dialog').getByRole('button',{name:'Cancel',exact:true}).click();await race.evaluate(()=>valueRace.submit());
    await race.waitForFunction(()=>valueRace.jobs.size===0);assert.equal(await race.locator('.grid-values-dialog').count(),0,'Late submissions are cancelled and released after closing');
    await race.evaluate(()=>valueRace.view.destroy());await race.close();
    assert.deepEqual(errors,[]);console.log('Value picker checks passed: Script, Table, builder, counts, paging, cached rows, typed values, layout, and failures.');
  }catch(error){
    console.error('PICKER DIAGNOSTICS',await page.evaluate(()=>({dialog:document.querySelector('.grid-values-dialog')?.innerText,list:(()=>{const e=document.querySelector('.grid-values-list');return e?{height:e.scrollHeight,client:e.clientHeight,top:e.scrollTop,rows:e.querySelectorAll('label').length,first:e.querySelector('label')?.dataset.index}:null;})()})),valueRequests.slice(-5),errors);
    await page.screenshot({path:'code-graph-dba/target/grid-value-picker-failure.png'});throw error;
  }finally{await context.close();}
};
