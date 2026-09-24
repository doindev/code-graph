const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
 const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
 page.on('pageerror',error=>errors.push(error.message));
 const open=async()=>{await page.locator('#workspace-settings').click();await page.locator('#settings').click();await page.locator('#settings-dialog').waitFor();};
 try{
  await page.goto(base+'/dba');await page.locator('#workspace-settings').waitFor();await open();
  const idle=page.locator('[name=mcpSessionIdleTimeoutMinutes]');assert.equal(await idle.inputValue(),'60');
  assert.equal(await idle.getAttribute('min'),'1');assert.equal(await idle.getAttribute('max'),'10080');
  for(const value of ['0','10081','1.5']){await idle.fill(value);assert.equal(await idle.evaluate(e=>e.validity.valid),false);}
  await idle.fill('120');
  const input=page.locator('[name=approvalTimeoutSeconds]');assert.equal(await input.inputValue(),'300');
  assert.equal(await input.getAttribute('min'),'10');assert.equal(await input.getAttribute('max'),'3600');
  await input.fill('9');assert.equal(await input.evaluate(e=>e.validity.valid),false);
  await input.fill('3601');assert.equal(await input.evaluate(e=>e.validity.valid),false);
  await input.fill('45');await page.locator('#settings-form button[type=submit]').click();await page.locator('#settings-dialog').waitFor({state:'hidden'});
  await page.reload();await open();assert.equal(await input.inputValue(),'45');assert.equal(await idle.inputValue(),'120');
  assert.equal(await page.locator('[name=decisionTimeoutSeconds]').inputValue(),'60');
  await idle.scrollIntoViewIfNeeded();await page.screenshot({path:'code-graph-dba/target/approval-settings-desktop.png'});
  await page.setViewportSize({width:390,height:844});await idle.scrollIntoViewIfNeeded();
  const box=await idle.boundingBox();assert.ok(box.x>=0&&box.x+box.width<=390,'Timeout input stays within a narrow viewport');
  await page.screenshot({path:'code-graph-dba/target/approval-settings-narrow.png'});
  await idle.fill('30');await input.fill('60');await page.locator('[data-close=settings-dialog]').click();await open();assert.equal(await input.inputValue(),'45','Cancel does not save changes');assert.equal(await idle.inputValue(),'120','Cancel does not change the MCP idle timeout');
  await page.locator('[data-close=settings-dialog]').click();assert.deepEqual(errors,[]);
  console.log('Agent timeout settings browser checks passed: MCP idle/approval defaults and bounds, persistence across reload, independent error timeout, cancellation, desktop and narrow layouts.');
 }finally{await context.close();}
};
