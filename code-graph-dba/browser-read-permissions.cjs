const assert=require('node:assert/strict');
module.exports=async(browser,base,jar)=>{
 const context=await browser.newContext(),page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 const api=(path,method='GET',body)=>page.evaluate(async({path,method,body})=>{
  const session=await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}).then(r=>r.json());
  const r=await fetch('/api/dba'+path,{method,headers:{'Content-Type':'application/json','X-Dba-CSRF':session.csrf},body:body===undefined?undefined:JSON.stringify(body)});return {status:r.status,data:await r.json()};
 },{path,method,body});
 try{
  await page.goto(base+'/dba');await page.locator('#database-permissions').waitFor({state:'attached'});
  const agent=(await api('/agents','POST',{name:'SELECT permission UI',grants:[]})).data;
  const created=await api('/connections','POST',{name:'Read scope fixture',templateId:'mysql',url:'jdbc:mysql://127.0.0.1:1/app',driverClass:'com.mysql.cj.jdbc.Driver',jar:process.env.USERPROFILE+'/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar',username:'sa',password:'',saveUntested:true});
  assert.equal(created.status,201,JSON.stringify(created));const connection=created.data;
  const call=async(operation,args)=>{const r=await fetch(base+'/__test/agent',{method:'POST',headers:{'Content-Type':'application/json','X-Test-Agent':agent.token},body:JSON.stringify({operation,args})});assert.equal(r.status,200,await r.clone().text());return r.json();};
  await call('dba_get_my_permissions',{});
  // UI target pagination uses deterministic fixtures; execution is covered by vendor integration tests.
  await page.route('**/api/dba/permissions/targets',async route=>{
   const body=route.request().postDataJSON();if(body.kind==='connections')return route.continue();
   const items=body.kind==='databases'?[{name:'app'},{name:'other'}]:body.kind==='schemas'?[{name:'public'}]:body.offset?[{name:'future',type:'TABLE'}]:[{name:'users',type:'TABLE'},{name:'orders',type:'TABLE'}];
   return route.fulfill({json:{items,hasMore:body.kind==='objects'&&!body.offset,nextOffset:2}});
  });
  await page.locator('#workspace-settings').click();await page.getByRole('menuitem',{name:'Database permissions',exact:true}).click();
  const manager=page.getByRole('dialog',{name:'Database permissions',exact:true});await manager.getByLabel('Permission recipient').selectOption(agent.id);
  await manager.getByRole('button',{name:'New read permission',exact:true}).click();
  let editor=page.getByRole('dialog',{name:'Allow SELECTs',exact:true});
  assert.equal(await editor.getByLabel('Duration', {exact:true}).inputValue(),'mcp_session');
  assert.equal(await editor.getByRole('button',{name:'Save permission',exact:true}).isDisabled(),true);
  await editor.getByLabel('Connection',{exact:true}).selectOption(connection.id);
  await editor.getByLabel('Database',{exact:true}).selectOption('app');
  await editor.getByLabel('Table/view',{exact:true}).selectOption(['users','orders']);
  await editor.getByRole('button',{name:'Add selected scope',exact:true}).click();
  await editor.getByRole('button',{name:'Load more',exact:true}).click();await editor.getByLabel('Table/view',{exact:true}).locator('option[value="future"]').waitFor();
  assert.match(await editor.locator('pre').innerText(),/users/);assert.match(await editor.locator('pre').innerText(),/orders/);
  await editor.getByLabel('Duration',{exact:true}).selectOption('until_revoked');
  await editor.screenshot({path:__dirname+'/target/read-permissions-dialog.png'});
  let fail=true;await page.route('**/api/dba/agents/'+agent.id+'/policies',route=>{if(fail&&route.request().method()==='POST'){fail=false;return route.fulfill({status:409,json:{error:'Fixture conflict; draft retained'}});}return route.continue();});
  await editor.getByRole('button',{name:'Save permission',exact:true}).click();await editor.getByRole('alert').filter({hasText:'Fixture conflict'}).waitFor();
  assert.match(await editor.locator('pre').innerText(),/users/);await editor.getByRole('button',{name:'Save permission',exact:true}).click();await editor.waitFor({state:'detached'});
  let policies=(await api('/agents/'+agent.id+'/permissions')).data.reusablePolicies;assert.equal(policies.length,1);assert.equal(policies[0].selectors.length,2);
  await manager.getByRole('button',{name:'Edit',exact:true}).click();editor=page.getByRole('dialog',{name:'Allow SELECTs',exact:true});
  assert.match(await editor.locator('pre').innerText(),/orders/);await page.keyboard.press('Escape');await editor.waitFor({state:'detached'});
  assert.equal(await manager.getByRole('button',{name:'Edit',exact:true}).evaluate(e=>e===document.activeElement),true);
  await manager.getByRole('button',{name:'Disable',exact:true}).click();await manager.getByRole('button',{name:'Enable',exact:true}).waitFor();
  await manager.getByRole('button',{name:'Close',exact:true}).click();
  const pending=await call('dba_request_live_sql',{connectionId:connection.id,connectionName:connection.name,database:'app',schema:'app',requestId:'read-scope-ui',purpose:'Verify SELECT permission picker defaults',sql:'SELECT * FROM app.unapproved'});
  const approval=page.locator('[data-approval="'+pending.id+'"]');await approval.waitFor();
  await approval.getByRole('button',{name:'Allow SELECTs…',exact:true}).click();editor=page.getByRole('dialog',{name:'Allow SELECTs',exact:true});
  assert.match(await editor.locator('pre').innerText(),/unapproved/);
  await editor.getByRole('button',{name:'These databases',exact:true}).click();assert.doesNotMatch(await editor.locator('pre').innerText(),/unapproved/);
  await editor.getByRole('button',{name:'These tables/views',exact:true}).click();assert.match(await editor.locator('pre').innerText(),/unapproved/);assert.equal(await editor.getByLabel('Duration',{exact:true}).inputValue(),'mcp_session');
  await editor.getByRole('button',{name:'Cancel',exact:true}).click();await editor.waitFor({state:'detached'});
  assert.equal((await call('dba_request_status',{requestId:pending.id})).state,'awaiting_approval');
  await approval.getByRole('button',{name:'Deny',exact:true}).click();
  const next=await call('dba_request_live_sql',{connectionId:connection.id,connectionName:connection.name,database:'app',schema:'app',requestId:'read-scope-grant',purpose:'Verify single submission after a grant',sql:'SELECT COUNT(*) FROM app.unapproved'});
  const nextApproval=page.locator('[data-approval="'+next.id+'"]');await nextApproval.waitFor();
  let submissions=0;await page.route('**/api/dba/approvals/'+next.id,route=>{if(route.request().method()==='POST'&&route.request().postDataJSON()?.action==='allow_selects')submissions++;return route.continue();});
  await nextApproval.getByRole('button',{name:'Allow SELECTs…',exact:true}).click();editor=page.getByRole('dialog',{name:'Allow SELECTs',exact:true});
  await editor.getByRole('button',{name:'Grant and run',exact:true}).click();await editor.waitFor({state:'detached'});
  const submitted=await call('dba_request_status',{requestId:next.id});assert.ok(submitted.jobId);assert.equal(submissions,1);
  policies=(await api('/agents/'+agent.id+'/permissions')).data.reusablePolicies;
  for(const policy of policies)if(policy.lifetime==='mcp_session'){const removed=await api('/agents/'+agent.id+'/policies/'+policy.id,'DELETE');assert.equal(removed.status,200,JSON.stringify(removed));}
  await page.locator('#agent-approval-dialog').getByRole('button',{name:'Close',exact:true}).click();
  await page.reload();await page.locator('#workspace-settings').click();await page.getByRole('menuitem',{name:'Database permissions',exact:true}).click();
  const reopened=page.getByRole('dialog',{name:'Database permissions',exact:true});await reopened.getByLabel('Permission recipient').selectOption(agent.id);await reopened.getByRole('button',{name:'Revoke',exact:true}).click();
  assert.equal((await api('/agents/'+agent.id+'/permissions')).data.reusablePolicies.length,0);assert.deepEqual(errors,[]);
  console.log('Read permissions passed: standalone settings, defaults, multi-object scopes, pagination, draft retention, editing, persistence, disable, revoke, approval cancellation, and keyboard focus.');
 }finally{await context.close();}
};
