import {lucide} from './tree-icons.js';
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
const button=(label,run)=>{const n=el('button',label);n.type='button';n.onclick=run;return n;};
const field=(label,input)=>{const n=el('label'),text=el('span',label);n.append(...(input.type==='checkbox'?[input,text]:[text,input]));return n;};
const choose=(label,entries)=>{const n=el('select');n.setAttribute('aria-label',label);for(const [id,name]of entries){const o=el('option',name);o.value=id;n.append(o);}return n;};
const display=value=>value===null||value===undefined?'':typeof value==='object'?JSON.stringify(value):String(value);
const stamp=value=>value?new Date(value).toLocaleString():'';

export function installOracleAdministration({api,profiles,notify}) {
  const launch=button('Oracle Administration',open);launch.id='oracle-administration';launch.setAttribute('role','menuitem');launch.prepend(lucide('database'));
  document.getElementById('workspace-settings-menu').append(launch);
  const dialog=el('dialog',undefined,'oracle-admin');dialog.id='oracle-admin-dialog';dialog.setAttribute('aria-labelledby','oracle-admin-title');document.body.append(dialog);
  const title=el('h2','Oracle Administration');title.id='oracle-admin-title';
  const connection=choose('Administration connection',[]),reload=button('Connect',()=>act(connect)),close=button('Close',requestClose),cancel=button('Cancel operation',cancelJob);
  const header=el('header');header.append(title,field('Connection',connection),reload,close);
  const target=el('p','Choose an Oracle connection.','oracle-admin-target'),status=el('p','','oracle-admin-status'),error=el('p','','oracle-admin-error');status.setAttribute('role','status');error.setAttribute('role','alert');
  const nav=el('nav');nav.setAttribute('aria-label','Oracle administration categories');
  const heading=el('h3','Catalog'),filter=el('input');filter.setAttribute('aria-label','Catalog filter');filter.maxLength=128;filter.placeholder='Filter names';
  const refresh=button('Refresh',()=>act(()=>read(0))),previous=button('Previous page',()=>act(()=>read(Math.max(0,offset-pageSize)))),next=button('Next page',()=>act(()=>read(offset+pageSize)));
  const tools=el('div',undefined,'oracle-admin-tools');tools.append(heading,filter,refresh,previous,next);
  const catalog=el('div',undefined,'oracle-admin-catalog');catalog.tabIndex=0;catalog.setAttribute('role','region');catalog.setAttribute('aria-label','Oracle catalog rows');
  const actions=choose('Administration action',[]),form=el('form',undefined,'oracle-admin-fields'),reviewButton=button('Review operation',()=>act(prepare));
  const operation=el('section',undefined,'oracle-admin-operation');operation.append(el('h3','Reviewed operation'),field('Action',actions),form,reviewButton);
  const review=el('section',undefined,'oracle-admin-review'),sql=el('textarea');sql.readOnly=true;sql.wrap='off';sql.spellcheck=false;sql.setAttribute('aria-label','Reviewed administration SQL');
  const notice=el('p'),ack=el('input');ack.type='checkbox';const password=el('input');password.type='password';password.autocomplete='new-password';password.setAttribute('aria-label','New account password');const passwordField=field('New account password (sent only when applying)',password);
  const apply=button('Apply reviewed operation',()=>act(applyPlan));review.append(el('h3','Review SQL and target'),notice,sql,passwordField,field('I reviewed the target and SQL. Oracle may commit each step.',ack),apply);
  const pump=el('section',undefined,'oracle-admin-pump'),pumpInfo=el('pre'),inspect=button('Inspect Data Pump job',()=>act(inspectPump)),watch=el('input');watch.type='checkbox';pump.append(el('h3','Data Pump status'),el('p','Oracle jobs continue after closing this window. Stop uses a reviewed operation and preserves restart state.'),inspect,field('Refresh status every 3 seconds while this window is open',watch),pumpInfo);pump.hidden=true;
  const rman=el('section',undefined,'oracle-admin-rman'),rmanMode=choose('RMAN script type',[['backup','Backup'],['validate','Validate database files'],['restore_validate','Validate available restore backups']]),backupPath=el('input'),archiveLogs=el('input');backupPath.placeholder='/srv/oracle/backups';archiveLogs.type='checkbox';
  const rmanSql=el('textarea');rmanSql.readOnly=true;rmanSql.wrap='off';rmanSql.spellcheck=false;rmanSql.setAttribute('aria-label','Manual RMAN script');
  const generateRman=button('Generate RMAN script',()=>act(async()=>{const result=await job('/oracle/rman/script',{...selected,mode:rmanMode.value,directory:backupPath.value,archiveLogs:archiveLogs.checked});rmanArtifact=result;rmanSql.value=result.script;}));
  const copyRman=button('Copy RMAN script',()=>act(async()=>{await navigator.clipboard.writeText(rmanArtifact.script);notify('Complete RMAN script copied.');})),saveRman=button('Save RMAN script',()=>{const url=URL.createObjectURL(new Blob([rmanArtifact.script],{type:'text/plain;charset=utf-8'}));const link=el('a');link.href=url;link.download=rmanArtifact.fileName;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);});
  rman.append(el('h3','Manual RMAN scripts'),el('p','Review, copy or save a script for manual execution in RMAN. The script uses the resolved database/container above and contains no credentials.'),field('Script type',rmanMode),field('Backup directory on the Oracle server',backupPath),field('Include shared database/CDB archived logs in backup',archiveLogs),generateRman,rmanSql,copyRman,saveRman);rman.hidden=true;
  const last=el('details',undefined,'oracle-admin-last'),lastTitle=el('summary','Last operation'),lastText=el('pre');last.append(lastTitle,lastText);
  const main=el('main');main.append(tools,catalog,pump,operation,review,rman,last);const layout=el('div',undefined,'oracle-admin-layout');layout.append(nav,main);
  const footer=el('footer');footer.append(status,cancel);dialog.append(header,target,error,layout,footer);
  let selected=null,capabilities=null,category='users',offset=0,pageSize=100,hasMore=false,busy=false,closing=false,currentJob=null,reviewJob=null,plan=null,selectedRow=null,monitored=null,watchTimer=null,rmanArtifact=null;
  function update(){
    dialog.setAttribute('aria-busy',String(busy));
    for(const control of dialog.querySelectorAll('input,select,button'))control.disabled=busy;
    close.disabled=false;cancel.disabled=!busy||!currentJob;reload.disabled=busy||!connection.value;
    refresh.disabled=busy||!capabilities;previous.disabled=busy||!capabilities||offset===0;next.disabled=busy||!capabilities||!hasMore;
    actions.disabled=busy||!actions.options.length;reviewButton.disabled=busy||!capabilities||!actions.value;
    copyRman.disabled=saveRman.disabled=busy||!rmanArtifact;backupPath.disabled=archiveLogs.disabled=busy||rmanMode.value!=='backup';generateRman.disabled=busy||!capabilities;
    inspect.disabled=busy||category!=='datapump'||!monitored;watch.disabled=category!=='datapump'||!monitored;
    review.hidden=!plan;apply.disabled=busy||!plan||!ack.checked||(plan?.requiresPassword&&!password.value);
  }
  function remember(state){last.open=true;lastTitle.textContent='Last operation: '+(state.result?.status||state.state);lastText.textContent=['Status: '+(state.result?.status||state.state),'Outcome: '+(state.result?.outcome||state.outcome||state.state),'Started: '+stamp(state.started),'Finished: '+stamp(state.finished),state.result?.message||state.error||'',...(state.result?.steps??[]).map(step=>'Step '+step.index+': '+step.status),...(state.result?.compilationErrors??[]).map(row=>row.type+' line '+row.line+':'+row.position+' '+row.text)].filter(Boolean).join('\n');}
  async function releaseReview(){const id=reviewJob;reviewJob=null;plan=null;password.value='';ack.checked=false;update();if(id)await api('/jobs/'+id,'DELETE').catch(()=>{});}
  async function cancelJob(){if(currentJob){cancel.disabled=true;status.textContent='Cancellation requested; waiting for Oracle to acknowledge it.';await api('/jobs/'+currentJob+'/cancel','POST',{}).catch(e=>{error.textContent=e.message;});}}
  async function requestClose(){closing=true;stopWatch();rmanArtifact=null;rmanSql.value='';if(busy){await cancelJob();return;}await releaseReview();dialog.close();}
  dialog.addEventListener('cancel',event=>{event.preventDefault();requestClose();});dialog.addEventListener('close',()=>document.getElementById('workspace-settings').focus());
  async function act(run){if(busy)return;busy=true;error.textContent='';update();try{await run();}catch(e){if(!closing)error.textContent=e.message;}finally{busy=false;update();if(closing)await requestClose();}}
  async function job(path,body,{retain=false,mutation=false}={}){
    const submitted=await api(path,'POST',body);currentJob=submitted.id;update();if(closing)await cancelJob();let retained=false;
    try{for(;;){const state=await api('/jobs/'+submitted.id);const elapsed=state.started?Math.floor(((state.finished||Date.now())-state.started)/1000):0;
      status.textContent=(state.progress||state.state)+(state.started?' | Started '+stamp(state.started)+' | '+elapsed+'s elapsed':'');
      if(state.finished){if(mutation)remember(state);if(state.state!=='complete')throw Error(state.error||state.state);if(retain){reviewJob=submitted.id;retained=true;}return state.result;}
      await new Promise(resolve=>setTimeout(resolve,300));}
    }finally{currentJob=null;if(!retained){await api('/jobs/'+submitted.id+'/cancel','POST',{}).catch(()=>{});await api('/jobs/'+submitted.id,'DELETE').catch(()=>{});}update();}
  }
  async function open(){if(busy)return;closing=false;await releaseReview();const existing=connection.value;connection.replaceChildren();
    for(const p of profiles().filter(p=>p.templateId==='oracle')){const option=el('option',p.name);option.value=p.id;connection.append(option);}if([...connection.options].some(o=>o.value===existing))connection.value=existing;
    selected=null;capabilities=null;monitored=null;stopWatch();rmanArtifact=null;rmanSql.value='';rman.hidden=true;tools.hidden=catalog.hidden=operation.hidden=false;pump.hidden=true;pumpInfo.textContent='';nav.replaceChildren();catalog.replaceChildren();form.replaceChildren();actions.replaceChildren();last.hidden=true;error.textContent='';status.textContent='';target.textContent=connection.options.length?'Choose Connect to inspect the resolved Oracle target.':'Add a native Oracle connection to use Administration.';update();dialog.showModal();
  }
  connection.onchange=()=>{stopWatch();monitored=null;pump.hidden=true;rmanArtifact=null;rmanSql.value='';selected=null;capabilities=null;nav.replaceChildren();catalog.replaceChildren();form.replaceChildren();actions.replaceChildren();target.textContent='Connect to inspect this Oracle target.';releaseReview();};
  async function connect(){await releaseReview();selected={connectionId:connection.value,database:''};capabilities=null;catalog.replaceChildren();nav.replaceChildren();
    capabilities=await job('/oracle/admin/read',{...selected,category:'overview'});selected.database=capabilities.target.database;
    target.textContent=[capabilities.version.split('\n')[0],capabilities.edition+' edition','Database '+capabilities.target.databaseUniqueName,'Service '+capabilities.target.service,'Container '+capabilities.target.container,'User '+capabilities.target.user,'Schema '+capabilities.target.schema].join(' | ');
    for(const item of capabilities.categories){const b=button(item.label,()=>act(async()=>{await releaseReview();category=item.id;filter.value='';selectedRow=null;monitored=null;stopWatch();pump.hidden=category!=='datapump';rman.hidden=true;tools.hidden=catalog.hidden=operation.hidden=false;pumpInfo.textContent='';renderActions();await read(0);}));b.dataset.category=item.id;nav.append(b);}
    const backup=button('RMAN scripts',()=>act(async()=>{await releaseReview();stopWatch();category='rman';tools.hidden=catalog.hidden=operation.hidden=pump.hidden=true;rman.hidden=false;for(const b of nav.querySelectorAll('button'))b.setAttribute('aria-current',String(b===backup));main.scrollTop=0;}));backup.dataset.category='rman';nav.append(backup);
    rman.hidden=true;tools.hidden=catalog.hidden=operation.hidden=false;category='users';filter.value='';renderActions();await read(0);
  }
  filter.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();act(()=>read(0));}};
  async function read(start){if(!capabilities)return;await releaseReview();const result=await job('/oracle/admin/read',{...selected,category,offset:start,filter:filter.value});offset=result.offset;pageSize=result.pageSize;hasMore=result.hasMore;selectedRow=null;heading.textContent=result.label;main.scrollTop=0;
    for(const b of nav.querySelectorAll('button'))b.setAttribute('aria-current',String(b.dataset.category===category));catalog.replaceChildren();
    if(!result.available){catalog.append(el('p',result.message,'oracle-admin-error'));return;}
    if(result.scopeNotice)catalog.append(el('p',result.scopeNotice));
    if(!result.rows.length){catalog.append(el('p','No matching rows.'));return;}
    const keys=Object.keys(result.rows[0]),table=el('table'),head=el('thead'),labels=el('tr');for(const key of keys)labels.append(el('th',key.replaceAll('_',' ')));head.append(labels);const body=el('tbody');
    for(const row of result.rows){const tr=el('tr');tr.tabIndex=0;tr.setAttribute('aria-selected','false');for(const key of keys)tr.append(el('td',display(row[key])));const select=()=>{selectedRow=row;if(category==='datapump'){monitored={owner:row.owner_name,name:row.job_name};pumpInfo.textContent='Choose Inspect Data Pump job to read its current state.';}for(const r of body.children)r.setAttribute('aria-selected',String(r===tr));releaseReview();renderFields();};tr.onclick=select;tr.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();select();}};body.append(tr);}table.append(head,body);catalog.append(table);renderFields();
  }
  function renderActions(){actions.replaceChildren();for(const a of capabilities.actions.filter(a=>a.category===category)){const o=el('option',a.label);o.value=a.id;actions.append(o);}renderFields();}
  function renderFields(){form.replaceChildren();const action=capabilities?.actions.find(a=>a.id===actions.value);if(!action)return;
    const row=selectedRow??{},values={name:row.username??row.role??row.profile??row.tablespace_name??row.table_name??row.name??row.job_name,owner:row.owner??row.owner_name??capabilities.target.schema,tablespace:row.tablespace_name,fileId:row.file_id,kind:row.kind,sid:row.sid,serial:row['serial#'],sqlId:row.sql_id,object:row.object_name,objectType:row.type};
    for(const spec of action.fields){let input;if(spec.type==='select')input=choose(spec.label,spec.options.map(v=>[v,v]));else{input=el('input');input.type=spec.type;input.autocomplete='off';}input.name=spec.key;
      if(spec.type==='checkbox')input.checked=spec.value==='true';else input.value=values[spec.key]??spec.value;
      if(spec.type==='select'&&!input.value)input.value=spec.value;if(spec.type==='number'){input.step='1';input.min=spec.key==='quotaMB'?'-1':'0';input.max='1048576';}else if(spec.type==='text')input.maxLength=1024;
      form.append(field(spec.label,input));}update();
  }
  actions.onchange=()=>{releaseReview();renderFields();};form.onsubmit=e=>{e.preventDefault();act(prepare);};form.oninput=()=>releaseReview();ack.onchange=update;password.oninput=update;
  async function prepare(){await releaseReview();const request={...selected,action:actions.value};for(const input of form.elements){request[input.name]=input.type==='checkbox'?input.checked:input.type==='number'?Number(input.value):input.value;}
    plan=await job('/oracle/admin/prepare',request,{retain:true});sql.value=plan.commands.map(c=>c.sql).join('\n\n');notice.textContent='Target '+plan.target.databaseUniqueName+' / '+plan.target.database+' / '+plan.target.user+'. '+plan.notice+' Review expires '+stamp(plan.expiresAt)+'.';passwordField.hidden=!plan.requiresPassword;review.hidden=false;review.scrollIntoView({block:'nearest'});
  }
  async function applyPlan(){const id=reviewJob,secret=password.value;password.value='';let result;
    try{result=await job('/oracle/admin/apply',{planId:id,confirmed:ack.checked,...(plan.requiresPassword?{password:secret}:{})},{mutation:true});last.hidden=false;if(result.status!=='success')error.textContent=result.message;else {notify(result.message);if(result.dataPump){monitored=result.dataPump;pump.hidden=false;pumpInfo.textContent=result.message;}}}finally{last.hidden=false;await releaseReview();main.scrollTop=0;}
  }
  const invalidateRman=()=>{rmanArtifact=null;rmanSql.value='';update();};rmanMode.onchange=backupPath.oninput=archiveLogs.onchange=invalidateRman;
  function stopWatch(){watch.checked=false;if(watchTimer)clearTimeout(watchTimer);watchTimer=null;}
  function scheduleWatch(){if(!watch.checked||!dialog.open)return;watchTimer=setTimeout(async()=>{watchTimer=null;if(!busy&&monitored)await act(inspectPump);scheduleWatch();},3000);}
  watch.onchange=()=>{if(watch.checked)scheduleWatch();else stopWatch();};
  async function inspectPump(){if(!monitored)return;const result=await job('/oracle/datapump/status',{...selected,...monitored});
    const old=result.previousObservation;pumpInfo.textContent=[result.owner+'.'+result.name,'State: '+result.state,'Observed: '+stamp(result.observedAt),result.started?'Started (Oracle server time): '+result.started:'',result.percentDone!==undefined?'Data progress: '+result.percentDone+'% | Bytes: '+result.bytesProcessed+' / '+result.totalBytes:'',result.phase!==undefined?'Phase: '+result.phase:'',result.errorCount!==undefined?'Errors: '+result.errorCount+' | Restarts: '+result.restartCount:'',result.message||'',...(result.errors??[]),old?'Previous observation: '+old.state+' at '+stamp(old.observedAt)+(old.started?' | Started '+old.started:''):''].filter(Boolean).join(String.fromCharCode(10));
    if(['COMPLETED','STOPPED','NOT RUNNING','UNKNOWN'].includes(result.state))stopWatch();
  }
  update();
}
