const assert=require('node:assert/strict');

module.exports=async(browser,base)=>{
  const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[];
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
    const dialog=page.locator('#editor-pairing-dialog');await dialog.waitFor();const code=await page.locator('#editor-pairing-code').inputValue();assert.match(code,/^[A-Z0-9-]{8,}$/);
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
    assert.deepEqual(errors,[]);console.log('Editor pairing browser checks passed: explicit pairing, revision-checked draft/edit, SSE resynchronization, no execution, stale conflict and revocation.');
  }finally{await page.close();}
};
