const assert=require('node:assert/strict');
module.exports=async(browser,base,jar)=>{
 const context=await browser.newContext({viewport:{width:1450,height:980}}),page=await context.newPage(),errors=[],writes=[];
 page.setDefaultTimeout(15000);
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/\/grids\/[^/]+\/apply$/.test(r.url()))writes.push(r.postDataJSON());});
 try{
  await page.goto(base+'/dba');await page.locator('#agent-approvals').waitFor({state:'attached'});
  await page.waitForFunction(()=>document.querySelector('#connection-count')?.textContent.includes('connection'));
  const profile=await page.evaluate(async jar=>{
   const s=await(await fetch('/api/dba/session')).json(),headers={'Content-Type':'application/json','X-Dba-CSRF':s.csrf};
   async function api(path,method='GET',body){const r=await fetch('/api/dba'+path,{method,headers,body:body?JSON.stringify(body):undefined});const data=await r.json();if(!r.ok)throw Error(data.error);return data;}
   await api('/settings','PUT',{uiRows:1000});
   const p=await api('/connections','POST',{name:'Find replace fixture',url:'jdbc:h2:mem:find_replace_browser;DB_CLOSE_DELAY=-1',jar,driverClass:'org.h2.Driver',username:'sa',saveUntested:true});
   const job=await api('/query/execute','POST',{connectionId:p.id,sql:"CREATE TABLE FIND_ITEMS(ID INT PRIMARY KEY, LABEL VARCHAR(80), NOTE VARCHAR(80), AMOUNT INT); INSERT INTO FIND_ITEMS VALUES(1,'Alpha alpha alphabet','alpha',11),(2,'ALPHA','word',22),(3,'beta','Alpha',33); INSERT INTO FIND_ITEMS SELECT X,'row-'||X,'note',X FROM SYSTEM_RANGE(4,251)",parameters:[]});
   for(let n=0;n<200;n++){const current=await api('/jobs/'+job.id);if(current.finished){if(current.state!=='complete')throw Error(current.error);await api('/jobs/'+job.id,'DELETE');return p;}await new Promise(r=>setTimeout(r,40));}throw Error('Fixture setup timed out');
  },jar);
  await page.reload();await page.locator('.connection-row[data-connection="'+profile.id+'"] .connection-select').click();await page.locator('#new-tab').click();
  await page.locator('#sql').fill('SELECT * FROM FIND_ITEMS');await page.locator('#run').click();await page.waitForFunction(()=>document.querySelector('.grid-row-status')?.textContent.includes('1–200'));
  const panel=page.locator('#grid .grid-find'),search=panel.getByRole('combobox',{name:'Find in loaded rows'}),replacement=panel.getByRole('combobox',{name:'Replacement text'}),status=panel.locator('.grid-find-status'),footer=page.locator('#grid .data-grid-footer');
  const settled=()=>page.waitForFunction(()=>document.querySelector('#grid .grid-find')?.getAttribute('aria-busy')==='false');
  async function find(text){await search.fill(text);await search.press('Enter');await settled();}
  async function toggle(name,on){const control=panel.getByRole('button',{name,exact:true});if((await control.getAttribute('aria-pressed'))!==String(on)){await control.click();await settled();}}
  await page.getByRole('button',{name:'Find and Replace',exact:true}).click();assert.equal(await panel.isVisible(),true);assert.equal(await replacement.isVisible(),false);
  await find('alpha');assert.match(await status.innerText(),/1 of 6 matches/);
  await toggle('Match case',true);assert.match(await status.innerText(),/of 3 matches/);
  await toggle('Match whole word',true);assert.match(await status.innerText(),/of 2 matches/);
  await toggle('Use regular expression',true);await find('^Alpha');assert.match(await status.innerText(),/of 2 matches/);
  await panel.getByRole('button',{name:'Next match',exact:true}).click();assert.match(await status.innerText(),/^2 of 2/);
  await panel.getByRole('button',{name:'Next match',exact:true}).click();assert.match(await status.innerText(),/^1 of 2/);
  await panel.getByRole('button',{name:'Previous match',exact:true}).click();assert.match(await status.innerText(),/^2 of 2/);
  await toggle('Use regular expression',false);await toggle('Match whole word',false);await toggle('Match case',false);await find('alpha');
  await page.locator('.grid-row[data-row-index="0"] .row-number').click();await page.locator('.grid-row[data-row-index="2"] .row-number').click({modifiers:['Control']});
  await toggle('Search selected rows only',true);assert.match(await status.innerText(),/of 5 matches/);
  await toggle('Show matching rows only',true);assert.deepEqual(await page.locator('#grid .grid-body .grid-row').evaluateAll(rows=>rows.map(r=>r.dataset.rowIndex)),['0','2']);
  await panel.getByRole('button',{name:'Show replacement controls',exact:true}).click();assert.equal(await replacement.isVisible(),true);
  const bounds=await page.evaluate(()=>{const s=document.querySelector('#grid .data-grid-scroll'),p=document.querySelector('#grid .grid-find'),a=s.getBoundingClientRect(),b=p.getBoundingClientRect();return{right:b.right,maxRight:a.left+s.clientWidth,bottom:b.bottom,maxBottom:a.top+s.clientHeight};});
  assert.ok(bounds.right<=bounds.maxRight-4&&bounds.bottom<=bounds.maxBottom-4,JSON.stringify(bounds));
  await page.screenshot({path:'code-graph-dba/target/grid-find-replace.png'});
  await replacement.fill('omega');await panel.getByRole('button',{name:'Replace current match',exact:true}).click();await settled();
  assert.equal(writes.length,0,'Replace never writes immediately');assert.equal(await footer.getByRole('button',{name:'Save',exact:true}).isEnabled(),true);assert.match(await status.innerText(),/staged/);
  await footer.getByRole('button',{name:'Cancel',exact:true}).click();await page.waitForTimeout(200);await settled();assert.equal(await footer.getByRole('button',{name:'Save',exact:true}).isDisabled(),true);
  await panel.getByRole('button',{name:'Close Find and Replace',exact:true}).click();assert.equal(await panel.isVisible(),false);assert.equal(await page.locator('.grid-find-match').count(),0);assert.ok(await page.locator('#grid .grid-body .grid-row').count()>3);
  await page.getByRole('button',{name:'Find and Replace',exact:true}).click();await settled();
  await panel.getByRole('button',{name:'Show replacement controls',exact:true}).click();
  await toggle('Use regular expression',true);await find('row-(\\d+)');assert.match(await status.innerText(),/197 matches/);
  await replacement.fill('item-$1');await panel.getByRole('button',{name:'Replace all matches',exact:true}).click();await settled();assert.match(await status.innerText(),/197 cells staged/);
  assert.equal(await footer.getByRole('button',{name:'Save',exact:true}).isEnabled(),true);assert.equal(writes.length,0);
  await footer.getByRole('button',{name:'Cancel',exact:true}).click();await page.waitForTimeout(200);await settled();assert.match(await status.innerText(),/197 matches/);
  await find('[');assert.match(await status.innerText(),/Invalid regular expression/);assert.equal(await footer.getByRole('button',{name:'Save',exact:true}).isDisabled(),true);
  await toggle('Use regular expression',false);
  for(let n=0;n<12;n++)await find('history-'+n);
  await panel.getByRole('button',{name:'Search history',exact:true}).click();assert.equal(await page.locator('.grid-find-history').getByRole('option').count(),10);assert.equal(await page.locator('.grid-find-history').getByRole('option').first().innerText(),'history-11');await search.press('ArrowDown');await search.press('Enter');await settled();assert.equal(await search.inputValue(),'history-10');
  await find('ALPHA');await toggle('Match case',true);await replacement.fill('OMEGA');await panel.getByRole('button',{name:'Replace all matches',exact:true}).click();await settled();assert.match(await status.innerText(),/1 cell staged/);
  await footer.getByRole('button',{name:'Save',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#grid')?.getAttribute('aria-busy')==='false'&&document.querySelector('#grid .data-grid-footer button[aria-label=Save]')?.disabled);
  assert.equal(writes.length,1,'Only Save commits the replacement');await find('OMEGA');assert.match(await status.innerText(),/1 of 1 matches/);
  // Histories and panel expansion survive view unmount/remount, but not unrelated grids.
  await page.locator('#new-tab').click();await page.locator('#tabs .tab').first().locator('button').first().click();
  await panel.waitFor({state:'visible'});await settled();assert.equal(await search.inputValue(),'OMEGA');assert.equal(await replacement.isVisible(),true);
  await panel.getByRole('button',{name:'Replacement history',exact:true}).click();assert.ok((await page.locator('.grid-find-history').getByRole('option').allTextContents()).includes('item-$1'));await search.press('Escape');assert.equal(await panel.isVisible(),true);
  await page.setViewportSize({width:650,height:650});await page.waitForTimeout(150);
  assert.equal(await panel.getByRole('button',{name:'Close Find and Replace',exact:true}).isVisible(),true);
  assert.equal(await panel.evaluate(e=>e.getBoundingClientRect().left>=e.parentElement.getBoundingClientRect().left),true);
  await panel.getByRole('button',{name:'Close Find and Replace',exact:true}).click();
  await page.setViewportSize({width:1250,height:900});
  // A non-Script host exercises reusable state, read-only cells and hostile patterns.
  await page.evaluate(async()=>{
   const {DataGridView,GridQueryController}=await import('/dba/data-grid.js');
   const NativeWorker=window.Worker;window.searchWorkers=0;window.searchStarts=0;
   window.Worker=class extends NativeWorker{constructor(...args){super(...args);window.searchWorkers++;window.searchStarts++;}postMessage(data){if(!window.holdSearchWork)super.postMessage(data);}terminate(){if(!this.stopped){this.stopped=true;window.searchWorkers--;}super.terminate();}};
   const host=document.createElement('div');host.id='search-fixture';Object.assign(host.style,{position:'fixed',inset:'100px 40px',display:'flex',zIndex:'50',background:'#101722'});document.body.append(host);
   const result={columns:[{id:'c1',label:'duplicate',jdbcType:12},{id:'c2',label:'number',jdbcType:4},{id:'c3',label:'duplicate',jdbcType:12}],rows:Array.from({length:250},(_,i)=>[i===0?'111':'value-'+i,i===0?111:i,'111']),grid:{capabilities:{edit:true,page:false},rowIds:Array.from({length:250},(_,i)=>'r'+i),columns:[{id:'c1',name:'Text',jdbcType:12,editable:true,size:80},{id:'c2',name:'Number',jdbcType:4,editable:true},{id:'c3',name:'Read only',jdbcType:12,editable:false}]}};
   result.rows[249][0]='a'.repeat(30000)+'!';const controller=new GridQueryController({run:async()=>{},api:async()=>{}});
   const view=new DataGridView(host,result,{sourceSql:'SELECT fixture',controller,refresh:controller.refresh});
   window.searchFixture={view,result,controller,NativeWorker};view.find.open();
  });
  const box=page.locator('#search-fixture'),bar=box.locator('.grid-find'),term=bar.getByRole('combobox',{name:'Find in loaded rows'}),replace=bar.getByRole('combobox',{name:'Replacement text'}),info=bar.locator('.grid-find-status');
  const done=()=>page.waitForFunction(()=>document.querySelector('#search-fixture .grid-find')?.getAttribute('aria-busy')==='false');
  async function localFind(text){await term.fill(text);await term.press('Enter');await done();}
  await bar.getByRole('button',{name:'Show replacement controls',exact:true}).click();
  await localFind('value-1');await bar.getByRole('button',{name:'Show matching rows only',exact:true}).click();
  const accepted=await page.evaluate(()=>{const v=searchFixture.view;window.searchRenders=0;const render=v.renderTable.bind(v);v.renderTable=(...args)=>{window.searchRenders++;return render(...args);};return{matches:JSON.stringify(v.find.matches),rows:JSON.stringify(v.searchRows),starts:window.searchStarts};});
  await term.fill('value-2');await term.pressSequentially('48',{delay:20});await page.waitForTimeout(250);
  assert.deepEqual(await page.evaluate(()=>({matches:JSON.stringify(searchFixture.view.find.matches),rows:JSON.stringify(searchFixture.view.searchRows),starts:window.searchStarts})),accepted,'Typing keeps applied matches, visible rows and worker starts unchanged');
  assert.equal(await page.evaluate(()=>window.searchRenders),0,'Typing must not rebuild the grid');assert.match(await info.innerText(),/Press Enter/);
  await term.fill('value-1');assert.equal(await bar.getByRole('button',{name:'Next match',exact:true}).isDisabled(),true,'Even retyping the accepted query waits for Enter');
  await term.fill('value-248');
  assert.equal(await bar.getByRole('button',{name:'Replace all matches',exact:true}).isDisabled(),true);
  await term.dispatchEvent('keydown',{key:'Enter',isComposing:true});assert.equal(await page.evaluate(()=>window.searchStarts),accepted.starts);
  await term.press('Enter');await done();assert.equal(await page.evaluate(()=>searchFixture.view.find.state.query),'value-248');assert.match(await info.innerText(),/1 of 1/);
  await term.fill('');assert.equal(await page.evaluate(()=>searchFixture.view.find.matches.length),1);await term.press('Enter');await done();assert.equal(await page.evaluate(()=>searchFixture.view.find.matches.length),0);
  await bar.getByRole('button',{name:'Search history',exact:true}).click();await page.locator('.grid-find-history').getByRole('option',{name:'value-1',exact:true}).click();await done();assert.equal(await page.evaluate(()=>searchFixture.view.find.state.query),'value-1','History selection explicitly submits');
  await bar.getByRole('button',{name:'Show matching rows only',exact:true}).click();
  await localFind('111');await replace.fill('invalid');await bar.getByRole('button',{name:'Replace all matches',exact:true}).click();await done();
  assert.match(await info.innerText(),/whole number/);assert.equal(await page.evaluate(()=>searchFixture.view.state.draft.dirty),false,'An invalid numeric replacement cannot partially edit text cells');
  await localFind('111');await replace.fill('222');await bar.getByRole('button',{name:'Replace all matches',exact:true}).click();await done();
  assert.match(await info.innerText(),/read-only matches skipped/);assert.equal(await page.evaluate(()=>searchFixture.view.state.draft.cell(0,{id:'c3',source:2}).value),'111');
  await box.locator('.data-grid-footer').getByRole('button',{name:'Cancel',exact:true}).click();await page.waitForTimeout(200);await done();
  await page.evaluate(()=>{searchFixture.view.state.hidden.add('c3');searchFixture.view.renderTable();});await localFind('111');assert.match(await info.innerText(),/of 4 matches/);
  await bar.getByRole('button',{name:'Search selected rows only',exact:true}).click();await done();assert.match(await info.innerText(),/0 of 0/);
  await bar.getByRole('button',{name:'Search selected rows only',exact:true}).click();await done();
  // Replacement histories are bounded independently and selecting one does not replace.
  await page.evaluate(()=>{searchFixture.view.state.draft.set(0,{id:'c1',source:0},{kind:'value',value:'token-0'});searchFixture.view.renderTable();});
  for(let n=0;n<12;n++){await localFind('token-'+n);await replace.fill('token-'+(n+1));await bar.getByRole('button',{name:'Replace all matches',exact:true}).click();await done();}
  await bar.getByRole('button',{name:'Replacement history',exact:true}).click();assert.equal(await page.locator('.grid-find-history').getByRole('option').count(),10);await page.locator('.grid-find-history').getByRole('option',{name:'token-5',exact:true}).click();assert.equal(await replace.inputValue(),'token-5');assert.equal(await page.evaluate(()=>searchFixture.view.state.draft.cell(0,{id:'c1',source:0}).value),'token-12');
  await page.evaluate(()=>{searchFixture.view.rowActions=false;searchFixture.view.find.update();});await localFind('token-12');assert.equal(await bar.getByRole('button',{name:'Replace all matches',exact:true}).isDisabled(),true);
  await page.evaluate(()=>{searchFixture.view.rowActions=true;searchFixture.view.find.update();});
  const priorDraft=await page.evaluate(()=>JSON.stringify(searchFixture.view.state.draft.payload()));
  await page.evaluate(()=>window.holdSearchWork=true);await replace.fill('obsolete');await bar.getByRole('button',{name:'Replace all matches',exact:true}).click();
  assert.equal(await bar.getAttribute('aria-busy'),'true');await replace.fill('new replacement');await done();
  assert.equal(await page.evaluate(()=>searchWorkers),0);assert.equal(await page.evaluate(()=>JSON.stringify(searchFixture.view.state.draft.payload())),priorDraft,'Changed replacement text cancels the old batch without staging it');
  await page.evaluate(()=>window.holdSearchWork=false);
  await bar.getByRole('button',{name:'Close Find and Replace',exact:true}).click();assert.equal(await page.evaluate(()=>searchFixture.view.state.draft.dirty),true,'Closing Find does not discard pending row edits');
  await box.getByRole('button',{name:'Find and Replace',exact:true}).click();await done();
  await bar.getByRole('button',{name:'Use regular expression',exact:true}).click();await done();
  await localFind('^value-(4|7)$');await bar.getByRole('button',{name:'Show matching rows only',exact:true}).click();
  await box.locator('[data-row-index="4"] .row-number').click();await box.locator('[data-row-index="7"] .row-number').click({modifiers:['Shift']});
  assert.deepEqual(await page.evaluate(()=>[...searchFixture.view.state.draft.selection]),[4,7],'Shift-select excludes locally hidden rows');
  await box.locator('[data-row-index="4"] [data-column-id=c2]').dblclick();await box.locator('.grid-cell-editor textarea').fill('44');await box.locator('.grid-cell-editor textarea').press('Tab');await page.waitForTimeout(250);
  assert.equal(await box.locator('.grid-cell-editor').locator('..').getAttribute('data-row-index'),'7','Tab editing follows the displayed row order');await box.locator('.grid-cell-editor textarea').press('Escape');
  await bar.getByRole('button',{name:'Show matching rows only',exact:true}).click();await localFind('^value-248$');
  const lastMatch=await box.locator('[data-row-index="248"] [data-column-id=c1]').boundingBox(),findBounds=await bar.boundingBox();
  assert.ok(lastMatch.y+lastMatch.height<=findBounds.y,'Virtualized matches scroll above the floating toolbar');
  // A pathological regex is terminated off-thread while the UI remains responsive.
  await term.fill('(a+)+$');await term.press('Enter');
  await page.evaluate(()=>{window.uiHeartbeat=false;setTimeout(()=>window.uiHeartbeat=true,40);});await page.waitForTimeout(100);assert.equal(await page.evaluate(()=>window.uiHeartbeat),true);
  await done();assert.match(await info.innerText(),/took too long/);assert.equal(await page.evaluate(()=>searchWorkers),0);
  await term.press('Enter');await bar.getByRole('button',{name:'Close Find and Replace',exact:true}).click();assert.equal(await page.evaluate(()=>searchWorkers),0);
  await page.evaluate(()=>{searchFixture.view.destroy();searchFixture.controller.dispose();window.Worker=searchFixture.NativeWorker;document.getElementById('search-fixture').remove();});
  assert.deepEqual(errors,[]);
  console.log('Grid Find/Replace passed: real H2 searches, regex/case/word/selected rows, navigation, local filtering, ten-entry history, staged replacements/Cancel/Save, remount, dark compact layout and scrollbar clearance.');
 }finally{await context.close();}
};
