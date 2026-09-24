const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
 const context=await browser.newContext({viewport:{width:1440,height:960}}),page=await context.newPage(),errors=[];
 page.on('pageerror',error=>errors.push(error.message));
 try{
  await page.goto(base+'/dba');await page.locator('#agent-approvals').waitFor({state:'attached'});
  await page.locator('#new-tab').click();await page.locator('.tab.active .tab-connection').selectOption({label:'Browser Oracle'});
  await page.locator('#toggle-sql-variables').click();
  await page.locator('#script-parameters').fill(JSON.stringify([{mode:'out',type:'NUMBER'},{mode:'out',type:'REF_CURSOR'}]));
  await page.getByRole('button',{name:'Apply parameters',exact:true}).click();
  await page.locator('#toggle-server-output').click();
  await page.locator('#sql').fill("BEGIN ?:=12345678901234567890123456789012345678; OPEN ? FOR SELECT 42 AS answer FROM dual; DBMS_OUTPUT.PUT_LINE('Oracle browser output'); END;\n/\n");
  const request=page.waitForRequest(r=>r.url().endsWith('/query/execute')&&r.method()==='POST');await page.locator('#run').click();
  const payload=(await request).postDataJSON();assert.equal(payload.serverOutput,true);assert.equal(payload.parameters[1].type,'REF_CURSOR');
  await page.waitForFunction(()=>!document.querySelector('#run').disabled);assert.equal(await page.locator('#error').innerText(),'');
  assert.match(await page.locator('#grid').innerText(),/42/);
  await page.locator('#result-tabs').getByRole('button',{name:'Server output',exact:true}).click();
  assert.match(await page.locator('#grid').innerText(),/Oracle browser output/);
  await page.locator('#result-tabs').getByRole('button',{name:'SQL variables',exact:true}).click();
  assert.match(await page.locator('#grid').innerText(),/12345678901234567890123456789012345678/);
  assert.equal(JSON.parse(await page.locator('#script-parameters').inputValue()).length,2);
  await page.locator('#script-parameters').fill('[]');await page.getByRole('button',{name:'Apply parameters',exact:true}).click();
  await page.locator('#sql').fill("SELECT 'second run' AS result FROM dual;");await page.locator('#run').click();
  await page.waitForFunction(()=>!document.querySelector('#run').disabled);assert.equal(await page.locator('#error').innerText(),'');assert.match(await page.locator('#grid').innerText(),/second run/);
  await page.locator('#result-tabs').getByRole('button',{name:'Server output',exact:true}).click();assert.match(await page.locator('#grid').innerText(),/No server output/);
  await page.screenshot({path:'code-graph-dba/target/oracle-output.png'});assert.deepEqual(errors,[]);
  console.log('Oracle SQL browser passed: output parameters, REF CURSOR grid, server output, retained inputs, repeat execution and error-free navigation.');
 }finally{await context.close();}
};
