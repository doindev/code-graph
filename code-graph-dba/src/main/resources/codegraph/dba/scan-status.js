const el=(tag,text)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;};
const time=value=>value?new Date(value).toLocaleString():'—';
const words=value=>(value||'').replaceAll('_',' ');
const duration=value=>{if(value>0&&value<1000)return value+'ms';const seconds=Math.floor((value||0)/1000);return seconds<60?seconds+'s':Math.floor(seconds/60)+'m '+seconds%60+'s';};
export function scanLabel(data){return words(data.currentRun?.state||data.lastRun?.state||'never scanned');}
export function renderScanDetails(container,data){
  container.replaceChildren();container.className='scan-details';
  for(const [title,run]of [['Current run',data.currentRun],['Last run',data.lastRun]]){
    const section=el('section');section.append(el('h3',title));container.append(section);
    if(!run){section.append(el('p',title==='Current run'?'No scan queued or running.':'No completed run in this retained catalog.'));continue;}
    section.dataset.runState=run.state;const fields=el('dl');
    for(const [label,value]of [['Status',words(run.state)],[title==='Last run'?'Final phase':'Phase',words(run.phase)],['Requested',time(run.requestedAt)],['Started',time(run.startedAt)],['Finished',time(run.finishedAt)],['Elapsed',duration(run.elapsedMillis)],['Time in queue',duration(run.queueMillis)],['Last progress',time(run.updatedAt)],['Objects',run.objects],['Dependencies',run.dependencies],['Captured bytes',run.bytes],['Run ID',run.id]]){
      if(value===undefined)continue;fields.append(el('dt',label),el('dd',String(value)));
    }
    section.append(fields);
    if(run.timeoutRequested)section.append(el('p',run.finishedAt?'The scan exceeded its time limit.':'Time limit reached. Cancellation requested; waiting for the driver to release the scan.'));
    if(run.error){const failure=el('p',run.error+(run.errorCode?' ('+run.errorCode+')':''));failure.className='context-error';section.append(failure);}
  }
  const snapshot=data.snapshot||{},note=el('section');note.className='scan-snapshot';note.append(el('h3','Retained snapshot'));
  note.append(el('p',data.generation?'Generation '+data.generation+' · '+words(data.state)+' · Captured '+time(snapshot.finishedAt):'No snapshot has been published.'));
  if(snapshot.changes)note.append(el('p','Last publication: '+snapshot.changes.added+' added · '+snapshot.changes.modified+' modified · '+snapshot.changes.removed+' removed'));
  if(snapshot.coverage)note.append(el('p','Coverage: '+words(snapshot.coverage)));
  if(snapshot.warnings?.length){const details=el('details'),summary=el('summary','Coverage details ('+snapshot.warnings.length+')'),list=el('ul');for(const warning of snapshot.warnings)list.append(el('li',warning));details.append(summary,list);note.append(details);}
  container.append(note);
}
export function installScanStatus({api,launch}){
  const dialog=el('dialog');dialog.id='scan-status-dialog';dialog.className='project-context-dialog scan-status-dialog';dialog.setAttribute('aria-labelledby','scan-status-title');
  const title=el('h2','Database scan status');title.id='scan-status-title';
  const note=el('p','Current and last catalog scans across retained databases. Updates every two seconds while open. Viewing status does not start scans or extend retention; run details are cleared when a catalog expires or the server restarts.');
  const status=el('p');status.setAttribute('role','status');const error=el('p');error.setAttribute('role','alert');error.className='context-error';
  const layout=el('div');layout.className='scan-status-layout';const list=el('div'),detail=el('div');list.className='scan-targets';detail.id='scan-status-details';layout.append(list,detail);
  const refresh=el('button','Refresh status'),close=el('button','Close');refresh.type=close.type='button';close.onclick=()=>dialog.close();
  const footer=el('div');footer.className='scan-status-footer';footer.append(refresh,close);dialog.append(title,note,status,error,layout,footer);document.body.append(dialog);
  let selected='',pending=false,revision=0,opener=launch,requestedTarget=null;
  const key=row=>JSON.stringify([row.connectionId,row.database,row.schema]);
  async function load(){
    if(pending||!dialog.open)return;pending=true;const token=revision;
    try{
      const response=await api('/catalog/scans');if(token!==revision||!dialog.open)return;
      const scans=response.scans||[];error.textContent='';status.textContent=scans.filter(row=>row.currentRun).length+' active / queued · '+scans.length+' retained catalogs';
      if(!requestedTarget&&!scans.some(row=>key(row)===selected))selected=scans[0]?key(scans[0]):'';
      const focused=list.contains(document.activeElement)?document.activeElement.dataset.target:null;
      list.replaceChildren();
      for(const row of scans){const button=el('button');button.type='button';button.dataset.target=key(row);button.setAttribute('aria-pressed',String(selected===key(row)));button.append(el('strong',row.connectionName),el('span',[row.database,row.schema].filter(Boolean).join(' / ')||'Saved default catalog'),el('span',scanLabel(row)+(row.currentRun?' · '+words(row.currentRun.phase):'')));
        button.onclick=()=>{requestedTarget=null;selected=key(row);for(const other of list.children)other.setAttribute('aria-pressed',String(other===button));renderScanDetails(detail,row);};list.append(button);
      }
      if(focused)Array.from(list.children).find(button=>button.dataset.target===focused)?.focus({preventScroll:true});
      const row=scans.find(row=>key(row)===selected);if(row){if(!detail.contains(document.activeElement)){const expanded=detail.querySelector('details')?.open;renderScanDetails(detail,row);if(expanded&&detail.querySelector('details'))detail.querySelector('details').open=true;}}else{detail.replaceChildren(el('p',requestedTarget?'No retained scan for this selected relationship. Use Scan now to start one.':'No retained scans yet. Use Refresh catalog or Scan now on a project relationship to start one.'));}
    }catch(failure){if(token===revision)error.textContent=failure.message;}
    finally{pending=false;}
  }
  const open=scope=>{opener=document.activeElement;requestedTarget=scope||null;if(scope)selected=key(scope);if(!dialog.open)dialog.showModal();load();};
  launch.onclick=()=>open();refresh.onclick=load;dialog.addEventListener('close',()=>{revision++;opener?.focus();});
  setInterval(()=>{if(dialog.open&&!document.hidden)load();},2000);
  return open;
}
