import {DataGridView} from './data-grid.js';
import {gridDialog,action} from './grid-interactions.js';

// Shared by Script, Table and query-builder hosts. Database access is injected.
export async function runOwnedGrid(result,change,execution,{api,replace,download=downloadExport}) {
  const context=result.grid;if(!context)throw Error('Run this query again to establish a grid context.');
  const endpoint='/grids/'+context.id;
  const submit=async(kind,body)=>{try{return await execution.submit({revision:result.grid.revision,...body},endpoint+'/'+kind);}catch(error){if(error.outcome==='unknown'||execution.uncertain)result.grid.uncertain=true;throw error;}};
  if(change.action==='reconcile'){
    if(!await confirmReconcile())return;
    // Read actual server state first; a lost submission may still be executing.
    const status=await api(endpoint);
    if(status.busy)throw Error('The previous operation is still active. Wait before reconciling.');
    result.grid.revision=status.revision;
    const next=await submit('reconcile',{direction:'refresh',limit:context.page.limit});
    DataGridView.cancelDraft(result);await replace(next);
    return true;
  }
  if(change.action==='save_rows'){
    const draft=DataGridView.draft(result);if(!draft.dirty)return true;
    const keyColumns=(context.columns??[]).filter(c=>c.key).map(c=>({...c,source:result.columns.findIndex(col=>col.id===c.id)}));
    const selection=[...draft.selection].filter(i=>!draft.deleted(i)).map(i=>keyColumns.map(c=>draft.cell(i,c))).filter(values=>values.length&&values.every(v=>v.kind==='value')).map(values=>JSON.stringify(values.map(v=>String(v.value))));
    const plan=await submit('prepare',draft.payload());execution.check();
    if(plan.deletes&&!await confirmDeletes(plan))throw Error('Save cancelled; staged changes are retained.');
    execution.check();let saved;
    try{saved=await submit('apply',{planId:plan.planId,confirmed:plan.deletes});}
    catch(error){if(['unknown','commit_acknowledged'].includes(error.outcome)||execution.uncertain){result.grid.uncertain=true;error.message+=' Reconcile before any retry; staged edits will not be replayed.';}throw error;}
    // Once acknowledged, these edits must never be resubmitted, even if reload fails.
    result.grid.revision=saved.revision;DataGridView.cancelDraft(result);
    try{execution.check();await replace(await submit(context.capabilities.page?'page':'reload',{direction:'refresh',limit:context.page.limit}));
      for(let i=0;i<result.rows.length;i++)if(selection.includes(JSON.stringify(keyColumns.map(c=>String(result.rows[i][c.source])))))draft.select(i,{ctrlKey:true});
      await replace(result);
    }
    catch(error){throw Error('Saved successfully, but reload failed. Do not repeat the save: '+error.message);}
    return true;
  }
  if(change.action==='page'){
    const kind=context.capabilities.page?'page':'reload';
    if(kind==='reload'&&(!context.capabilities.rowLimit||!['first','refresh'].includes(change.direction)))throw Error(context.capabilities.rowLimitReason||'Server paging is unavailable for this query.');
    await replace(await submit(kind,{direction:change.direction,limit:change.limit}));
    return true;
  }
  if(change.action==='export'){
    const exported=await submit('export',change);execution.check();
    try{await download(exported);}catch(error){await api('/grids/exports/'+exported.exportId,'DELETE').catch(()=>{});throw error;}
    return true;
  }
  if(change.action==='review_rows'){
    const plan=await submit('prepare',DataGridView.draft(result).payload());
    gridDialog('Prepared row changes',({dialog,body,footer})=>{const pre=document.createElement('pre');pre.textContent=JSON.stringify(plan,null,2);body.append(pre);action(footer,'Close',()=>dialog.close());});
    return true;
  }
  return false;
}
function confirmReconcile(){return new Promise(resolve=>{
  let approved=false;const d=gridDialog('Reconcile save outcome',({dialog,body,footer})=>{
    body.textContent='Reload the actual database state without replaying any writes. This discards staged edits, whose earlier commit may have succeeded. Inspect the reloaded values before making further changes.';
    action(footer,'Stay',()=>dialog.close());action(footer,'Reload actual data',()=>{approved=true;dialog.close();});
  });d.addEventListener('close',()=>resolve(approved));
});}
export async function releaseGrid(api,result){DataGridView.cancelDraft(result);const id=result?.grid?.id;if(id)await api('/grids/'+id,'DELETE');}
function confirmDeletes(plan){return new Promise(resolve=>{
  let approved=false;const dialog=gridDialog('Save row deletions?',({dialog,body,footer})=>{
    const summary=document.createElement('p');summary.textContent=plan.connectionName+' · '+plan.database+' · '+plan.target+' — '+plan.statements.filter(s=>s.operation==='delete').length+' deletions, '+plan.statements.filter(s=>s.operation!=='delete').length+' other row changes. '+plan.warning;
    const details=document.createElement('details'),label=document.createElement('summary'),sql=document.createElement('pre');label.textContent='Inspect prepared SQL and parameters';sql.textContent=JSON.stringify(plan.statements,null,2);details.append(label,sql);body.append(summary,details);
    action(footer,'Cancel',()=>dialog.close());action(footer,'Save',()=>{approved=true;dialog.close();});
  });dialog.addEventListener('close',()=>resolve(approved));
});}
function downloadExport(result){
  // The browser streams the authenticated download; do not duplicate a 64 MiB file in a Blob.
  const link=document.createElement('a');link.href='/api/dba/grids/exports/'+encodeURIComponent(result.exportId);link.download='result.'+result.format;document.body.append(link);link.click();link.remove();
}
