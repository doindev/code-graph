const assert=require('node:assert/strict');
module.exports=async(browser,base,jar,schema='')=>{
 const context=await browser.newContext({viewport:{width:1500,height:1100},acceptDownloads:true}),page=await context.newPage(),errors=[];let profile;
 page.on('pageerror',error=>errors.push(error.message));
 try{
  await page.goto(base+'/dba');
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
  await page.getByRole('tab',{name:'Data',exact:true}).click();await noRowActions(page.locator('.table-panel .data-grid-footer'));
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
   for(const [group,name]of [['Views','query_view'],['Materialized Views','query_materialized']]){
    await root.getByRole('button',{name:'Expand '+group,exact:true}).click();await root.locator('.metadata-node[data-name="'+name+'"] > .metadata-title > .metadata-name').dblclick();await page.waitForFunction(()=>document.querySelector('.object-properties')?.getAttribute('aria-busy')==='false');
    await page.getByRole('tab',{name:'Diagram',exact:true}).click();await ready();await noRowActions(footer);await builder.getByLabel('Distinct rows',{exact:true}).check();await ready();assert.equal(await save.isEnabled(),true);assert.equal(await revert.isEnabled(),true);
    await save.click();await review.getByRole('textbox',{name:'Reviewed object SQL'}).waitFor();assert.match(await review.getByRole('textbox',{name:'Reviewed object SQL'}).inputValue(),/SELECT DISTINCT/);await review.getByRole('checkbox').check();await review.getByRole('button',{name:'Apply',exact:true}).click();await review.waitFor({state:'detached'});await page.waitForFunction(()=>document.querySelector('.qb-status')?.textContent.includes('Object changes saved'));await ready();assert.equal(await save.isDisabled(),true);
    const definition=await sql(pg.id,"SELECT pg_get_viewdef('"+schema+'.'+name+"'::regclass, true)");assert.match(definition.results[0].rows[0][0],/SELECT DISTINCT/);
    await builder.getByLabel('Distinct rows',{exact:true}).uncheck();await ready();await revert.click();await ready();assert.equal(await builder.getByLabel('Distinct rows',{exact:true}).isChecked(),true);
    await page.getByRole('tab',{name:'Data',exact:true}).click();await noRowActions(page.locator('.table-panel .data-grid-footer'));
   }
   console.log('Live PostgreSQL view and materialized-view saves, saved baselines, and hidden row controls passed.');
  }

  assert.deepEqual(errors,[]);console.log('View query: canvas/grid dirty state, shared Save/Revert, reviewed database update, cancel/revert, saved baseline, workspace recovery, and hidden row actions passed.');
 }catch(error){console.error('VIEW_PAGE_ERRORS',errors);console.error(await page.locator('.qb-status').textContent().catch(()=>''));await page.screenshot({path:'code-graph-dba/target/view-query-failure.png'});throw error;}
 finally{if(profile)await page.evaluate(async id=>{const s=await(await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json();await fetch('/api/dba/connections/'+id,{method:'DELETE',headers:{'X-Dba-CSRF':s.csrf}});},profile.id).catch(()=>{});await context.close();}
};
