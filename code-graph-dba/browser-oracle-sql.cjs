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
  await page.screenshot({path:'code-graph-dba/target/oracle-output.png'});
  const packageName='CG_BROWSER_'+Date.now();
  await page.locator('#sql').fill(`CREATE PACKAGE ${packageName} AS FUNCTION answer RETURN NUMBER; END;\n/\nCREATE PACKAGE BODY ${packageName} AS FUNCTION answer RETURN NUMBER IS BEGIN RETURN 91; END; END;\n/\n`);
  await page.locator('#run').click();await page.waitForFunction(()=>!document.querySelector('#run').disabled);assert.equal(await page.locator('#error').innerText(),'');
  for(const name of ['Browser Oracle','Schemas','SYSTEM','Packages']){await page.getByRole('button',{name:new RegExp('^(Expand|Collapse) '+name+'$')}).waitFor();const expand=page.getByRole('button',{name:'Expand '+name,exact:true});if(await expand.count())await expand.click();}
  const object=page.locator(`.metadata-node[data-name="${packageName}"] > .metadata-title`);await object.getByRole('button').first().dblclick();
  const view=page.locator('.object-properties');await page.waitForFunction(()=>document.querySelector('.object-properties')?.getAttribute('aria-busy')==='false');
  await view.getByRole('tab',{name:'Body',exact:true}).click();assert.match(await view.innerText(),/RETURN 91/);
  await view.getByRole('tab',{name:'DDL',exact:true}).click();await view.getByRole('button',{name:'Use definition as draft',exact:true}).click();
  assert.equal(await view.getByRole('checkbox',{name:/Split multiple statements/}).isChecked(),true);
  const native= view.getByRole('textbox',{name:'Object change SQL',exact:true});await native.fill((await native.inputValue()).replace('RETURN 91','RETURN 92'));
  await view.getByRole('button',{name:'Save',exact:true}).click();const review=page.getByRole('dialog');await review.getByRole('textbox',{name:'Reviewed object SQL',exact:true}).waitFor();
  assert.match(await review.innerText(),/implicitly|partial|commit/i);await review.getByRole('checkbox').check();await review.getByRole('button',{name:'Apply',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.object-properties')?.getAttribute('aria-busy')==='false');assert.match(await view.innerText(),/Object changes saved/);
  await view.getByRole('tab',{name:'DDL',exact:true}).click();assert.match(await view.getByRole('textbox',{name:'Existing object DDL',exact:true}).inputValue(),/RETURN 92/);
  await page.screenshot({path:'code-graph-dba/target/oracle-package-editor.png'});assert.deepEqual(errors,[]);
  console.log('Oracle SQL browser passed: output parameters, REF CURSOR grid, server output, retained inputs, repeat execution, native package/body editing, reviewed apply and error-free navigation.');
 }finally{await context.close();}
};
