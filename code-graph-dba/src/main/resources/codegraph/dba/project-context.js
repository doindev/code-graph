import {installApprovalUI} from './approval-ui.js';
import {lucide} from './tree-icons.js';
const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;};
const time=value=>value?new Date(value).toLocaleString():'Not yet';
const environments=['local','dev','test','stage','prod'];
const typeName=value=>value.replaceAll('_',' ').replace(/\b\w/g,c=>c.toUpperCase());
export function installProjectContext({api,profiles,notify,openConnection,reviewConnection}){
  const menu=document.getElementById('workspace-settings-menu'),toolbar=document.getElementById('workspace-toolbar-actions');
  const contextButton=el('button','Project databases');contextButton.id='project-databases';contextButton.setAttribute('role','menuitem');contextButton.prepend(lucide('database'));menu.append(contextButton);
  const approvalButton=el('button','Approvals');approvalButton.id='agent-approvals';approvalButton.title='Review agent database requests';approvalButton.setAttribute('aria-label','Agent database approvals');toolbar.prepend(approvalButton);
  const context=el('dialog',undefined,'project-context-dialog');context.id='project-context-dialog';context.setAttribute('aria-labelledby','project-context-title');
  const title=el('h2','Project databases');title.id='project-context-title';context.append(title,el('p','Associate connections with onboarded applications by environment and logical role. Multiple roles can share an environment; a role must be unique within that application/environment.'));
  const error=el('p',undefined,'context-error');error.setAttribute('role','alert');context.append(error);
  const list=el('div',undefined,'context-bindings');context.append(list);
  const form=el('form');form.id='context-binding-form';const fields={};
  const input=(name,label,type='text',value='')=>{const row=el('label',label),field=el(type==='select'?'select':'input');field.name=name;if(type!=='select')field.type=type;field.value=value;row.append(field);form.append(row);fields[name]=field;return field;};
  input('projectId','Application / project','select');input('connectionId','Database connection','select');const environment=input('environment','Environment','select');for(const value of environments){const option=el('option',value);option.value=value;environment.append(option);}environment.value='local';
  input('role','Logical role (for example primary, analytics, reporting)').required=true;input('purpose','Purpose / how this database is used').required=true;input('database','Database / catalog');input('schema','Schema (optional)');
  for(const [name,label,value,max]of [['scanIntervalSeconds','Scan interval (seconds)',900,86400],['idleTimeoutSeconds','Activity idle timeout (seconds)',1800,604800]]){const field=input(name,label,'number',value);field.min=10;field.max=max;field.required=true;}
  const save=el('button','Add relationship');save.type='submit';const clear=el('button','New relationship');clear.type='button';const addConnection=el('button','Create connection');addConnection.type='button';form.append(save,clear,addConnection);context.append(el('h3','Relationship settings'),form);
  const grantSection=el('section');grantSection.append(el('h3','Agent access'),el('p','Legacy grants retain their existing scope. New scoped SQL permissions are created only by an eligible human approval. Inspect, disable or revoke them below.'));
  const grantForm=el('form');grantForm.id='context-grant-form';const agentSelect=el('select'),bindingSelect=el('select');
  for(const [label,node]of [['Agent',agentSelect],['Database relationship',bindingSelect]]){const row=el('label',label);row.append(node);grantForm.append(row);}
  agentSelect.setAttribute('aria-label','Context agent');bindingSelect.setAttribute('aria-label','Context database binding');const live=el('input');live.type='checkbox';const liveLabel=el('label','Allow this agent to request one-time live SQL review');liveLabel.append(live);grantForm.append(liveLabel);
  const grant=el('button','Grant exact access');grant.type='submit';const revoke=el('button','Remove exact access');revoke.type='button';grantForm.append(grant,revoke);grantSection.append(grantForm);
  const policies=el('div',undefined,'agent-policies');grantSection.append(el('h4','Reusable and legacy permissions'),policies);
  const createForm=el('form');createForm.id='context-agent-form';const agentName=el('input');agentName.required=true;agentName.maxLength=120;agentName.placeholder='New agent name';agentName.setAttribute('aria-label','New context agent name');const create=el('button','Create agent');create.type='submit';const token=el('textarea');token.readOnly=true;token.hidden=true;token.setAttribute('aria-label','New agent token; copy once');createForm.append(agentName,create,token);grantSection.append(createForm);context.append(grantSection);
  const close=el('button','Close');close.type='button';close.onclick=()=>context.close();context.append(close);document.body.append(context);

  let state={bindings:[],projects:[]},agents=[],editing=null,loading=false,lastApprovals='',returnAfterConnection=false;
  const safely=fn=>async event=>{event?.preventDefault();try{error.textContent='';await fn(event);}catch(e){error.textContent=e.message;}};
  function options(select,items,label){const current=select.value;select.replaceChildren(...items.map(item=>{const option=el('option',label(item));option.value=item.id;return option;}));if(items.some(x=>x.id===current))select.value=current;}
  function reset(){editing=null;form.reset();fields.environment.value='local';fields.scanIntervalSeconds.value=900;fields.idleTimeoutSeconds.value=1800;for(const name of ['projectId','connectionId','database','schema'])fields[name].disabled=false;save.textContent='Add relationship';}
  function edit(binding){editing=binding;for(const[name,node]of Object.entries(fields))node.value=binding[name]??'';for(const name of ['projectId','connectionId','database','schema'])fields[name].disabled=true;save.textContent='Save relationship';fields.role.focus();}
  clear.onclick=reset;addConnection.onclick=safely(async()=>{returnAfterConnection=true;context.close();await openConnection?.();});document.addEventListener('dba-connection-saved',safely(async event=>{if(!returnAfterConnection)return;returnAfterConnection=false;await refresh();fields.connectionId.value=event.detail.id;context.showModal();}));
  function renderBindings(){list.replaceChildren();if(!state.bindings.length){list.append(el('p','No application database relationships yet.'));return;}
    const rank=value=>{const index=environments.indexOf(value);return index<0?environments.length:index;},ordered=[...state.bindings].sort((a,b)=>a.projectName.localeCompare(b.projectName)||rank(a.environment)-rank(b.environment)||a.role.localeCompare(b.role));
    let group='';for(const binding of ordered){const next=binding.projectName+'|'+binding.environment;if(next!==group){group=next;list.append(el('h3',binding.projectName+' · '+binding.environment));}
      const row=el('article',undefined,'context-binding');row.dataset.binding=binding.id;row.append(el('h4',binding.role+' · '+binding.connectionName),el('p',binding.purpose));
      row.append(el('p',[binding.database,binding.schema,binding.connectionState?.state].filter(Boolean).join(' / ')),el('p',(binding.active?'Active':'Idle')+' · '+binding.state+' · Last scan: '+time(binding.snapshot?.finishedAt)+' · Last MCP activity: '+time(binding.lastActivityAt)));
      if(binding.effectivePermissions?.length)row.append(el('p','Agent policy coverage: '+binding.effectivePermissions.join(', ')));
      if(binding.reviewRequired)row.append(el('p','Migrated relationship needs environment, role, and purpose review.','context-error'));if(binding.error)row.append(el('p',binding.error,'context-error'));
      for(const [label,action]of [['Edit',()=>edit(binding)],['Scan now',async()=>{await api('/project-context/'+binding.id+'/scan','POST',{});await refresh();}],[binding.enabled?'Disable':'Enable',async()=>{await api('/project-context','POST',{...binding,enabled:!binding.enabled});await refresh();}],['Remove',async()=>{if(!confirm('Remove this application/database relationship? The saved connection and database are not deleted.'))return;await api('/project-context/'+binding.id,'DELETE');if(editing?.id===binding.id)reset();await refresh();}]]){const button=el('button',label);button.type='button';button.onclick=safely(action);row.append(button);}list.append(row);
    }
  }
  async function renderPolicies(){
    policies.replaceChildren();const id=agentSelect.value;if(!id)return;const data=await api('/agents/'+id+'/permissions');
    const all=[...(data.reusablePolicies||[]),...(data.readPolicies||[])];if(!all.length){policies.append(el('p','No reusable or legacy read permissions.'));return;}
    for(const policy of all){
      const reusable=!!policy.category,scope=reusable?policy.scope:null;
      const label=reusable?[policy.identity,policy.match,policy.category,policy.lifetime,scope.projectId,scope.environment,scope.role,scope.bindingId,scope.connectionId,scope.database,scope.schema,policy.sessionLabel?'Session '+policy.sessionLabel:'',policy.lastUsedAt?'Last used '+new Date(policy.lastUsedAt).toLocaleString():'Not used yet',policy.enabled?'Enabled':'Disabled'].filter(Boolean).join(' · '):'Legacy · '+policy.scope+' · '+policy.capability+' · '+(policy.environment||policy.bindingId||policy.connectionId);
      const row=el('div',label,'policy-row');
      if(reusable){const toggle=el('button',policy.enabled?'Disable':'Enable');toggle.onclick=safely(async()=>{await api('/agents/'+id+'/policies/'+policy.id,'PATCH',{enabled:!policy.enabled});await renderPolicies();});row.append(toggle);}
      const remove=el('button','Revoke');remove.onclick=safely(async()=>{await api('/agents/'+id+'/policies/'+policy.id,'DELETE');await renderPolicies();});row.append(remove);policies.append(row);
    }
  }
  async function refresh(){state=await api('/project-context');agents=await api('/agents');renderBindings();options(fields.projectId,state.projects,p=>p.name);options(fields.connectionId,profiles(),p=>p.name);options(agentSelect,agents,a=>a.name);options(bindingSelect,state.bindings,b=>b.projectName+' · '+b.environment+' · '+b.role);save.disabled=!state.projects.length||!profiles().length;grant.disabled=revoke.disabled=!agents.length||!state.bindings.length;if(!state.projects.length)error.textContent='Onboard an application in the code graph UI before adding a relationship.';await renderPolicies();}
  form.onsubmit=safely(async()=>{const payload={...(editing??{}),enabled:editing?.enabled??true};for(const[name,node]of Object.entries(fields))payload[name]=name.endsWith('Seconds')?Number(node.value):node.value;await api('/project-context','POST',payload);reset();await refresh();});
  async function updateGrant(remove){const agent=agents.find(a=>a.id===agentSelect.value);const grants=(agent.contextGrants??[]).filter(g=>g.bindingId!==bindingSelect.value);if(!remove)grants.push({bindingId:bindingSelect.value,requestLive:live.checked});await api('/agents/'+agent.id+'/context','PUT',{grants});await refresh();notify(remove?'Exact binding access removed.':'Exact binding access saved.');}
  grantForm.onsubmit=safely(()=>updateGrant(false));revoke.onclick=safely(()=>updateGrant(true));agentSelect.onchange=safely(renderPolicies);
  createForm.onsubmit=safely(async()=>{const result=await api('/agents','POST',{name:agentName.value,grants:[]});token.value=result.token;token.hidden=false;await refresh();agentSelect.value=result.id;await renderPolicies();});
  contextButton.onclick=safely(async()=>{menu.hidden=true;document.getElementById('workspace-settings').setAttribute('aria-expanded','false');await refresh();context.showModal();});context.addEventListener('close',()=>{token.value='';token.hidden=true;contextButton.focus();});
  const managePolicies=el('button','Manage reusable permissions');managePolicies.type='button';managePolicies.title='List, disable or revoke scoped SQL and active MCP-session permissions';
  document.getElementById('agents-dialog').prepend(managePolicies);document.getElementById('agents').title='Manage MCP identities and scoped reusable permissions';
  managePolicies.onclick=safely(async()=>{document.getElementById('agents-dialog').close();await refresh();context.showModal();grantSection.scrollIntoView({block:'start'});agentSelect.focus();});
  installApprovalUI({api,button:approvalButton,reviewConnection});
  setInterval(async()=>{if(loading||document.hidden||!context.open)return;loading=true;try{if(!form.contains(document.activeElement)&&!grantSection.contains(document.activeElement)){state=await api('/project-context');renderBindings();}}catch{}finally{loading=false;}},5000);
}
