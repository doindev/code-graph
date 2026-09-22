const assert=require('node:assert/strict');
module.exports=async(browser,base,jar,schema='')=>{
 const context=await browser.newContext({viewport:{width:1500,height:1100},acceptDownloads:true}),page=await context.newPage(),errors=[];let profile;
 page.on('pageerror',error=>errors.push(error.message));
 try{
  await page.goto(base+'/dba');await page.locator('#agent-approvals').waitFor({state:'attached'});
  profile=await page.evaluate(async jar=>{
   const session=await(await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json();
   const r=await fetch('/api/dba/connections',{method:'POST',headers:{'Content-Type':'application/json','X-Dba-CSRF':session.csrf},body:JSON.stringify({name:'View query fixture',url:'jdbc:h2:mem:view_query_'+Date.now()+';DB_CLOSE_DELAY=-1',jar,driverClass:'org.h2.Driver',username:'sa',saveUntested:true})});if(!r.ok)throw Error(await r.text());return r.json();
  },jar);
  async function sql(connectionId,sql){return page.evaluate(async({connectionId,sql})=>{
   const session=await(await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json(),headers={'Content-Type':'application/json','X-Dba-CSRF':session.csrf};
   const response=await fetch('/api/dba/query/execute',{method:'POST',headers,body:JSON.stringify({connectionId,sql,parameters:[],autoCommit:true})});if(!response.ok)throw Error(await response.text());const job=await response.json();
   for(let i=0;i<150;i++){const result=await(await fetch('/api/dba/jobs/'+job.id)).json();if(result.finished){await fetch('/api/dba/jobs/'+job.id,{method:'DELETE',headers});if(result.state!=='complete')throw Error(JSON.stringify(result));return result.result;}await new Promise(resolve=>setTimeout(resolve,100));}throw Error('SQL fixture timeout');
  },{connectionId,sql});}
  await sql(profile.id,"CREATE TABLE PUBLIC.PEOPLE(ID INT, NAME VARCHAR(30)); INSERT INTO PUBLIC.PEOPLE VALUES(1,'Ada'),(2,'Grace'),(3,'Linus'); CREATE VIEW PUBLIC.PEOPLE_VIEW AS SELECT ID, NAME FROM PUBLIC.PEOPLE");
  await page.reload();
  for(const name of ['View query fixture','Schemas','PUBLIC','Views'])await page.getByRole('button',{name:'Expand '+name,exact:true}).click();
  await page.locator('.metadata-node[data-name="PEOPLE_VIEW"] > .metadata-title > .metadata-name').dblclick();
  await page.waitForFunction(()=>document.querySelector('.object-properties')?.getAttribute('aria-busy')==='false');
  await page.getByRole('tab',{name:'Diagram',exact:true}).click();
  const builder=page.locator('.query-builder'),footer=builder.locator('.data-grid-footer'),save=footer.getByRole('button',{name:'Save',exact:true}),revert=footer.getByRole('button',{name:'Revert',exact:true}),preview=builder.locator('.grid-source-preview');
  const ready=()=>page.waitForFunction(()=>document.querySelector('.qb-toolbar [aria-label="Run query (Ctrl+Enter)"]')?.disabled===false&&!document.querySelector('.qb-toolbar').inert);
  await ready();const original=await preview.inputValue();assert.equal(await save.isDisabled(),true);assert.equal(await revert.isDisabled(),true);
  const noRowActions=async host=>{assert.equal(await host.count(),1,'Expected one view grid footer');for(const name of ['Edit','Add row','Delete'])assert.equal(await host.getByRole('button',{name,exact:true}).count(),0,'View grids hide '+name);};await noRowActions(footer);
  await builder.getByLabel('Distinct rows',{exact:true}).check();await ready();assert.equal(await save.isEnabled(),true);assert.equal(await revert.isEnabled(),true);
  await builder.getByRole('button',{name:'Rows: no filters',exact:true}).click();const filter=page.getByRole('dialog',{name:'Rows filters',exact:true});await filter.getByLabel('Column',{exact:true}).selectOption(await filter.getByLabel('Column',{exact:true}).evaluate(e=>[...e.options].find(o=>/\.ID \/ INTEGER$/.test(o.textContent)).value));await filter.getByLabel('Operator',{exact:true}).selectOption('>');await filter.getByLabel('Value type',{exact:true}).selectOption('integer');await filter.getByLabel('Value',{exact:true}).fill('1');await filter.getByLabel('Value',{exact:true}).press('Tab');await filter.getByRole('button',{name:'Apply',exact:true}).click();await filter.waitFor({state:'detached'});await ready();
  await page.waitForFunction(()=>document.querySelectorAll('.qb-results .grid-body .grid-row').length===2);
  await builder.locator('.grid-column-toggle').first().click();await page.getByRole('menuitem',{name:'Order by ID DESC',exact:true}).click();await ready();
  await page.waitForFunction(()=>document.querySelector('.grid-source-preview').value.includes('ORDER BY'));
  const changed=await preview.inputValue();assert.match(changed,/DISTINCT/);assert.match(changed,/WHERE/);assert.match(changed,/ORDER BY/);
  await page.getByRole('tab',{name:'Properties',exact:true}).click();assert.equal(await page.locator('.object-properties').getByRole('button',{name:'Save',exact:true}).isEnabled(),true);assert.equal(await page.locator('.object-properties').getByRole('button',{name:'Revert',exact:true}).isEnabled(),true);
  await page.getByRole('tab',{name:'Diagram',exact:true}).click();assert.equal(await preview.inputValue(),changed);
  await save.click();const review=page.getByRole('dialog');await review.getByRole('textbox',{name:'Reviewed object SQL'}).waitFor();assert.match(await review.getByRole('textbox',{name:'Reviewed object SQL'}).inputValue(),/CREATE OR REPLACE VIEW[\s\S]*DISTINCT[\s\S]*WHERE[\s\S]*ORDER BY/);await review.getByRole('button',{name:'Cancel',exact:true}).click();await ready();assert.equal(await save.isEnabled(),true);
  await revert.click();await ready();assert.equal(await preview.inputValue(),original);assert.equal(await builder.getByLabel('Distinct rows',{exact:true}).isChecked(),false);assert.equal(await save.isDisabled(),true);assert.equal(await revert.isDisabled(),true);assert.equal(await builder.getByRole('button',{name:'Rows: no filters',exact:true}).count(),1);
  // Rebuild the same changes through import, then save the actual object and reload its catalog definition.
  await builder.getByRole('button',{name:'Paste or import SQL',exact:true}).click();await page.getByLabel('SQL to import',{exact:true}).fill(changed);await page.getByRole('button',{name:'Import query',exact:true}).click();await page.getByRole('dialog',{name:'Import SQL',exact:true}).waitFor({state:'detached'});await ready();
  await save.click();await review.getByRole('textbox',{name:'Reviewed object SQL'}).waitFor();await review.getByRole('checkbox').check();await review.getByRole('button',{name:'Apply',exact:true}).click();await review.waitFor({state:'detached'});await page.waitForFunction(()=>document.querySelector('.qb-status')?.textContent.includes('Object changes saved'));await ready();assert.equal(await save.isDisabled(),true);assert.equal(await revert.isDisabled(),true);assert.match(await preview.inputValue(),/WHERE/);
  const rows=await sql(profile.id,'SELECT ID, NAME FROM PUBLIC.PEOPLE_VIEW ORDER BY ID DESC');assert.deepEqual(rows.results[0].rows.map(row=>row[1]),['Linus','Grace']);
  const saved=await preview.inputValue();await builder.getByLabel('Distinct rows',{exact:true}).uncheck();await ready();await builder.getByRole('button',{name:/^Remove rows filters/}).click();await ready();await revert.click();await ready();assert.equal(await preview.inputValue(),saved,'Revert returns to the last successful save');
  await page.getByRole('tab',{name:'Data',exact:true}).click();for(const name of ['Add row','Delete'])assert.equal(await page.locator('.table-panel .data-grid-footer').getByRole('button',{name,exact:true}).isDisabled(),true,'Unverified H2 view row writes stay disabled');
  await page.getByRole('tab',{name:'Diagram',exact:true}).click();await builder.getByLabel('Distinct rows',{exact:true}).uncheck();await ready();const unsaved=await preview.inputValue();await page.waitForTimeout(700);await page.reload();await ready();assert.equal(await preview.inputValue(),unsaved);assert.equal(await save.isEnabled(),true,'Restored changes remain dirty against the database view');await revert.click();await ready();assert.match(await preview.inputValue(),/DISTINCT/);assert.match(await preview.inputValue(),/WHERE/);
  // Database failures retain the working query and keep Revert available.
  await builder.getByLabel('Distinct rows',{exact:true}).uncheck();await ready();const failedDraft=await preview.inputValue();
  await page.route('**/api/dba/object-properties/prepare',route=>route.fulfill({status:409,contentType:'application/json',body:JSON.stringify({error:'Synthetic view save failure'})}));
  await save.click();await page.waitForFunction(()=>document.querySelector('.qb-status')?.textContent.includes('Synthetic view save failure'));await ready();assert.equal(await preview.inputValue(),failedDraft);assert.equal(await revert.isEnabled(),true);
  await page.unroute('**/api/dba/object-properties/prepare');await revert.click();await ready();
  await page.screenshot({path:'code-graph-dba/target/view-query-save.png'});
  if(schema){
   const pg=await page.evaluate(async()=>{const profiles=await(await fetch('/api/dba/connections')).json();return profiles.find(p=>p.name==='Browser PostgreSQL');});assert.ok(pg);
   await sql(pg.id,'CREATE TABLE '+schema+'.query_people(id int, name varchar(30)); INSERT INTO '+schema+".query_people VALUES(1,'Ada'),(2,'Grace'); CREATE VIEW "+schema+'.query_view AS SELECT id,name FROM '+schema+'.query_people; CREATE MATERIALIZED VIEW '+schema+'.query_materialized AS SELECT id,name FROM '+schema+'.query_people');
   await page.reload();const root=page.locator('[data-connection="'+pg.id+'"]');
   for(const name of ['Browser PostgreSQL','Databases','postgres','Schemas',schema])await root.getByRole('button',{name:'Expand '+name,exact:true}).click();
   const createWithDiagram=async(group,name)=>{
    await root.getByRole('button',{name:'Actions for '+group,exact:true}).click();await page.locator('.connection-menu').getByRole('menuitem',{name:'New',exact:true}).click();
    const properties=page.locator('.object-properties');await properties.getByRole('button',{name:'Save',exact:true}).waitFor();
    assert.equal(await page.getByRole('tab',{name:'Data',exact:true}).isDisabled(),true,group+' Data waits for creation');
    assert.equal(await page.getByRole('tab',{name:'Diagram',exact:true}).isEnabled(),true,group+' Diagram is available during creation');
    await properties.getByRole('textbox',{name:'Name',exact:true}).fill(name);
    if(group==='Materialized Views'){await properties.getByRole('tab',{name:'Refresh',exact:true}).click();assert.match(await properties.locator('.schedule-summary').innerText(),/PostgreSQL with pg_cron/);if(await properties.getByText('Setup required',{exact:true}).count()){assert.ok(await properties.getByText(/Install the matching server package|Create and grant the extension/).count());assert.equal(await properties.getByRole('button',{name:'Recheck capabilities',exact:true}).count(),1);}}
    await page.getByRole('tab',{name:'Properties',exact:true}).press('End');assert.equal(await page.getByRole('tab',{name:'Diagram',exact:true}).getAttribute('aria-selected'),'true');
    await builder.locator('.qb-empty').waitFor();assert.match(await builder.locator('.qb-empty').innerText(),/Drag tables and views here/);
    await builder.getByRole('button',{name:'Add source',exact:true}).click();const picker=page.getByRole('dialog',{name:'Add source',exact:true});
    await picker.getByLabel('Schema',{exact:true}).selectOption(schema);await picker.getByRole('button',{name:'query_people',exact:true}).click();await picker.waitFor({state:'detached'});await ready();
    const visualSql=await preview.inputValue();assert.match(visualSql,new RegExp('FROM "'+schema+'"\\."query_people"'));
    await page.getByRole('tab',{name:'Properties',exact:true}).click();await properties.getByRole('tab',{name:'Definition',exact:true}).click();
    const definition=properties.getByRole('textbox',{name:'SELECT query',exact:true}),compact=value=>value.replace(/\s+/g,' ').trim();assert.equal(compact(await definition.inputValue()),compact(visualSql),'Diagram SQL is the shared Definition draft');
    await definition.fill('SELECT id FROM "'+schema+'"."query_people" WHERE id > 1');await page.getByRole('tab',{name:'Diagram',exact:true}).click();await ready();assert.match(await preview.inputValue(),/WHERE .*"t1"\."id" > 1/);
    await save.click();await review.getByRole('textbox',{name:'Reviewed object SQL'}).waitFor();const reviewed=await review.getByRole('textbox',{name:'Reviewed object SQL'}).inputValue();assert.match(reviewed,new RegExp('CREATE '+(group==='Materialized Views'?'MATERIALIZED VIEW':'VIEW')+'[\\s\\S]*'+name+'[\\s\\S]*WHERE'));
    await review.getByRole('checkbox').check();await review.getByRole('button',{name:'Apply',exact:true}).click();await review.waitFor({state:'detached'});await page.waitForFunction(()=>document.querySelector('.qb-status')?.textContent.includes('Object changes saved'));
    assert.equal(await page.getByRole('tab',{name:'Data',exact:true}).isEnabled(),true);assert.equal((await sql(pg.id,'SELECT count(*) FROM '+schema+'.'+name)).results[0].rows[0][0],'1');
    await page.locator('#tabs .tab.active .close').click();
   };
   await createWithDiagram('Views','created_visual_view');
   await createWithDiagram('Materialized Views','created_visual_materialized');
   for(const [group,name]of [['Views','query_view'],['Materialized Views','query_materialized']]){
    await root.getByRole('button',{name:'Expand '+group,exact:true}).click();await root.locator('.metadata-node[data-name="'+name+'"] > .metadata-title > .metadata-name').dblclick();await page.waitForFunction(()=>document.querySelector('.object-properties')?.getAttribute('aria-busy')==='false');
    if(group==='Materialized Views'){const properties=page.locator('.object-properties');await properties.getByRole('tab',{name:'Refresh',exact:true}).click();assert.match(await properties.locator('.schedule-summary').innerText(),/PostgreSQL with pg_cron/);assert.match(await properties.innerText(),/Manual Refresh remains a separate action/);}
    await page.getByRole('tab',{name:'Diagram',exact:true}).click();await ready();await noRowActions(footer);await builder.getByLabel('Distinct rows',{exact:true}).check();await ready();assert.equal(await save.isEnabled(),true);assert.equal(await revert.isEnabled(),true);
    await save.click();await review.getByRole('textbox',{name:'Reviewed object SQL'}).waitFor();assert.match(await review.getByRole('textbox',{name:'Reviewed object SQL'}).inputValue(),/SELECT DISTINCT/);await review.getByRole('checkbox').check();await review.getByRole('button',{name:'Apply',exact:true}).click();await review.waitFor({state:'detached'});await page.waitForFunction(()=>document.querySelector('.qb-status')?.textContent.includes('Object changes saved'));await ready();assert.equal(await save.isDisabled(),true);
    const definition=await sql(pg.id,"SELECT pg_get_viewdef('"+schema+'.'+name+"'::regclass, true)");assert.match(definition.results[0].rows[0][0],/SELECT DISTINCT/);
    await builder.getByLabel('Distinct rows',{exact:true}).uncheck();await ready();await revert.click();await ready();assert.equal(await builder.getByLabel('Distinct rows',{exact:true}).isChecked(),true);
    await page.getByRole('tab',{name:'Data',exact:true}).click();for(const name of ['Add row','Delete'])assert.equal(await page.locator('.table-panel .data-grid-footer').getByRole('button',{name,exact:true}).isDisabled(),true,'Unverified H2 view row writes stay disabled');
   }
   // Reproduce a SELECT * view extended with another table that has exactly the same column names.
   await sql(pg.id,'CREATE TABLE '+schema+'.query_users(id int, name varchar(30), signup_date date); CREATE TABLE '+schema+'.query_users2(id int, name varchar(30), signup_date date); INSERT INTO '+schema+".query_users VALUES(1,'Ada',DATE '2026-01-01'),(2,'Grace',DATE '2026-02-01'); INSERT INTO "+schema+".query_users2 VALUES(1,'Ada2',DATE '2026-03-01'),(2,'Grace2',DATE '2026-04-01'); CREATE VIEW "+schema+'.query_join AS SELECT * FROM '+schema+'.query_users');
   await page.reload();
   for(const name of ['Browser PostgreSQL','Databases','postgres','Schemas',schema,'Views'])await root.getByRole('button',{name:'Expand '+name,exact:true}).click();
   await root.locator('.metadata-node[data-name="query_join"] > .metadata-title > .metadata-name').dblclick();await page.waitForFunction(()=>document.querySelector('.object-properties')?.getAttribute('aria-busy')==='false');await page.getByRole('tab',{name:'Diagram',exact:true}).click();await ready();
   const originalJoin=await preview.inputValue();let executions=0;page.on('request',request=>{if(request.url().endsWith('/query/execute'))executions++;});
   await root.getByRole('button',{name:'Expand Tables',exact:true}).click();await root.locator('.metadata-node[data-name="query_users2"] > .metadata-title > .metadata-name').dragTo(builder.locator('.qb-canvas'),{targetPosition:{x:350,y:150}});await builder.locator('.qb-source').nth(1).waitFor();
   await builder.getByRole('button',{name:'Connect t2.id',exact:true}).dragTo(builder.getByRole('button',{name:'Connect t1.id',exact:true}));await page.getByRole('dialog',{name:'Edit join',exact:true}).getByRole('button',{name:'Apply',exact:true}).click();await ready();
   const joined=await preview.inputValue();for(const name of ['id','name','signup_date'])assert.ok(joined.includes('"t2"."'+name+'" AS "t2_'+name+'"'),joined);assert.match(joined,/SELECT "t1"\."id",/);assert.equal(executions,0,'Automatic aliases do not fetch rows');
   assert.equal(await builder.getByLabel('Alias for output 4',{exact:true}).getAttribute('placeholder'),'t2_id');
   let prepares=0;page.on('request',request=>{if(request.url().endsWith('/object-properties/prepare'))prepares++;});
   for(const index of [1,4]){await builder.getByLabel('Alias for output '+index,{exact:true}).fill('id');await builder.getByLabel('Alias for output '+index,{exact:true}).press('Tab');}
   await page.waitForFunction(()=>document.querySelector('.qb-status')?.textContent.includes('unique alias in Query Output'));await save.click();await page.waitForFunction(()=>!document.querySelector('.qb-toolbar').inert);assert.equal(prepares,0,'Repeated explicit aliases are diagnosed before database review');
   await builder.getByRole('button',{name:'Undo',exact:true}).click();await builder.getByRole('button',{name:'Undo',exact:true}).click();await ready();assert.equal(await preview.inputValue(),joined);
   await save.click();await review.getByRole('textbox',{name:'Reviewed object SQL'}).waitFor();assert.ok((await review.getByRole('textbox',{name:'Reviewed object SQL'}).inputValue()).replace(/[\r\n]/g,' ').includes(joined));await review.getByRole('button',{name:'Cancel',exact:true}).click();await ready();await revert.click();await ready();assert.equal(await preview.inputValue(),originalJoin);
   // Import the original failing SELECT and confirm the same aliases also repair existing drafts.
   await builder.getByRole('button',{name:'Paste or import SQL',exact:true}).click();await page.getByLabel('SQL to import',{exact:true}).fill(joined.replace(/ AS "t2_(id|name|signup_date)"/g,''));await page.getByRole('button',{name:'Import query',exact:true}).click();await page.getByRole('dialog',{name:'Import SQL',exact:true}).waitFor({state:'detached'});await ready();assert.equal(await preview.inputValue(),joined);
   await builder.getByLabel('Alias for output 5',{exact:true}).fill('other_name');await builder.getByLabel('Alias for output 5',{exact:true}).press('Tab');await ready();assert.match(await preview.inputValue(),/AS "other_name"/);await page.waitForTimeout(700);await page.reload();await ready();assert.match(await preview.inputValue(),/AS "t2_id"/);assert.match(await preview.inputValue(),/AS "other_name"/);assert.equal(await save.isEnabled(),true);
   await builder.getByRole('button',{name:'Run query (Ctrl+Enter)',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.qb-results .grid-body')?.textContent.includes('Grace2'));await ready();
   await builder.locator('.grid-column-toggle').nth(3).click();await page.getByRole('menuitem',{name:'Order by t2_id DESC',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.grid-source-preview').value.includes('ORDER BY'));await ready();const saveSql=await preview.inputValue();
   await save.click();await review.getByRole('textbox',{name:'Reviewed object SQL'}).waitFor();assert.ok((await review.getByRole('textbox',{name:'Reviewed object SQL'}).inputValue()).replace(/[\r\n]/g,' ').includes(saveSql));await review.getByRole('checkbox').check();await review.getByRole('button',{name:'Apply',exact:true}).click();await review.waitFor({state:'detached'});await page.waitForFunction(()=>document.querySelector('.qb-status')?.textContent.includes('Object changes saved'));await ready();assert.equal(await save.isDisabled(),true);
   const joinedRows=(await sql(pg.id,'SELECT * FROM '+schema+'.query_join ORDER BY id')).results[0];assert.deepEqual(joinedRows.columns.map(c=>c.name),['id','name','signup_date','t2_id','other_name','t2_signup_date']);assert.equal(joinedRows.rows[1][4],'Grace2');
   await builder.getByLabel('Distinct rows',{exact:true}).check();await ready();await revert.click();await ready();assert.equal(await preview.inputValue(),saveSql,'Revert preserves the saved unique aliases and grid sorting');
   await page.reload();await ready();assert.equal(await save.isDisabled(),true);assert.match(await preview.inputValue(),/AS "t2_id"/);
   // A baseline must not hide an independently changed database definition.
   await sql(pg.id,'CREATE OR REPLACE VIEW '+schema+'.query_join AS '+saveSql.replace(/^SELECT /,'SELECT DISTINCT '));
   await page.reload();await ready();assert.equal(await save.isEnabled(),true,'A changed database fingerprint invalidates the saved draft baseline');await revert.click();await ready();assert.equal(await builder.getByLabel('Distinct rows',{exact:true}).isChecked(),true);assert.equal(await save.isDisabled(),true);
   await page.screenshot({path:'code-graph-dba/target/view-join-aliases.png'});
   console.log('Live PostgreSQL duplicate-column join: automatic aliases, override, review, save, import, workspace restore, grid sort and Revert passed.');
   console.log('Live PostgreSQL view and materialized-view saves, saved baselines, and hidden row controls passed.');
  }

  assert.deepEqual(errors,[]);console.log('View query: canvas/grid dirty state, shared Save/Revert, reviewed database update, cancel/revert, saved baseline, workspace recovery, and hidden row actions passed.');
 }catch(error){console.error('VIEW_PAGE_ERRORS',errors);if(await page.getByRole('textbox',{name:'Reviewed object SQL'}).count())console.error(await page.getByRole('textbox',{name:'Reviewed object SQL'}).inputValue());console.error(await page.locator('.qb-status').textContent().catch(()=>''));await page.screenshot({path:'code-graph-dba/target/view-query-failure.png'});throw error;}
 finally{if(profile)await page.evaluate(async id=>{const s=await(await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json();await fetch('/api/dba/connections/'+id,{method:'DELETE',headers:{'X-Dba-CSRF':s.csrf}});},profile.id).catch(()=>{});await context.close();}
};
