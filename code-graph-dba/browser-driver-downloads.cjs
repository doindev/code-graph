const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),tls=require('node:tls'),crypto=require('node:crypto');
module.exports=async(browser,base)=>{
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'codegraph-driver-ui-'));
  const settings=path.join(directory,'corporate settings.xml'),pem=path.join(directory,'corporate cert.pem'),command=path.join(directory,'mvn.cmd');
  fs.writeFileSync(command,'This picker fixture must never be executed.');
  let commandResult={available:true,paths:[command]},commandError='';
  fs.writeFileSync(settings,'<settings><servers><server><password>never-render-this-secret</password></server></servers></settings>');
  fs.writeFileSync(pem,tls.rootCertificates.find(c=>{const x=new crypto.X509Certificate(c);return Date.parse(x.validFrom)<Date.now()&&Date.parse(x.validTo)>Date.now();}));
  page.on('pageerror',e=>errors.push(e.message));const picks=[];
  try{
    // Only native selection is mocked: a CI browser must not open a window on the user's desktop.
    await page.route(base+'/api/dba/setup/file-select',async route=>{
      const kind=route.request().postDataJSON().kind;picks.push(kind);
      await route.fulfill({json:{id:kind==='maven-settings'?'picker-settings':kind==='maven-executable'?'picker-command':'picker-cert'}});
    });
    await page.route(base+'/api/dba/jobs/picker-*',route=>route.fulfill({json:route.request().method()==='DELETE'?{}:
      route.request().url().endsWith('picker-command')?(commandError?{state:'failed',error:commandError}:{state:'complete',result:commandResult}):
      {state:'complete',result:{available:true,paths:[route.request().url().endsWith('picker-settings')?settings:pem]}}}));
    await page.goto(base+'/dba');await page.waitForFunction(()=>!document.getElementById('workspace-settings').inert);
    const open=async()=>{await page.locator('#workspace-settings').click();await page.locator('#driver-download-settings').click();await page.locator('#driver-download-settings-dialog').waitFor();};
    await open();const dialog=page.locator('#driver-download-settings-dialog'),apply=dialog.getByRole('button',{name:'Apply',exact:true});
    assert.equal(await apply.isDisabled(),true);assert.match(await dialog.locator('#driver-default-settings').innerText(),/\.m2[\\/]settings\.xml/);
    const commandBrowse=dialog.getByRole('button',{name:'Browse for Maven executable',exact:true});
    assert.equal(await commandBrowse.isDisabled(),true,'Embedded mode does not browse installed Maven');
    const certBrowse=dialog.locator('[data-browse=certPem]');assert.equal(await certBrowse.isEnabled(),true,'Embedded mode can browse a public CA PEM');assert.equal(await dialog.locator('[name=insecureTls]').isEnabled(),true);
    await certBrowse.click();await page.waitForFunction(value=>document.querySelector('#driver-download-settings-dialog [name=certPem]').value===value,pem);
    await dialog.locator('[name=insecureTls]').check();assert.equal(await dialog.locator('#driver-tls-warning').isVisible(),true);await apply.click();await dialog.waitFor({state:'hidden'});
    await page.reload();await page.waitForFunction(()=>!document.getElementById('workspace-settings').inert);await open();
    assert.equal(await dialog.locator('[name=mode]').inputValue(),'embedded');assert.equal(await dialog.locator('[name=certPem]').inputValue(),pem);assert.equal(await dialog.locator('[name=insecureTls]').isChecked(),true);
    await dialog.locator('[name=insecureTls]').uncheck();assert.equal(await dialog.locator('#driver-tls-warning').isHidden(),true);await apply.click();await dialog.waitFor({state:'hidden'});await open();
    assert.equal(await dialog.locator('[name=insecureTls]').isChecked(),false);assert.equal(await dialog.locator('[name=certPem]').inputValue(),pem,'Certificate remains configured when verification is re-enabled');

    await dialog.locator('[name=mode]').selectOption('maven');await commandBrowse.click();
    await page.waitForFunction(value=>document.querySelector('#driver-download-settings-dialog [name=command]').value===value,command);
    await dialog.locator('[data-browse=settings]').click();
    await page.waitForFunction(value=>document.querySelector('#driver-download-settings-dialog [name=settings]').value===value,settings);
    await dialog.locator('[data-browse=certPem]').click();
    await page.waitForFunction(value=>document.querySelector('#driver-download-settings-dialog [name=certPem]').value===value,pem);
    assert.deepEqual(picks,['maven-cert','maven-executable','maven-settings','maven-cert']);
    commandResult={available:true,paths:[]};await commandBrowse.click();
    await page.waitForFunction(()=>!document.querySelector('[data-browse=command]').matches(':disabled'));
    assert.equal(await dialog.locator('[name=command]').inputValue(),command,'Picker Cancel preserves the existing path');
    commandResult={available:false,message:'Enter the existing local mvn/mvn.cmd file path manually.'};await commandBrowse.click();
    await page.waitForFunction(()=>document.querySelector('#driver-settings-status').textContent.includes('manually'));
    assert.equal(await dialog.locator('[name=command]').inputValue(),command);
    commandError='Select the readable Maven launcher file';await commandBrowse.click();
    await page.waitForFunction(()=>document.querySelector('#driver-settings-status').textContent.includes('readable Maven launcher'));
    assert.equal(await dialog.locator('[name=command]').inputValue(),command);commandError='';
    await dialog.locator('[name=insecureTls]').check();assert.equal(await dialog.locator('#driver-tls-warning').isVisible(),true);
    assert.equal(await apply.isEnabled(),true);await apply.click();await dialog.waitFor({state:'hidden'});
    await page.reload();await page.waitForFunction(()=>!document.getElementById('workspace-settings').inert);await open();
    assert.equal(await dialog.locator('[name=settings]').inputValue(),settings);assert.equal(await dialog.locator('[name=certPem]').inputValue(),pem);
    assert.equal(await dialog.locator('[name=command]').inputValue(),command,'Apply persists the browsed path across reload');
    assert.equal(await dialog.locator('[name=insecureTls]').isChecked(),true);assert.equal(await apply.isDisabled(),true);
    assert.equal((await dialog.innerText()).includes('never-render-this-secret'),false);
    await page.screenshot({path:'code-graph-dba/target/driver-download-settings.png'});
    await dialog.locator('[name=settings]').fill('discard-this-change');await dialog.locator('[name=command]').fill('discard-command');await dialog.getByRole('button',{name:'Cancel',exact:true}).click();await open();
    assert.equal(await dialog.locator('[name=settings]').inputValue(),settings);
    assert.equal(await dialog.locator('[name=command]').inputValue(),command);
    await dialog.locator('[name=command]').fill(path.join(directory,'missing-mvn.cmd'));await apply.click();await dialog.waitFor({state:'hidden'});
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
    assert.deepEqual(errors,[]);console.log('Driver download UI checks passed: embedded PEM/bypass persistence and re-enable, installed Maven persistence, defaults, all three Browse actions, picker cancel/headless/error handling, warning, Cancel, error details, retry, and preserved draft.');
  }finally{await context.close();fs.rmSync(directory,{recursive:true,force:true});}
};
