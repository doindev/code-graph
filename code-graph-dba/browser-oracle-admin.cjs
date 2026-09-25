const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
 const context=await browser.newContext({viewport:{width:1440,height:960}}),page=await context.newPage(),errors=[];
 page.on('pageerror',error=>errors.push(error.message));let created=false;const user='CG_BRA_'+Date.now(),password='BrowserFixture1_'+Date.now();
 const api=(path,method='GET',body)=>page.evaluate(async({path,method,body})=>{const session=await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}).then(r=>r.json());const r=await fetch('/api/dba'+path,{method,headers:{'Content-Type':'application/json','X-Dba-CSRF':session.csrf},body:body===undefined?undefined:JSON.stringify(body)});if(!r.ok)throw Error(await r.text());return r.json();},{path,method,body});
 try{
  await page.goto(base+'/dba');await page.locator('#agent-approvals').waitFor({state:'attached'});await page.locator('#workspace-settings').click();await page.getByRole('menuitem',{name:'Oracle Administration',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Oracle Administration',exact:true}),idle=()=>page.waitForFunction(()=>document.querySelector('#oracle-admin-dialog')?.getAttribute('aria-busy')==='false');
  await dialog.getByRole('button',{name:'Connect',exact:true}).click();await idle();assert.equal(await dialog.locator('[role=alert]').innerText(),'');assert.match(await dialog.locator('.oracle-admin-target').innerText(),/FREEPDB1/);assert.ok(await dialog.locator('tbody tr').count()>0);
  const box=await dialog.boundingBox();assert.ok(box.width>1400&&box.height>920);assert.ok(box.x>=0&&box.y>=0);
  await dialog.getByLabel('Administration action',{exact:true}).selectOption('create_user');await dialog.getByLabel('Name',{exact:true}).fill(user);
  await dialog.getByRole('button',{name:'Review operation',exact:true}).click();await idle();assert.equal(await dialog.locator('[role=alert]').innerText(),'');assert.match(await dialog.getByLabel('Reviewed administration SQL',{exact:true}).inputValue(),new RegExp('CREATE USER "'+user+'"'));
  assert.equal(await dialog.getByRole('button',{name:'Apply reviewed operation',exact:true}).isDisabled(),true);
  await dialog.getByLabel('Name',{exact:true}).fill(user+'_CHANGED');await page.waitForFunction(()=>document.querySelector('.oracle-admin-review').hidden);await dialog.getByLabel('Name',{exact:true}).fill(user);
  let prepared=page.waitForResponse(r=>r.url().endsWith('/oracle/admin/prepare'));await dialog.getByRole('button',{name:'Review operation',exact:true}).click();const planId=(await (await prepared).json()).id;await idle();
  await dialog.getByLabel('New account password',{exact:true}).fill(password);await dialog.getByRole('checkbox',{name:/I reviewed the target/}).check();
  let applyRequest=page.waitForRequest(r=>r.url().endsWith('/oracle/admin/apply'));await dialog.getByRole('button',{name:'Apply reviewed operation',exact:true}).click();assert.equal((await applyRequest).postDataJSON().password,password);await idle();
  assert.equal(await dialog.locator('[role=alert]').innerText(),'');assert.match(await dialog.locator('.oracle-admin-last').innerText(),/success/);assert.ok(!(await dialog.innerText()).includes(password));assert.equal(await dialog.getByLabel('New account password',{exact:true}).inputValue(),'');created=true;
  await dialog.getByLabel('Catalog filter',{exact:true}).fill(user);await dialog.getByRole('button',{name:'Refresh',exact:true}).click();await idle();assert.equal(await dialog.locator('tbody tr').count(),1);assert.match(await dialog.locator('tbody').innerText(),new RegExp(user));
  await dialog.getByLabel('Administration action',{exact:true}).selectOption('user_lock');await dialog.locator('tbody tr').click();assert.equal(await dialog.getByLabel('Name',{exact:true}).inputValue(),user);
  await dialog.getByRole('button',{name:'Review operation',exact:true}).click();await idle();await dialog.getByRole('checkbox',{name:/I reviewed the target/}).check();await dialog.getByRole('button',{name:'Apply reviewed operation',exact:true}).click();await idle();assert.match(await dialog.locator('.oracle-admin-last').innerText(),/success/);
  await page.screenshot({path:'code-graph-dba/target/oracle-administration.png'});
  await dialog.getByLabel('Administration action',{exact:true}).selectOption('drop_user');await dialog.getByLabel('Name',{exact:true}).fill(user);await dialog.getByRole('button',{name:'Review operation',exact:true}).click();await idle();await dialog.getByRole('checkbox',{name:/I reviewed the target/}).check();await dialog.getByRole('button',{name:'Apply reviewed operation',exact:true}).click();await idle();assert.equal(await dialog.locator('[role=alert]').innerText(),'');created=false;
  await dialog.getByRole('button',{name:'Sessions',exact:true}).click();await idle();assert.ok(await dialog.locator('tbody tr').count()>0);
  await page.setViewportSize({width:600,height:740});const small=await dialog.boundingBox();assert.ok(small.width<=600&&small.height<=740);await page.screenshot({path:'code-graph-dba/target/oracle-administration-small.png'});
  await dialog.getByRole('button',{name:'Close',exact:true}).click();await dialog.waitFor({state:'hidden'});
  assert.deepEqual(errors,[]);console.log('Oracle administration browser checks passed');
 }finally{
  if(created){const connections=await api('/connections');const p=connections.find(p=>p.name==='Browser Oracle');const review=await api('/oracle/admin/prepare','POST',{connectionId:p.id,action:'drop_user',name:user,cascade:true});for(let i=0;i<50;i++){const state=await api('/jobs/'+review.id);if(state.finished){if(state.state==='complete')await api('/oracle/admin/apply','POST',{planId:review.id,confirmed:true});break;}await new Promise(r=>setTimeout(r,200));}}
  await context.close();
 }
};
