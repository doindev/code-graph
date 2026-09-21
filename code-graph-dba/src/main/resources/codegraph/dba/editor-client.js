// Collaboration presence is independent of SQL approval mode, including YOLO.
// Only opaque workspace identity is stored in sessionStorage; consent is server-owned.
export class EditorClient {
  constructor({api,flush,notify}){
    this.api=api;this.flush=flush;this.notify=notify;this.documentId=crypto.randomUUID();this.workspaceId=crypto.randomUUID();this.ready=false;this.busy=false;
    try{this.workspaceId=sessionStorage.getItem('dba-editor-workspace')||this.workspaceId;}catch{}
    const fragment=new URLSearchParams(location.hash.slice(1));this.ticket=fragment.get('editor-handoff')||'';
    if(this.ticket){this.workspaceId=crypto.randomUUID();history.replaceState(null,'',location.pathname+location.search);}
    this.badge=document.createElement('button');this.badge.id='editor-connected';this.badge.hidden=true;this.badge.title='Disconnect this agent from this DBA tab';this.badge.setAttribute('aria-label','Disconnect editor agent');this.badge.onclick=()=>this.disconnect();
    document.getElementById('workspace-settings').before(this.badge);
    this.visibility=()=>this.heartbeat();window.addEventListener('focus',this.visibility);document.addEventListener('visibilitychange',this.visibility);
    window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
  }
  headers(){return this.ready?{'X-Dba-Workspace':this.workspaceId,'X-Dba-Document':this.documentId}:{};}
  eventsUrl(){return '/api/dba/editor/events?'+new URLSearchParams({workspaceId:this.workspaceId,documentId:this.documentId});}
  async register(){
    const result=await this.api('/editor/register','POST',{workspaceId:this.workspaceId,documentId:this.documentId});
    this.workspaceId=result.workspaceId;try{sessionStorage.setItem('dba-editor-workspace',this.workspaceId);}catch{}
    this.ready=true;
  }
  async start(){
    await this.heartbeat();
    if(this.ticket){const ticket=this.ticket;this.ticket='';try{await this.accept({id:''},ticket);}catch(e){this.notify(e.message);}}
    this.timer=setInterval(()=>this.heartbeat(),10000);
  }
  async heartbeat(){if(!this.ready||this.left)return;try{this.deliver(await this.api('/editor/presence','POST',{}));}catch{/* Session/ownership failures stop edits on the server. */}}
  deliver(data){
    if(this.left)return;
    const paired=data.pairing?.paired;this.badge.hidden=!paired;this.badge.textContent=paired?'Agent connected · Disconnect':'';
    const requests=data.requests??[];const first=requests[0];
    if(this.dialog&&!requests.some(r=>r.id===this.offered?.id)){this.dismiss();}
    if(this.busy||!first||this.dialog)return;
    if(first.autoAccept){this.busy=true;this.accept(first).catch(e=>this.notify(e.message)).finally(()=>{this.busy=false;});return;}
    this.offer(first);
  }
  offer(request){
    this.offered=request;const dialog=document.createElement('dialog');dialog.className='editor-access-dialog';dialog.setAttribute('aria-labelledby','editor-access-title');
    const title=document.createElement('h2');title.id='editor-access-title';title.textContent='Use this /dba instance?';
    const agent=document.createElement('p');agent.textContent=request.agentName+' requests Script collaboration.';
    const purpose=document.createElement('p');purpose.textContent=request.purpose;
    const scope=document.createElement('p');scope.textContent=request.scopeNotice;
    const status=document.createElement('p');status.setAttribute('role','status');
    const update=()=>{status.textContent=Math.max(0,Math.ceil((request.expiresAt-Date.now())/1000))+' seconds remaining · The first accepting tab is selected.';};
    update();this.countdown=setInterval(update,1000);
    const actions=document.createElement('div');actions.className='actions';
    const deny=document.createElement('button');deny.textContent='Deny';deny.type='button';deny.onclick=()=>this.reject(request);
    const accept=document.createElement('button');accept.textContent='Use this /dba instance';accept.type='button';accept.onclick=async()=>{
      accept.disabled=true;deny.disabled=true;this.busy=true;
      try{await this.accept(request);this.dismiss();}catch(e){status.textContent=e.message;accept.disabled=false;deny.disabled=false;}finally{this.busy=false;}
    };
    actions.append(deny,accept);dialog.append(title,agent,purpose,scope,status,actions);document.body.append(dialog);this.dialog=dialog;
    dialog.addEventListener('cancel',event=>{event.preventDefault();void this.reject(request);});dialog.showModal();deny.focus();this.originalTitle??=document.title;document.title='DBA · Agent requests this tab';
  }
  async accept(request,ticket=''){
    await this.flush();
    const result=await this.api('/editor/claim','POST',{approvalId:request.id,ticket});
    if(result.state==='paired'){this.badge.hidden=false;this.badge.textContent='Agent connected · Disconnect';try{window.focus();}catch{}this.notify('Agent connected to this DBA tab. SQL is not executed automatically.');}
  }
  async reject(request){try{await this.api('/editor/reject','POST',{approvalId:request.id});this.dismiss();}catch(e){this.notify(e.message);await this.heartbeat();}}
  dismiss(){clearInterval(this.countdown);this.dialog?.close();this.dialog?.remove();this.dialog=null;this.offered=null;if(this.originalTitle!==undefined){document.title=this.originalTitle;this.originalTitle=undefined;}}
  async disconnect(){try{await this.api('/editor/pair','DELETE',{});await this.heartbeat();this.notify('Editor agent disconnected.');}catch(e){this.notify(e.message);}}
  leave(csrf,workspace){this.left=true;clearInterval(this.timer);clearInterval(this.countdown);window.removeEventListener('focus',this.visibility);document.removeEventListener('visibilitychange',this.visibility);let body=JSON.stringify(workspace?{workspace}:{});if(new TextEncoder().encode(body).length>60*1024)body='{}';return fetch('/api/dba/editor/leave',{method:'POST',credentials:'same-origin',keepalive:true,headers:{'Content-Type':'application/json','X-Dba-CSRF':csrf,...this.headers()},body}).catch(()=>{});}
}
