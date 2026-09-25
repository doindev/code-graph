const assert=require('node:assert/strict');

// Real tree components and DOM identity checks with controllable metadata jobs.
module.exports=async(browser,base)=>{
  const page=await browser.newPage({viewport:{width:1000,height:750}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  try{
    await page.route(base+'/dba/__tree-refresh-fixture',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><html><head><link rel="stylesheet" href="/dba/style.css"></head><body><div id="tree" style="width:420px;height:360px;overflow:auto"></div></body></html>'}));
    await page.goto(base+'/dba/__tree-refresh-fixture');
    await page.evaluate(async()=>{
      const {ConnectionTree}=await import('/dba/connection-tree.js'),{MetadataTreeView}=await import('/dba/metadata-tree.js');
      const s=window.refreshTest={requests:[],notices:[],opened:[],menus:[],cancelled:[],selected:'beta',version:0};
      s.node=(key,kind,name=key,branch=true,extra={})=>({key,kind,name,branch,...extra});
      s.profiles=[{id:'alpha',name:'Alpha',url:'jdbc:h2:mem:alpha'},{id:'beta',name:'Beta',url:'jdbc:h2:mem:beta'}];
      const n=s.node;
      s.replies={root:[n('schemas','schemas','Schemas')],schemas:[n('PUBLIC','schema')],schema:[n('tables','tables','Tables'),n('views','views','Views')],tables:Array.from({length:40},(_,i)=>n('table-'+String(i).padStart(2,'0'),'relation')),views:[n('view','relation','View',false)],relation:[n('columns','table_columns','Columns')],table_columns:[n('id','column','id',false)]};
      s.api=async(path,method,body)=>{
        if(path.endsWith('/cancel')){s.cancelled.push(path);return {};}
        if(path.endsWith('/state'))return {connected:true,busy:false};
        if(path!=='/metadata/tree')throw Error('Unexpected request '+path);
        s.requests.push({...body});
        if(s.delayKind===body.kind){s.delayKind=null;return new Promise(resolve=>s.release=()=>resolve({id:'late',result:{nodes:[n('obsolete','column','obsolete',false)]}}));}
        if(s.failKind===body.kind&&(s.failOffset===undefined||s.failOffset===body.offset))throw Error('fixture metadata unavailable');
        const nodes=s.replies[body.kind]??[];
        return {id:'job-'+s.requests.length,result:s.pageMode&&body.kind==='tables'?{nodes:nodes.slice(body.offset,body.offset+2),...(body.offset+2<nodes.length?{nextOffset:body.offset+2}:{})}:{nodes}};
      };
      s.tree=new ConnectionTree(document.querySelector('#tree'),{api:s.api,wait:async j=>j.result,notice:message=>s.notices.push(message),select:id=>{s.selected=id;s.tree.select(id);},load:(p,host)=>s.metadata.load(p,host),order:async()=>{}});
      s.metadata=new MetadataTreeView({api:s.api,wait:async j=>j.result,notice:message=>s.notices.push(message),select:s.tree.cb.select,menu:(...args)=>s.menus.push(args),openTable:(p,d,selection)=>s.opened.push({profile:p,descriptor:{...d},selection})});
      await s.tree.render(s.profiles,s.selected);await s.tree.toggle('alpha',true);
      s.root=s.metadata.roots.get('alpha');s.schemas=s.root.children[0];await s.metadata.toggle(s.schemas,true);
      s.schema=s.schemas.children[0];await s.metadata.toggle(s.schema,true);
      s.tables=s.schema.children[0];s.views=s.schema.children[1];await s.metadata.toggle(s.tables,true);
      s.table=s.tables.children[0];await s.metadata.toggle(s.table,true);s.columns=s.table.children[0];await s.metadata.toggle(s.columns,true);
      s.tree.cb.select('beta');
      s.capture=()=>{s.elements=[...document.querySelectorAll('#tree .connection-row,#tree .metadata-node,#tree .metadata-name')];s.removed=[];s.observer?.disconnect();s.observer=new MutationObserver(records=>{for(const r of records)for(const node of r.removedNodes)if(node.nodeType===1&&(node.matches('.connection-row,.metadata-node')||node.querySelector('.connection-row,.metadata-node')))s.removed.push(node);});s.observer.observe(document.querySelector('#tree'),{childList:true,subtree:true});};
    });
    // No-op global refresh keeps every mounted element, expanded state, focus and scroll.
    const stable=await page.evaluate(async()=>{const s=refreshTest;s.table.wrapper.querySelector('.metadata-name').focus();const host=document.querySelector('#tree');host.scrollTop=80;const scroll=host.scrollTop,focused=document.activeElement;s.capture();await s.tree.render(structuredClone(s.profiles),s.selected);await new Promise(requestAnimationFrame);return {same:s.elements.every(e=>e.isConnected),removed:s.removed.length,scroll:host.scrollTop,expectedScroll:scroll,focus:document.activeElement===focused,selected:s.selected,expanded:s.table.wrapper.getAttribute('aria-expanded')};});
    assert.deepEqual(stable,{same:true,removed:0,scroll:stable.expectedScroll,expectedScroll:stable.expectedScroll,focus:true,selected:'beta',expanded:'true'});
    // A slow refresh never blanks the branch while metadata is pending.
    await page.evaluate(()=>{const s=refreshTest;s.delayKind='table_columns';s.pending=s.metadata.reload(s.columns);});
    assert.equal(await page.locator('.metadata-node[data-name="id"]').isVisible(),true);
    await page.evaluate(async()=>{const s=refreshTest;s.release();await s.pending;});
    assert.equal(await page.locator('.metadata-node[data-name="obsolete"]').count(),1);
    // Reconcile changed descriptors, insert/delete nodes, and refresh expanded descendants.
    const changed=await page.evaluate(async()=>{
      const s=refreshTest;s.capture();const kept=s.tables.children[0],removed=s.tables.children[1],n=s.node;
      s.replies.tables=[{...s.replies.tables[0],name:'Renamed table',canCreate:true},...s.replies.tables.slice(2),n('table-new','relation','New table')];s.replies.table_columns=[n('new-column','column','new-column',false)];
      await s.metadata.reload(s.tables);kept.wrapper.querySelector('.metadata-name').dispatchEvent(new MouseEvent('dblclick',{bubbles:true}));kept.wrapper.querySelector('.metadata-actions').click();
      return {kept:s.tables.children[0]===kept,removed:!removed.wrapper.isConnected,added:s.tables.children.at(-1).descriptor.name,opened:s.opened.at(-1).descriptor.name,menu:s.menus.at(-1)[3].descriptor.name,expanded:kept.wrapper.getAttribute('aria-expanded'),column:s.columns.children[0].descriptor.name,oldColumnGone:!document.querySelector('[data-name="obsolete"]')};
    });
    assert.deepEqual(changed,{kept:true,removed:true,added:'New table',opened:'Renamed table',menu:'Renamed table',expanded:'true',column:'new-column',oldColumnGone:true});
    // Collapsed branches remain lazy and refresh on reopening without replacing retained rows.
    const collapsed=await page.evaluate(async()=>{const s=refreshTest;await s.metadata.toggle(s.views,true);const view=s.views.children[0];await s.metadata.toggle(s.views,false);s.replies.views.push(s.node('view2','relation','Second view',false));s.requests=[];await s.metadata.reload(s.schema);const fetched=s.requests.some(r=>r.kind==='views'),hidden=s.views.container.hidden;await s.metadata.toggle(s.views,true);return {fetched,hidden,same:s.views.children[0]===view,count:s.views.children.length};});
    assert.deepEqual(collapsed,{fetched:false,hidden:true,same:true,count:2});
    // Failure leaves visible rows intact; a later success clears the error in place.
    const failed=await page.evaluate(async()=>{const s=refreshTest;s.capture();s.failKind='tables';const result=await s.metadata.reload(s.tables),same=s.elements.every(e=>e.isConnected);s.failKind=null;await s.metadata.reload(s.tables);return {result,same,error:!!s.tables.container.querySelector('.metadata-error')};});
    assert.deepEqual(failed,{result:false,same:true,error:false});
    // Remembered pagination is fetched before reconciliation, preserving later-page rows.
    const paged=await page.evaluate(async()=>{const s=refreshTest;s.pageMode=true;s.metadata.pages.set(s.tables.key,2);s.replies.tables=s.replies.tables.slice(0,5);await s.metadata.reload(s.tables);s.pageRows=s.tables.children.slice();s.requests=[];await s.metadata.reload(s.tables);return {offsets:s.requests.filter(r=>r.kind==='tables').map(r=>r.offset),same:s.pageRows.every((c,i)=>s.tables.children[i]===c),count:s.tables.children.length};});
    assert.deepEqual(paged,{offsets:[0,2],same:true,count:4});
    const pageFailure=await page.evaluate(async()=>{const s=refreshTest,prior=s.tables.children.slice();s.failKind='tables';s.failOffset=2;s.replies.tables[0]={...s.replies.tables[0],name:'Not committed yet'};const result=await s.metadata.reload(s.tables);const intact=prior.every((c,i)=>s.tables.children[i]===c)&&s.tables.children[0].descriptor.name==='Renamed table';s.failKind=null;delete s.failOffset;await s.metadata.reload(s.tables);return {result,intact,name:s.tables.children[0].descriptor.name};});
    assert.deepEqual(pageFailure,{result:false,intact:true,name:'Not committed yet'});
    await page.locator('[data-name="Tables"] > .metadata-children > .metadata-more').click();
    assert.equal(await page.evaluate(()=>refreshTest.tables.children.length),5);
    // Connection changes patch the existing header and preserve sibling roots and selection.
    const profiles=await page.evaluate(async()=>{const s=refreshTest,alpha=s.tree.rows.get('alpha'),beta=s.tree.rows.get('beta');s.tree.cb.select('beta');s.profiles=[s.profiles[1],{...s.profiles[0],name:'Renamed connection',color:'#123456'},{id:'gamma',name:'Gamma',url:'jdbc:h2:mem:gamma'}];await s.tree.render(s.profiles,'beta');return {same:s.tree.rows.get('alpha')===alpha&&s.tree.rows.get('beta')===beta,order:[...s.tree.rows.keys()],label:alpha.row.querySelector('.connection-select').textContent,color:alpha.row.dataset.color,selected:s.selected};});
    assert.deepEqual(profiles,{same:true,order:['beta','alpha','gamma'],label:'Renamed connection',color:'#123456',selected:'beta'});
    // A leaf/branch shape change replaces only that node and clears obsolete expansion.
    const shape=await page.evaluate(async()=>{const s=refreshTest,old=s.tables.children[0],other=s.tables.children[1];s.replies.tables[0]={...s.replies.tables[0],branch:false};await s.metadata.reload(s.tables);return {replaced:!old.wrapper.isConnected,other:s.tables.children[1]===other,expanded:s.metadata.expanded.has(old.key),children:s.tables.children[0].children.length};});
    assert.deepEqual(shape,{replaced:true,other:true,expanded:false,children:0});
    // Retained descendants must still count toward the node and byte budgets.
    const capacity=await page.evaluate(()=>{const s=refreshTest;const c=s.tables.children[1],children=c.children;let nodeLimit=false,byteLimit=false;try{c.children=Array.from({length:2000},(_,i)=>({descriptor:{key:'x'+i},children:[]}));try{s.metadata.checkCapacity(s.tables,s.tables.children.map(c=>c.descriptor),true);}catch(e){nodeLimit=/2,000 items/.test(e.message);}c.children=[{descriptor:{key:'huge',data:'x'.repeat(2*1024*1024)},children:[]}];try{s.metadata.checkCapacity(s.tables,s.tables.children.map(c=>c.descriptor),true);}catch(e){byteLimit=/2 MiB/.test(e.message);}}finally{c.children=children;}return {nodeLimit,byteLimit};});
    assert.deepEqual(capacity,{nodeLimit:true,byteLimit:true});
    // A deleted connection cancels its pending metadata job and ignores late results.
    await page.evaluate(()=>{const s=refreshTest;s.delayKind='tables';s.pending=s.metadata.reload(s.tables);});
    const removed=await page.evaluate(async()=>{const s=refreshTest;s.profiles=s.profiles.filter(p=>p.id!=='alpha');s.metadata.retain(s.profiles);await s.tree.render(s.profiles,'beta');s.release();await s.pending;return {removed:!s.root.container.isConnected,roots:[...s.metadata.roots.keys()],cancelled:s.cancelled,late:document.querySelectorAll('[data-name="obsolete"]').length,operations:s.tables.operations.size};});
    assert.deepEqual(removed,{removed:true,roots:[],cancelled:['/jobs/late/cancel'],late:0,operations:0});
    assert.deepEqual(errors,[]);
    console.log('PASS incremental tree refresh: DOM identity, focus/scroll/selection, changes, lazy branches, paging, errors, limits and late-job cancellation');
  }finally{await page.close();}
};
