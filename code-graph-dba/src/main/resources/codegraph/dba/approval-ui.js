import {editReadGrant} from './read-permissions.js';
import {ApprovalClient} from './approval-client.js';
import {lucide} from './tree-icons.js';
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
const kindName=s=>s.replaceAll('_',' ').replace(/\b\w/g,c=>c.toUpperCase());
export function installApprovalUI({api,button,reviewConnection,only=null}){
  const dialog=el('dialog',undefined,'agent-approval-dialog');dialog.id='agent-approval-dialog';dialog.setAttribute('aria-labelledby','agent-approval-title');
  const title=el('h2','Agent database approvals');title.id='agent-approval-title';
  const error=el('p','','context-error');error.setAttribute('role','alert');
  const list=el('div'),close=el('button','Close');close.onclick=()=>dialog.close();
  dialog.append(title,el('p','Review the exact request and target. Dangerous and administrative operations require one-time approval.'),error,list,close);document.body.append(dialog);
  let active=null,opening=false,editing=false,requests=[],queued=[],refreshing=false;const deferred=new Set();
  const client=new ApprovalClient({api,reviewOnly:!!only,changed:(count,mode)=>{if(button){button.hidden=!!mode?.automatic;button.textContent=count?'Approvals ('+count+')':'Approvals';}},offer:id=>{if((!only||only===id)&&!deferred.has(id)&&!queued.includes(id)&&active!==id)queued.push(id);void present();},unavailable:e=>{error.textContent=e.message;for(const b of list.querySelectorAll('button'))b.disabled=true;}});
  const safely=fn=>async()=>{try{error.textContent='';await fn();}catch(e){error.textContent=e.message;}};
  const json=(name,value)=>{const detail=el('details');detail.open=true;detail.append(el('summary',name),el('pre',JSON.stringify(value,null,2)));return detail;};
  function render(request){
    list.replaceChildren();list.dataset.job=JSON.stringify(request?.job??null);if(!request){list.append(el('p','No pending agent database requests.'));return;}
    const row=el('article',undefined,'approval-request');row.dataset.approval=request.id;
    const h=el('h3');h.append(lucide(request.type?.startsWith('connection_')?'plug':request.type?.startsWith('binding_')?'link':'terminal'),document.createTextNode(kindName(request.type||'live_sql')));row.append(h);
    row.append(el('p','Agent: '+(request.agentName||request.agentId)+' · '+request.state),el('p',request.purpose||''),el('p',[request.project,request.environment,request.role,request.connectionName,request.database,request.schema].filter(Boolean).join(' · ')));
    if(request.environment){const environment=el('p','Environment: '+request.environment.toUpperCase(),'approval-environment');environment.dataset.environment=request.environment;row.append(environment);}
    if(request.operation)row.append(el('p','Category: '+request.operation.category+' · '+request.operation.reason),el('p',request.operation.limitations||''));
    if(request.permissionScope)row.append(json('Exact reusable scope',request.permissionScope),el('p',request.identityNotice||''),el('p','Always allow matches exact SQL, typed parameters and execution options until revoked. Similar grants only this category and exact scope, either until this MCP session ends or until revoked.'));
    for(const key of ['target','before','after'])if(request[key])row.append(json(key==='target'?'Exact target':key==='before'?'Current configuration':'Proposed configuration',request[key]));
    if(request.sql){const sql=el('textarea');sql.readOnly=true;sql.spellcheck=false;sql.value=request.sql;sql.setAttribute('aria-label','SQL awaiting approval');row.append(sql,json('Parameters',request.parameters),el('p',request.scopeNotice||''),el('p',request.transactionNotice||''));}
    if(request.job)row.append(json('Ephemeral test / execution status',request.job));
    if(request.state!=='awaiting_approval'){list.append(row);return;}
    const expires=el('p');expires.dataset.expires=request.expiresAt;row.append(expires);
    row.append(el('p','Choosing an approval action confirms the exact request, target, and displayed risks.'));
    const actions=el('div',undefined,'approval-actions');
    // The explicit approval click is the acknowledgement; rendering or dismissing is not.
    const decide=async(action,extra={})=>{if(extra.saveUntested&&!confirm('Save without a successful connection test?'))return;const outcome=await api('/approvals/'+request.id,'POST',{action,acknowledged:action!=='reject',...extra});render(outcome);client.leases.delete(request.id);client.active=null;active=null;await refresh();await present();};
    const reject=el('button','Deny');reject.onclick=safely(()=>decide('reject'));actions.append(reject);
    const add=(text,action,extra={})=>{const b=el('button',text);b.onclick=safely(()=>decide(action,extra));actions.append(b);};
    if(['connection_create','connection_update'].includes(request.type)){
      const edit=el('button','Review / edit proposal');edit.onclick=safely(async()=>{editing=true;dialog.close();await reviewConnection(request,async()=>{editing=false;await client.release(request.id).catch(()=>{});active=null;queued.unshift(request.id);await present();});});actions.append(edit);
      if(request.requiresDriverInstall)row.append(el('p','Review and explicitly install or select the requested JDBC driver before testing or approval.'));
      else{
        const test=el('button','Test proposal');test.onclick=safely(async()=>{await api('/approvals/'+request.id+'/test','POST',{});await refresh();});actions.append(test);
        add('Apply after successful test','approve_once');add('Save untested','approve_once',{saveUntested:true});
      }
    }else{
      add('Allow once','approve_once');
      if(request.selectPermission){
        const selectRead=el('button','Allow SELECTs…');selectRead.disabled=!request.selectPermission.eligible;selectRead.title=request.selectPermission.reason||'';
        selectRead.onclick=safely(async()=>{editing=true;try{await editReadGrant({api,identity:request.agentName||request.agentId,initial:{selectors:request.selectPermission.selectors||[],lifetime:'mcp_session'},request,onSave:readGrant=>decide('allow_selects',{readGrant})});}finally{editing=false;}});
        actions.append(selectRead);if(!request.selectPermission.eligible)row.append(el('small',request.selectPermission.reason));
      }
      if(request.approvalChoices){
        const split=el('span',undefined,'approval-split'),arrow=el('button','▾'),menu=el('div',undefined,'approval-choice-menu');
        arrow.title='Reusable approval choices';arrow.setAttribute('aria-label',arrow.title);arrow.setAttribute('aria-haspopup','menu');arrow.setAttribute('aria-expanded','false');menu.setAttribute('role','menu');menu.hidden=true;
        const dismiss=()=>{menu.hidden=true;arrow.setAttribute('aria-expanded','false');};
        for(const choice of request.approvalChoices){const item=el('button',choice.label);item.setAttribute('role','menuitem');item.setAttribute('aria-disabled',String(!choice.enabled));item.title=choice.reason+' · '+choice.lifetime;item.onclick=safely(async()=>{if(!choice.enabled)return;dismiss();await decide(choice.action);});menu.append(item);if(!choice.enabled)menu.append(el('small',choice.reason));}
        arrow.onclick=()=>{menu.hidden=!menu.hidden;arrow.setAttribute('aria-expanded',String(!menu.hidden));if(!menu.hidden)menu.querySelector('button')?.focus();};
        split.addEventListener('keydown',event=>{const items=[...menu.querySelectorAll('button')];if(event.key==='Escape'&&!menu.hidden){event.preventDefault();event.stopPropagation();dismiss();arrow.focus();}else if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)&&!menu.hidden){event.preventDefault();let i=items.indexOf(document.activeElement);i=event.key==='Home'?0:event.key==='End'?items.length-1:(i+(event.key==='ArrowDown'?1:-1)+items.length)%items.length;items[i]?.focus();}});
        split.addEventListener('focusout',event=>{if(!split.contains(event.relatedTarget))dismiss();});
        split.append([...actions.children].find(b=>b.textContent==="Allow once"),arrow,menu);actions.append(split);
      }else if(request.eligiblePersistentRead){add('Always allow this read',request.projectId?'always_binding_read':'always_connection_read');}
    }
    row.append(actions);list.append(row);countdown();
  }
  function countdown(){for(const node of list.querySelectorAll('[data-expires]')){const seconds=Math.max(0,Math.ceil((Number(node.dataset.expires)-Date.now())/1000));node.textContent=seconds+' seconds remaining';if(!seconds)for(const b of list.querySelectorAll('button'))b.disabled=true;}}
  async function refresh(){
    if(refreshing)return;refreshing=true;
    try{requests=(await api('/approvals')).filter(r=>!only||r.id===only);if(button){const count=requests.filter(r=>r.state==='awaiting_approval').length;button.textContent=count?'Approvals ('+count+')':'Approvals';}
      if(active){const request=requests.find(r=>r.id===active);if(!request||request.state!=='awaiting_approval'){await client.release(active).catch(()=>{});active=null;render(request);}else if(dialog.open&&JSON.stringify(request.job??null)!==list.dataset.job){list.dataset.job=JSON.stringify(request.job??null);render(request);}}
    }finally{refreshing=false;}
  }
  async function present(force=false){
    if(opening||editing||document.hidden||document.querySelector('dialog[open]:not(#agent-approval-dialog)'))return;
    if(active&&!force)return;opening=true;
    try{
      await refresh();const pending=requests.filter(r=>r.state==='awaiting_approval').sort((a,b)=>a.createdAt-b.createdAt);
      const id=only??queued.find(id=>pending.some(r=>r.id===id))??(force?pending[0]?.id:null);
      if(!id){if(force){render(null);if(!dialog.open)dialog.showModal();}return;}
      const lease=await client.claim(id);active=id;queued=queued.filter(x=>x!==id);
      const latest=(await api('/approvals')).find(r=>r.id===id);
      if(!latest||latest.reviewRevision!==lease.reviewRevision){await client.release(id);active=null;throw new Error('Proposal changed. Open it again to review the updated details.');}
      render(latest);if(!dialog.open)dialog.showModal();
    }catch(e){error.textContent=e.message;if(force&&!dialog.open)dialog.showModal();}
    finally{opening=false;}
  }
  dialog.addEventListener('close',()=>{if(!editing&&active){deferred.add(active);void client.release(active).catch(()=>{});active=null;queued=[];}});
  if(button)button.onclick=safely(()=>{deferred.clear();return present(true);});
  const pendingLink=location.hash.match(/^#approval=([a-f0-9-]{36})$/);if(pendingLink){queued.push(pendingLink[1]);history.replaceState(null,'',location.pathname+location.search);}
  if(only)queued.push(only);
  const tick=setInterval(()=>{countdown();if(dialog.open&&!editing)void refresh().catch(e=>error.textContent=e.message);if(!active)void present();},1000);
  void client.ready.then(()=>present()).catch(e=>error.textContent=e.message);
  window.addEventListener('pagehide',()=>{clearInterval(tick);client.dispose();},{once:true});
  return {open:()=>present(true),dispose:()=>{clearInterval(tick);client.dispose();dialog.remove();}};
}
