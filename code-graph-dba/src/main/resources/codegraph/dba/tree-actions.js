/** Right-click uses the existing action button without toggling an already open menu. */
export function bindTreeContextMenu(header,button){
  header.addEventListener('contextmenu',event=>{
    if(button.disabled||!header.contains(button))return;
    event.preventDefault();event.stopPropagation();
    if(button.getAttribute('aria-expanded')!=='true')button.click();
  });
}

/** Confirmation/rename dialog shared by catalog objects and saved connection labels. */
export class TreeActions {
  constructor({api,wait,notice}){
    Object.assign(this,{api,wait,notice});this.busy=false;
    this.dialog=document.createElement('dialog');this.dialog.id='tree-object-dialog';this.dialog.className='tree-object-dialog';
    this.dialog.setAttribute('aria-labelledby','tree-object-title');
    this.dialog.innerHTML='<div class="editor-heading"><h2 id="tree-object-title"></h2><button type="button" id="tree-object-close" aria-label="Close object action">×</button></div><div class="tree-object-body"><p id="tree-object-context"></p><p id="tree-object-warning"></p><label id="tree-object-name-label">New name<input id="tree-object-name" autocomplete="off" maxlength="256"></label><p id="tree-object-error" role="alert" hidden></p></div><div class="actions"><button type="button" id="tree-object-cancel">Cancel</button><button type="button" id="tree-object-confirm">Confirm</button></div>';
    document.body.append(this.dialog);const close=()=>{if(!this.busy)this.dialog.close();};
    const details=this.dialog.querySelector('.tree-object-body');
    this.sql=document.createElement('textarea');this.sql.readOnly=true;this.sql.className='designer-review-sql';this.sql.setAttribute('aria-label','Object action SQL');details.append(this.sql);
    this.ackLabel=document.createElement('label');this.ack=document.createElement('input');this.ack.type='checkbox';this.ackLabel.append(this.ack,document.createTextNode('I understand that this operation can permanently remove data or objects.'));details.append(this.ackLabel);
    this.ack.onchange=()=>this.$('tree-object-confirm').disabled=this.busy||(!this.ackLabel.hidden&&!this.ack.checked);
    this.$('tree-object-close').onclick=close;this.$('tree-object-cancel').onclick=close;
    this.dialog.addEventListener('cancel',e=>{e.preventDefault();close();});
    this.$('tree-object-confirm').onclick=()=>this.submit();
    this.$('tree-object-name').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();this.submit();}});
  }
  $(id){return this.dialog.querySelector('#'+id);}
  open({action,name,context,warning,execute,refresh,sql=''}){
    if(this.busy||this.dialog.open){this.notice('Finish or cancel the current object action first.');return;}
    this.operation={action,execute,refresh};this.$('tree-object-title').textContent=action==='delete'?'Delete object?':action==='truncate'?'Truncate table?':'Rename';
    this.$('tree-object-context').textContent=context;this.$('tree-object-warning').textContent=warning;
    this.$('tree-object-name-label').hidden=action!=='rename';this.$('tree-object-name').value=name;
    this.$('tree-object-error').hidden=true;this.$('tree-object-confirm').textContent=action==='delete'?'Delete':action==='truncate'?'Truncate':'Rename';
    this.sql.value=sql;this.sql.hidden=!sql;this.ack.checked=false;this.ackLabel.hidden=!['delete','truncate'].includes(action);this.$('tree-object-confirm').disabled=!this.ackLabel.hidden;
    this.$('tree-object-confirm').classList.toggle('destructive',['delete','truncate'].includes(action));this.dialog.showModal();
    if(action==='rename'){this.$('tree-object-name').focus();this.$('tree-object-name').select();}else this.$('tree-object-cancel').focus();
  }
  async submit(){
    if(this.busy||!this.dialog.open||(!this.ackLabel.hidden&&!this.ack.checked))return;const operation=this.operation;this.busy=true;
    this.dialog.setAttribute('aria-busy','true');for(const el of this.dialog.querySelectorAll('button,input'))el.disabled=true;this.$('tree-object-error').hidden=true;
    try{await operation.execute(this.$('tree-object-name').value);this.dialog.close();try{const refreshed=await operation.refresh();this.notice(refreshed===false?'Change succeeded, but the parent could not be refreshed. Refresh it manually before further changes.':operation.action==='delete'?'Object deleted; parent refreshed.':operation.action==='truncate'?'Table truncated; parent refreshed.':'Name updated; parent refreshed.');}catch(error){this.notice('Change succeeded, but refresh failed: '+error.message);}}
    catch(error){this.$('tree-object-error').textContent=error.message;this.$('tree-object-error').hidden=false;}
    finally{this.busy=false;this.dialog.removeAttribute('aria-busy');for(const el of this.dialog.querySelectorAll('button,input'))el.disabled=false;this.$('tree-object-confirm').disabled=!this.ackLabel.hidden&&!this.ack.checked;}
  }
  object(action,profile,selection,plan,refresh,before=async()=>{}){
    const execute=async newName=>{await before();return this.wait(await this.api('/metadata/object/action','POST',{connectionId:profile.id,...selection,action,newName,fingerprint:plan.fingerprint,confirmed:action!=='rename'}));};
    if(action==='refresh')return (async()=>{if(this.busy)throw new Error('An object action is already running.');this.busy=true;this.notice('Refreshing materialized view '+plan.target+'…');try{await execute('');await refresh();this.notice('Materialized view refreshed.');}finally{this.busy=false;}})();
    this.open({action,name:plan.name,context:profile.name+' · '+(plan.database||'')+' · '+plan.type+' '+plan.target,
      warning:action==='delete'?'This will delete the selected database object, including its data where applicable. It may commit immediately and cannot be undone here. No CASCADE or FORCE will be added. Database-owned dependencies may also be removed.':action==='truncate'?'This removes ALL rows from the table and its partitions, where applicable, while retaining its structure. The database may reset identity values or commit immediately. There is no undo here. No CASCADE is added.':"Renaming may break SQL scripts or dependencies that reference the old name. "+plan.reason,
      sql:action==='delete'?plan.deleteSql:action==='truncate'?plan.truncateSql:'',execute,refresh});
  }
  connection(profile,refresh){this.open({action:'rename',name:profile.name,context:'Connection: '+profile.name,
    warning:'Only the saved connection name changes—not the database itself. Existing MCP name-bound grants must be reviewed and reissued afterward.',
    execute:name=>this.api('/connections/'+profile.id+'/rename','PUT',{name,expectedName:profile.name}),refresh});}
}

export async function copyObjectName(name){
  if(navigator.clipboard?.writeText){await navigator.clipboard.writeText(name);return;}
  throw new Error('Clipboard access is unavailable. Use a secure localhost browser session.');
}
