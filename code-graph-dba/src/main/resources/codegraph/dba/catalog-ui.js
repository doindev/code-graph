import {installScanStatus,renderScanDetails} from './scan-status.js';
import {lucide} from './tree-icons.js';
const element=(tag,text)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;};
/** Explicit, database-scoped cached observation; opening the dialog never opens a database. */
export function installCatalogUI({api,profiles,menu}){
  const launch=element('button','Cached catalogs');launch.id='cached-catalogs';launch.type='button';launch.setAttribute('role','menuitem');launch.prepend(lucide('database'));menu.append(launch);
  const dialog=element('dialog');dialog.id='catalog-dialog';dialog.className='project-context-dialog';
  const title=element('h2','Cached database catalogs');title.id='catalog-title';dialog.setAttribute('aria-labelledby',title.id);
  const description=element('p','Inspect or refresh bounded metadata without an application relationship. Standalone snapshots expire after 30 idle minutes; checking status does not renew them.');
  const form=element('form'),connection=element('select'),database=element('input'),schema=element('input');
  for(const [label,node,id]of [['Connection',connection,'catalog-connection'],['Database / catalog',database,'catalog-database'],['Schema (optional)',schema,'catalog-schema']]){
    const row=element('label',label);node.id=id;node.name=id;row.htmlFor=id;row.append(node);form.append(row);
  }
  database.required=true;database.maxLength=schema.maxLength=128;
  const allScans=element('button','All scan status');allScans.id='all-scan-status';allScans.type='button';const openScans=installScanStatus({api,launch:allScans});
  const scanDetails=element('div');scanDetails.id='catalog-scan-details';
  const actions=element('div'),statusButton=element('button','Status'),refresh=element('button','Refresh catalog');
  statusButton.type=refresh.type='button';statusButton.prepend(lucide('info'));refresh.prepend(lucide('refresh-cw'));actions.append(statusButton,refresh,allScans);
  const status=element('p','Select an exact database to inspect its cache.');status.id='catalog-status';status.setAttribute('role','status');status.setAttribute('aria-live','polite');
  const error=element('p');error.className='context-error';error.setAttribute('role','alert');
  const searchForm=element('form'),query=element('input'),search=element('button','Search snapshot');
  query.placeholder='Object name or schema';query.setAttribute('aria-label','Search cached objects');query.maxLength=256;search.type='submit';search.prepend(lucide('search'));searchForm.append(query,search);
  const results=element('div');results.id='catalog-results';const ddl=element('textarea');ddl.readOnly=true;ddl.rows=10;ddl.wrap='off';ddl.hidden=true;ddl.setAttribute('aria-label','Cached object DDL');ddl.spellcheck=false;
  const close=element('button','Close');close.type='button';close.prepend(lucide('x'));
  dialog.append(title,description,form,actions,status,error,scanDetails,searchForm,results,ddl,close);document.body.append(dialog);
  let revision=0,busy=false,activeScan=false;
  const controls=[connection,database,schema,statusButton,refresh,search];
  function availability(){for(const node of controls)node.disabled=busy||node===search&&!database.value.trim();statusButton.disabled=refresh.disabled=busy||!connection.value||!database.value.trim();}
  function target(){
    const profile=profiles().find(p=>p.id===connection.value);
    if(!profile)throw Error('Choose a saved connection.');
    if(!database.value.trim())throw Error('Enter the exact database / catalog.');
    const selected={connectionId:profile.id,connectionName:profile.name,database:database.value.trim()};
    if(!profile.transport||profile.transport==='jdbc')selected.schema=schema.value.trim();
    else if(schema.value.trim())throw Error('Native catalogs do not have a SQL schema; clear the Schema field.');
    return selected;
  }
  function showStatus(data){
    const snapshot=data.snapshot||data,version=snapshot.version||{};
    status.textContent=[data.state||'Snapshot',data.generation?'Generation '+data.generation:'',
      snapshot.finishedAt||data.scannedAt?'Scanned '+new Date(snapshot.finishedAt||data.scannedAt).toLocaleString():'',
      [version.product,version.server].filter(Boolean).join(' '),
      snapshot.coverage?'Coverage: '+snapshot.coverage:'',
      data.waitTimedOut?'No status change during this wait.':''].filter(Boolean).join(' · ');
    if(data.error)error.textContent=data.error;
    if(Object.hasOwn(data,'scanInProgress')){activeScan=data.scanInProgress;renderScanDetails(scanDetails,data);}
  }
  async function perform(operation,extra={}){
    const token=++revision;busy=true;error.textContent='';availability();
    try{
      const scope=target();let data=await api('/catalog','POST',{...scope,operation,...extra});
      if(token!==revision||!dialog.open)return;
      showStatus(data);
      if(operation==='refresh_catalog'&&data.scanInProgress){
        for(let attempt=0;attempt<6&&data.scanInProgress;attempt++){
          data=await api('/catalog','POST',{...scope,operation:'scan_status',afterScanRevision:data.scanRevision||0,waitMillis:5000});
          if(token!==revision||!dialog.open)return;showStatus(data);
        }
      }
      if(operation==='search_objects'){
        results.replaceChildren();ddl.hidden=true;
        for(const object of data.objects||[]){
          const row=element('div'),open=element('button',[object.schema,object.displayName||object.name].filter(Boolean).join('.')+' · '+object.kind);
          open.type='button';open.onclick=()=>perform('get_indexed_ddl',{objectId:object.id});row.append(open);results.append(row);
        }
        if(!(data.objects||[]).length)results.append(element('p',data.message||'No matching objects in this snapshot.'));
        if(data.nextCursor){const more=element('button','Next page');more.type='button';more.onclick=()=>perform('search_objects',{query:extra.query||'',cursor:data.nextCursor,limit:30});results.append(more);}
      }
      if(operation==='get_indexed_ddl'){ddl.value=data.ddl||'Definition unavailable for this object.';ddl.hidden=false;if(data.truncated)status.textContent+=' · Definition preview truncated.';}
    }catch(failure){if(token===revision)error.textContent=failure.message;}
    finally{if(token===revision){busy=false;availability();}}
  }
  for(const node of [connection,database,schema])node.addEventListener('input',()=>{revision++;busy=false;activeScan=false;results.replaceChildren();scanDetails.replaceChildren();ddl.hidden=true;status.textContent='Target changed; check Status or Refresh catalog.';availability();});
  form.onsubmit=event=>{event.preventDefault();perform('scan_status');};
  statusButton.onclick=()=>perform('scan_status');refresh.onclick=()=>perform('refresh_catalog');
  searchForm.onsubmit=event=>{event.preventDefault();perform('search_objects',{query:query.value,limit:30});};
  close.onclick=()=>dialog.close();dialog.addEventListener('close',()=>{revision++;busy=false;availability();document.getElementById('workspace-settings')?.focus();});
  launch.onclick=()=>{menu.hidden=true;document.getElementById('workspace-settings')?.setAttribute('aria-expanded','false');
    const prior=connection.value;connection.replaceChildren(...profiles().map(p=>{const option=element('option',p.name);option.value=p.id;return option;}));
    if(profiles().some(p=>p.id===prior))connection.value=prior;
    dialog.showModal();availability();database.focus();
  };
  setInterval(()=>{if(dialog.open&&!document.hidden&&!busy&&activeScan)perform('scan_status');},2000);
  availability();
  return openScans;
}
