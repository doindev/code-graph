const assert=require('node:assert/strict');
module.exports=async(browser,base,jar)=>{
 const context=await browser.newContext(),setup=await context.newPage(),errors=[];
 let agent;
 try{
  await setup.goto(base+'/dba');await setup.locator('#agent-approvals').waitFor({state:'attached'});
  agent=await setup.evaluate(async()=>{const s=await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}).then(r=>r.json());return fetch('/api/dba/agents',{method:'POST',headers:{'Content-Type':'application/json','X-Dba-CSRF':s.csrf},body:JSON.stringify({name:'Temporary review QA',grants:[]})}).then(r=>r.json());});
 }finally{await context.close();}
 const call=async(operation,args)=>{const r=await fetch(base+'/__test/agent',{method:'POST',headers:{'Content-Type':'application/json','X-Test-Agent':agent.token},body:JSON.stringify({operation,args})});assert.equal(r.status,200);return r.json();};
 const request=await call('dba_request_connection_create',{requestId:'temporary-create',purpose:'Validate restricted review site',profile:{name:'Temporary review draft',templateId:'h2',driverClass:'org.h2.Driver',jar,url:'jdbc:h2:mem:temporary_review',username:'sa',password:''}});
 const handoff=await fetch(base+'/__test/review',{method:'POST',headers:{'Content-Type':'application/json','X-Test-Agent':agent.token},body:JSON.stringify({id:request.id})}).then(r=>r.json());
 const isolated=await browser.newContext(),page=await isolated.newPage();page.on('pageerror',e=>errors.push(e.message));page.on('dialog',dialog=>dialog.accept());
 try{
  await page.goto(handoff.url);const dialog=page.locator('#agent-approval-dialog');await dialog.waitFor();
  assert.equal(new URL(page.url()).hash,'','Single-use token must immediately leave browser history');
  assert.equal(await page.evaluate(()=>fetch('/api/dba/connections').then(r=>r.status)),403);
  await page.reload();await page.getByRole('button',{name:'Review / edit proposal',exact:true}).waitFor({timeout:35000});
  await page.getByRole('button',{name:'Review / edit proposal',exact:true}).click();
  const editor=page.locator('#connection-editor');await editor.locator('#ce-name').fill('Isolated reviewed connection');
  await editor.getByRole('button',{name:'Test',exact:true}).click();await page.locator('#connection-test-result').waitFor({timeout:15000});assert.match(await page.locator('#connection-test-result').innerText(),/Connection successful/);
  await page.locator('#test-result-close').click();await editor.getByRole('button',{name:'Save',exact:true}).click();await dialog.waitFor();
  assert.equal(await dialog.getByRole('checkbox').count(),0);assert.equal(await dialog.getByRole('button',{name:'Apply after successful test',exact:true}).isEnabled(),true);
  await dialog.getByRole('button',{name:'Apply after successful test',exact:true}).click();
  let result;for(let n=0;n<50;n++){result=await call('dba_request_status',{requestId:request.id});if(result.state==='complete')break;await page.waitForTimeout(100);}
  assert.equal(result.state,'complete',JSON.stringify(result));assert.equal(result.result.name,'Isolated reviewed connection');assert.deepEqual(errors,[]);
  console.log('Approval-only site passed: one-use fragment removal, isolated APIs, cookie reload, full editor/test/review/apply.');
 }finally{await isolated.close();}
};
