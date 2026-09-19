const assert=require('node:assert/strict');
module.exports=async(browser,base,jar)=>{
  const context=await browser.newContext({viewport:{width:1440,height:900}}),page=await context.newPage(),errors=[],approvalRequests=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/\/approvals\/(events|presence)/.test(r.url()))approvalRequests.push(r.url());});
  try{
    await page.goto(base+'/dba');await page.locator('#yolo-warning').waitFor();assert.match(await page.locator('#yolo-warning').innerText(),/YOLO/);
    await page.locator('#project-databases').waitFor({state:'attached'});assert.equal(await page.locator('#agent-approvals').isHidden(),true);
    await page.getByRole('button',{name:'Workspace settings',exact:true}).click();await page.getByRole('menuitem',{name:'RAM',exact:true}).click();
    assert.match(await page.locator('#yolo-settings-warning').innerText(),/DANGER/);await page.locator('[data-close=settings-dialog]').click();
    await page.evaluate(async({base,jar})=>{
      const s=await (await fetch(base+'/api/dba/session')).json();
      const result=await fetch(base+'/api/dba/connections',{method:'POST',headers:{'Content-Type':'application/json','X-Dba-CSRF':s.csrf},body:JSON.stringify({name:'YOLO browser',templateId:'h2',url:'jdbc:h2:mem:YOLO_BROWSER',driverClass:'org.h2.Driver',jar,username:'sa',saveUntested:true})});if(!result.ok)throw Error(await result.text());
    },{base,jar});
    await page.locator('#refresh').click();const row=page.locator('.connection-row').first();await row.waitFor();await row.locator('.connection-more').click();await page.getByRole('menuitem',{name:'Delete',exact:true}).click();
    await page.locator('#remove-connection-dialog').waitFor();await page.locator('#remove-connection-cancel').click();assert.equal(await page.locator('.connection-row').count(),1);
    assert.deepEqual(approvalRequests,[]);assert.deepEqual(errors,[]);
    await page.screenshot({path:'code-graph-dba/target/yolo-warning.png'});
    console.log('YOLO browser checks passed: persistent warning, no approval polling, and unchanged destructive confirmation.');
  }finally{await context.close();}
};
