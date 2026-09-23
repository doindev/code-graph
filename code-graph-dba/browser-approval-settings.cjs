const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
 const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
 page.on('pageerror',error=>errors.push(error.message));
 const open=async()=>{await page.locator('#workspace-settings').click();await page.locator('#settings').click();await page.locator('#settings-dialog').waitFor();};
 try{
  await page.goto(base+'/dba');await page.locator('#workspace-settings').waitFor();await open();
  const input=page.locator('[name=approvalTimeoutSeconds]');assert.equal(await input.inputValue(),'300');
  assert.equal(await input.getAttribute('min'),'10');assert.equal(await input.getAttribute('max'),'3600');
  await input.fill('9');assert.equal(await input.evaluate(e=>e.validity.valid),false);
  await input.fill('3601');assert.equal(await input.evaluate(e=>e.validity.valid),false);
  await input.fill('45');await page.locator('#settings-form button[type=submit]').click();await page.locator('#settings-dialog').waitFor({state:'hidden'});
  await page.reload();await open();assert.equal(await input.inputValue(),'45');
  assert.equal(await page.locator('[name=decisionTimeoutSeconds]').inputValue(),'60');
  await input.scrollIntoViewIfNeeded();await page.screenshot({path:'code-graph-dba/target/approval-settings-desktop.png'});
  await page.setViewportSize({width:390,height:844});await input.scrollIntoViewIfNeeded();
  const box=await input.boundingBox();assert.ok(box.x>=0&&box.x+box.width<=390,'Timeout input stays within a narrow viewport');
  await page.screenshot({path:'code-graph-dba/target/approval-settings-narrow.png'});
  await input.fill('60');await page.locator('[data-close=settings-dialog]').click();await open();assert.equal(await input.inputValue(),'45','Cancel does not save changes');
  await page.locator('[data-close=settings-dialog]').click();assert.deepEqual(errors,[]);
  console.log('Approval settings browser checks passed: bounds, persistence across reload, independent error timeout, cancellation, desktop and narrow layouts.');
 }finally{await context.close();}
};
