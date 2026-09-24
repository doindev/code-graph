import {DatabaseCompare} from './compare.js';
import {initializeGridPreferences,defaultPageSize,pagePreference} from './grid-preferences.js';
import {approvalHeaders} from './approval-client.js';
import {EditorClient} from './editor-client.js';
import {NativeWorkspace} from './native-workspace.js';
import {installProjectContext} from './project-context.js';
import {connectionEditor} from './connection-editor.js';
import {installDriverDownloadSettings} from './driver-download-settings.js';
import {VisualQueryBuilder} from './query-builder.js';
import {lucide} from './tree-icons.js';
import {ObjectProperties} from './object-properties.js';
import {TableProperties} from './table-properties.js';
import {DataGridView,GridQueryController} from './data-grid.js';
import {runOwnedGrid,releaseGrid} from './grid-operations.js';
import {ConnectionTree} from './connection-tree.js';
import {MetadataTreeView} from './metadata-tree.js';
import {TreeActions} from './tree-actions.js';
const $=id=>document.getElementById(id);
// Restoring the authenticated workspace must precede any user action. Inert
// blocks pointer/focus activation without changing per-control disabled state.
const startupRegions=[$('layout'),$('workspace-toolbar-actions'),$('workspace-settings')];
for(const region of startupRegions){region.inert=true;region.setAttribute('aria-busy','true');}
let csrf='',profiles=[],tabs=[],active=null,lastSelected=null,toastTimer,workspaceTimer=0,workspaceReady=false,workspaceSaving=false,workspaceQueued=false,lastWorkspaceJson='',workspaceRevision=0,editorEvents=null;
// Four MiB of the existing 32 MiB result allowance is reserved for shared row drafts.
const MAX_TABS=12,MAX_BROWSER_BYTES=28*1024*1024;
const MAX_WORKSPACE_BYTES=16*1024*1024;
const collaboration=new EditorClient({api,notify:message=>toast(message),flush:async()=>{
  const deadline=performance.now()+10000;
  do{await flushWorkspace();if(!workspaceSaving&&JSON.stringify(workspaceState())===lastWorkspaceJson)return;await new Promise(resolve=>setTimeout(resolve,100));}while(performance.now()<deadline);
  throw new Error('Local workspace changes have not finished saving to this session. Retry pairing after synchronization.');
}});
let gutterText=null,gutterLines=1,gutterFrame=0,activeGrid=null;
const gridContexts=new WeakMap();
const gridRefreshers=new Map();
const tableDocument=document.createElement('section');tableDocument.id='table-document';tableDocument.hidden=true;$('workspace').append(tableDocument);
const isScript=tab=>!!tab&&!['table','builder','object','native','compare'].includes(tab.type);
function nativeFor(tab){
  const profile=profiles.find(p=>p.id===tab.connection);if(!profile){tab.native?.invalidate('Connection removed · command execution is disabled; this draft is retained.');return tab.native??null;}
  if(!tab.native)tab.native=new NativeWorkspace({api,profile,state:tab,changed:()=>queueWorkspaceSave(),account:bytes=>{
    if(tabs.reduce((sum,t)=>sum+(t===tab?0:t.bytes??0),0)+bytes>MAX_BROWSER_BYTES)throw new Error('Browser result allowance full; close a result first.');tab.bytes=bytes;
  }});tab.native.updateProfile(profile);return tab.native;
}
function openNative(connection=lastSelected,initial={}){
  const profile=profiles.find(p=>p.id===connection);if(!profile||!profile.transport||profile.transport==='jdbc')throw new Error('Select a native MongoDB or Redis connection.');
  saveEditor();if(tabs.length>=MAX_TABS)throw new Error('Close a tab before opening another (maximum 12).');
  const tab={id:crypto.randomUUID(),type:'native',title:profile.transport==='mongodb'?'Documents':'Keys',connection,database:profile.nativeOptions?.database??(profile.transport==='redis'?'0':''),bytes:0,...initial};
  tabs.push(tab);active=tab.id;renderTabs();renderDocument();return tab;
}
const isView=tab=>['views','materialized_views'].includes((tab.object??tab.table)?.parent.kind);
function builderFor(tab){if(!tab.builder)tab.builder=new VisualQueryBuilder({api,initialDirty:!!tab.dirty,connectionId:tab.connection,connectionName:profiles.find(p=>p.id===tab.connection)?.name??'Unavailable',database:tab.database??tab.table?.parent.database??'',selection:tab.table??null,sql:tab.type==='builder'?tab.sql:'',draft:tab.draft??tab.builderDraft,viewEditor:isView(tab)?objectDesigner(tab):null,openScript:(sql,database)=>{const script=openTab(tab.connection,'Query.sql');script.sql=sql;script.dirty=true;renderDocument();},changed:builder=>{if(tab.type==='builder'){tab.sql=builder.sql;tab.database=builder.database;}tab.dirty=isView(tab)?!!tab.designer?.dirty:builder.dirty||!!tab.designer?.dirty;tab.designer?.syncButtons();renderTabs();},openBuilder:(sql,database)=>openBuilder(tab.connection,sql,database),account:bytes=>{if(tabs.reduce((sum,t)=>sum+(t===tab?0:(t.bytes??0)+(t.table?t.builderBytes??0:0)),0)+bytes+(tab.table?tab.bytes??0:0)>MAX_BROWSER_BYTES)throw new Error('Browser result allowance full; close a result and retry.');tab.builderBytes=bytes;if(tab.type==='builder')tab.bytes=bytes;}});tab.builder.connectionName=profiles.find(p=>p.id===tab.connection)?.name??'Unavailable';return tab.builder;}
function openBuilder(connection=lastSelected,sql='',database=''){if(!profiles.some(p=>p.id===connection))throw new Error('Select a database connection before opening a query builder.');saveEditor();if(tabs.length>=MAX_TABS)throw new Error('Close a tab before opening another (maximum 12).');const tab={type:'builder',id:crypto.randomUUID(),title:'Query builder.sql',connection,database,sql,dirty:false,result:null,bytes:0,job:null};tabs.push(tab);active=tab.id;renderTabs();renderDocument();return tab;}
function tableQualifiedName(tab){const parent=(tab.table??tab.object).parent;return[parent.database,parent.schema,tab.title].filter(Boolean).join('.');}
function tableDesigner(tab){if(!tab.designer)tab.designer=new TableProperties({api,creation:!!tab.creating,target:()=>({connectionId:tab.connection,connectionName:profiles.find(p=>p.id===tab.connection)?.name??'Unavailable',...(tab.creating?{creation:true,target:tab.table.parent}:tab.table)}),changed:()=>{if(active===tab.id)for(const control of tableDocument.querySelectorAll('.table-inner-tabs button')){const key=control.id.replace('table-view-','');control.disabled=!!tab.creating&&key!=='properties';control.title=control.disabled?'Create the table successfully before opening '+key:'Show '+key;}tab.dirty=(tab.designer?.dirty??false)||(tab.builder?.dirty??false);renderTabs();},beforeApply:async()=>{
    const related=tabs.filter(t=>t.connection===tab.connection);if(related.some(t=>t.job))throw new Error('A Script is running on this connection. Wait for it before applying schema changes.');await Promise.all(related.map(stopScriptRefreshes));
  },saved:async fields=>{
    const wasCreating=!!tab.creating;const oldSchema=tab.table.parent.schema;for(const other of tabs.filter(t=>!wasCreating&&t.type==='table'&&t.connection===tab.connection&&t.table.key===tab.table.key&&t.table.parent.schema===oldSchema)){other.title=fields.name;other.table.parent.schema=fields.schema;other.result=null;other.bytes=0;other.loadAttempted=false;}
    // Resolve the renamed object through the catalog rather than inventing a vendor key.
    const parent={...tab.table.parent,offset:0};let offset=0,found=false;while(offset<=2000){const page=await waitJob(await api('/metadata/tree','POST',{connectionId:tab.connection,...parent,offset}));const node=page.nodes.find(n=>n.name===fields.name);if(node){tab.table={parent:{...parent,offset},key:node.key};found=true;break;}if(page.nextOffset===undefined)break;offset=page.nextOffset;}
    if(!found)throw new Error('Changes committed, but the renamed table could not be found on the bounded catalog pages. Refresh the tree and reopen the table.');
    if(wasCreating){tab.creating=false;tab.title=fields.name;tab.inner='properties';tab.designer.creation=false;}
    for(const entry of metadataTree.roots.values())if(entry.profile.id===tab.connection)await metadataTree.reload(entry);
    tab.dirty=!!tab.builder?.dirty;queueWorkspaceSave();renderTabs();
  }});return tab.designer;}
