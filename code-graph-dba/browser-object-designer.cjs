const assert=require('node:assert/strict');
module.exports=async(browser,base,jar)=>{
  const context=await browser.newContext({viewport:{width:1440,height:960}}),page=await context.newPage(),errors=[];let profile;
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.goto(base+'/dba');
    profile=await page.evaluate(async jar=>{
      const session=await(await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json();
      const response=await fetch('/api/dba/connections',{method:'POST',headers:{'Content-Type':'application/json','X-Dba-CSRF':session.csrf},body:JSON.stringify({name:'Object editor fixture',url:'jdbc:h2:mem:objects_'+Date.now()+';DB_CLOSE_DELAY=-1',jar,driverClass:'org.h2.Driver',username:'sa',saveUntested:true})});
      if(!response.ok)throw Error(await response.text());return response.json();
    },jar);
    await page.reload();
    for(const name of ['Object editor fixture','Schemas','PUBLIC'])await page.getByRole('button',{name:'Expand '+name,exact:true}).click();
    const view=page.locator('.object-properties');
    const ready=()=>page.waitForFunction(()=>document.querySelector('.object-properties')?.getAttribute('aria-busy')==='false');
    const save=async()=>{
      await view.getByRole('button',{name:'Save',exact:true}).click();
      const dialog=page.getByRole('dialog');await dialog.getByRole('textbox',{name:'Reviewed object SQL'}).waitFor();
      assert.equal(await dialog.getByRole('button',{name:'Apply',exact:true}).isDisabled(),true);
      await dialog.getByRole('checkbox').check();await dialog.getByRole('button',{name:'Apply',exact:true}).click();await ready();
      assert.match(await view.innerText(),/Object changes saved/);
    };
    await page.getByRole('button',{name:'Actions for Sequences',exact:true}).click();await page.getByRole('menuitem',{name:'New',exact:true}).click();await ready();
    assert.equal(await page.getByRole('dialog').count(),0);assert.equal(await page.getByRole('tab',{name:'Properties',exact:true}).count(),1);
    await view.getByRole('textbox',{name:'Name',exact:true}).fill('ui_sequence');
    await view.getByRole('tab',{name:'Definition',exact:true}).click();await view.getByRole('textbox',{name:'Start value',exact:true}).fill('9007199254740993');await view.getByRole('textbox',{name:'Cache size',exact:true}).fill('8');
    await save();
    assert.equal(await page.locator('#tabs .tab[data-type=object]').count(),1);
    await view.getByRole('tab',{name:'DDL',exact:true}).click();assert.match(await view.getByRole('textbox',{name:'Existing object DDL',exact:true}).inputValue(),/9007199254740993/);
    await view.getByRole('tab',{name:'Definition',exact:true}).click();await view.getByRole('textbox',{name:'Increment',exact:true}).fill('3');await save();
    await page.getByRole('button',{name:'Expand Sequences',exact:true}).click();
    const seq=page.locator('.metadata-node[data-name="ui_sequence"] > .metadata-title');await seq.getByRole('button',{name:/ui_sequence/}).first().dblclick();await ready();
    assert.equal(await page.locator('#tabs .tab[data-type=object]').count(),1,'Reopening focuses the existing object tab');
    await seq.click({button:'right'});assert.deepEqual((await page.getByRole('menuitem').allTextContents()).slice(0,2),['New','Delete']);await page.keyboard.press('Escape');
    await view.getByRole('tab',{name:'Advanced',exact:true}).click();assert.match(await view.innerText(),/sequence_name/i);
    await view.getByRole('searchbox',{name:'Filter properties'}).fill('increment');assert.equal(await view.locator('[data-property]:visible').count(),1);
    await page.screenshot({path:'code-graph-dba/target/object-designer.png',fullPage:true});
    await page.waitForTimeout(1000);await page.reload();await ready();assert.equal(await page.locator('#tabs .tab[data-type=object]').count(),1);
    // Create a view in another instance of the same editor.
    for(const name of ['Object editor fixture','Schemas','PUBLIC'])await page.getByRole('button',{name:'Expand '+name,exact:true}).click();
    await page.getByRole('button',{name:'Actions for Views',exact:true}).click();await page.getByRole('menuitem',{name:'New',exact:true}).click();await ready();
    await view.getByRole('textbox',{name:'Name',exact:true}).fill('ui_view');await view.getByRole('tab',{name:'Definition',exact:true}).click();await view.getByRole('textbox',{name:'SELECT query',exact:true}).fill('SELECT 1 AS ID');await save();
    await view.getByRole('textbox',{name:'SELECT query',exact:true}).fill('SELECT 2 AS ID');await view.getByRole('button',{name:'Revert',exact:true}).click();assert.match(await view.getByRole('textbox',{name:'SELECT query',exact:true}).inputValue(),/1/);
    // A destructive action cannot execute before its acknowledgement.
    await page.evaluate(async id=>{
      const s=await(await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json();
      const r=await fetch('/api/dba/query/execute',{method:'POST',headers:{'Content-Type':'application/json','X-Dba-CSRF':s.csrf},body:JSON.stringify({connectionId:id,sql:'CREATE TABLE PUBLIC.UI_TRUNCATE(ID INT); INSERT INTO PUBLIC.UI_TRUNCATE VALUES(1)',parameters:[],autoCommit:true})});
      if(!r.ok)throw Error(await r.text());const j=await r.json();for(let i=0;i<80;i++){const job=await(await fetch('/api/dba/jobs/'+j.id)).json();if(job.finished){if(job.state!=='complete')throw Error(JSON.stringify(job));await fetch('/api/dba/jobs/'+j.id,{method:'DELETE',headers:{'X-Dba-CSRF':s.csrf}});return;}await new Promise(r=>setTimeout(r,100));}throw Error('Fixture SQL timed out');
    },profile.id);
    await page.getByRole('button',{name:'Expand Tables',exact:true}).click();const table=page.locator('.metadata-node[data-name="UI_TRUNCATE"] > .metadata-title');
    let truncates=0;page.on('request',r=>{if(r.url().endsWith('/metadata/object/action')&&r.postDataJSON()?.action==='truncate')truncates++;});
    await table.click({button:'right'});await page.getByRole('menuitem',{name:'Truncate',exact:true}).click();const dialog=page.locator('#tree-object-dialog');
    assert.equal(await dialog.getByRole('button',{name:'Truncate',exact:true}).isDisabled(),true);assert.equal(truncates,0);await dialog.getByRole('button',{name:'Cancel',exact:true}).click();assert.equal(truncates,0);
    await table.click({button:'right'});await page.getByRole('menuitem',{name:'Truncate',exact:true}).click();await dialog.getByRole('checkbox').check();await dialog.getByRole('button',{name:'Truncate',exact:true}).click();await dialog.waitFor({state:'hidden'});assert.equal(truncates,1);
    assert.deepEqual(errors,[]);
    console.log('Object editor browser checks passed: nested Properties/DDL, create/edit, exact large sequence values, reopen, workspace restore, native properties, menu order, and confirmed Truncate.');
  }finally{
    if(profile)await page.evaluate(async id=>{const s=await(await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json();await fetch('/api/dba/connections/'+id,{method:'DELETE',headers:{'X-Dba-CSRF':s.csrf}});},profile.id).catch(()=>{});
    await context.close();
  }
};
