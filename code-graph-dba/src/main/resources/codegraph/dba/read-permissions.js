const node=(tag,text)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;return e;};
const describe=s=>[s.connectionName||s.connectionId,s.database,s.schema!==s.database?s.schema:null,s.object,s.level==='connection'?'all databases and schemas':s.level==='database'?'all schemas and tables':s.level==='schema'?'all tables and views':null].filter(Boolean).join(' / ');
export function readGrantSummary(grant){return (grant.selectors||[]).map(describe).join('\n');}
export function editReadGrant({api,identity,initial={},request=null,onSave}){
  return new Promise(resolve=>{
    const returnFocus=document.activeElement,dialog=node('dialog');dialog.className='read-permission-dialog';dialog.setAttribute('aria-label','Allow SELECTs');
    const heading=node('h2',request?'Allow SELECTs':'Database read permission'),error=node('p');error.className='context-error';error.setAttribute('role','alert');
    const recipient=node('p','Recipient: '+identity),notice=node('p','Includes read-only SELECTs and metadata inspection. All tables includes future tables. All databases includes system catalogs. Explicit object names do not follow renames. Supported connections: PostgreSQL, MySQL, MariaDB and Oracle 19c+. Oracle reusable SELECTs require an ordinary account and exact ordinary tables; views, synonyms, custom functions and sequence access require one-time review.');
    const selection=node('div'),preview=node('pre'),shortcuts=node('div'),picker=node('fieldset');picker.append(node('legend','Add permission targets'));
    const field=(title,items=[])=>{const label=node('label',title),select=node('select');select.setAttribute('aria-label',title);for(const [value,text]of items){const o=node('option',text);o.value=value;select.append(o);}label.append(select);picker.append(label);return select;};
    const connection=field('Connection'),level=field('Scope',[['object','Selected tables/views'],['schema','Selected schema'],['database','Selected database'],['connection','Selected connection']]);
    const database=field('Database'),schema=field('Schema'),object=field('Table/view');object.multiple=true;object.size=5;
    const more=node('button','Load more'),add=node('button','Add selected scope');more.type=add.type='button';picker.append(more,add);
    const durationLabel=node('label','Duration'),duration=node('select');duration.setAttribute('aria-label','Duration');
    for(const [value,text]of [['mcp_session','This MCP session'],['until_revoked','Until revoked']]){const o=node('option',text);o.value=value;duration.append(o);}duration.value=initial.lifetime||'mcp_session';durationLabel.append(duration);
    const session=node('select');session.setAttribute('aria-label','MCP session');durationLabel.append(session);
    const buttons=node('div'),cancel=node('button','Cancel'),save=node('button',request?'Grant and run':'Save permission');buttons.className='actions';buttons.append(cancel,save);
    dialog.append(heading,recipient,notice,error,shortcuts,picker,selection,durationLabel,node('h3','Permission preview'),preview,buttons);document.body.append(dialog);
    let draft=(initial.selectors||[]).map(s=>({...s})),connections=[],closed=false,sequence=0,next=null,busy=0,saving=false;
    const jobs=new Set(),targetPath=request?'/approvals/'+request.id+'/permission-targets':'/permissions/targets';
    const jobPath=id=>request?targetPath+'/'+id:'/jobs/'+id;
    async function release(id){jobs.delete(id);try{await api(jobPath(id),'DELETE');}catch{}}
    async function targets(input,generation=sequence){
      let result=await api(targetPath,'POST',input);
      if(result.id){
        const id=result.id;jobs.add(id);if(closed||generation!==sequence){await release(id);return {items:[]};}
        try{while(!closed&&generation===sequence){result=await api(jobPath(id));if(result.state==='complete')return result.result;if(['failed','cancelled'].includes(result.state))throw Error(result.error||'Target discovery failed');await new Promise(r=>setTimeout(r,150));}return {items:[]};}
        finally{await release(id);}
      }
      return result;
    }
    const setOptions=(select,items,placeholder,append=false)=>{if(!append){select.replaceChildren();if(placeholder){const o=node('option',placeholder);o.value='';select.append(o);}}for(const item of items){const o=node('option',item.name);o.value=item.id||item.name;select.append(o);}};
    const mysql=()=>['mysql','mariadb'].includes(connections.find(c=>c.id===connection.value)?.vendor);
    function render(){
      const byId=new Map(connections.map(c=>[c.id,c.name]));selection.replaceChildren();
      for(let i=0;i<draft.length;i++){const row=node('p',describe({...draft[i],connectionName:byId.get(draft[i].connectionId)})),remove=node('button','Remove');remove.type='button';remove.onclick=()=>{draft.splice(i,1);render();};row.append(remove);selection.append(row);}
      preview.textContent=identity+'\n'+(duration.value==='mcp_session'?'This MCP session':'Until revoked')+'\n'+draft.map(s=>describe({...s,connectionName:byId.get(s.connectionId)})).join('\n')+'\nIncludes scoped metadata. Database account privileges still apply.';
      database.parentElement.hidden=level.value==='connection';
      schema.parentElement.hidden=mysql()||['connection','database'].includes(level.value);
      object.parentElement.hidden=level.value!=='object';
      session.hidden=!!request||duration.value!=='mcp_session';
      save.disabled=saving||busy>0||draft.length===0||(duration.value==='mcp_session'&&!request&&!session.value);
      more.hidden=!next;add.disabled=busy>0||!connection.value||(level.value!=='connection'&&!database.value)||(['schema','object'].includes(level.value)&&!mysql()&&!schema.value)||(level.value==='object'&&!object.selectedOptions.length);
    }
    async function safely(fn){try{error.textContent='';await fn();}catch(e){if(!closed)error.textContent=e.message;}finally{render();}}
    async function load(kind,append=false){
      const generation=++sequence;await Promise.allSettled([...jobs].map(release));busy++;next=null;render();
      const input={kind,connectionId:connection.value};if(kind!=='databases')input.database=database.value;if(kind==='objects')input.schema=mysql()?database.value:schema.value;
      if(append&&more.dataset.offset)input.offset=Number(more.dataset.offset);
      try{const result=await targets(input,generation);if(closed||generation!==sequence)return;const select=kind==='databases'?database:kind==='schemas'?schema:object;setOptions(select,result.items||[],kind==='objects'?null:'Choose '+kind,append);if(result.hasMore){next=kind;more.dataset.offset=result.nextOffset;}}
      finally{busy--;render();}
    }
    connection.onchange=()=>safely(async()=>{database.replaceChildren();schema.replaceChildren();object.replaceChildren();await load('databases');});
    database.onchange=()=>safely(async()=>{schema.replaceChildren();object.replaceChildren();if(database.value)await load(mysql()?'objects':'schemas');});
    schema.onchange=()=>safely(async()=>{object.replaceChildren();if(schema.value)await load('objects');});
    object.onchange=render;level.onchange=render;duration.onchange=render;session.onchange=render;more.onclick=()=>safely(()=>load(next,true));
    add.onclick=()=>{const s={connectionId:connection.value,level:level.value};if(s.level!=='connection')s.database=database.value;if(['schema','object'].includes(s.level))s.schema=mysql()?database.value:schema.value;
      const additions=s.level==='object'?[...object.selectedOptions].map(o=>({...s,object:o.value})):[s];for(const a of additions)if(!draft.some(d=>JSON.stringify(d)===JSON.stringify(a)))draft.push(a);render();};
    async function finish(value){if(closed)return;closed=true;++sequence;await Promise.allSettled([...jobs].map(release));dialog.close();dialog.remove();returnFocus?.focus();resolve(value);}
    cancel.onclick=()=>finish(null);dialog.addEventListener('cancel',e=>{e.preventDefault();void finish(null);});
    save.onclick=()=>safely(async()=>{saving=true;render();const payload={selectors:draft,lifetime:duration.value,enabled:initial.enabled!==false};if(!request&&duration.value==='mcp_session')payload.sessionLabel=session.value;if(initial.revision!==undefined)payload.revision=initial.revision;
      try{await onSave(payload);await finish(payload);}finally{saving=false;}});
    if(request){
      const current=request.selectPermission?.selectors||[];
      for(const [scope,title]of [['object','These tables/views'],['schema','These schemas'],['database','These databases'],['connection','These connections']]){
        const button=node('button',title);button.type='button';
        const required=scope==='object'?['database','schema','object']:scope==='schema'?['database','schema']:scope==='database'?['database']:[];
        button.disabled=!current.length||current.some(s=>required.some(key=>!s[key]));
        if(button.disabled)button.title='This request does not identify every target needed for this scope.';
        button.onclick=()=>{const selected=current.map(source=>{const s={connectionId:source.connectionId,level:scope};for(const key of required)s[key]=source[key];return s;});draft=[...new Map(selected.map(s=>[JSON.stringify(s),s])).values()];render();};
        shortcuts.append(button);
      }
    }
    dialog.showModal();render();void safely(async()=>{
      connections=(await targets({kind:'connections'})).items||[];if(closed)return;setOptions(connection,connections,'Choose connection');
      if(!request){const sessions=await api('/agents/'+encodeURIComponent(initial.agentId)+'/read-sessions');setOptions(session,sessions.map(s=>({id:s.label,name:'Session '+s.label})),'Choose session');if(initial.sessionLabel)session.value=initial.sessionLabel;else if(sessions.length===1)session.value=sessions[0].label;}
      render();connection.focus({preventScroll:true});
    });
  });
}
const overlaps=(a,b)=>a.connectionId===b.connectionId&&(a.level==='connection'||b.level==='connection'||a.database===b.database&&(a.level==='database'||b.level==='database'||a.schema===b.schema&&(a.level==='schema'||b.level==='schema'||a.object===b.object)));
export function installReadPermissions({api,menu}){
  const entry=node('button','Database permissions');entry.id='database-permissions';entry.setAttribute('role','menuitem');menu.append(entry);
  entry.onclick=async()=>{
    menu.hidden=true;
    const dialog=node('dialog');dialog.className='read-permission-dialog';dialog.setAttribute('aria-label','Database permissions');
    const error=node('p');error.className='context-error';error.setAttribute('role','alert');const identities=node('select');identities.setAttribute('aria-label','Permission recipient');
    const list=node('div'),create=node('button','New read permission'),close=node('button','Close');let refreshSequence=0;
    dialog.append(node('h2','Database permissions'),node('p','Persistent permissions for Trusted local agents are shared by local agents. Session permissions end with their MCP session. Overlapping grants are additive; revoke every covering grant to remove access.'),error,identities,create,list,close);document.body.append(dialog);
    const safe=fn=>async()=>{try{error.textContent='';await fn();}catch(e){error.textContent=e.message;}};
    const identity=()=>identities.selectedOptions[0]?.textContent||'';
    async function edit(policy={}){const saved=await editReadGrant({api,identity:identity(),initial:{...policy,agentId:identities.value},onSave:body=>api('/agents/'+identities.value+'/policies'+(policy.id?'/'+policy.id:''),policy.id?'PUT':'POST',body)});if(saved)await refresh();}
    async function refresh(){
      const generation=++refreshSequence,recipient=identities.value;list.replaceChildren();const data=await api('/agents/'+recipient+'/permissions');
      const policies=[...(data.reusablePolicies||[]),...(data.readPolicies||[])];
      const connections=(await api('/permissions/targets','POST',{kind:'connections'})).items||[];
      if(generation!==refreshSequence||recipient!==identities.value||!dialog.isConnected)return;
      const names=new Map(connections.map(c=>[c.id,c.name]));
      for(const p of policies){
        const row=node('article');row.append(node('h3',p.kind==='select_read'?'SELECTs and metadata':'Existing '+(p.category||p.capability)+' permission'),node('pre',p.kind==='select_read'?readGrantSummary({...p,selectors:p.selectors.map(s=>({...s,connectionName:names.get(s.connectionId)}))}):JSON.stringify(p.scope)),node('p',(p.enabled===false?'Disabled':'Enabled')+' · '+p.lifetime+' · Last used: '+(p.lastUsedAt?new Date(p.lastUsedAt).toLocaleString():'Never')));
        if(p.kind==='select_read'){
          const shared=policies.filter(other=>other.id!==p.id&&other.enabled!==false&&p.enabled!==false&&other.kind==='select_read'&&(!p.sessionLabel||!other.sessionLabel||p.sessionLabel===other.sessionLabel)&&p.selectors.some(a=>other.selectors.some(b=>overlaps(a,b))));
          row.append(node('p',shared.length?'Overlaps active permissions: '+shared.map(other=>other.id).join(', '):'No overlapping active SELECT permissions.'));
          const e=node('button','Edit');e.onclick=safe(()=>edit(p));row.append(e);}
        if(p.kind==='select_read'||p.category){const toggle=node('button',p.enabled?'Disable':'Enable');toggle.onclick=safe(async()=>{await api('/agents/'+identities.value+'/policies/'+p.id,'PATCH',{enabled:!p.enabled});await refresh();});row.append(toggle);}
        const revoke=node('button','Revoke');revoke.onclick=safe(async()=>{await api('/agents/'+identities.value+'/policies/'+p.id,'DELETE');await refresh();});row.append(revoke);list.append(row);
      }
      if(!list.childElementCount)list.append(node('p','No saved permissions.'));
    }
    close.onclick=()=>dialog.close();dialog.addEventListener('close',()=>{dialog.remove();entry.focus();});
    identities.onchange=safe(refresh);create.onclick=safe(()=>edit());dialog.showModal();
    await safe(async()=>{for(const a of await api('/agents')){const o=node('option',a.name);o.value=a.id;identities.append(o);}create.disabled=!identities.value;if(identities.value)await refresh();})();
  };
}