function tableIdentity(connection,selection){const p=selection.parent,k=({table_columns:'columns',table_constraints:'constraints',table_foreign_keys:'constraints',table_indexes:'indexes',table_triggers:'triggers',table_policies:'policies',table_rules:'rules'})[p.kind]??p.kind;return JSON.stringify([connection,p.database??'',p.schema??'',k,['columns','constraints','triggers','policies','rules'].includes(k)?p.table??p.name??'':'',selection.key]);}
function newTableState(item){return{...item,type:'table',inner:item.inner??'data',result:null,bytes:0,dirty:false,job:null};}
function openTable(profile,descriptor,selection){
  saveEditor();let tab=tabs.find(t=>t.type==='table'&&!t.creating&&tableIdentity(t.connection,t.table)===tableIdentity(profile.id,selection));
  if(!tab){if(tabs.length>=MAX_TABS)throw new Error('Close a tab before opening another (maximum 12).');tab=newTableState({id:crypto.randomUUID(),connection:profile.id,title:descriptor.name,table:selection});tabs.push(tab);}
  active=tab.id;renderTabs();renderDocument();
}
function openObject(profile,descriptor,selection){if(profile.transport&&profile.transport!=='jdbc')return openNative(profile.id,{title:descriptor.name,database:descriptor.database,collection:descriptor.collection,commandText:JSON.stringify(descriptor.command,null,2)});
  if(selection.parent.kind==='tables')return openTable(profile,descriptor,selection);
  saveEditor();let tab=tabs.find(t=>t.type==='object'&&!t.creating&&tableIdentity(t.connection,t.object)===tableIdentity(profile.id,selection));
  if(!tab){if(tabs.length>=MAX_TABS)throw new Error('Close a tab before opening another (maximum 12).');tab={id:crypto.randomUUID(),type:'object',connection:profile.id,title:descriptor.name,object:selection,dirty:false,result:null,bytes:0,job:null};tabs.push(tab);}
  active=tab.id;renderTabs();renderDocument();
}
function openNewObject(profile,group){
  saveEditor();if(tabs.length>=MAX_TABS)throw new Error('Close a tab before opening another (maximum 12).');
  const tab={id:crypto.randomUUID(),type:'object',connection:profile.id,title:'New '+group.name,object:{parent:{...group}},creating:true,dirty:false,result:null,bytes:0,job:null};
  tabs.push(tab);active=tab.id;renderTabs();renderDocument();
}
async function settleObjectConnection(id){
  const related=tabs.filter(t=>t.connection===id);
  if(related.some(t=>t.job))throw new Error('A Script is running on this connection. Wait for it before changing objects.');
  await Promise.all(related.map(stopScriptRefreshes));
}
async function invalidateObjectTabs(id,selection,action){
  for(const tab of tabs.filter(t=>t.connection===id&&['table','object'].includes(t.type)&&!t.creating&&tableIdentity(id,t.object??t.table)===tableIdentity(id,selection))){
    await stopScriptRefreshes(tab);tab.result=null;tab.bytes=0;tab.loadAttempted=false;
    if(action==='delete'){tab.deleted=true;if(tab.designer){tab.designer.unreconciled=true;tab.designer.message='This object was deleted. The draft is retained for reference.';}}
    else if(action==='rename'&&tab.designer){tab.designer.unreconciled=true;tab.designer.message='This object was renamed from the tree. Reopen it using its new identity.';}
    else if(tab.designer&&!tab.designer.dirty)await tab.designer.load();
  }
  renderTabs();renderDocument();
}
function objectDesigner(tab){
  if(!tab.designer)tab.designer=new ObjectProperties({
    api,creation:!!tab.creating,queryBuilder:()=>isView(tab)?tab.builder:null,
    target:()=>({connectionId:tab.connection,connectionName:profiles.find(p=>p.id===tab.connection)?.name??'Unavailable',...(tab.creating?{creation:true,target:tab.object.parent}:tab.object)}),
    changed:()=>{tab.dirty=!!tab.designer?.dirty||!!tab.builder?.dirty;tab.builder?.syncViewActions();renderTabs();},
    beforeApply:()=>settleObjectConnection(tab.connection),
    saved:async(fields,result)=>{
      const parent={...tab.object.parent,schema:fields.schema??tab.object.parent.schema,offset:0};let found=result?.selection?{key:result.selection.key,name:result.title}:null,offset=result?.selection?.parent.offset??0;
      const prior=tab.designer.snapshot,originalKey=tab.object.key;
      while(!found&&offset<=2000){
        const page=await waitJob(await api('/metadata/tree','POST',{connectionId:tab.connection,...parent,offset}));
        found=page.nodes.find(n=>!tab.creating&&n.key===originalKey);
        if(!found&&!['functions','procedures'].includes(parent.kind))found=page.nodes.find(n=>n.name===fields.name||n.objectName===fields.name||(!tab.creating&&prior?.node?.oid&&n.oid===prior.node.oid));
        if(found)break;if(page.nextOffset===undefined)break;offset=page.nextOffset;
      }
      if(!found&&['functions','procedures'].includes(parent.kind)){
        const wanted=fields.name;let candidates=[];offset=0;
        while(offset<=2000){const page=await waitJob(await api('/metadata/tree','POST',{connectionId:tab.connection,...parent,offset}));candidates.push(...page.nodes.filter(n=>n.name===wanted||n.name.startsWith(wanted+'(')).map(n=>({node:n,offset})));if(page.nextOffset===undefined)break;offset=page.nextOffset;}
        if(candidates.length===1){found=candidates[0].node;offset=candidates[0].offset;}
      }
      if(!found)throw new Error('Changes committed, but the object could not be resolved uniquely. Refresh its tree category and reopen it; do not repeat the SQL.');
      tab.object={parent:{...parent,offset},key:found.key};if(tab.table)tab.table=tab.object;tab.result=null;tab.bytes=0;tab.loadAttempted=false;tab.title=found.name;tab.creating=false;tab.deleted=false;
      for(const root of metadataTree.roots.values())if(root.profile.id===tab.connection)await metadataTree.reload(root);
      tab.dirty=false;queueWorkspaceSave();renderTabs();if(active===tab.id)renderDocument();
    }
  });
  return tab.designer;
}
function renderObjectDocument(tab){
  if(['views','materialized_views','foreign_tables','external_tables'].includes(tab.object.parent.kind)){tab.table=tab.object;tab.inner??='properties';renderTableDocument(tab);return;}
  destroyGrid();tableDocument.replaceChildren();
  if(tab.deleted){tableDocument.append(Object.assign(document.createElement('p'),{className:'designer-message',textContent:'This object was deleted. Close this tab or create a new object from its category.'}));return;}
  const nav=document.createElement('nav');nav.className='table-inner-tabs';nav.setAttribute('role','tablist');nav.setAttribute('aria-label','Object views');
  const panel=document.createElement('section');panel.id='object-panel-'+tab.id;panel.setAttribute('role','tabpanel');
  const props=button('Properties','Show object properties',()=>objectDesigner(tab).mount(panel));props.id='object-view-properties';props.setAttribute('role','tab');props.setAttribute('aria-selected','true');props.setAttribute('aria-controls',panel.id);panel.setAttribute('aria-labelledby',props.id);nav.append(props);tableDocument.append(nav,panel);objectDesigner(tab).mount(panel);
}
function openNewTable(profile,group){
  saveEditor();if(tabs.length>=MAX_TABS)throw new Error('Close a tab before opening another (maximum 12).');
  const tab=newTableState({id:crypto.randomUUID(),connection:profile.id,title:'New Table',table:{parent:{...group}},inner:'properties',creating:true});
  tabs.push(tab);active=tab.id;renderTabs();renderDocument();
}
function renderTableDocument(tab){
  if(isView(tab)&&tab.type==='table'){tab.type='object';tab.object=tab.table;}
  if(tab.deleted){destroyGrid();tableDocument.replaceChildren(Object.assign(document.createElement('p'),{className:'designer-message',textContent:'This table was deleted. Close this tab or create another table.'}));return;}
  destroyGrid();tab.builder?.unmount();tableDocument.replaceChildren();
  const nav=document.createElement('nav');nav.className='table-inner-tabs';nav.setAttribute('role','tablist');nav.setAttribute('aria-label','Table views');
  const panel=document.createElement('section');panel.className='table-panel';panel.setAttribute('role','tabpanel');panel.id='table-panel-'+tab.id;
  const viewObject=isView(tab),objectLabel=viewObject?(tab.object.parent.kind==='materialized_views'?'materialized view':'view'):'table';
  for(const [index,key] of ['properties','data','diagram'].entries()){
    const unavailable=!!tab.creating&&(key==='data'||key==='diagram'&&!viewObject);const control=button(key[0].toUpperCase()+key.slice(1),'Show '+key,()=>{tab.inner=key;renderTableDocument(tab);queueWorkspaceSave();tableDocument.querySelector('[aria-selected=true]').focus();});
    control.disabled=unavailable;if(unavailable)control.title='Create the '+objectLabel+' successfully before opening '+key;
    control.setAttribute('role','tab');control.id='table-view-'+key;control.setAttribute('aria-controls',panel.id);control.setAttribute('aria-selected',String(tab.inner===key));control.tabIndex=tab.inner===key?0:-1;
    control.addEventListener('keydown',event=>{const keys=tab.creating?(viewObject?['properties','diagram']:['properties']):['properties','data','diagram'];const current=keys.indexOf(key);let next;if(event.key==='ArrowRight')next=(current+1)%keys.length;else if(event.key==='ArrowLeft')next=(current+keys.length-1)%keys.length;else if(event.key==='Home')next=0;else if(event.key==='End')next=keys.length-1;else return;event.preventDefault();tab.inner=keys[next];renderTableDocument(tab);queueWorkspaceSave();tableDocument.querySelector('[aria-selected=true]').focus();});nav.append(control);
  }
  panel.setAttribute('aria-labelledby','table-view-'+tab.inner);tableDocument.append(nav,panel);
  if(tab.inner==='properties'){if(tab.type==='object'){objectDesigner(tab).mount(panel);return;}if(tab.table.parent.kind!=='tables'){panel.append(Object.assign(document.createElement('p'),{className:'table-placeholder',textContent:'View properties are read-only. Use Diagram to build and save SELECT queries; the saved database view is never changed.'}));return;}tableDesigner(tab).mount(panel);return;}
  if(tab.inner==='diagram'){const builder=builderFor(tab);builder.mount(panel);void builder.syncViewDefinition().catch(error=>builder.notice(error.message));return;}
  if(!profiles.some(p=>p.id===tab.connection)){const message=document.createElement('p');message.textContent='This connection is no longer available. Close this Table tab or restore its connection.';panel.append(message);return;}
  if(!tab.result){tab.result={kind:'rows',columns:[],rows:[],rowCount:0,tablePending:true};gridContexts.set(tab.result,{connectionId:tab.connection,parameters:[]});}
  const result=tab.result,context=gridContexts.get(result),controller=gridControllerFor(tab,result),host=document.createElement('div');host.className='data-grid-host table-data-grid';host.setAttribute('aria-label','Table data for '+tableQualifiedName(tab));panel.append(host);
  activeGrid=new DataGridView(host,result,{rowActions:true,sourceSql:context?.previewSql??context?.displaySql??result.sourceSql??context?.sql,notice:toast,transform:change=>controller.execute(change),refresh:controller.refresh,controller});
  if(result.tablePending){const retry=button('Load table data','Retry loading table data',()=>controller.execute({action:'refresh'}));retry.className='table-load';activeGrid.interactive.append(retry);if(!tab.loadAttempted){tab.loadAttempted=true;void controller.execute({action:'refresh'}).catch(()=>{});}}
}
async function loadTableData(tab,result,execution){
  const prepared=await execution.waitJob(await api('/metadata/table-query','POST',{connectionId:tab.connection,...tab.table}));execution.check();
  if(tab.closing||!tabs.includes(tab))return;
  gridContexts.set(result,{connectionId:tab.connection,database:prepared.database,sql:prepared.sql,parameters:[]});if(activeGrid?.result===result)activeGrid.updateData(prepared.sql);
  const response=await execution.submit({connectionId:tab.connection,database:prepared.database,sql:prepared.sql,parameters:[],autoCommit:false,rowLimit:pagePreference(result,tab.connection)});execution.check();
  if(tab.closing||!tabs.includes(tab))return;const rows=response.results?.filter(r=>r.kind==='rows');if(rows?.length!==1)throw new Error('Table query did not return one result set');
  const bytes=new TextEncoder().encode(JSON.stringify(rows[0])).length*3;if(tabs.reduce((n,t)=>n+(t===tab?0:(t.bytes??0)+(t.table?t.builderBytes??0:0)),0)+bytes+(tab.table?tab.builderBytes??0:0)>MAX_BROWSER_BYTES)throw new Error('Browser result allowance full; close another result and retry.');
  Object.assign(result,rows[0]);delete result.tablePending;tab.bytes=bytes;DataGridView.resetLayout(result);if(active===tab.id&&tab.inner==='data')renderTableDocument(tab);
}
async function stopGridRefresh(result){
  const controller=gridRefreshers.get(result);
  if(controller?.busy){controller.stopRefresh();await controller.cancel();try{await controller.completion;}catch(error){if(controller.uncertain){if(result.grid)result.grid.uncertain=true;controller.emit();throw error;}}}
  // Do not discard the snapshot/draft when cancellation could not be confirmed.
  await controller?.dispose();await releaseGrid(api,result);gridRefreshers.delete(result);
}
async function guardGrid(tab,result){return DataGridView.guard(result,()=>gridControllerFor(tab,result).execute({action:'save_rows'}));}
async function guardTabGrids(tab){for(const result of tab.result?.results??(tab.result?[tab.result]:[]))if(!await guardGrid(tab,result))return false;return !tab.builder||await tab.builder.guardRows();}
function stopScriptRefreshes(tab){const done=Promise.all([tab.gridDrain??Promise.resolve(),...(tab.result?.results??(tab.result?[tab.result]:[])).map(stopGridRefresh),...(tab.builder?[tab.builder.settle()]:[])]).then(()=>{});tab.gridDrain=done;done.catch(()=>{});return done;}
function pruneGridRefreshers(){const retained=new Set(tabs.flatMap(tab=>tab.result?.results??(tab.result?[tab.result]:[])));for(const result of gridRefreshers.keys())if(!retained.has(result))stopGridRefresh(result).catch(()=>{});}
function gridControllerFor(tab,result){DataGridView.bindPreferences(result,tab.connection);if(tab.closing||tab.job)return null;let controller=gridRefreshers.get(result);if(!controller){controller=new GridQueryController({api,run:(change,execution)=>result.tablePending?loadTableData(tab,result,execution):transformGrid(tab,result,change,execution),canRun:automatic=>!tab.closing&&!tab.job&&!(automatic&&(result.grid?.refreshRequired||document.hidden||document.querySelector('dialog[open]')||document.activeElement?.matches('input,textarea,[contenteditable=true]')))});gridRefreshers.set(result,controller);}return controller;}
window.addEventListener('pagehide',()=>{
  const ids=new Set();for(const tab of tabs){for(const result of tab.result?.results??(tab.result?[tab.result]:[]))if(result.grid?.id)ids.add(result.grid.id);if(tab.builder?.result?.grid?.id)ids.add(tab.builder.result.grid.id);tab.designer?.dispose();tab.native?.dispose();tab.compare?.dispose(true);tab.builder?.dispose().catch(()=>{});}
  for(const schedule of gridRefreshers.values())schedule.dispose().catch(()=>{});gridRefreshers.clear();
  if(csrf&&ids.size)fetch('/api/dba/grids/dispose',{method:'POST',credentials:'same-origin',keepalive:true,headers:{'Content-Type':'application/json','X-Dba-CSRF':csrf},body:JSON.stringify({ids:[...ids]})}).catch(()=>{});
});
// Paint only visible numbers: even a large file of blank lines has a bounded gutter DOM.
function updateLineNumbers(){
  const input=$('sql'),gutter=$('sql-gutter');
  if(gutterText!==input.value){gutterText=input.value;gutterLines=1;for(let i=gutterText.indexOf('\n');i!==-1;i=gutterText.indexOf('\n',i+1))gutterLines++;}
  gutter.dataset.lineCount=String(gutterLines);gutter.style.width='calc('+Math.max(2,String(gutterLines).length)+'ch + 20px)';
  if(gutterFrame)return;gutterFrame=requestAnimationFrame(()=>{
    gutterFrame=0;if(!$('sql').clientHeight)return;
    const style=getComputedStyle(input),lineHeight=parseFloat(style.lineHeight),padding=parseFloat(style.paddingTop);
    const start=Math.max(0,Math.floor((input.scrollTop-padding)/lineHeight)-2),end=Math.min(gutterLines,start+Math.ceil(input.clientHeight/lineHeight)+5);
    const numbers=$('sql-line-numbers'),labels=[];for(let line=start+1;line<=end;line++)labels.push(line);
    numbers.textContent=labels.join('\n');numbers.style.transform='translateY('+(padding+start*lineHeight-input.scrollTop)+'px)';
    gutter.style.height=input.clientHeight+'px';
  });
}
$('sql').addEventListener('scroll',updateLineNumbers,{passive:true});
new ResizeObserver(updateLineNumbers).observe($('sql'));
async function api(path,method='GET',body){const response=await fetch('/api/dba'+path,{method,credentials:'same-origin',headers:{'Content-Type':'application/json','X-Dba-CSRF':csrf,...approvalHeaders(path),...collaboration.headers()},body:body===undefined?undefined:JSON.stringify(body)});const data=await response.json();if(!response.ok){if(response.status===403&&data.error==='DBA session expired'){const session=await api('/bootstrap','POST',{});csrf=session.csrf;throw new Error('Session renewed. Reload this page to restore tab ownership; active tests must be repeated.');}const failure=new Error(data.error||'Request failed');failure.status=response.status;throw failure;}return data;}
function toast(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,6000);}
function safe(action){return (...args)=>{if(args[0]?.type==='submit')args[0].preventDefault();return Promise.resolve().then(()=>action(...args)).catch(e=>toast(e.message));};}
function button(text,title,action){const b=document.createElement('button');b.textContent=text;b.title=title;b.addEventListener('click',safe(action));return b;}
function workspaceState(){saveEditor();return{version:1,active:tabs.some(t=>t.id===active&&!t.creating&&t.type!=='compare')?active:(tabs.filter(t=>!t.creating&&t.type!=='compare').at(-1)?.id??null),lastSelected,tabs:tabs.filter(tab=>!tab.creating&&tab.type!=='compare').map(tab=>tab.type==='native'?{id:tab.id,type:'native',title:tab.title,connection:tab.connection,database:tab.database??'',collection:tab.collection??'',commandText:tab.commandText??''}:tab.type==='object'?{id:tab.id,type:'object',title:tab.title,connection:tab.connection,object:tab.object,inner:tab.inner??'properties',builderDraft:tab.builder?.draft??tab.builderDraft}:tab.type==='builder'?{id:tab.id,type:'builder',title:tab.title,connection:tab.connection,database:tab.builder?.database??tab.database,draft:tab.builder?.draft??tab.draft,sql:tab.builder?.sql??tab.sql,dirty:!!tab.dirty}:tab.type==='table'?{id:tab.id,type:'table',title:tab.title,connection:tab.connection,table:tab.table,inner:tab.inner,builderDraft:tab.builder?.draft??tab.builderDraft}:({id:tab.id,title:tab.title,lastEdited:tab.lastEdited??0,connection:tab.connection??null,sql:tab.sql,dirty:!!tab.dirty,editorRatio:tab.editorRatio??.38,railToggles:{serverOutput:!!tab.railToggles?.serverOutput,executionLog:!!tab.railToggles?.executionLog,sqlVariables:!!tab.railToggles?.sqlVariables},outputView:['serverOutput','executionLog','sqlVariables'].includes(tab.outputView)?tab.outputView:null}))};}
function queueWorkspaceSave(delay=700){if(!workspaceReady||!csrf)return;clearTimeout(workspaceTimer);workspaceTimer=setTimeout(flushWorkspace,delay);}
async function flushWorkspace(){clearTimeout(workspaceTimer);workspaceTimer=0;if(!workspaceReady||!csrf)return;if(workspaceSaving){workspaceQueued=true;return;}const state=workspaceState(),json=JSON.stringify(state);if(json===lastWorkspaceJson)return;if(new TextEncoder().encode(json).length>MAX_WORKSPACE_BYTES){toast('Open Script state exceeds the 16 MiB session workspace limit. Save or close a large Script to resume recovery updates.');return;}workspaceSaving=true;try{const saved=await api('/workspace','PUT',{...state,expectedWorkspaceRevision:workspaceRevision});workspaceRevision=saved.workspaceRevision;lastWorkspaceJson=json;}catch(e){console.warn('Workspace recovery update failed:',e.message);if(e.message.includes('Browser workspace changed')){workspaceReady=false;await restoreWorkspace();toast('The workspace changed in another paired client. The current server revision was restored; review the agent change before continuing.');}else workspaceQueued=true;}finally{workspaceSaving=false;if(workspaceQueued){workspaceQueued=false;queueWorkspaceSave(1000);}}}
function mergeAgentWorkspace(remote,change,local,baseline){const before=new Map((baseline?.tabs??[]).map(tab=>[tab.id,tab])),target=change.id,remoteById=new Map((remote.tabs??[]).map(tab=>[tab.id,tab]));let merged=false,conflict=false,blocked=false;for(const tab of local.tabs??[]){const changed=JSON.stringify(tab)!==JSON.stringify(before.get(tab.id));if(!changed)continue;if(tab.id!==target){const existing=(remote.tabs??[]).findIndex(item=>item.id===tab.id);if(existing<0)remote.tabs.push(tab);else remote.tabs[existing]=tab;merged=true;continue;}if((remote.tabs??[]).length>=MAX_TABS){blocked=true;continue;}const copy={...tab,id:crypto.randomUUID(),title:(tab.title||'Script')+' (local conflict)',dirty:true,lastEdited:Date.now()};remote.tabs.push(copy);remote.active=copy.id;merged=true;conflict=true;}if(local.active!==baseline?.active&&local.active!==target&&remoteById.has(local.active)){remote.active=local.active;merged=true;}if(local.lastSelected!==baseline?.lastSelected){remote.lastSelected=local.lastSelected;merged=true;}return{merged,conflict,blocked};}
async function restoreWorkspace(provided=null,serverJson=null){for(const tab of tabs)await tab.compare?.dispose();const saved=provided??await api('/workspace');workspaceRevision=Number(saved.workspaceRevision)||0;const restored=Array.isArray(saved.tabs)?saved.tabs.slice(0,MAX_TABS):[];tabs=restored.map(item=>item.type==='native'?{...item,result:null,bytes:0,job:null,dirty:false}:item.type==='object'?{...item,result:null,bytes:0,job:null,dirty:false}:item.type==='builder'?{...item,result:null,bytes:0,job:null}:item.type==='table'?newTableState(item):({id:item.id,title:item.title,lastEdited:item.lastEdited??0,connection:item.connection??null,sql:item.sql??'',savedText:item.dirty?null:(item.sql??''),dirty:!!item.dirty,fileHandle:null,params:'[]',autoCommit:false,result:null,resultIndex:0,outputView:item.outputView??null,error:'',warning:'',job:null,bytes:0,editorRatio:Math.max(.1,Math.min(.9,Number(item.editorRatio)||.38)),railToggles:{serverOutput:!!item.railToggles?.serverOutput,executionLog:!!item.railToggles?.executionLog,sqlVariables:!!item.railToggles?.sqlVariables}}));active=tabs.some(tab=>tab.id===saved.active)?saved.active:(tabs.at(-1)?.id??null);if(profiles.some(profile=>profile.id===saved.lastSelected))lastSelected=saved.lastSelected;renderTabs();renderDocument();workspaceReady=true;lastWorkspaceJson=serverJson??JSON.stringify(workspaceState());if(serverJson)queueWorkspaceSave(0);if(active&&isScript(tabs.find(t=>t.id===active)))requestAnimationFrame(()=>$('sql').focus());}
function showAuthorizationMode(session){
  for(const id of ['yolo-warning','yolo-settings-warning']){const node=$(id);node.hidden=!session.yolo;node.title=session.warning||'';}
  $('yolo-settings-warning').textContent=session.yolo?session.warning:'';
  const approvals=$('agent-approvals');if(approvals)approvals.hidden=!!session.yolo;
}
async function initialize(){workspaceReady=false;const session=await api('/bootstrap','POST',{});csrf=session.csrf;showAuthorizationMode(session);await initializeGridPreferences(api);await collaboration.register();await refresh();await restoreWorkspace();connectEditorEvents();await collaboration.start();for(const region of startupRegions){region.inert=false;region.removeAttribute('aria-busy');}}
document.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>{if(b.dataset.close==='agents-dialog'){$('agent-token').value='';$('agent-token-label').hidden=true;}$(b.dataset.close).close();});
const editor=connectionEditor({api,toast,saved:refresh,csrf:()=>csrf});
const treeActions=new TreeActions({api,wait:waitJob,notice:toast});
const connectionTree=new ConnectionTree($('tree'),{api,notice:toast,select:chooseConnection,load:loadSchemas,wait:waitJob,edit:profileDialog,
  open:id=>{chooseConnection(id);return openScript();},new:id=>openTab(id),native:id=>openNative(id),test:profile=>editor.testSaved(profile),
  builder:(id,object)=>{openObject(profiles.find(p=>p.id===id),object.descriptor,object.selection);const tab=tabs.find(t=>t.id===active);tab.inner='diagram';renderDocument();},addToBuilder:payload=>{const tab=tabs.find(t=>t.id===active);if(!tab?.builder)throw new Error('Open a Visual Query Builder or Diagram first.');return tab.builder.addSource(payload);},
  hasLast:id=>tabs.some(tab=>isScript(tab)&&tab.connection===id),last:id=>{saveEditor();const tab=tabs.filter(tab=>isScript(tab)&&tab.connection===id).sort((a,b)=>(b.lastEdited??0)-(a.lastEdited??0))[0];if(tab){active=tab.id;renderTabs();renderDocument();$('sql').focus();}},
  create:(profile,group,refresh)=>group.kind==='tables'?openNewTable(profile,group):openNewObject(profile,group),remove:confirmRemoveConnection,rename:profile=>treeActions.connection(profile,refresh),objectAction:(action,profile,selection,plan,refresh)=>treeActions.object(action,profile,selection,plan,async()=>{await invalidateObjectTabs(profile.id,selection,action);return refresh();},()=>settleObjectConnection(profile.id)),order:async ids=>{const ordered=await api('/connections/order','PUT',{ids});profiles=ordered;}
});
let removalTarget=null,removalBusy=false;
function confirmRemoveConnection(profile){removalTarget={id:profile.id,name:profile.name};$('remove-connection-name').textContent=profile.name;$('remove-connection-error').hidden=true;$('remove-connection-dialog').showModal();$('remove-connection-cancel').focus();}
function cancelRemoveConnection(){if(!removalBusy)$('remove-connection-dialog').close();}
$('remove-connection-cancel').onclick=cancelRemoveConnection;$('remove-connection-close').onclick=cancelRemoveConnection;
$('remove-connection-dialog').addEventListener('cancel',e=>{if(removalBusy)e.preventDefault();});
$('remove-connection-dialog').addEventListener('close',()=>{removalTarget=null;});
$('remove-connection-confirm').onclick=async()=>{
  if(!removalTarget||removalBusy)return;const target=removalTarget;removalBusy=true;const dialog=$('remove-connection-dialog');dialog.setAttribute('aria-busy','true');dialog.querySelectorAll('button').forEach(b=>b.disabled=true);$('remove-connection-error').hidden=true;
  try{await api('/connections/'+target.id,'DELETE');dialog.close();await refresh();toast('Removed connection '+target.name+'.');}
  catch(error){$('remove-connection-error').textContent=error.message;$('remove-connection-error').hidden=false;}
  finally{removalBusy=false;dialog.removeAttribute('aria-busy');dialog.querySelectorAll('button').forEach(b=>b.disabled=false);}
};
function profileDialog(profile=null){editor.open(profile).catch(e=>toast(e.message));}
$('add').onclick=()=>profileDialog();$('welcome-add').onclick=()=>profileDialog();$('refresh').onclick=safe(refresh);
function chooseConnection(id){lastSelected=id;connectionTree.select(id);queueWorkspaceSave();}
async function refresh(){saveEditor();profiles=await api('/connections');metadataTree.retain(profiles);for(const tab of tabs)if(!profiles.some(p=>p.id===tab.connection)){if(tab.type==='table')stopScriptRefreshes(tab);if(tab.type==='native')tab.native?.invalidate('Connection removed · command execution is disabled; this draft is retained.');}if(!profiles.some(p=>p.id===lastSelected))lastSelected=profiles.length===1?profiles[0].id:null;$('connection-count').textContent=profiles.length+' connection'+(profiles.length===1?'':'s');$('empty').hidden=profiles.length>0;connectionTree.render(profiles,lastSelected);renderTabs();renderDocument();}
const decisionQueue=[];let activeDecision=null;
function jobDecision(job,tab){return new Promise(resolve=>{decisionQueue.push({job,tab,resolve});showNextDecision();});}
function showNextDecision(){if(activeDecision||!decisionQueue.length)return;activeDecision=decisionQueue.shift();const {job,tab}=activeDecision,decision=job.decision,dialog=$('sql-error-decision'),actions=new Set(decision.actions??[]),connection=tab?.gridConnectionName??profiles.find(p=>p.id===tab?.connection)?.name??'Disconnected';$('decision-context').textContent=(tab?.title??'Script')+' · '+connection+' · statement '+decision.statementIndex;$('decision-message').textContent=decision.message;$('decision-impact').textContent=decision.canContinue?(decision.transactional?'The failed statement was rolled back to its savepoint. Continuing will commit the other successful statements when the script completes.':'Auto-commit is enabled. Earlier statements may already be committed.'):'This driver could not safely roll back the failed statement. Only cancellation is available.';$('decision-continue').disabled=!actions.has('continue');$('decision-skip').disabled=!actions.has('skip_similar');const update=()=>{$('decision-countdown').textContent=Math.max(0,Math.ceil((decision.expiresAt-Date.now())/1000))+' seconds remaining';if(Date.now()>=decision.expiresAt)finishDecision();};dialog.showModal();activeDecision.timer=setInterval(update,250);update();}
function finishDecision(){if(!activeDecision)return;clearInterval(activeDecision.timer);if($('sql-error-decision').open)$('sql-error-decision').close();const done=activeDecision;activeDecision=null;done.resolve();showNextDecision();}
async function chooseJobDecision(action){if(!activeDecision)return;const {job}=activeDecision;try{await api('/jobs/'+job.id+'/decision','POST',{decisionId:job.decision.id,action});finishDecision();}catch(e){toast(e.message);}}
function dismissJobDecision(id){if(activeDecision?.job.id===id)finishDecision();for(let i=decisionQueue.length-1;i>=0;i--)if(decisionQueue[i].job.id===id)decisionQueue.splice(i,1)[0].resolve();}
$('decision-cancel').onclick=()=>chooseJobDecision('cancel');$('decision-close').onclick=()=>chooseJobDecision('cancel');$('decision-skip').onclick=()=>chooseJobDecision('skip_similar');$('decision-continue').onclick=()=>chooseJobDecision('continue');$('sql-error-decision').addEventListener('cancel',e=>{e.preventDefault();chooseJobDecision('cancel');});
async function waitJob(job,tab=null){if(tab){tab.job=job.id;if(tab.closing||!tabs.includes(tab))await api('/jobs/'+job.id+'/cancel','POST',{});if(active===tab.id)renderDocument();}try{let prompted='';while(true){await new Promise(r=>setTimeout(r,250));const current=await api('/jobs/'+job.id);if(current.state==='awaiting_decision'&&current.decision?.id!==prompted){prompted=current.decision.id;await jobDecision(current,tab);continue;}if(!current.finished)continue;if(['failed','cancelled'].includes(current.state)){const error=new Error(current.error||current.state);error.result=current.result;error.interrupted=current.state==='cancelled';throw error;}if(current.state==='complete')return current.result;}}finally{try{await api('/jobs/'+job.id,'DELETE');}catch{}if(tab){tab.job=null;if(active===tab.id)renderDocument();}}}
const metadataTree=new MetadataTreeView({api,wait:waitJob,notice:toast,select:chooseConnection,openTable:safe(openObject),menu:(id,anchor,refresh,object,group)=>connectionTree.openMetadataMenu(id,anchor,refresh,object,group)});
async function loadSchemas(p,container){await metadataTree.load(p,container);}
function quote(name){return '"'+String(name).replaceAll('"','""')+'"';}
function saveEditor(){const tab=tabs.find(t=>t.id===active);if(isScript(tab)){if(tab.sql!==$('sql').value)tab.lastEdited=Date.now();tab.sql=$('sql').value;tab.dirty=tab.savedText===null||tab.sql!==tab.savedText;}}
function scriptSqlForExecution(tab){
  const editor=$('sql'),selected=editor.value.substring(editor.selectionStart,editor.selectionEnd);
  // Keep the native selection visible when Run or Explain takes focus from the editor.
  if(editor.selectionStart!==editor.selectionEnd)editor.focus({preventScroll:true});
  return selected||tab.sql;
}
function openTab(connection=lastSelected,title='Untitled.sql'){if(profiles.some(p=>p.id===connection&&p.transport&&p.transport!=='jdbc'))return openNative(connection);saveEditor();if(tabs.length>=MAX_TABS)throw new Error('Close a tab before opening another (maximum 12).');const tab={type:'script',id:crypto.randomUUID(),lastEdited:Date.now(),title,connection,sql:'',savedText:'',dirty:false,fileHandle:null,params:'[]',autoCommit:false,result:null,resultIndex:0,outputView:null,error:'',warning:'',job:null,bytes:0,editorRatio:.38,railToggles:{serverOutput:false,executionLog:false,sqlVariables:false}};tabs.push(tab);active=tab.id;renderTabs();renderDocument();return tab;}
function openCompare(){
  saveEditor();if(tabs.length>=MAX_TABS)throw new Error('Close a tab before opening another (maximum 12).');
  const tab={id:crypto.randomUUID(),type:'compare',title:'Database Compare',bytes:0};
  tab.compare=new DatabaseCompare({api,profiles:()=>profiles,notify:toast,csrf:()=>csrf,close:()=>{tabs=tabs.filter(t=>t!==tab);if(active===tab.id)active=tabs.at(-1)?.id??null;renderTabs();renderDocument();}});
  tabs.push(tab);active=tab.id;renderTabs();renderDocument();
}
const newCompare=button('Compare','Compare databases',()=>openCompare());newCompare.id='new-database-compare';$('file-tools').after(newCompare);
const newBuilder=button('','New Visual Query Builder',()=>openBuilder());newBuilder.id='new-query-builder';newBuilder.classList.add('icon-button');newBuilder.setAttribute('aria-label','New Visual Query Builder');newBuilder.append(lucide('workflow'));$('file-tools').after(newBuilder);
const selectionBuilder=button('','Open selected SQL in Visual Query Builder',()=>{saveEditor();const tab=tabs.find(t=>t.id===active);if(!isScript(tab))return;const sql=$('sql').value.substring($('sql').selectionStart,$('sql').selectionEnd);if(!sql.trim())throw new Error('Select one SELECT query in the Script editor first.');openBuilder(tab.connection,sql);});selectionBuilder.id='selection-query-builder';selectionBuilder.classList.add('icon-button');selectionBuilder.setAttribute('aria-label','Open selected SQL in Visual Query Builder');selectionBuilder.append(lucide('workflow'));$('stop').after(selectionBuilder);
const RAIL_TOGGLES=[['toggle-server-output','serverOutput','Server output'],['toggle-execution-log','executionLog','Execution log'],['toggle-sql-variables','sqlVariables','SQL variables']];
function renderRailToggles(tab){for(const [id,key] of RAIL_TOGGLES){const control=$(id);control.disabled=!tab;control.setAttribute('aria-pressed',String(!!tab?.railToggles?.[key]));}}
const MAX_CONNECTION_NAME_CHARS=50,connectionNameCanvas=document.createElement('canvas').getContext('2d');
function connectionOptionName(name){const characters=Array.from(name);return characters.length>MAX_CONNECTION_NAME_CHARS?characters.slice(0,MAX_CONNECTION_NAME_CHARS).join('')+'…':name;}
function sizeConnectionSelector(select){const label=select.selectedOptions[0]?.textContent??'';connectionNameCanvas.font=getComputedStyle(select).font;select.style.width=Math.ceil(Math.max(34,connectionNameCanvas.measureText(label).width+16))+'px';}
function renderTabs(){
  const nav=$('tabs');nav.replaceChildren();
  for(const tab of tabs){
    const item=document.createElement('div');item.className='tab'+(tab.id===active?' active':'');item.dataset.tab=tab.id;item.dataset.type=tab.type??'script';item.draggable=true;
    item.addEventListener('dragstart',e=>{if(e.target.closest('select')){e.preventDefault();return;}e.dataTransfer.setData('text/plain',tab.id);});
    item.addEventListener('dragover',e=>e.preventDefault());item.addEventListener('drop',e=>{e.preventDefault();const from=tabs.findIndex(t=>t.id===e.dataTransfer.getData('text/plain')),to=tabs.indexOf(tab);if(from<0)return;const [moved]=tabs.splice(from,1);tabs.splice(to,0,moved);renderTabs();});
    const connection=tab.type==='compare'?'Source → destination':profiles.find(p=>p.id===tab.connection)?.name??'Not connected';
    const select=button((tab.dirty?'● ':'')+tab.title,'Select tab · '+connection,()=>{saveEditor();active=tab.id;renderTabs();renderDocument();});
    const target=document.createElement('select');target.className='tab-connection';target.setAttribute('aria-label','Execution connection for '+tab.title+': '+connection);target.title='Execution connection: '+connection+' · Change for this script only';
    const blank=document.createElement('option');blank.value='';blank.textContent='Choose connection…';target.append(blank);
    for(const profile of profiles){const option=document.createElement('option');option.value=profile.id;option.textContent=connectionOptionName(profile.name);option.title=profile.name;option.setAttribute('aria-label',profile.name);target.append(option);}
    target.value=profiles.some(p=>p.id===tab.connection)?tab.connection:'';target.disabled=!!tab.job;
    target.addEventListener('change',safe(async()=>{
      if(tab.job)return;if(!await guardTabGrids(tab)){target.value=tab.connection??'';return;}saveEditor();const nextConnection=target.value||null;tab.job='submitting';renderDocument();try{await stopScriptRefreshes(tab);}catch(error){tab.error=error.message;return;}finally{tab.job=null;renderDocument();}if(!tabs.includes(tab))return;active=tab.id;tab.connection=nextConnection;chooseConnection(tab.connection);
      tab.result=null;tab.bytes=0;tab.resultConnection=null;tab.error='';tab.warning='';renderTabs();renderDocument();
      document.querySelector('.tab.active .tab-connection')?.focus();toast('Only this script now targets '+(profiles.find(p=>p.id===tab.connection)?.name??'no connection')+'.');
    }));
    const close=button('×','Close tab and release results',async()=>{if(!await guardTabGrids(tab))return;if(tab.native&&!tab.native.canClose())return;if(tab.designer){if(!await tab.designer.canClose())return;}if(tab.closing||(tab.designer?!isView(tab)&&!!tab.builder?.dirty:tab.dirty)&&!confirm('Discard unsaved changes to '+tab.title+'?'))return;tab.closing=true;try{await stopScriptRefreshes(tab);await tab.builder?.dispose();await tab.compare?.dispose();tab.designer?.dispose();tab.native?.dispose();if(tab.job&&tab.job!=='submitting'){await api('/jobs/'+tab.job+'/cancel','POST',{});dismissJobDecision(tab.job);}tabs=tabs.filter(t=>t!==tab);if(active===tab.id)active=tabs.at(-1)?.id??null;}finally{tab.closing=false;renderTabs();renderDocument();}});
    close.className='close';if(!isScript(tab)){const fixed=document.createElement('span');fixed.className='tab-connection-fixed';fixed.textContent=connectionOptionName(connection);fixed.title=connection+' · '+(['table','object'].includes(tab.type)?tableQualifiedName(tab):tab.database??'');item.append(select,fixed,close);select.title=(['table','object'].includes(tab.type)?'Open object · '+tableQualifiedName(tab):tab.type==='compare'?'Open Database Compare':'Open Visual Query Builder')+' · '+connection;}else item.append(select,target,close);nav.append(item);if(isScript(tab))sizeConnectionSelector(target);
  }
  queueWorkspaceSave();
}
function renderDocument(){
  pruneGridRefreshers();for(const open of tabs){open.builder?.unmount();open.native?.unmount();open.compare?.unmount();}
  const tab=tabs.find(t=>t.id===active);$('welcome').hidden=!!tab;$('document').hidden=!isScript(tab);$('table-document').hidden=!tab||isScript(tab);
  renderRailToggles(isScript(tab)?tab:null);
  for(const id of ['file-save','file-save-as'])if($(id))$(id).disabled=!isScript(tab);
  $('new-tab').disabled=tabs.length>=MAX_TABS;if($('file-open'))$('file-open').disabled=tabs.length>=MAX_TABS;
  document.querySelectorAll('.tab-connection').forEach(select=>{select.disabled=!!tabs.find(t=>t.id===select.closest('.tab').dataset.tab)?.job;});
  queueWorkspaceSave();if(!tab){destroyGrid();$('table-document').replaceChildren();return;}if(tab.type==='compare'){destroyGrid();tab.compare.mount(tableDocument);return;}if(tab.type==='native'){destroyGrid();const view=nativeFor(tab);if(view)view.mount(tableDocument);else{const notice=document.createElement('p');notice.setAttribute('role','status');notice.textContent='Connection removed · reopen a native workspace with an existing connection. The saved draft is retained.';const draft=document.createElement('pre');draft.textContent=tab.commandText??'';tableDocument.replaceChildren(notice,draft);}return;}if(tab.type==='object'){renderObjectDocument(tab);return;}if(tab.type==='builder'){destroyGrid();tableDocument.replaceChildren();builderFor(tab).mount(tableDocument);return;}if(tab.type==='table'){$('run').disabled=true;$('explain').disabled=true;$('stop').disabled=true;renderTableDocument(tab);return;}$('table-document').replaceChildren();
  if($('sql').value!==tab.sql)$('sql').value=tab.sql;applyEditorSize(tab);updateLineNumbers();const target=profiles.find(p=>p.id===tab.connection);
  $('run').disabled=!!tab.job||!target;$('explain').disabled=$('run').disabled;$('stop').disabled=!tab.job||tab.job==='submitting';
  $('run').title=target?'Run SQL selection (or entire editor) on '+target.name+' (Ctrl+Enter)':'Choose a connection in this tab before running SQL';
  $('explain').title=target?'Explain SELECT on '+target.name+' without executing it':'Choose a connection in this tab before explaining SQL';
  $('status').textContent=tab.job?'Running on '+(tab.gridConnectionName??target?.name??'disconnected'):tab.result?tab.result.rowCount+' rows'+(tab.result.affectedRows!==undefined?' · '+tab.result.affectedRows+' affected':'')+(tab.result.outcome?' · '+(tab.result.outcome==='commit_acknowledged'?'commit acknowledged':'auto-commit completed'):'')+(tab.result.truncated?' · truncated':'')+(tab.result.cellsTruncated?' · cell previews shortened':''):'Ready';
  $('warning').hidden=!tab.warning;$('warning').textContent=tab.warning;$('error').hidden=!tab.error;$('error').textContent=tab.error;renderResults(tab);
}
const editorDivider=$('editor-output-divider');
function applyEditorSize(tab){
  if(!isScript(tab))return;const available=Math.max(1,$('document').clientHeight-editorDivider.offsetHeight),minimum=Math.min(100,Math.floor(available*.4)),maximum=Math.max(minimum,available-minimum),height=Math.max(minimum,Math.min(maximum,available*(tab.editorRatio??.38)));
  $('script-editor').style.flexBasis=height+'px';tab.editorRatio=height/available;editorDivider.setAttribute('aria-valuenow',String(Math.round(tab.editorRatio*100)));updateLineNumbers();
}
function resizeScriptWorkspace(clientY){const tab=tabs.find(t=>t.id===active);if(!isScript(tab))return;const bounds=$('document').getBoundingClientRect(),available=Math.max(1,$('document').clientHeight-editorDivider.offsetHeight),minimum=Math.min(100,Math.floor(available*.4)),maximum=Math.max(minimum,available-minimum),height=Math.max(minimum,Math.min(maximum,clientY-bounds.top));tab.editorRatio=height/available;applyEditorSize(tab);}
editorDivider.addEventListener('pointerdown',e=>{editorDivider.setPointerCapture(e.pointerId);editorDivider.classList.add('dragging');});
editorDivider.addEventListener('pointermove',e=>{if(editorDivider.hasPointerCapture(e.pointerId))resizeScriptWorkspace(e.clientY);});
editorDivider.addEventListener('pointerup',e=>{if(editorDivider.hasPointerCapture(e.pointerId))editorDivider.releasePointerCapture(e.pointerId);editorDivider.classList.remove('dragging');queueWorkspaceSave(0);});
editorDivider.addEventListener('pointercancel',()=>editorDivider.classList.remove('dragging'));
editorDivider.addEventListener('keydown',e=>{if(!['ArrowUp','ArrowDown','Home','End'].includes(e.key))return;e.preventDefault();const bounds=$('document').getBoundingClientRect(),editor=$('script-editor').getBoundingClientRect();if(e.key==='Home')resizeScriptWorkspace(bounds.top);else if(e.key==='End')resizeScriptWorkspace(bounds.bottom);else resizeScriptWorkspace(editor.bottom+(e.key==='ArrowUp'?-20:20));queueWorkspaceSave();});
new ResizeObserver(()=>{const tab=tabs.find(t=>t.id===active);if(tab&&!$('document').hidden)applyEditorSize(tab);}).observe($('document'));
function setResult(tab,result){const bytes=new TextEncoder().encode(JSON.stringify(result)).length*3;if(tabs.reduce((n,t)=>n+(t===tab?0:(t.bytes??0)+(t.table?t.builderBytes??0:0)),0)+bytes+(tab.table?tab.builderBytes??0:0)>MAX_BROWSER_BYTES)throw new Error('Browser result allowance full; close a result tab.');tab.result=result;for(const entry of result.results??[result])if(entry.kind==='rows')DataGridView.bindPreferences(entry,tab.connection);tab.bytes=bytes;const hasDataGrid=result?.kind==='rows'||result?.results?.some(item=>item.kind==='rows');if(hasDataGrid){tab.resultIndex=0;tab.outputView='result';}const origin=tab.pendingExecution;if(origin)for(const entry of result.results??[result]){const statement=result.statements?.find(s=>s.index===entry.statementIndex);if(!statement||entry.kind!=='rows')continue;const valid=origin.parameters.length===0||Number.isInteger(statement.parameterOffset)&&Number.isInteger(statement.parameterCount);if(valid)gridContexts.set(entry,{connectionId:origin.connectionId,sql:statement.sql,parameters:origin.parameters.slice(statement.parameterOffset??0,(statement.parameterOffset??0)+(statement.parameterCount??0))});}}
function destroyGrid(){if(activeGrid){activeGrid.destroy();activeGrid=null;}else $('grid').replaceChildren();$('grid').className='';}
function renderGrid(result,sourceSql=null){destroyGrid();const grid=$('grid');if(!result)return;
if(result.kind==='update'){const summary=document.createElement('p');summary.className='command-summary';summary.textContent='Command completed · '+result.affectedRows+' rows affected';grid.append(summary);return;}
if(result.columns&&result.rows){const tab=tabs.find(t=>t.id===active),context=gridContexts.get(result),controller=context?gridControllerFor(tab,result):null;activeGrid=new DataGridView(grid,result,{sourceSql:context?.previewSql??context?.displaySql??result.sourceSql??context?.sql??sourceSql,notice:toast,transform:controller?change=>controller.execute(change):null,refresh:controller?.refresh,controller});if(result.observations){const evidence=document.createElement('pre');evidence.className='plan-evidence';evidence.textContent=result.analysis+'\n'+(result.observationsUnavailable||result.observations.map(o=>JSON.stringify(o)).join('\n'));activeGrid.command.after(evidence);}}}
async function transformGrid(tab,result,change,execution){
  const context=gridContexts.get(result),container=tab?.result;
  const refreshOnly=change.action==='refresh'||change.action==='expression'&&change.expression===(context?.filterExpression??'');
  if(!context||!tabs.includes(tab)||tab.job)throw new Error('Wait for the current job before changing result SQL.');
  const connection=profiles.find(p=>p.id===context.connectionId);if(!connection)throw new Error('The original result connection is no longer available. Run the SELECT again.');
  const retained=()=>!tab.closing&&tabs.includes(tab)&&tab.result===container&&(container===result||container.results?.includes(result));
  const updateView=()=>{if(activeGrid?.result===result){const current=gridContexts.get(result);activeGrid.updateData(current?.previewSql??current?.displaySql??result.sourceSql??current?.sql);}};
  const replace=async(next,options={})=>{
    const replacement={...next,statementIndex:result.statementIndex},projected=container===result?replacement:{...container,results:container.results.map(item=>item===result?replacement:item)};if(projected.results){const first=projected.results.find(item=>item.kind==='rows');projected.rows=first?.rows??[];projected.columns=first?.columns??[];}const bytes=new TextEncoder().encode(JSON.stringify(projected)).length*3;
    if(tabs.reduce((n,t)=>n+(t===tab?0:(t.bytes??0)+(t.table?t.builderBytes??0:0)),0)+bytes+(tab.table?tab.builderBytes??0:0)>MAX_BROWSER_BYTES)throw Error('Browser result allowance full; close another result.');
    if(!retained())return;
    if(next!==result)DataGridView.replacePage(result,replacement,options.window);else Object.assign(result,replacement);
    if(next.sourceSql)gridContexts.set(result,{...gridContexts.get(result),displaySql:next.sourceSql});
    if(container.results){const first=container.results.find(r=>r.kind==='rows');container.rows=first?.rows??[];container.columns=first?.columns??[];}
    tab.bytes=new TextEncoder().encode(JSON.stringify(container)).length*3;updateView();
  };
  const ownedOptions={api,replace};
  if(!['save_rows','review_rows','reconcile','count'].includes(change.action)&&!await DataGridView.guard(result,()=>runOwnedGrid(result,{action:'save_rows'},execution,ownedOptions)))return;
  if(['save_rows','review_rows','page','export','reconcile','count'].includes(change.action)){await runOwnedGrid(result,change,execution,ownedOptions);return;}
  if(refreshOnly&&(result.grid?.capabilities.rowLimit??result.grid?.capabilities.page)){await runOwnedGrid(result,{action:'page',direction:'refresh',limit:pagePreference(result)},execution,ownedOptions);return;}
  try{
    const {displaySql,previewSql,...requestContext}=context;
    const edited=await api('/query/grid-edit','POST',{...requestContext,columns:result.columns,...change,...(refreshOnly?{action:'refresh'}:{})});execution.check();if(!retained())return;
    if(refreshOnly)edited.displaySql=context.displaySql??context.sql;
    if(change.action!=='filter_values'){gridContexts.set(result,{...context,previewSql:edited.displaySql??edited.sql});if(!refreshOnly)DataGridView.setFilter(result,edited.filterExpression??'');updateView();}execution.check();
    const response=await execution.submit({connectionId:context.connectionId,sql:edited.sql,parameters:edited.parameters,autoCommit:false,rowLimit:pagePreference(result),...(context.database!==undefined?{database:context.database}:{})});
    if(!retained())return;
    const rows=response.results?.filter(r=>r.kind==='rows')??[];if(rows.length!==1)throw new Error('The edited SELECT did not return exactly one result; the old grid was retained.');
    const replacement={...rows[0],statementIndex:result.statementIndex};
    if(JSON.stringify(replacement.columns)!==JSON.stringify(result.columns))throw new Error('The result columns changed. Run the SELECT again to rebuild the grid.');
    const projected=container.results?{...container,results:container.results.map(r=>r===result?replacement:r)}:replacement;
    if(projected.results){const first=projected.results.find(r=>r.kind==='rows');projected.rows=first?.rows??[];projected.columns=first?.columns??[];}
    const bytes=new TextEncoder().encode(JSON.stringify(projected)).length*3;
    if(tabs.reduce((n,t)=>n+(t===tab?0:(t.bytes??0)+(t.table?t.builderBytes??0:0)),0)+bytes+(tab.table?tab.builderBytes??0:0)>MAX_BROWSER_BYTES)throw new Error('Browser result allowance full; the old grid was retained.');
    await releaseGrid(api,result);DataGridView.replacePage(result,replacement);DataGridView.clearSelection(result);gridContexts.set(result,{...context,...edited,displaySql:replacement.sourceSql??edited.displaySql??edited.sql});
    container.rowCount=(container.results??[result]).filter(r=>r.kind==='rows').reduce((n,r)=>n+r.rowCount,0);container.truncated=(container.results??[result]).some(r=>r.truncated);
    if(container.results){const first=container.results.find(r=>r.kind==='rows');container.rows=first?.rows??[];container.columns=first?.columns??[];}
    tab.bytes=new TextEncoder().encode(JSON.stringify(container)).length*3;DataGridView.setValueFilters(result,edited.valueFilters??[]);if(!refreshOnly)DataGridView.setFilter(result,edited.filterExpression??'');updateView();return true;
  }catch(error){if(retained()){gridContexts.set(result,context);updateView();}throw error;}
}
$('new-tab').onclick=safe(()=>openTab(lastSelected));
$('run').onclick=safe(async()=>{saveEditor();const tab=tabs.find(t=>t.id===active);if(!isScript(tab)||tab.job||!profiles.some(p=>p.id===tab.connection))return;if(!await guardTabGrids(tab))return;const sql=scriptSqlForExecution(tab),connectionId=tab.connection;tab.job='submitting';try{await stopScriptRefreshes(tab);}catch(error){tab.job=null;tab.error=error.message;renderDocument();return;}if(!tabs.includes(tab)){tab.job=null;return;}tab.error='';tab.warning='';tab.result=null;tab.resultIndex=0;tab.outputView='result';tab.bytes=0;tab.pendingExecution=null;tab.resultConnection=profiles.find(p=>p.id===connectionId).name;tab.job='submitting';renderDocument();try{const parameters=JSON.parse(tab.params);tab.pendingExecution={connectionId,parameters};const result=await waitJob(await api('/query/execute','POST',{connectionId,sql,parameters,autoCommit:!!tab.autoCommit,rowLimit:defaultPageSize(connectionId)}),tab);if(tabs.includes(tab)){setResult(tab,result);if(result.completedWithErrors)tab.warning='Script completed with skipped statement errors. Review the statement error decisions before relying on these partial results.';}}catch(e){if(e.result&&tabs.includes(tab)){setResult(tab,e.result);tab.warning=e.message+' Completed result sets were retained; transactional changes were rolled back where possible.';}else tab.error=e.message;}finally{tab.job=null;tab.pendingExecution=null;}renderDocument();});
$('stop').onclick=safe(async()=>{const tab=tabs.find(t=>t.id===active);if(tab?.job){await api('/jobs/'+tab.job+'/cancel','POST',{});dismissJobDecision(tab.job);}});
$('sql').addEventListener('input',()=>{saveEditor();updateLineNumbers();renderTabs();});
function shiftEditorLines(input,outdent){const value=input.value,start=input.selectionStart,end=input.selectionEnd,direction=input.selectionDirection,scrollTop=input.scrollTop,scrollLeft=input.scrollLeft;if(start===end&&!outdent){input.setRangeText('\t',start,end,'end');return;}const blockStart=value.lastIndexOf('\n',start-1)+1,lastPosition=end>start&&value[end-1]==='\n'?end-1:end;let blockEnd=value.indexOf('\n',lastPosition);if(blockEnd<0)blockEnd=value.length;const lines=value.slice(blockStart,blockEnd).split('\n'),edits=[];let position=blockStart;const changed=lines.map(line=>{if(outdent){const prefix=line.match(/^(\t| {1,4})/)?.[0]??'';edits.push({position,remove:prefix.length,add:0});position+=line.length+1;return line.slice(prefix.length);}edits.push({position,remove:0,add:1});position+=line.length+1;return '\t'+line;}).join('\n');const mapPosition=original=>{let mapped=original;for(const edit of edits){if(edit.add&&original>=edit.position)mapped+=edit.add;if(edit.remove&&original>edit.position)mapped-=Math.min(edit.remove,original-edit.position);}return mapped;};input.setRangeText(changed,blockStart,blockEnd,'start');input.setSelectionRange(mapPosition(start),mapPosition(end),direction);input.scrollTop=scrollTop;input.scrollLeft=scrollLeft;}
$('sql').addEventListener('keydown',e=>{if(e.key==='Tab'&&!e.ctrlKey&&!e.metaKey&&!e.altKey){e.preventDefault();shiftEditorLines(e.target,e.shiftKey);saveEditor();updateLineNumbers();renderTabs();}});
$('sql').addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key==='/'){e.preventDefault();const input=e.target,start=input.value.lastIndexOf('\n',input.selectionStart-1)+1;let end=input.value.indexOf('\n',input.selectionEnd);if(end<0)end=input.value.length;const lines=input.value.slice(start,end).split('\n'),uncomment=lines.every(l=>l.trimStart().startsWith('--'));const changed=lines.map(l=>uncomment?l.replace(/^(\s*)-- ?/,'$1'):'-- '+l).join('\n');input.setRangeText(changed,start,end,'select');saveEditor();}});
const settingsToggle=$('workspace-settings'),settingsMenu=$('workspace-settings-menu');
installDriverDownloadSettings({api,toast});
settingsToggle.append(lucide('settings'));$('settings').prepend(lucide('cpu'));$('agents').prepend(lucide('key-round'));$('editor-pairing').prepend(lucide('panel-top-open'));
function closeWorkspaceSettings(focus=false){settingsMenu.hidden=true;settingsToggle.setAttribute('aria-expanded','false');if(focus)settingsToggle.focus();}
function showWorkspaceSettings(last=false){
  connectionTree.closeMenu();settingsMenu.hidden=false;settingsToggle.setAttribute('aria-expanded','true');
  const anchor=settingsToggle.getBoundingClientRect(),box=settingsMenu.getBoundingClientRect();settingsMenu.style.left=Math.max(4,Math.min(anchor.right-box.width,innerWidth-box.width-4))+'px';settingsMenu.style.top=Math.min(anchor.bottom+4,innerHeight-box.height-4)+'px';
  const items=settingsMenu.querySelectorAll('button');items[last?items.length-1:0].focus();
}
settingsToggle.onclick=()=>settingsMenu.hidden?showWorkspaceSettings():closeWorkspaceSettings(true);
settingsToggle.onkeydown=e=>{if(['ArrowDown','ArrowUp'].includes(e.key)){e.preventDefault();showWorkspaceSettings(e.key==='ArrowUp');}};
settingsMenu.addEventListener('keydown',e=>{
  if(e.key==='Escape'){e.preventDefault();e.stopPropagation();closeWorkspaceSettings(true);return;}
  if(e.key==='Tab'){closeWorkspaceSettings(true);return;}
  const items=[...settingsMenu.querySelectorAll('button')],index=items.indexOf(document.activeElement);
  if(['ArrowDown','ArrowUp','Home','End'].includes(e.key)){e.preventDefault();items[e.key==='Home'?0:e.key==='End'?items.length-1:(index+(e.key==='ArrowDown'?1:-1)+items.length)%items.length].focus();}
});
settingsMenu.addEventListener('click',e=>{if(e.target.closest('button'))closeWorkspaceSettings();});
document.addEventListener('pointerdown',e=>{if(!settingsMenu.contains(e.target)&&!settingsToggle.contains(e.target))closeWorkspaceSettings();});
window.addEventListener('resize',()=>closeWorkspaceSettings());
$('workspace-toolbar-actions').addEventListener('scroll',()=>closeWorkspaceSettings());
for(const id of ['settings-dialog','agents-dialog','editor-pairing-dialog'])$(id).addEventListener('close',()=>{const focused=document.activeElement;if(!focused||focused===document.body||settingsMenu.contains(focused)||$(id).contains(focused))settingsToggle.focus();});
$('settings').onclick=safe(async()=>{const s=await api('/settings'),f=$('settings-form');showAuthorizationMode(s);f.elements.memory.value=(s.memoryBudget/1048576)+'m';for(const k of ['concurrency','uiRows','agentRows','timeoutSeconds','decisionTimeoutSeconds','approvalTimeoutSeconds','mcpSessionIdleTimeoutMinutes'])f.elements[k].value=s[k];$('telemetry').textContent=JSON.stringify(s,null,2);$('settings-dialog').showModal();});
$('settings-form').addEventListener('submit',safe(async e=>{e.preventDefault();const b=Object.fromEntries(new FormData(e.target));for(const k of ['concurrency','uiRows','agentRows','timeoutSeconds','decisionTimeoutSeconds','approvalTimeoutSeconds','mcpSessionIdleTimeoutMinutes'])b[k]=Number(b[k]);await api('/settings','PUT',b);$('settings-dialog').close();toast('Agent timeouts saved. Resource limits updated for this run.');}));
const divider=$('divider'),NAV_WIDTH_KEY='codegraph.dba.sidebar-width';
function resize(x,persist=true){if(!Number.isFinite(x))return;const width=Math.max(180,Math.min(500,x));document.documentElement.style.setProperty('--nav',width+'px');divider.setAttribute('aria-valuenow',String(width));if(persist)try{sessionStorage.setItem(NAV_WIDTH_KEY,String(width));}catch{/* Browser storage can be disabled; resizing must still work. */}}
try{const stored=sessionStorage.getItem(NAV_WIDTH_KEY);if(stored?.trim())resize(Number(stored),false);}catch{/* Keep the default width when browser storage is unavailable. */}
divider.setAttribute('aria-valuemin','180');divider.setAttribute('aria-valuemax','500');divider.addEventListener('pointerdown',e=>{divider.setPointerCapture(e.pointerId);});divider.addEventListener('pointermove',e=>{if(divider.hasPointerCapture(e.pointerId))resize(e.clientX);});divider.addEventListener('keydown',e=>{if(e.key==='ArrowLeft'||e.key==='ArrowRight'){e.preventDefault();resize(divider.getBoundingClientRect().left+(e.key==='ArrowLeft'?-20:20));}});
$('explain').onclick=safe(runPlan);
async function runPlan(){saveEditor();const tab=tabs.find(t=>t.id===active);if(!isScript(tab)||tab.job||!profiles.some(p=>p.id===tab.connection))return;if(!await guardTabGrids(tab))return;const sql=scriptSqlForExecution(tab),connectionId=tab.connection;tab.job='submitting';try{await stopScriptRefreshes(tab);}catch(error){tab.job=null;tab.error=error.message;renderDocument();return;}if(!tabs.includes(tab)){tab.job=null;return;}tab.error='';tab.warning='';tab.outputView='result';tab.resultConnection=profiles.find(p=>p.id===connectionId).name;tab.job='submitting';renderDocument();try{const result=await waitJob(await api('/query/explain','POST',{connectionId,sql,parameters:JSON.parse(tab.params)}),tab);if(tabs.includes(tab)){result.statements=[{index:1,sql}];result.statementIndex=1;setResult(tab,result);}toast(result.analysis);}catch(e){tab.error=e.message;}finally{tab.job=null;}renderDocument();}
async function refreshAgents(){const list=await api('/agents'),container=$('agent-list');container.replaceChildren();for(const agent of list){const row=document.createElement('p');const label=document.createElement('span');label.textContent=agent.name+' · '+(agent.trustedLocal?'Built-in shared local identity; no token required. Read policies are managed in Project databases.':agent.grants.map(g=>(g.connectionName||'Legacy connection')+' ('+g.objects.length+' objects)').join(', '))+' ';row.append(label);if(!agent.trustedLocal)row.append(button('Revoke','Revoke token and cancel owned jobs',async()=>{await api('/agents/'+agent.id,'DELETE');await refreshAgents();}));container.append(row);}}
$('agents').onclick=safe(async()=>{await refreshAgents();const select=$('agent-form').elements.connectionId;select.replaceChildren(...profiles.map(p=>{const o=document.createElement('option');o.value=p.id;o.textContent=p.name;return o;}));$('grant-agent').disabled=profiles.length===0;$('agent-token').value='';$('agent-token-label').hidden=true;$('agents-dialog').showModal();});
$('agents-dialog').addEventListener('close',()=>{$('agent-token').value='';$('agent-token-label').hidden=true;});
$('agent-form').addEventListener('submit',safe(async e=>{const f=e.target.elements;const objects=f.objects.value.split('\n').map(n=>n.trim()).filter(Boolean).map(name=>({schema:f.schema.value,name}));const result=await api('/agents','POST',{name:f.name.value,grants:[{connectionId:f.connectionId.value,objects}]});$('agent-token').value=result.token;$('agent-token-label').hidden=false;await refreshAgents();}));
async function refreshEditorPairing(){const state=await api('/editor/pair');$('editor-pairing-status').textContent=state.paired?'Paired with '+state.agent+'. Agent changes are revision checked and shown in this workspace.':'Not paired';$('editor-pairing-revoke').disabled=!state.paired;}
$('editor-pairing').onclick=safe(async()=>{await flushWorkspace();const created=await api('/editor/pair','POST',{});$('editor-pairing-code').value=created.code;$('editor-pairing-status').textContent='Code expires at '+new Date(created.expiresAt).toLocaleTimeString()+'. Give it only to the MCP session you want to pair.';$('editor-pairing-revoke').disabled=!created.paired;$('editor-pairing-dialog').showModal();});
$('editor-pairing-revoke').onclick=safe(async()=>{await api('/editor/pair','DELETE',{});$('editor-pairing-code').value='';await refreshEditorPairing();toast('Editor pairing revoked.');});
function connectEditorEvents(){editorEvents?.close();editorEvents=new EventSource(collaboration.eventsUrl());editorEvents.addEventListener('editor',safe(async event=>{const change=JSON.parse(event.data);await api('/editor/ack','POST',{eventId:change.eventId});if(change.type==='paired'||change.type==='revoked'){await refreshEditorPairing().catch(()=>{});toast(change.type==='paired'?'MCP editor session paired.':'MCP editor pairing ended.');return;}const local=workspaceState(),baseline=JSON.parse(lastWorkspaceJson||'{"tabs":[]}'),remote=await api('/workspace'),serverState={...remote};delete serverState.workspaceRevision;const merge=mergeAgentWorkspace(remote,change,local,baseline);workspaceReady=false;await restoreWorkspace(remote,merge.merged?JSON.stringify(serverState):null);toast(merge.blocked?'Agent change is retained on the server, but a conflicting local edit could not be copied because the 12-tab limit is full. Copy it before refreshing.':merge.conflict?'Agent change applied. A concurrent local edit was preserved in a conflict Script tab.':'Agent Script change applied. SQL was not executed and no file was saved.');}));editorEvents.addEventListener('collaboration',event=>{try{collaboration.deliver(JSON.parse(event.data));}catch{}});}
const agentRows=document.createElement('label');agentRows.textContent='Agent result row cap (hard maximum 100)';const agentRowsInput=document.createElement('input');agentRowsInput.name='agentRows';agentRowsInput.type='number';agentRowsInput.min='1';agentRowsInput.max='100';agentRowsInput.required=true;agentRows.append(agentRowsInput);$('settings-form').insertBefore(agentRows,$('telemetry'));
initialize().then(()=>installProjectContext({api,profiles:()=>profiles,notify:toast,openConnection:()=>editor.open(),reviewConnection:(request,done)=>editor.openProposal(request,done)})).catch(e=>toast(e.message));

