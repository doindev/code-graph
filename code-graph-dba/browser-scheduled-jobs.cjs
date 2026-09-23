const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
  if(!process.env.DBA_SCHEDULER_DISPOSABLE)throw Error('Run with test-scheduled-jobs.ps1 and an owned database fixture');
  const vendor=process.env.DBA_SCHEDULER_VENDOR,pg=vendor==='postgresql',name='browser_scheduled_job';
  const context=await browser.newContext({viewport:{width:1440,height:960}}),page=await context.newPage(),errors=[],requests=[];let profile;
  page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(r.url().includes('/api/dba/'))requests.push({url:r.url(),body:r.postDataJSON?.()});});
  try{
    await page.goto(base+'/dba');await page.locator('#agent-approvals').waitFor({state:'attached'});
    profile=await page.evaluate(async fixture=>{
      const session=await(await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json();
      const r=await fetch('/api/dba/connections',{method:'POST',headers:{'Content-Type':'application/json','X-Dba-CSRF':session.csrf},body:JSON.stringify({...fixture,name:'Scheduler fixture',password:'scheduler-fixture-only',saveUntested:true,readOnly:false})});if(!r.ok)throw Error(await r.text());return r.json();
    },{url:process.env.DBA_SCHEDULER_URL,jar:process.env.DBA_SCHEDULER_JAR,username:process.env.DBA_SCHEDULER_USER,driverClass:pg?'org.postgresql.Driver':vendor==='mysql'?'com.mysql.cj.jdbc.Driver':'org.mariadb.jdbc.Driver'});
    await page.reload();for(const n of ['Scheduler fixture','Databases','scheduler_test'])await page.getByRole('button',{name:'Expand '+n,exact:true}).click();
    const folder=page.locator('.metadata-node[data-kind="scheduled_jobs"] > .metadata-title');await folder.waitFor();
    assert.equal(requests.filter(r=>r.url.endsWith('/metadata/tree')&&r.body?.kind==='scheduled_jobs').length,0,'Job rows are lazy');
    await page.getByRole('button',{name:'Expand Scheduled Jobs',exact:true}).click();
    await page.getByRole('button',{name:'Actions for Scheduled Jobs',exact:true}).click();await page.getByRole('menuitem',{name:'New',exact:true}).click();
    const view=page.locator('.object-properties');const ready=()=>page.waitForFunction(()=>document.querySelector('.object-properties')?.getAttribute('aria-busy')==='false');await ready();
    const save=async(pattern)=>{
      await view.getByRole('button',{name:'Save',exact:true}).click();const dialog=page.getByRole('dialog');const sql=dialog.getByRole('textbox',{name:'Reviewed object SQL'});await sql.waitFor();assert.match(await sql.inputValue(),pattern);
      assert.equal(await dialog.getByRole('button',{name:'Apply',exact:true}).isDisabled(),true);await dialog.getByRole('checkbox').check();await dialog.getByRole('button',{name:'Apply',exact:true}).click();await ready();assert.match(await view.innerText(),/Object changes saved/);
    };
    assert.equal(await view.getByRole('checkbox',{name:'Enabled',exact:true}).isChecked(),false);
    await view.getByRole('textbox',{name:'Job name',exact:true}).fill(name);
    await view.getByRole('tab',{name:'Schedule',exact:true}).click();
    if(pg)await view.getByRole('textbox',{name:'Cron schedule',exact:true}).fill('0 0 1 1 *');
    else{await view.getByRole('combobox',{name:'Schedule type',exact:true}).selectOption('ONE TIME');await view.getByRole('textbox',{name:'Run once at',exact:true}).fill('2030-01-01 00:00:00');}
    await view.getByRole('tab',{name:'Definition',exact:true}).click();await view.getByRole('textbox',{name:'SQL command',exact:true}).fill('SELECT 1');await save(pg?/cron.schedule/:/CREATE EVENT/);
    const job=page.locator('.metadata-node[data-kind="scheduled_job"][data-name="'+name+'"]');await job.waitFor();await job.locator(':scope > .metadata-title .metadata-name').focus();await page.keyboard.press('Enter');await ready();
    await view.getByRole('tab',{name:'General',exact:true}).click();assert.equal(await view.getByRole('textbox',{name:'Job name',exact:true}).inputValue(),name);
    await view.getByRole('checkbox',{name:'Enabled',exact:true}).check();await save(pg?/active := true/:/ ENABLE/);
    await view.getByRole('checkbox',{name:'Enabled',exact:true}).uncheck();await view.getByRole('button',{name:'Revert',exact:true}).click();assert.equal(await view.getByRole('checkbox',{name:'Enabled',exact:true}).isChecked(),true);
    await view.getByRole('checkbox',{name:'Enabled',exact:true}).uncheck();await save(pg?/active := false/:/ DISABLE/);
    await page.getByRole('button',{name:'Expand '+name,exact:true}).click();await job.locator('.metadata-children .metadata-node').first().waitFor();
    await page.screenshot({path:'code-graph-dba/target/scheduler-'+vendor+'-desktop.png',fullPage:true});
    await page.setViewportSize({width:600,height:900});await page.screenshot({path:'code-graph-dba/target/scheduler-'+vendor+'-narrow.png',fullPage:true});
    await page.setViewportSize({width:1440,height:960});await job.locator(':scope > .metadata-title').click({button:'right'});await page.getByRole('menuitem',{name:'Delete',exact:true}).click();const dialog=page.locator('#tree-object-dialog');await dialog.waitFor();assert.equal(await dialog.getByRole('button',{name:'Delete',exact:true}).isDisabled(),true);await dialog.getByRole('checkbox').check();await dialog.getByRole('button',{name:'Delete',exact:true}).click();await dialog.waitFor({state:'hidden'});await job.waitFor({state:'detached'});
    assert.equal(requests.filter(r=>r.url.endsWith('/query/execute')).length,0,'Tree and job editing never submit the stored query');assert.deepEqual(errors,[]);
    console.log('Scheduler browser checks passed ('+vendor+'): lazy catalog, New, reviewed Save, keyboard open, enable/disable, Revert, details, desktop/narrow and confirmed Delete.');
  }finally{if(profile)await page.evaluate(async id=>{const s=await(await fetch('/api/dba/bootstrap',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json();await fetch('/api/dba/connections/'+id,{method:'DELETE',headers:{'X-Dba-CSRF':s.csrf}});},profile.id).catch(()=>{});await context.close();}
};
