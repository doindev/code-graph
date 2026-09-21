const assert=require('node:assert/strict');

module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  const browserApi=async(path,method='GET',body)=>page.evaluate(async({path,method,body})=>{
    const session=await fetch('/api/dba/session').then(response=>response.json());
    const response=await fetch('/api/dba'+path,{method,headers:{'Content-Type':'application/json','X-Dba-CSRF':session.csrf},body:body===undefined?undefined:JSON.stringify(body)});
    return{status:response.status,data:await response.json()};
  },{path,method,body});
  try{
    await page.goto(base+'/dba');await page.locator('#workspace-settings').waitFor();
    const agent=await browserApi('/agents','POST',{name:'Editor pairing QA',grants:[]});assert.equal(agent.status,201,JSON.stringify(agent));
    const agentCall=async(operation,args={},expected=200)=>{
      const response=await fetch(base+'/__test/agent',{method:'POST',headers:{'Content-Type':'application/json','X-Test-Agent':agent.data.token},body:JSON.stringify({operation,args})});
      const data=await response.json();assert.equal(response.status,expected,JSON.stringify(data));return data;
    };

    await page.locator('#workspace-settings').click();await page.locator('#editor-pairing').click();
    // Six random bytes become eight uppercase base64url characters, including '_' and '-'.
    const dialog=page.locator('#editor-pairing-dialog');await dialog.waitFor();const code=await page.locator('#editor-pairing-code').inputValue();assert.match(code,/^[A-Z0-9_-]{8}$/);
    const paired=await agentCall('dba_pair_editor',{pairingCode:code});assert.equal(paired.state,'paired');
    await page.waitForFunction(()=>document.querySelector('#editor-pairing-status').textContent.includes('Paired with'));

    const initial=await agentCall('dba_list_editor_documents');
    const created=await agentCall('dba_create_editor_draft',{title:'Agent review.sql',sql:'',expectedWorkspaceRevision:initial.workspaceRevision});
    assert.equal(created.executed,false);assert.equal(created.fileSaved,false);
    await page.waitForFunction(()=>[...document.querySelectorAll('#tabs .tab')].some(tab=>tab.textContent.includes('Agent review.sql')));
    const document=await agentCall('dba_get_editor_document',{documentId:created.id});
    const changed=await agentCall('dba_apply_editor_edit',{documentId:created.id,expectedRevision:document.revision,start:0,end:0,text:'SELECT 42'});
    assert.equal(changed.executed,false);assert.equal(changed.fileSaved,false);
    await page.waitForFunction(()=>document.querySelector('#sql')?.value==='SELECT 42');
    assert.match(await page.locator('#tabs .tab.active').innerText(),/Agent review\.sql/);
    assert.equal((await agentCall('dba_apply_editor_edit',{documentId:created.id,expectedRevision:document.revision,start:0,end:0,text:'stale'},400)).error,'IllegalArgumentException');
    assert.equal(await page.locator('#run').isEnabled(),false,'Agent edits never select a connection or execute SQL');

    await page.locator('#editor-pairing-revoke').click();await page.waitForFunction(()=>document.querySelector('#editor-pairing-status').textContent==='Not paired');
    assert.equal((await agentCall('dba_list_editor_documents',{},400)).error,'SecurityException');
    await dialog.getByRole('button',{name:'Close',exact:true}).click();
    const second=await page.context().newPage();second.on('pageerror',error=>errors.push(error.message));await second.goto(base+'/dba');
    await second.locator('#new-tab').waitFor();await second.waitForFunction(()=>!document.querySelector('#workspace-toolbar-actions').inert);
    assert.equal(await second.locator('#tabs .tab').count(),0,'Tabs sharing cookies do not share the Script workspace');
    const request=await agentCall('dba_request_editor_access',{requestId:'browser-pair-one',purpose:'Review an unsaved Script in the chosen tab'});
    assert.equal(request.state,'awaiting_approval');
    await page.locator('.editor-access-dialog').waitFor();await second.locator('.editor-access-dialog').waitFor();
    assert.equal(await page.locator('.editor-access-dialog').getByRole('heading').innerText(),'Use this /dba instance?');
    await second.getByRole('button',{name:'Use this /dba instance',exact:true}).click();
    await second.locator('#editor-connected').waitFor();
    await page.waitForFunction(()=>!document.querySelector('.editor-access-dialog'));
    assert.equal((await agentCall('dba_request_status',{approvalId:request.approvalId})).state,'paired');
    const fresh=await agentCall('dba_list_editor_documents');assert.equal(fresh.documents.length,0);
    const secondDraft=await agentCall('dba_create_editor_draft',{title:'Only in chosen tab.sql',sql:'SELECT 77',expectedWorkspaceRevision:fresh.workspaceRevision});
    await second.waitForFunction(()=>document.querySelector('#sql').value==='SELECT 77');
    assert.equal(await page.locator('#sql').inputValue(),'SELECT 42','Other tab keeps its existing unsaved text');
    const workspaceId=await second.evaluate(()=>sessionStorage.getItem('dba-editor-workspace'));
    await second.reload();await second.waitForFunction(()=>document.querySelector('#sql').value==='SELECT 77');
    assert.equal(await second.evaluate(()=>sessionStorage.getItem('dba-editor-workspace')),workspaceId,'Refresh restores the same selected workspace');
    await second.locator('#editor-connected').waitFor();
    assert.equal((await agentCall('dba_get_editor_document',{documentId:secondDraft.id})).sql,'SELECT 77');
    const duplicate=await page.context().newPage();await duplicate.addInitScript(id=>sessionStorage.setItem('dba-editor-workspace',id),workspaceId);await duplicate.goto(base+'/dba');
    await duplicate.waitForFunction(()=>!document.querySelector('#workspace-toolbar-actions').inert);
    assert.notEqual(await duplicate.evaluate(()=>sessionStorage.getItem('dba-editor-workspace')),workspaceId,'Duplicated storage cannot take over a paired live tab');
    assert.equal(await duplicate.locator('#tabs .tab').count(),0);
    await second.locator('#editor-connected').click();await second.waitForFunction(()=>document.querySelector('#editor-connected').hidden);
    assert.equal((await agentCall('dba_list_editor_documents',{},400)).error,'SecurityException');
    const denied=await agentCall('dba_request_editor_access',{requestId:'browser-pair-deny',purpose:'Verify rejection'});
    await page.locator('.editor-access-dialog').waitFor();await page.locator('.editor-access-dialog').getByRole('button',{name:'Deny',exact:true}).click();
    assert.equal((await agentCall('dba_request_status',{approvalId:denied.approvalId})).state,'denied');
    await duplicate.close();await second.close();
    assert.deepEqual(errors,[]);console.log('Editor pairing browser checks passed: native-request browser fallback, multiple tabs, first acceptance, isolated workspaces, reload, duplicate protection, denial, revision checks, legacy code, SSE, no execution and revocation.');
  }finally{await context.close();}
};
