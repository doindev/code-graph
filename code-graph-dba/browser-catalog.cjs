const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
 const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[],calls=[];
 page.on('pageerror',e=>errors.push(e.message));
 page.on('request',request=>{if(request.url().endsWith('/api/dba/catalog'))calls.push(request.postDataJSON());});
 try{
  await page.goto(base+'/dba');await page.locator('#cached-catalogs').waitFor({state:'attached'});
  await page.locator('#workspace-settings').click();await page.locator('#cached-catalogs').click();
  const dialog=page.locator('#catalog-dialog');await dialog.waitFor();
  assert.equal(calls.length,0,'Opening discovery must not connect or scan');
  assert.equal(await dialog.getByRole('button',{name:'Refresh catalog',exact:true}).isDisabled(),true);
  const workspace=await page.evaluate(async()=>await(await fetch('/api/dba/project-context')).json());
  assert.equal(workspace.projects.length,0);assert.equal(workspace.bindings.length,0);
  await dialog.locator('#catalog-database').fill('CONTEXT-DB');await dialog.locator('#catalog-schema').fill('PUBLIC');
  await dialog.getByRole('button',{name:'Status',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#catalog-status').textContent.includes('never_scanned'));
  assert.equal(calls.at(-1).operation,'scan_status');
  await dialog.getByRole('button',{name:'Refresh catalog',exact:true}).click();
  await page.waitForFunction(()=>/Generation 1/.test(document.querySelector('#catalog-status').textContent)&&!document.querySelector('#catalog-database').disabled);
  assert.ok(calls.some(call=>call.operation==='refresh_catalog'&&call.database==='CONTEXT-DB'&&call.schema==='PUBLIC'));
  await dialog.getByLabel('Search cached objects').fill('ITEMS');await dialog.getByRole('button',{name:'Search snapshot',exact:true}).click();
  const object=dialog.locator('#catalog-results button').filter({hasText:'PUBLIC.ITEMS'});await object.waitFor();
  await object.click();await dialog.getByLabel('Cached object DDL').waitFor();
  assert.match(await dialog.getByLabel('Cached object DDL').inputValue(),/CREATE TABLE.*ITEMS/s);
  assert.equal(await dialog.getByLabel('Cached object DDL').getAttribute('readonly'),'');
  await dialog.getByRole('button',{name:'Close',exact:true}).click();
  assert.equal(await dialog.isVisible(),false);await page.waitForFunction(()=>document.activeElement.id==='workspace-settings');
  assert.deepEqual(errors,[]);
  console.log('Standalone catalog browser checks passed: no opening scan, no project required, exact target, status, refresh, search, DDL and focus return.');
 }finally{await page.close();}
};
