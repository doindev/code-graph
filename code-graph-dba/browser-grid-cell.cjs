const assert=require('node:assert/strict');
module.exports=async(browser,base)=>{
 const context=await browser.newContext({viewport:{width:1200,height:820}}),page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 try{
  await page.goto(base+'/dba');await page.waitForFunction(()=>document.querySelector('#workspace')?.inert===false&&/^\d+ connection/.test(document.querySelector('#connection-count')?.textContent??''));
  await page.evaluate(async()=>{
   const {DataGridView}=await import('/dba/data-grid.js');
   const host=document.createElement('div');host.id='cell-fixture';Object.assign(host.style,{position:'fixed',inset:'90px 40px',display:'flex',zIndex:50,background:'#101722'});document.body.append(host);
   const columns=Array.from({length:12},(_,i)=>({id:'c'+i,label:'Column '+i,jdbcType:12}));
   const result={columns,rows:Array.from({length:200},(_,i)=>columns.map(c=>'value-'+i+'-'+c.id)),grid:{page:{offset:123456789,limit:200,hasMore:false},capabilities:{edit:true},rowIds:Array.from({length:200},(_,i)=>'r'+i),columns:columns.map(c=>({...c,name:c.label,editable:true,nullable:true,hasDefault:true,size:80}))}};
   const view=new DataGridView(host,result,{sourceSql:'SELECT fixture'});
   window.cellFixture={view,result,host,DataGridView};
  });
  const box=page.locator('#cell-fixture'),scroll=box.locator('.data-grid-scroll'),corner=box.getByRole('columnheader',{name:'Row numbers',exact:true});
  const rowResize=corner.getByRole('separator'),row=box.locator('[data-row-index="0"]'),cell=row.locator('[data-column-id=c0]');
  assert.ok(await corner.evaluate(e=>e.clientWidth>70),'Large row numbers auto-fit');
  assert.equal(await row.locator('.row-number').evaluate(e=>e.scrollWidth<=e.clientWidth),true);
  const firstWidth=await corner.evaluate(e=>e.getBoundingClientRect().width);
  await rowResize.focus();await rowResize.press('ArrowRight');assert.equal(await corner.evaluate(e=>e.getBoundingClientRect().width),firstWidth+10);
  const resizeBox=await rowResize.boundingBox();await page.mouse.move(resizeBox.x+5,resizeBox.y+10);await page.mouse.down();await page.mouse.move(resizeBox.x+65,resizeBox.y+10);await page.mouse.up();
  const userWidth=await corner.evaluate(e=>e.getBoundingClientRect().width);assert.equal(userWidth,firstWidth+70);
  await cell.dblclick();const editor=box.locator('.grid-cell-editor'),input=editor.locator('textarea'),mode=page.locator('.grid-cell-options select');
  await mode.waitFor({state:'visible'});assert.equal(await editor.locator('select').count(),0);
  assert.equal(await editor.evaluate(e=>Math.abs(e.getBoundingClientRect().width-e.querySelector('textarea').getBoundingClientRect().width)<3),true);
  const before=await mode.boundingBox(),header=box.locator('.data-column-header[data-column-id=c0] .column-resize'),hb=await header.boundingBox();
  await page.mouse.move(hb.x+5,hb.y+10);await page.mouse.down();await page.mouse.move(hb.x+75,hb.y+10);await page.mouse.up();
  await page.waitForFunction(x=>document.querySelector('.grid-cell-options').getBoundingClientRect().left>x+60,before.x);
  assert.equal(await editor.count(),1,'Resizing does not finish cell editing');
  await page.screenshot({path:'code-graph-dba/target/grid-cell-floating.png'});
  await mode.focus();await mode.selectOption('null');assert.equal(await input.isDisabled(),true);await mode.selectOption('value');await input.fill('draft only');
  await input.press('Escape');assert.equal(await mode.count(),0);assert.equal(await cell.innerText(),'value-0-c0');
  // Keep the editor mounted while it is visible; scroll and resize reposition its portal.
  await box.locator('[data-row-index="5"] [data-column-id=c2]').dblclick();
  const oldTop=(await mode.boundingBox()).y;
  await scroll.evaluate(e=>{e.scrollTop=56;e.scrollLeft=60;});
  await page.waitForFunction(y=>document.querySelector('.grid-cell-options')?.getBoundingClientRect().top<y-40,oldTop);
  assert.equal(await editor.count(),1);
  await page.setViewportSize({width:900,height:700});
  await page.waitForFunction(()=>{const e=document.querySelector('.grid-cell-options')?.getBoundingClientRect();return e&&e.right<=innerWidth&&e.bottom<=innerHeight;});
  // Keyboard resizing from a focused separator must keep the active editor.
  await box.locator('.data-column-header[data-column-id=c2] .column-resize').focus();
  await box.locator('.data-column-header[data-column-id=c2] .column-resize').press('ArrowRight');
  assert.equal(await editor.count(),1);
  await scroll.evaluate(e=>e.scrollTop=3000);await mode.waitFor({state:'detached'});
  assert.ok(await box.locator('.grid-body .grid-row').count()<40,'Virtualization resumes after the editor leaves view');
  assert.equal(await box.locator('.grid-body .row-number').first().evaluate(e=>e.getBoundingClientRect().width),userWidth,'Newly rendered rows retain the resized gutter');
  assert.equal(await corner.evaluate(e=>Math.abs(e.getBoundingClientRect().left-e.closest('.data-grid-scroll').getBoundingClientRect().left)<3),true,'Corner remains sticky after both-axis scrolling');
  // At the right edge the portal flips, stays outside the cell, and stays in bounds.
  await scroll.evaluate(e=>{e.scrollLeft=0;e.scrollTop=0;});await page.waitForTimeout(100);
  await page.evaluate(()=>{const v=cellFixture.view;v.resizeColumn('c0',600);v.editCell(v.scroll.querySelector('[data-row-index="0"] [data-column-id=c0]'),0,v.columns()[0]);});
  await mode.waitFor();assert.equal(await mode.evaluate(e=>{const m=e.parentElement.getBoundingClientRect(),c=document.querySelector('#cell-fixture .grid-cell-editor').getBoundingClientRect();return m.right<=c.left||m.left>=c.right||m.bottom<=c.top||m.top>=c.bottom;}),true,'Fallback does not cover the textarea');
  await input.press('Enter');
  // Width survives data replacement, reordered columns and an unmount/remount.
  await page.evaluate(()=>{const f=cellFixture;f.view.moveBy('c0',1);f.view.updateData('SELECT updated');f.view.destroy();f.view=new f.DataGridView(f.host,f.result,{sourceSql:'SELECT updated'});});
  assert.equal(await corner.evaluate(e=>e.getBoundingClientRect().width),userWidth);
  await rowResize.dblclick();assert.equal(await corner.evaluate(e=>e.getBoundingClientRect().width),firstWidth,'Double-click restores auto-fit');
  await row.locator('[data-column-id=c1]').dblclick();await mode.waitFor();
  await page.evaluate(()=>{cellFixture.view.destroy();cellFixture.host.remove();});assert.equal(await mode.count(),0);
  assert.deepEqual(errors,[]);
  console.log('Grid cell controls passed: floating Value/NULL/DEFAULT, full-width editing, pointer/keyboard resize, scrolling, viewport fallback, cleanup, auto-fit and persistent resizable row numbers.');
 }finally{await context.close();}
};
