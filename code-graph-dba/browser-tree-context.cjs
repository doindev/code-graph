const assert=require('node:assert/strict');

// Real tree/menu components with deterministic capability responses; no database mutations.
module.exports=async function testTreeContextMenus(browser,base){
  const page=await browser.newPage({viewport:{width:1100,height:850}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  try{
    await page.route('**/dba/app.js',route=>route.fulfill({status:200,contentType:'application/javascript',body:''}));
    await page.goto(base+'/dba');
    await page.evaluate(async()=>{
      const {ConnectionTree}=await import('/dba/connection-tree.js');
      const {MetadataTreeView}=await import('/dba/metadata-tree.js');
      document.body.innerHTML='<div id="tree" style="width:420px;height:650px;overflow:auto"></div><textarea id="outside">Native context menu here</textarea>';
      const state=window.contextTest={selected:'beta',actions:[],requests:[],notices:[]};
      const node=(kind,name,branch=true)=>({key:name,kind,name,branch});
      const nodes={root:[node('schemas','Schemas')],schemas:[node('schema','PUBLIC')],schema:[node('tables','Tables')],tables:[node('relation','example')],relation:[node('table_columns','Columns')],table_columns:[node('column','id',false)]};
      const api=async(path,method,body)=>{
        state.requests.push({path,body});
        if(path.endsWith('/state'))return {connected:false,busy:false};
        if(path==='/metadata/tree')return {nodes:nodes[body.kind]??[]};
        if(path==='/metadata/object')return {name:body.key,canDelete:true,canRename:body.parent.kind==='tables',reason:'Rename is not supported for this object'};
        throw Error('Unexpected API request '+path);
      };
      const action=name=>(...args)=>state.actions.push({name,args});
      const tree=new ConnectionTree(document.querySelector('#tree'),{api,wait:async v=>v,notice:message=>state.notices.push(message),select:id=>{state.selected=id;tree.select(id);},load:(p,host)=>metadata.load(p,host),hasLast:()=>false,edit:action('edit'),remove:action('remove'),rename:action('rename'),objectAction:action('object'),order:action('order'),open:action('open'),last:action('last'),new:action('new')});
      const metadata=new MetadataTreeView({api,wait:async v=>v,notice:message=>state.notices.push(message),select:tree.cb.select,menu:(...args)=>tree.openMetadataMenu(...args)});
      tree.render([{id:'alpha',name:'Alpha',url:'jdbc:h2:mem:alpha'},{id:'beta',name:'Beta',url:'jdbc:h2:mem:beta'}],'beta');
      state.tree=tree;state.metadata=metadata;
      document.addEventListener('contextmenu',event=>{state.defaultPrevented=event.defaultPrevented;state.bubbled=(state.bubbled??0)+1;});
    });
    const root=page.locator('[data-connection="alpha"]'),rootHead=root.locator(':scope > .connection-title'),menu=page.locator('.connection-menu:not(.connection-submenu)');
    const menuSnapshot=()=>menu.locator(':scope > button').evaluateAll(items=>items.map(b=>({label:b.textContent,disabled:b.disabled,icon:b.querySelector('svg')?.dataset.lucide})));
    async function opened(){await menu.waitFor({state:'visible'});await page.waitForFunction(()=>![...document.querySelectorAll('.connection-menu > button')].some(b=>b.title==='Checking this object…'));assert.equal(await menu.count(),1);}
    async function unchanged(){assert.equal(await page.evaluate(()=>contextTest.selected),'beta');assert.deepEqual(await page.evaluate(()=>contextTest.actions),[]);}
    async function compareRow(row,head,targets){
      const more=head.locator('.connection-more'),expanded=await row.getAttribute('aria-expanded');
      await more.click();await opened();const expected=await menuSnapshot();await page.keyboard.press('Escape');
      for(const target of targets){
        await target.click({button:'right'});await opened();assert.deepEqual(await menuSnapshot(),expected);
        assert.equal(await row.getAttribute('aria-expanded'),expanded);assert.equal(await more.getAttribute('aria-expanded'),'true');await unchanged();
        await menu.evaluate(e=>e.dataset.sameMenu='yes');
        await target.click({button:'right'});await opened();assert.equal(await menu.getAttribute('data-same-menu'),'yes','Repeated right-click retains the current menu');
        const a=await more.boundingBox(),b=await menu.boundingBox(),expectedX=Math.max(8,Math.min(a.x+a.width-b.width,page.viewportSize().width-b.width-8));assert.ok(Math.abs(b.x-expectedX)<2,'Menu remains button-anchored with viewport clamping: '+JSON.stringify({a,b,expectedX}));
        await page.keyboard.press('Escape');assert.equal(await menu.count(),0);assert.equal(await more.getAttribute('aria-expanded'),'false');assert.equal(await more.evaluate(e=>document.activeElement===e),true);
      }
      // Dispatch directly on the row to cover padding/blank space without relying on theme geometry.
      const prevented=await head.evaluate(e=>!e.dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true,button:2})));
      assert.equal(prevented,true);await opened();assert.deepEqual(await menuSnapshot(),expected);await unchanged();await page.keyboard.press('Escape');
    }
    await compareRow(root,rootHead,['.connection-select','.connection-icon','.connection-toggle','.connection-address','.connection-more'].map(s=>rootHead.locator(s)));
    await rootHead.locator('.connection-select').click({button:'right'});await opened();
    await page.locator('[data-connection="beta"] .connection-select').click({button:'right'});await opened();assert.equal(await menu.getAttribute('aria-label'),'Actions for Beta');assert.equal(await rootHead.locator('.connection-more').getAttribute('aria-expanded'),'false');await unchanged();
    await page.locator('#outside').click();assert.equal(await menu.count(),0);
    await rootHead.locator('.connection-toggle').click();
    for(const name of ['Schemas','PUBLIC','Tables','example','Columns']){
      await root.locator('.metadata-node[data-name="'+name+'"] > .metadata-title > .metadata-toggle').click();
      await root.locator('.metadata-node[data-name="'+name+'"][aria-expanded="true"]:not([aria-busy])').waitFor();
    }
    await page.evaluate(()=>contextTest.tree.cb.select('beta'));
    const treeFonts=await page.evaluate(()=>{const sql=document.createElement('textarea');sql.id='sql';sql.hidden=true;document.body.append(sql);const expected=getComputedStyle(sql).fontSize;const nodes=[...document.querySelectorAll('#tree .connection-select,#tree .connection-address,#tree .metadata-name')];return {expected,sizes:nodes.map(n=>getComputedStyle(n).fontSize),count:nodes.length};});
    assert.equal(treeFonts.expected,'14px');assert.ok(treeFonts.count>=7);assert.ok(treeFonts.sizes.every(size=>size===treeFonts.expected),'All root, branch, leaf and address text matches the SQL editor font size');
    for(const name of ['Schemas','PUBLIC','Tables','example','Columns','id']){
      const row=root.locator('.metadata-node[data-name="'+name+'"]'),head=row.locator(':scope > .metadata-title');
      await compareRow(row,head,[head.locator('.metadata-name'),head.locator('.metadata-icon'),head.locator('.connection-more'),...(name==='id'?[]:[head.locator('.metadata-toggle')])]);
    }
    const leaf=root.locator('.metadata-node[data-name="id"] > .metadata-title');
    await leaf.locator('.metadata-name').click({button:'right'});await opened();assert.equal(await page.getByRole('menuitem',{name:'Rename',exact:true}).isDisabled(),true);
    assert.equal(await rootHead.locator('.connection-more').getAttribute('aria-expanded'),'false','A nested context menu never opens the root menu');
    await root.locator('.metadata-node[data-name="Tables"] > .metadata-title > .metadata-name').click({button:'right'});await opened();assert.deepEqual((await menuSnapshot()).map(i=>i.label),['Refresh']);
    await page.keyboard.press('Escape');
    await page.evaluate(async()=>{await contextTest.metadata.reload(contextTest.metadata.roots.get('alpha'));contextTest.tree.cb.select('beta');});
    await leaf.locator('.metadata-name').click({button:'right'});await opened();assert.equal(await page.getByRole('menuitem',{name:'Rename',exact:true}).isDisabled(),true,'Refreshed descendants retain capability checks');await unchanged();
    await page.keyboard.press('ArrowDown');assert.equal(await page.evaluate(()=>document.activeElement.getAttribute('role')),'menuitem');await page.keyboard.press('Escape');
    await rootHead.locator('.connection-more').click();await opened();await rootHead.locator('.connection-more').click();assert.equal(await menu.count(),0,'Left-click retains toggle-to-close behavior');
    assert.equal(await page.locator('#outside').evaluate(e=>!e.dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true,button:2}))),false,'Native context menus remain available outside eligible rows');
    assert.equal(await page.evaluate(()=>contextTest.defaultPrevented),false);
    assert.deepEqual(await page.evaluate(()=>contextTest.notices),[]);assert.deepEqual(errors,[]);
    console.log('Tree context-menu browser checks passed (root, branches, objects, leaves, refresh, permissions, dismissal).');
  }finally{await page.close();}
};
