const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
module.exports=async(browser,base)=>{
 const context=await browser.newContext({viewport:{width:1440,height:960},acceptDownloads:true}),page=await context.newPage(),errors=[],requests=[];
 page.setDefaultTimeout(90000);page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(r.url().includes('/api/dba/'))requests.push([r.method(),r.url()]);});
 try{
  await page.goto(base+'/dba');await page.locator('#agent-approvals').waitFor({state:'attached'});await page.getByRole('button',{name:'Compare',exact:true}).click();
  const wizard=page.getByRole('region',{name:'Database Compare'}),owners={};
  for(const side of ['source','destination']){
   const select=wizard.getByLabel(side+' connection',{exact:true}),label=(await select.locator('option').allTextContents()).find(x=>x.startsWith('Oracle '+side+' / '));assert.ok(label);
   owners[side]=label.split(' / ')[1];await select.selectOption({label});
   await page.waitForFunction(([side,owner])=>[...document.querySelector(`[aria-label="${side} schema"]`).options].some(o=>o.value===owner),[side,owners[side]]);
   await wizard.getByLabel(side+' schema',{exact:true}).selectOption(owners[side]);
  }
  await wizard.getByRole('button',{name:'Choose object types',exact:true}).click();await wizard.getByRole('heading',{name:'Object types',exact:true}).waitFor();
  assert.equal(await wizard.getByLabel('Default table data mode',{exact:true}).inputValue(),'none');
  await wizard.getByRole('button',{name:'Clear all',exact:true}).click();for(const name of ['Tables','Views','Sequences'])await wizard.getByRole('checkbox',{name,exact:true}).check();
  await wizard.getByRole('checkbox',{name:'Sync sequence values',exact:true}).check();await wizard.getByRole('button',{name:'Compare',exact:true}).click();
  await wizard.getByRole('button',{name:owners.source+'.ITEMS',exact:true}).click();await wizard.getByRole('tab',{name:'Source / Destination',exact:true}).click();
  assert.match(await wizard.locator('.compare-code-pair').innerText(),/CREATE TABLE/);await wizard.getByRole('button',{name:'Expand',exact:true}).click();
  const box=await wizard.boundingBox();assert.ok(box.width>1300&&box.height>850,JSON.stringify(box));
  await page.screenshot({path:'code-graph-dba/target/oracle-compare-review.png'});
  await wizard.getByRole('button',{name:'Generate script',exact:true}).click();const sql=wizard.getByRole('textbox',{name:'Generated destination SQL',exact:true});await sql.waitFor();
  const script=await sql.inputValue();assert.match(script,/RESTART START WITH 13/);assert.ok(script.includes('"'+owners.destination+'"."ITEMS"'));assert.doesNotMatch(script,/^(INSERT INTO|UPDATE |DELETE FROM)/m);
  const downloadEvent=page.waitForEvent('download');await wizard.getByRole('button',{name:'Save',exact:true}).click();const downloaded=await downloadEvent;assert.equal(await fs.readFile(await downloaded.path(),'utf8'),script);
  await context.grantPermissions(['clipboard-read','clipboard-write'],{origin:base});await wizard.getByRole('button',{name:'Copy',exact:true}).click();await page.waitForFunction(expected=>navigator.clipboard.readText().then(value=>value===expected),script);
  const url=requests.filter(([method,url])=>method==='GET'&&url.endsWith('/download')).at(-1)[1];await page.screenshot({path:'code-graph-dba/target/oracle-compare-script.png'});
  await wizard.getByRole('button',{name:'Cancel',exact:true}).click();await wizard.waitFor({state:'detached'});assert.equal((await context.request.get(url)).status(),403);
  assert.equal(requests.filter(([method,url])=>method==='POST'&&url.endsWith('/query/execute')).length,0);assert.deepEqual(errors,[]);
  console.log('ORACLE_COMPARE_BROWSER_VERIFIED automatic tests, native review, structure-only, independent sequence sync, full viewport, complete copy/save and cancellation disposal');
 }catch(error){await page.screenshot({path:'code-graph-dba/target/oracle-compare-failure.png'});console.error(await page.locator('body').innerText());throw error;}finally{await context.close();}
};
