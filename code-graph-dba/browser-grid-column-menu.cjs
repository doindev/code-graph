const assert=require('node:assert/strict');

module.exports=async function testGridColumnMenus(browser,base){
  const page=await browser.newPage({viewport:{width:1050,height:850}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await page.route('**/dba/app.js',route=>route.fulfill({status:200,contentType:'application/javascript',body:''}));
    await page.goto(base+'/dba');
    await page.evaluate(async()=>{
      const {DataGridView}=await import('/dba/data-grid.js');
      document.body.innerHTML='<div id="grid" style="height:500px;flex:none"></div><button id="outside">Outside</button>';
      const columns=[{id:'a',label:'value'},{id:'b',label:'value'},{id:'c',label:'nullable'},{id:'d',label:'<img src=x onerror=alert(1)>'},...Array.from({length:12},(_,i)=>({id:'extra'+i,label:'Extra '+i}))];
      const result={columns,rows:Array.from({length:200},(_,i)=>[i,"O'Brien "+i,null,'<script>not markup</script>',...Array.from({length:12},(_,j)=>i+j)])};
      window.gridTest={result,original:JSON.stringify(result),notices:[],DataGridView};
      gridTest.view=new DataGridView(document.querySelector('#grid'),result,{sourceSql:'SELECT example',notice:message=>gridTest.notices.push(message)});
    });
    const toggle=id=>page.locator('.data-column-header[data-column-id="'+id+'"] .grid-column-toggle'),menu=page.locator('.grid-column-menu');
    const labels=()=>menu.locator('[role=menuitem]').allTextContents();
    async function open(id){await toggle(id).click();await menu.waitFor({state:'visible'});assert.equal(await menu.count(),1);}
    assert.equal(await page.locator('.grid-column-toggle').count(),16);assert.equal(await page.locator('.row-number-corner button').count(),0);
    const triangle=await toggle('a').locator('path').getAttribute('d');assert.equal(triangle,'M6 7h12l-6 10.3923048454z');
    await open('a');
    assert.deepEqual(await labels(),['Order by value ASC','Order by value DESC','Find and Replace','Filter by value','value = (select a row)','value <> (select a row)','value > (select a row)','value < (select a row)','value = ...','value <> ...','value > ...','value < ...','value IS NULL','value IS NOT NULL','Remove all filters/orderings','Customize filters ...']);
    assert.equal(await menu.locator('[role=separator]').count(),4);assert.equal(await menu.getByRole('group',{name:'Cell Value',exact:true}).count(),1);assert.equal(await menu.getByRole('group',{name:'Custom',exact:true}).count(),1);assert.deepEqual(await menu.locator('.grid-menu-heading').allTextContents(),['Cell Value','Custom']);assert.deepEqual(await menu.getByRole('group',{name:'Custom',exact:true}).getByRole('menuitem').allTextContents(),['value = ...','value <> ...','value > ...','value < ...']);
    assert.equal(await menu.locator('[role=group] button:disabled').count(),4);assert.equal(await menu.locator('[role=menuitem] svg').count(),16);
    await toggle('a').click();assert.equal(await menu.count(),0,'Triangle toggles the menu off');assert.equal(await toggle('a').getAttribute('aria-expanded'),'false');
    const firstRow=page.locator('.grid-body .grid-row').first();await firstRow.locator('[role=gridcell]').first().click();assert.equal(await firstRow.getAttribute('aria-selected'),'true');
    await open('b');assert.deepEqual((await labels()).slice(4,8),["value = 'O''Brien 0'","value <> 'O''Brien 0'","value > 'O''Brien 0'","value < 'O''Brien 0'"]);
    await page.keyboard.press('Escape');assert.equal(await toggle('b').evaluate(e=>e===document.activeElement),true);
    await open('a');assert.equal((await labels())[4],'value = 0','Duplicate labels use their stable source column');await page.getByRole('menuitem',{name:'Order by value DESC',exact:true}).click();
    assert.equal(await toggle('a').locator('path').getAttribute('d'),triangle,'Menu triangle does not track ordering');assert.match(await page.evaluate(()=>gridTest.notices.at(-1)),/no executable SELECT context/);
    await open('c');assert.equal((await labels())[4],'nullable = NULL');assert.equal(await menu.locator('[role=group] button:disabled').count(),4);assert.equal(await page.getByRole('menuitem',{name:'nullable IS NULL',exact:true}).isEnabled(),true);
    await page.locator('#outside').click();assert.equal(await menu.count(),0);
    const secondRow=page.locator('.grid-body .grid-row').nth(1);await secondRow.focus();await secondRow.press('Space');assert.equal(await secondRow.getAttribute('aria-selected'),'true');assert.equal(await firstRow.getAttribute('aria-selected'),'false');
    await toggle('a').focus();await toggle('a').press('ArrowDown');await menu.waitFor({state:'visible'});assert.equal((await labels())[4],'value = 1');
    await page.keyboard.press('End');assert.equal(await page.evaluate(()=>document.activeElement.textContent),'Customize filters ...');await page.keyboard.press('Enter');
    assert.equal(await menu.count(),0);await page.locator('.result-settings').waitFor({state:'visible'});assert.equal(await page.locator('.result-settings h2').innerText(),'Result Set Order/Filter Settings');await page.locator('.settings-cancel').click();
    await page.evaluate(()=>{gridTest.view.resizeColumn('b',240);gridTest.view.moveColumn('b','a');});
    await open('b');assert.equal((await labels())[4],"value = 'O''Brien 1'",'Selected values survive visual column reordering');assert.equal((await toggle('b').locator('..').boundingBox()).width,240);await page.keyboard.press('Escape');
    await toggle('b').press('Alt+ArrowRight');assert.equal(await page.locator('.data-column-header').first().getAttribute('data-column-id'),'b','Menu keyboard events do not reorder columns');
    await page.evaluate(()=>gridTest.view.resizeColumn('b',60));const buttonBox=await toggle('b').boundingBox(),headerBox=await toggle('b').locator('..').boundingBox();assert.ok(buttonBox.x>=headerBox.x&&buttonBox.x+buttonBox.width<=headerBox.x+headerBox.width,'Triangle fits even the minimum column width');
    await open('b');await page.locator('.data-grid-scroll').evaluate(e=>e.scrollTop=2000);await menu.waitFor({state:'detached'});assert.ok(await page.locator('.grid-body .grid-row').count()<=40);
    await page.locator('.data-grid-scroll').evaluate(e=>e.scrollTop=0);await page.waitForFunction(()=>document.querySelector('.grid-body .grid-row')?.dataset.rowIndex==='0');
    assert.equal(await page.locator('.grid-body .grid-row[data-row-index="1"]').getAttribute('aria-selected'),'true','Virtualization restores the selected row');
    await open('d');assert.equal(await menu.locator('img,script').count(),0,'Column names and values are plain text');
    const box=await menu.boundingBox();assert.ok(box.x>=0&&box.y>=0&&box.x+box.width<=1050&&box.y+box.height<=850);
    await page.setViewportSize({width:360,height:360});await menu.waitFor({state:'detached'});await toggle('a').scrollIntoViewIfNeeded();await open('a');
    const smallBox=await menu.boundingBox();assert.ok(smallBox.x>=0&&smallBox.y>=0&&smallBox.x+smallBox.width<=360&&smallBox.y+smallBox.height<=360,'Menu is bounded and scrollable on small viewports');
    await page.keyboard.press('Escape');await page.setViewportSize({width:1050,height:850});
    await page.evaluate(()=>{gridTest.view.destroy();gridTest.view=new gridTest.DataGridView(document.querySelector('#grid'),gridTest.result);});await open('b');assert.equal((await labels())[4],"value = 'O''Brien 1'",'Selection survives switching away from and back to a retained result');
    assert.equal(await page.evaluate(()=>JSON.stringify(gridTest.result)===gridTest.original),true,'Placeholder actions never mutate the dataset');
    await page.screenshot({path:'code-graph-dba/target/grid-column-menu.png'});
    await page.evaluate(()=>gridTest.view.destroy());assert.equal(await menu.count(),0,'Disposing a result also disposes its menu');
    await page.evaluate(()=>{gridTest.view=new gridTest.DataGridView(document.querySelector('#grid'),{columns:[{id:'a',label:'empty'}],rows:[]});});await open('a');assert.equal(await menu.locator('[role=group] button:disabled').count(),4,'Empty/new results have no selected value');
    assert.deepEqual(errors,[]);console.log('Data Grid column-menu checks passed (exact menu, values, duplicates, keyboard, resizing, virtualization, cleanup).');
  }finally{await page.close();}
};
