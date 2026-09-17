const assert=require('node:assert/strict');
module.exports=async(browser,base,jar)=>{
 const context=await browser.newContext(),a=await context.newPage(),b=await context.newPage(),errors=[];
 for(const page of [a,b]){page.on('pageerror',e=>errors.push(e.message));page.on('dialog',dialog=>dialog.accept());}
 const api=async(page,path,method='GET',body)=>page.evaluate(async({path,method,body})=>{
   const session=await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}).then(r=>r.json());
   const response=await fetch('/api/dba'+path,{method,headers:{'Content-Type':'application/json','X-Dba-CSRF':session.csrf},body:body===undefined?undefined:JSON.stringify(body)});
   return {status:response.status,data:await response.json()};
 },{path,method,body});
 try{
  await a.goto(base+'/dba');await a.locator('#agent-approvals').waitFor();
  const agent=await api(a,'/agents','POST',{name:'Approval routing QA',grants:[]});assert.equal(agent.status,201);
  const profiles=(await api(a,'/connections')).data,profile=profiles[0];
  const call=async(operation,args)=>{const response=await fetch(base+'/__test/agent',{method:'POST',headers:{'Content-Type':'application/json','X-Test-Agent':agent.data.token},body:JSON.stringify({operation,args})});const result=await response.json();assert.equal(response.status,200,JSON.stringify(result));return result;};
  await b.goto(base+'/dba');await b.locator('#agent-approvals').waitFor();await b.bringToFront();await b.evaluate(()=>window.dispatchEvent(new Event('focus')));
  const pending=await call('dba_get_connection_details',{requestId:'inspect-routing',purpose:'Inspect non-secret fixture settings',connectionId:profile.id,connectionName:profile.name});
  const dialog=b.locator('#agent-approval-dialog');await dialog.waitFor({state:'visible',timeout:10000});
  assert.equal(await a.locator('#agent-approval-dialog').evaluate(d=>d.open),false);
  const row=b.locator('[data-approval="'+pending.id+'"]');assert.equal(await row.getByRole('button',{name:'Approve once',exact:true}).isEnabled(),true);
  assert.equal(await row.getByRole('checkbox').count(),0);
  assert.equal((await call('dba_request_status',{requestId:pending.id})).state,'awaiting_approval','Displaying a prompt must not approve it');
  // No lease, no CSRF, or a different tab cannot approve.
  assert.equal((await api(a,'/approvals/'+pending.id,'POST',{action:'approve_once',acknowledged:true})).status,403);
  await row.getByRole('button',{name:'Always allow this read',exact:true}).focus();await b.keyboard.press('Enter');
  await b.waitForFunction(()=>!document.querySelector('.approval-request .approval-actions'));
  const permitted=await call('dba_get_connection_details',{requestId:'inspect-permitted',purpose:'Use reviewed exact read access',connectionId:profile.id,connectionName:profile.name});assert.equal(permitted.name,profile.name);
  await dialog.getByRole('button',{name:'Close',exact:true}).click();
  const created=await call('dba_request_connection_create',{requestId:'creation-review',purpose:'Test full connection proposal editor',profile:{name:'Synthetic proposal',templateId:'h2',driverClass:'org.h2.Driver',jar,url:'jdbc:h2:mem:approval_browser',username:'sa',password:'',readOnly:true}});
  await b.locator('[data-approval="'+created.id+'"]').waitFor();
  await b.getByRole('button',{name:'Review / edit proposal',exact:true}).click();
  const editor=b.locator('#connection-editor');await editor.waitFor();assert.equal(await editor.getByRole('tab').count(),6);
  await editor.locator('#ce-name').fill('Reviewed synthetic proposal');
  await editor.getByRole('button',{name:'Test',exact:true}).click();await b.locator('#connection-test-result').waitFor({state:'visible',timeout:15000});
  assert.match(await b.locator('#connection-test-result').innerText(),/Connection successful/);
  await b.locator('#test-result-close').click();await editor.getByRole('button',{name:'Save',exact:true}).click();
  await dialog.waitFor();const revised=b.locator('[data-approval="'+created.id+'"]');assert.match(await revised.innerText(),/Reviewed synthetic proposal/);
  assert.equal(await revised.getByRole('checkbox').count(),0);await revised.getByRole('button',{name:'Apply after successful test',exact:true}).click();
  let state;for(let n=0;n<50;n++){state=await call('dba_request_status',{requestId:created.id});if(state.state==='complete')break;await b.waitForTimeout(100);}
  assert.equal(state.state,'complete',JSON.stringify(state));assert.equal(state.result.name,'Reviewed synthetic proposal');
  await dialog.getByRole('button',{name:'Close',exact:true}).click();
  const deletion=await call('dba_request_connection_delete',{requestId:'reject-deletion',purpose:'Verify dismissal never authorizes deletion',connectionId:state.result.id,connectionName:state.result.name});
  await b.locator('[data-approval="'+deletion.id+'"]').waitFor();await b.getByRole('button',{name:'Reject',exact:true}).click();
  assert.equal((await call('dba_request_status',{requestId:deletion.id})).state,'rejected');
  await dialog.getByRole('button',{name:'Close',exact:true}).click();
  const standalone=await call('dba_request_live_sql',{requestId:'standalone-create',purpose:'Test standalone SQL without any project binding',connectionId:profile.id,connectionName:profile.name,sql:'CREATE TABLE PUBLIC.STANDALONE_QA(ID INT PRIMARY KEY)'});
  assert.equal(standalone.bindingId,undefined);assert.equal(standalone.projectId,undefined);
  const sqlRow=b.locator('[data-approval="'+standalone.id+'"]');await sqlRow.waitFor();
  assert.match(await sqlRow.innerText(),/Saved connection default/);
  assert.equal(await sqlRow.getByRole('button',{name:/Always allow/}).count(),0);
  assert.equal(await sqlRow.getByRole('checkbox').count(),0);
  assert.equal((await call('dba_request_status',{requestId:standalone.id})).state,'awaiting_approval');
  await sqlRow.getByRole('button',{name:'Approve once',exact:true}).click();
  for(let n=0;n<50;n++){state=await call('dba_request_status',{requestId:standalone.id});if(['complete','failed'].includes(state.state))break;await b.waitForTimeout(100);}
  assert.equal(state.state,'complete',JSON.stringify(state));
  assert.equal((await call('dba_job_status',{jobId:state.jobId})).state,'complete');
  assert.equal((await call('dba_release_job',{jobId:state.jobId})).ok,true);
  await dialog.getByRole('button',{name:'Close',exact:true}).click();
  const read=await call('dba_request_live_sql',{requestId:'standalone-read',purpose:'Test standalone read choices',connectionId:profile.id,connectionName:profile.name,sql:'SELECT ID FROM PUBLIC.STANDALONE_QA'});
  const readRow=b.locator('[data-approval="'+read.id+'"]');await readRow.waitFor();
  assert.equal(await readRow.getByRole('button',{name:'Always allow this read',exact:true}).count(),1);
  assert.equal(await readRow.getByRole('button',{name:/Always allow read-only for/}).count(),0);
  await readRow.getByRole('button',{name:'Reject',exact:true}).click();
  assert.deepEqual(errors,[]);console.log('Approval browser coverage passed: SSE routing, competing tab denial, persistent reads, full proposal editing/testing, changed-draft review, apply, and rejection.');
 }finally{await context.close();}
};
