import {lucide} from './tree-icons.js';

/** Browser-owned creation draft; nothing executes before reviewed Apply. */
export function createObject({api,profile,group,refresh,notice}) {
  const el=(tag,text)=>{const e=document.createElement(tag);if(text)e.textContent=text;return e;};
  const dialog=el('dialog'),header=el('header','New '+group.name),body=el('div'),footer=el('footer');
  dialog.className='designer-dialog object-creation-dialog';body.className='designer-dialog-body';footer.className='actions';
  let closed=false,busy=false,plan=null,reviewId=null;const jobs=new Set();
  const button=(label,icon,action)=>{const b=el('button');b.type='button';b.title=label;b.setAttribute('aria-label',label);b.append(lucide(icon),document.createTextNode(label));b.onclick=action;return b;};
  const close=button('Close','x',()=>dialog.close());header.append(close);
  const target=el('p',profile.name+' · '+[group.database,group.schema].filter(Boolean).join(' / '));
  const form=el('fieldset'),message=el('p');message.setAttribute('role','status');
  const field=(label,multi=false,value='')=>{const wrap=el('label',label),input=el(multi?'textarea':'input');input.setAttribute('aria-label',label);input.spellcheck=false;input.value=value;if(multi){input.rows=6;input.wrap='off';}wrap.append(input);form.append(wrap);return input;};
  const name=field('Object name');let query,table,indexColumns;const columns=[];
  if(group.kind==='tables'){
    const list=el('div');form.append(list);
    const add=()=>{const row=el('div');row.className='creation-column';const n=el('input'),t=el('input'),nullable=el('input');n.placeholder='Column name';n.setAttribute('aria-label','Column name');t.placeholder='Datatype, e.g. integer';t.setAttribute('aria-label','Column datatype');nullable.type='checkbox';nullable.checked=true;nullable.setAttribute('aria-label','Allow NULL');const label=el('label','Allow NULL');label.prepend(nullable);const entry={n,t,nullable};columns.push(entry);row.append(n,t,label,button('Delete draft column','trash-2',()=>{columns.splice(columns.indexOf(entry),1);row.remove();}));list.append(row);};
    form.append(button('Add column','list-plus',add));add();
  }
  if(['views','materialized_views'].includes(group.kind))query=field('SELECT query',true);
  if(group.kind==='indexes'){table=field('Table name');indexColumns=field('Column names (one per line)',true);}
  const sql=el('textarea');sql.readOnly=true;sql.wrap='off';sql.rows=9;sql.setAttribute('aria-label','Reviewed creation SQL');sql.hidden=true;
  const warning=el('p','Creation uses your database permissions. It may acquire locks; SELECT functions can have side effects. No objects are replaced.');
  const release=async id=>{jobs.delete(id);await api('/jobs/'+id,'DELETE').catch(()=>{});};
  const job=async(path,data,retain=false)=>{const started=await api(path,'POST',data);jobs.add(started.id);let complete=false;try{for(;;){if(closed){await api('/jobs/'+started.id+'/cancel','POST',{}).catch(()=>{});throw Error('Dialog closed; cancellation requested.');}const state=await api('/jobs/'+started.id);if(state.finished){if(state.state!=='complete')throw Error(state.error||state.state);complete=true;return{result:state.result,id:started.id};}await new Promise(r=>setTimeout(r,250));}}finally{if(!retain||closed||!complete)await release(started.id);}};
  const sync=()=>{form.disabled=busy||!!plan;review.disabled=busy||!!plan;apply.disabled=busy||!plan;edit.disabled=busy||!plan;dialog.setAttribute('aria-busy',String(busy));};
  const run=async action=>{busy=true;message.textContent='Working…';sync();try{await action();}catch(e){message.textContent=e.message;}finally{busy=false;sync();}};
  const review=button('Review SQL','file-code',()=>run(async()=>{
    const draft={connectionId:profile.id,...group,name:name.value};
    if(query)draft.query=query.value;if(table){draft.table=table.value;draft.indexColumns=indexColumns.value.split('\n').map(v=>v.trim()).filter(Boolean);}
    if(group.kind==='tables')draft.columns=columns.map(c=>({name:c.n.value,type:c.t.value,nullable:c.nullable.checked}));
    const value=await job('/objects/prepare',draft,true);reviewId=value.id;plan=value.result;sql.value=plan.sql;sql.hidden=false;message.textContent=plan.atomic?'Review the SQL and fixed target. Apply uses a transaction.':'Review the SQL and fixed target. This engine may commit DDL immediately.';
  }));
  const edit=button('Edit draft','pencil',()=>run(async()=>{if(reviewId)await release(reviewId);reviewId=null;plan=null;sql.hidden=true;message.textContent='Draft retained; review again after editing.';}));
  const apply=button('Apply','check',()=>run(async()=>{
    const id=reviewId;reviewId=null;plan=null;const value=await job('/objects/apply',{planId:id,confirmed:true}).finally(()=>release(id));
    if(value.result.status!=='success')throw Error(value.result.message);notice(value.result.message);dialog.close();await refresh();
  }));
  footer.append(review,edit,apply,button('Cancel','x',()=>dialog.close()));body.append(target,form,warning,sql,message);dialog.append(header,body,footer);
  dialog.addEventListener('cancel',event=>event.preventDefault());
  const abandon=()=>{closed=true;for(const id of jobs){api('/jobs/'+id+'/cancel','POST',{}).catch(()=>{});void release(id);}};
  window.addEventListener('pagehide',abandon,{once:true});dialog.onclose=()=>{abandon();window.removeEventListener('pagehide',abandon);dialog.remove();};
  document.body.append(dialog);sync();dialog.showModal();name.focus();return dialog;
}
