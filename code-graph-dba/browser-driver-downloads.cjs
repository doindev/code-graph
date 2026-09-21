const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),tls=require('node:tls'),crypto=require('node:crypto');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'codegraph-driver-ui-'));
  const settings=path.join(directory,'corporate settings.xml'),pem=path.join(directory,'corporate cert.pem');
  fs.writeFileSync(settings,'<settings><servers><server><password>never-render-this-secret</password></server></servers></settings>');
  fs.writeFileSync(pem,tls.rootCertificates.find(c=>{const x=new crypto.X509Certificate(c);return Date.parse(x.validFrom)<Date.now()&&Date.parse(x.validTo)>Date.now();}));
  page.on('pageerror',e=>errors.push(e.message));const picks=[];
  try{
    // Only native selection is mocked: a CI browser must not open a window on the user's desktop.
    await page.route(base+'/api/dba/setup/file-select',async route=>{
      const kind=route.request().postDataJSON().kind;picks.push(kind);
      await route.fulfill({json:{id:kind==='maven-settings'?'picker-settings':'picker-cert'}});
    });
    await page.route(base+'/api/dba/jobs/picker-*',route=>route.fulfill({json:route.request().method()==='DELETE'?{}:
      {state:'complete',result:{available:true,paths:[route.request().url().endsWith('picker-settings')?settings:pem]}}}));
    await page.goto(base+'/dba');await page.waitForFunction(()=>!document.getElementById('workspace-settings').inert);
    const open=async()=>{await page.locator('#workspace-settings').click();await page.locator('#driver-download-settings').click();await page.locator('#driver-download-settings-dialog').waitFor();};
    await open();const dialog=page.locator('#driver-download-settings-dialog'),apply=dialog.getByRole('button',{name:'Apply',exact:true});
    assert.equal(await apply.isDisabled(),true);assert.match(await dialog.locator('#driver-default-settings').innerText(),/\.m2[\\/]settings\.xml/);
    await dialog.locator('[name=mode]').selectOption('maven');await dialog.locator('[data-browse=settings]').click();
    await page.waitForFunction(value=>document.querySelector('#driver-download-settings-dialog [name=settings]').value===value,settings);
    await dialog.locator('[data-browse=certPem]').click();
    await page.waitForFunction(value=>document.querySelector('#driver-download-settings-dialog [name=certPem]').value===value,pem);
    assert.deepEqual(picks,['maven-settings','maven-cert']);
    await dialog.locator('[name=command]').fill(path.join(directory,'missing-mvn.cmd'));
    await dialog.locator('[name=insecureTls]').check();assert.equal(await dialog.locator('#driver-tls-warning').isVisible(),true);
    assert.equal(await apply.isEnabled(),true);await apply.click();await dialog.waitFor({state:'hidden'});
    await page.reload();await page.waitForFunction(()=>!document.getElementById('workspace-settings').inert);await open();
    assert.equal(await dialog.locator('[name=settings]').inputValue(),settings);assert.equal(await dialog.locator('[name=certPem]').inputValue(),pem);
    assert.equal(await dialog.locator('[name=insecureTls]').isChecked(),true);assert.equal(await apply.isDisabled(),true);
    assert.equal((await dialog.innerText()).includes('never-render-this-secret'),false);
    await page.screenshot({path:'code-graph-dba/target/driver-download-settings.png'});
    await dialog.locator('[name=settings]').fill('discard-this-change');await dialog.getByRole('button',{name:'Cancel',exact:true}).click();await open();
    assert.equal(await dialog.locator('[name=settings]').inputValue(),settings);
    await dialog.getByRole('button',{name:'Cancel',exact:true}).click();
    await page.locator('#add').click();await page.locator('[data-database=postgresql]').click();
    const choice=page.locator('#driver-choice');await choice.waitFor();assert.match(await choice.locator('pre').innerText(),/Maven executable not found/);
    assert.equal(await choice.locator('#driver-download').isDisabled(),true);await choice.locator('#driver-retry').click();
    await page.waitForFunction(()=>document.getElementById('ce-cancel-job').hidden);
    assert.match(await choice.locator('pre').innerText(),/Maven executable not found/);await choice.locator('#driver-cancel').click();
    await page.locator('#ce-name').fill('Retained draft');await page.locator('#ce-tab-1').click();await page.locator('#ce-version').fill('1.0');
    await page.getByRole('button',{name:'Download pinned version',exact:true}).click();const failure=page.locator('#driver-failure');await failure.waitFor();
    assert.match(await failure.locator('pre').innerText(),/Maven executable not found/);assert.equal((await failure.innerText()).includes('never-render-this-secret'),false);
    await failure.locator('#driver-retry-install').click();await failure.waitFor();await page.screenshot({path:'code-graph-dba/target/driver-download-failure.png'});
    await failure.locator('#driver-failure-close').click();await page.locator('#ce-tab-0').click();assert.equal(await page.locator('#ce-name').inputValue(),'Retained draft');
    assert.deepEqual(errors,[]);console.log('Driver download UI checks passed: persistence, defaults, both Browse actions, warning, Cancel, error details, retry, and preserved draft.');
  }finally{await context.close();fs.rmSync(directory,{recursive:true,force:true});}
};