const resultTabs=$('result-tabs');
function enabledOutputKeys(tab){return RAIL_TOGGLES.filter(([,key])=>tab.railToggles?.[key]).map(([,key])=>key);}
function fallbackOutputView(tab,rowResults){return rowResults.length?'result':enabledOutputKeys(tab)[0]??null;}
function outputTab(label,title,selected,onSelect,onClose){const item=document.createElement('div');item.className='result-tab output-tab';const select=button(label,title,onSelect);select.className='result-select';select.setAttribute('aria-pressed',String(selected));const close=button('×','Close '+label,onClose);close.className='result-close';close.setAttribute('aria-label','Close '+label);item.append(select,close);return item;}
function renderOutputPlaceholder(label){destroyGrid();const grid=$('grid');grid.setAttribute('aria-label',label);const panel=document.createElement('section');panel.className='output-placeholder';const heading=document.createElement('strong');heading.textContent=label;panel.append(heading);grid.append(panel);}
function resultSql(container,result){const index=result?.statementIndex;if(!index)return null;return container?.statements?.find(statement=>statement.index===index)?.sql??null;}
function renderResults(tab){
  resultTabs.replaceChildren();const results=tab.result?.results;
  const rowResults=results?.filter(result=>result.kind==='rows')??(tab.result?.kind==='rows'?[tab.result]:[]);
  tab.resultIndex=Math.min(tab.resultIndex??0,Math.max(0,rowResults.length-1));
  if(tab.outputView==='result'&&!rowResults.length)tab.outputView=fallbackOutputView(tab,rowResults);
  if(tab.outputView&&tab.outputView!=='result'&&!tab.railToggles?.[tab.outputView])tab.outputView=fallbackOutputView(tab,rowResults);
  if(!tab.outputView)tab.outputView=fallbackOutputView(tab,rowResults);
  rowResults.forEach((result,index)=>{const label='Result '+(index+1);resultTabs.append(outputTab(label,'Show SQL result '+(index+1),tab.outputView==='result'&&index===tab.resultIndex,()=>{tab.outputView='result';tab.resultIndex=index;renderResults(tab);},async()=>{if(!await guardGrid(tab,result))return;await stopGridRefresh(result);const current=tab.resultIndex??0,actualIndex=results?.indexOf(result)??-1;if(actualIndex>=0)results.splice(actualIndex,1);else if(tab.result===result)tab.result=null;const remainingRows=results?.filter(item=>item.kind==='rows')??[];if(!results?.length){tab.result=null;tab.resultIndex=0;tab.resultConnection=null;tab.bytes=0;}else{tab.resultIndex=index<current?current-1:Math.min(current,Math.max(0,remainingRows.length-1));tab.result.rowCount=remainingRows.reduce((count,item)=>count+(item.rows?.length??0),0);const affected=results.filter(item=>item.affectedRows!==undefined);if(affected.length)tab.result.affectedRows=affected.reduce((count,item)=>count+item.affectedRows,0);else delete tab.result.affectedRows;tab.bytes=new TextEncoder().encode(JSON.stringify(tab.result)).length*3;}if(tab.outputView==='result')tab.outputView=fallbackOutputView(tab,remainingRows);renderDocument();}));});
  for(const [,key,label] of RAIL_TOGGLES){if(!tab.railToggles?.[key])continue;resultTabs.append(outputTab(label,'Show '+label.toLowerCase(),tab.outputView===key,()=>{tab.outputView=key;renderResults(tab);},()=>{tab.railToggles[key]=false;if(tab.outputView===key)tab.outputView=fallbackOutputView(tab,rowResults);renderDocument();}));}
  resultTabs.hidden=!resultTabs.children.length;
  if(tab.outputView&&tab.outputView!=='result'){renderOutputPlaceholder(RAIL_TOGGLES.find(([,key])=>key===tab.outputView)?.[2]??'Output');return;}
  $('grid').setAttribute('aria-label','Query results');
  if(rowResults.length){const result=rowResults[tab.resultIndex];renderGrid(result,resultSql(tab.result,result));return;}
  renderGrid(results?.find(result=>result.kind==='update')??tab.result);
}

