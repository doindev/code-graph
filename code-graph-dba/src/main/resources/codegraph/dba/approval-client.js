// Presence and review capabilities are memory-only and unique to this browser document.
let current=null;
export function approvalHeaders(path=''){
  if(!current)return {};
  const headers={'X-Dba-Tab':current.tabId};
  const match=path.match(/\/approvals\/([^/]+)/);
  const lease=current.leases.get(match?.[1]??current.active);
  if(lease&&(/\/approvals\//.test(path)||/\/(setup|drivers)\//.test(path)))headers['X-Dba-Review']=lease.lease;
  return headers;
}
export class ApprovalClient {
  constructor({api,changed,offer,unavailable,reviewOnly=false}){
    this.reviewOnly=reviewOnly;this.api=api;this.changed=changed;this.offer=offer;this.unavailable=unavailable;
    this.tabId=crypto.randomUUID();this.leases=new Map();this.active=null;this.closed=false;this.focused=false;current=this;
    this.visibility=()=>this.heartbeat(false);this.focus=()=>this.heartbeat(true);
    document.addEventListener('visibilitychange',this.visibility);window.addEventListener('focus',this.focus);
    this.leave=()=>this.dispose();window.addEventListener('pagehide',this.leave);
    this.ready=this.start();
  }
  async start(){const settings=this.reviewOnly?{}:await this.api('/settings');if(settings.yolo){this.changed?.(0,{automatic:true});this.dispose();return;}await this.heartbeat(document.hasFocus());if(this.closed)return;this.connect();this.timer=setInterval(()=>{if(!document.hidden)this.heartbeat(false);},10000);this.renewTimer=setInterval(()=>this.renew(),10000);}
  connect(){
    if(this.closed||typeof EventSource==='undefined')return;
    this.events=new EventSource('/api/dba/approvals/events?tabId='+encodeURIComponent(this.tabId));
    this.events.onopen=()=>{this.connected=true;this.heartbeat(false);};
    this.events.onerror=()=>{this.connected=false;this.heartbeat(false);};
    this.events.addEventListener('approvals',event=>{try{this.deliver(JSON.parse(event.data));}catch{}});
  }
  deliver(data){this.changed?.(data.pendingCount);for(const id of data.requests??[])this.offer?.(id);}
  async heartbeat(focused){
    if(this.closed)return;
    try{const data=await this.api('/approvals/presence','POST',{tabId:this.tabId,visible:!document.hidden,focused:!!focused,polling:!this.connected});this.deliver(data);}
    catch(error){this.unavailable?.(error);}
  }
  async claim(id){await this.ready;if(this.closed)throw new Error('Approval session is closed');const lease=await this.api('/approvals/'+id+'/claim','POST',{});this.leases.set(id,lease);this.active=id;return lease;}
  async renew(){
    for(const [id,lease]of this.leases)try{await this.api('/approvals/'+id+'/renew','POST',{});}
    catch(error){this.leases.delete(id);if(this.active===id)this.active=null;this.unavailable?.(error);this.offer?.(id);}
  }
  async release(id){if(!this.leases.has(id))return;try{await this.api('/approvals/'+id+'/release','POST',{});}finally{this.leases.delete(id);if(this.active===id)this.active=null;}}
  dispose(){if(this.closed)return;for(const id of this.leases.keys())void this.release(id).catch(()=>{});this.closed=true;clearInterval(this.timer);clearInterval(this.renewTimer);this.events?.close();document.removeEventListener('visibilitychange',this.visibility);window.removeEventListener('focus',this.focus);window.removeEventListener('pagehide',this.leave);if(current===this)current=null;}
}