// A script owns its execution target. Tree selection supplies a default only for new/opened files.
const fileGroup=$('file-tools');
const openButton=button('','Open SQL file · defaults to last selected connection (Ctrl+O)',openScript);openButton.id='file-open';
const saveButton=button('','Save active SQL script (Ctrl+S)',()=>saveScript(false));saveButton.id='file-save';
const saveAsButton=button('','Save active SQL script as another file (Ctrl+Shift+S)',()=>saveScript(true));saveAsButton.id='file-save-as';fileGroup.append(openButton,saveButton,saveAsButton);
// Local SVG glyphs keep icon-only controls sharp, with accessible labels and native tooltips.
function iconize(id,label,path){const control=$(id);control.setAttribute('aria-label',label);control.classList.add('icon-button');const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');svg.setAttribute('focusable','false');const shape=document.createElementNS(svg.namespaceURI,'path');shape.setAttribute('d',path);svg.append(shape);control.replaceChildren(svg);}
iconize('new-tab','New SQL script','M14 2H5v20h14V7l-5-5Zm0 0v6h5M8 14h8m-4-4v8');
iconize('file-open','Open SQL file','M3 18V5h6l3 3h9v3M3 21l3-10h16l-3 10H3Z');
iconize('file-save','Save active SQL script','M4 3h13l4 4v14H3V3h1Zm3 0v6h10V3M7 21v-8h10v8');
iconize('file-save-as','Save active SQL script as','M12 21H3V3h14l4 4v4M7 3v6h10V3M7 21v-8h5m3 5 5-5 3 3-5 5-4 1 1-4Z');
iconize('run','Run SQL','m7 4 14 8-14 8V4Z');
iconize('explain','Explain','M4 4h6v6H4V4Zm10 10h6v6h-6v-6ZM7 10v7h7m3-7V7h-7');
iconize('stop','Cancel','M5 5h14v14H5V5Z');
iconize('script-settings','Settings','M12 2.75v2m0 14.5v2M2.75 12h2m14.5 0h2M5.46 5.46l1.42 1.42m10.24 10.24 1.42 1.42m0-13.08-1.42 1.42M6.88 17.12l-1.42 1.42M12 18a6 6 0 1 0 0-12 6 6 0 0 0 0 12Zm0-3.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z');
iconize('toggle-server-output','Show server output','M3 5h18v14H3V5Zm4 4 3 3-3 3m5 0h5');
iconize('toggle-execution-log','Show execution log','M6 3h12v18H6V3Zm3 5h6m-6 4h6m-6 4h4');
iconize('toggle-sql-variables','Show SQL variables','M8 4H5v16h3m8-16h3v16h-3m-6-5 4-6m0 6-4-6');
for(const [id,key] of RAIL_TOGGLES)$(id).onclick=()=>{const tab=tabs.find(item=>item.id===active);if(!tab)return;tab.railToggles??={serverOutput:false,executionLog:false,sqlVariables:false};tab.railToggles[key]=!tab.railToggles[key];const rows=tab.result?.results?.filter(result=>result.kind==='rows')??(tab.result?.kind==='rows'?[tab.result]:[]);tab.outputView=tab.railToggles[key]?key:(tab.outputView===key?fallbackOutputView(tab,rows):tab.outputView);renderDocument();};
$('script-settings').onclick=()=>toast('Script settings are reserved for a future update.');
saveButton.disabled=true;saveAsButton.disabled=true;
$('welcome').append(button('Open SQL file','Open a local SQL file; nothing is executed automatically',openScript),button('New SQL script','Create a blank SQL script',()=>openTab(lastSelected)));
const fileInput=document.createElement('input');fileInput.type='file';fileInput.accept='.sql,.txt,text/plain,application/sql';fileInput.hidden=true;fileInput.id='script-file-input';document.body.append(fileInput);
async function loadScript(file,handle=null){if(file.size>1024*1024)throw new Error('Script file exceeds the 1 MiB editor limit.');if(!/\.(sql|txt)$/i.test(file.name))throw new Error('Choose a .sql or .txt script file.');const text=await file.text();if(text.includes('\0'))throw new Error('Choose a UTF-8 text script, not a binary file.');const tab=openTab(lastSelected,file.name);tab.sql=text;tab.savedText=text;tab.fileHandle=handle;tab.dirty=false;renderTabs();renderDocument();toast('Opened '+file.name+' · '+(profiles.find(p=>p.id===tab.connection)?.name??'choose a connection before running')+'. Nothing executed.');}
fileInput.onchange=safe(async()=>{try{if(fileInput.files[0])await loadScript(fileInput.files[0]);}finally{fileInput.value='';}});
async function openScript(){if(tabs.length>=MAX_TABS)throw new Error('Close a tab before opening another (maximum 12).');if(window.showOpenFilePicker){try{const [handle]=await window.showOpenFilePicker({multiple:false,types:[{description:'SQL scripts',accept:{'text/plain':['.sql','.txt']}}]});await loadScript(await handle.getFile(),handle);}catch(e){if(e.name!=='AbortError')throw e;}}else fileInput.click();}
async function saveScript(asNew){saveEditor();const tab=tabs.find(t=>t.id===active);if(!isScript(tab))return;const contents=tab.sql;if(new TextEncoder().encode(contents).length>1024*1024)throw new Error('Script exceeds the 1 MiB file limit.');let handle=asNew?null:tab.fileHandle;
  try{if(!handle&&window.showSaveFilePicker)handle=await window.showSaveFilePicker({suggestedName:/\.(sql|txt)$/i.test(tab.title)?tab.title:tab.title+'.sql',types:[{description:'SQL script',accept:{'text/plain':['.sql']}}]});
    if(handle){const output=await handle.createWritable();try{await output.write(contents);await output.close();}catch(e){await output.abort().catch(()=>{});throw e;}tab.fileHandle=handle;tab.title=handle.name;toast('Saved '+handle.name+'.');}
    else{const requested=asNew?prompt('Save SQL file as',tab.title):tab.title;if(!requested)return;const filename=requested.replace(/[\\/:*?"<>|]/g,'_');const blob=new Blob([contents],{type:'text/plain;charset=utf-8'}),url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=/\.(sql|txt)$/i.test(filename)?filename:filename+'.sql';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);tab.title=link.download;toast('Downloaded '+tab.title+'. This browser cannot overwrite the original file; choose its destination in Downloads.');}
    tab.savedText=contents;tab.dirty=tab.sql!==contents;renderTabs();if(active===tab.id)renderDocument();
  }catch(e){if(e.name!=='AbortError')throw e;}}
document.addEventListener('keydown',e=>{if(!workspaceReady||document.querySelector('dialog[open]')||!(e.ctrlKey||e.metaKey))return;const key=e.key.toLowerCase();if(key==='s'){e.preventDefault();const tab=tabs.find(t=>t.id===active);safe(()=>tab?.builder?(isView(tab)&&!e.shiftKey?objectDesigner(tab).save():tab.builder.saveFile(e.shiftKey)):saveScript(e.shiftKey))();}else if(key==='o'){e.preventDefault();safe(openScript)();}else if(key==='n'&&e.altKey){e.preventDefault();safe(()=>openTab(lastSelected))();}else if(e.key==='Enter'){e.preventDefault();const tab=tabs.find(t=>t.id===active);if(tab?.builder)void tab.builder.run('data');else $('run').click();}});
setInterval(()=>queueWorkspaceSave(0),5000);
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden')flushWorkspace();});
window.addEventListener('pagehide',()=>{editorEvents?.close();let workspace;try{if(workspaceReady&&csrf){const state=workspaceState();if(JSON.stringify(state)!==lastWorkspaceJson)workspace={...state,expectedWorkspaceRevision:workspaceRevision};}}finally{void collaboration.leave(csrf,workspace);}});
window.addEventListener('beforeunload',e=>{if(tabs.some(t=>t.dirty||t.native?.dirty||(t.result?.results??(t.result?[t.result]:[])).some(DataGridView.dirty)||t.builder?.rowsDirty)){e.preventDefault();e.returnValue='';}});
