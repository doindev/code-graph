import {lucide} from './tree-icons.js';
import {redisBytes,redisBase64} from './redis-string-editor.js';
const node=(tag,text)=>{const n=document.createElement(tag);if(text)n.textContent=text;return n;};

export function redisSetSnapshot(result){
  const entries=result?.entries;
  if(result?.kind!=='pipeline'||result.truncated||entries?.length!==4||entries.some((e,i)=>e.index!==i||e.state!=='acknowledged'||e.valueOmitted))throw new Error('Incomplete membership check; no draft was prepared.');
  const [type,present,count,ttl]=entries.map(e=>e.value);
  if(type!=='set')throw new Error('Choose an existing set. This editor never creates or replaces a set key.');
  if(typeof present!=='boolean'||!Number.isSafeInteger(count)||count<1||!Number.isSafeInteger(ttl)||ttl < -1)throw new Error('Set changed, expired, or returned incomplete metadata. Check membership again.');
  return{present,count,ttl};
}

/** One exact member, not a set inventory. Injected services own review/jobs/accounting. */
export class RedisSetEditor{
  constructor({run,readOnly,changed,close,key='',member=''}){
    Object.assign(this,{run,readOnly,changed,close});this.snapshot=null;this.busy=false;this.uncertain=false;this.disposed=false;
    this.root=node('section');this.root.className='redis-string-editor';this.root.setAttribute('aria-label','Redis set member editor');
    const header=node('div');header.className='redis-string-actions';header.append(node('strong','Set member · 8 KiB maximum'));
    const label=node('label','Key');this.key=node('input');this.key.setAttribute('aria-label','Key');this.key.autocomplete='off';this.key.spellcheck=false;this.key.maxLength=10924;label.append(this.key);header.append(label);
    this.keyMode=this.select(header,'Key encoding');this.key.value=typeof key==='string'?key:key?.base64??'';this.keyMode.value=typeof key==='string'?'text':'base64';
    this.loadButton=this.button(header,'Check set membership','refresh-cw',()=>this.load());
    this.resetButton=this.button(header,'Choose another member','plus',()=>this.reset());
    this.closeButton=this.button(header,'Close member editor','x',()=>{if(this.canClose())this.close();});this.root.append(header);
    this.root.append(node('p','One member in an existing set · current membership WATCH checks, not change history. Save preserves key TTL unless deletion removes the last member and its key. No set inventory, renaming or automatic retries.'));
    const format=node('div');format.className='redis-string-actions';this.mode=this.select(format,'Member encoding');this.mode.onchange=()=>this.changeMode();this.root.append(format);
    this.value=node('textarea');this.value.setAttribute('aria-label','Redis set member');this.value.spellcheck=false;this.value.wrap='off';this.value.maxLength=10924;
    this.value.value=typeof member==='string'?member:member?.base64??'';this.activeMode=typeof member==='string'?'text':'base64';this.mode.value=this.activeMode;this.value.oninput=()=>this.sync();this.root.append(this.value);
    const footer=node('div');footer.className='redis-string-actions';
    this.stageButton=this.button(footer,'Stage membership change','plus',()=>this.stage());
    this.saveButton=this.button(footer,'Save set member','save',()=>this.save());this.revertButton=this.button(footer,'Revert membership draft','undo-2',()=>this.revert());
    this.status=node('span','Enter a member (empty is valid), then check its membership. Nothing has been written.');this.status.setAttribute('role','status');footer.append(this.status);this.root.append(footer);this.sync();
  }
  select(parent,label){const s=node('select');s.setAttribute('aria-label',label);for(const value of ['text','base64']){const o=node('option',value);o.value=value;s.append(o);}parent.append(s);return s;}
  button(parent,label,icon,action){const b=node('button',label);b.type='button';b.title=label;b.setAttribute('aria-label',label);b.prepend(lucide(icon));b.onclick=action;parent.append(b);return b;}
  get dirty(){return !!this.snapshot&&this.desired!==this.snapshot.present;}
  canClose(){return !(this.dirty||this.uncertain)||window.confirm(this.uncertain?'Save outcome uncertain. Close this draft? Reconcile membership before another write.':'Discard this unsaved Redis membership change?');}
  sync(){
    const blocked=this.busy||this.disposed||!!this.unavailable;let valid=true;try{redisBytes(this.value.value,this.activeMode);}catch{valid=false;}
    this.key.disabled=this.keyMode.disabled=blocked||!!this.snapshot;
    this.value.readOnly=blocked||!!this.snapshot;this.mode.disabled=blocked||!!this.snapshot;
    this.value.setAttribute('aria-invalid',String(!valid));this.loadButton.disabled=blocked||(!this.snapshot&&!valid);
    this.resetButton.disabled=blocked||this.uncertain||!this.snapshot;this.closeButton.disabled=this.busy;
    this.stageButton.disabled=blocked||this.uncertain||!this.snapshot||this.readOnly();
    const deleting=this.snapshot?.present===true,label=deleting?'Mark member for deletion':'Stage member insertion';
    this.stageButton.replaceChildren(lucide(deleting?'trash-2':'plus'),document.createTextNode(label));this.stageButton.title=label;this.stageButton.setAttribute('aria-label',label);this.stageButton.setAttribute('aria-pressed',String(this.dirty));
    this.saveButton.disabled=blocked||this.uncertain||!this.dirty||this.readOnly();this.revertButton.disabled=blocked||this.uncertain||!this.dirty;
    this.root.classList.toggle('redis-pending-delete',this.dirty&&deleting);this.root.setAttribute('aria-busy',String(this.busy));
  }
  changeMode(){
    try{const bytes=redisBytes(this.value.value,this.activeMode);const text=this.mode.value==='base64'?redisBase64(bytes):new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);if(this.mode.value==='text'&&text.includes('\r'))throw Error('Use base64 to preserve carriage returns exactly.');this.value.value=text;this.activeMode=this.mode.value;}
    catch(e){this.mode.value=this.activeMode;this.status.textContent=e.message;}this.sync();
  }
  stage(){if(this.busy||this.disposed||this.unavailable||this.uncertain||!this.snapshot||this.readOnly())return;this.desired=this.dirty?this.snapshot.present:!this.snapshot.present;this.status.textContent=this.dirty?(this.desired?'Insertion staged; Save opens review.':'Deletion staged; deleting the last member removes its set key and TTL. Save opens destructive review.'):'Change unstaged; nothing was written.';this.sync();this.changed();}
  revert(){if(this.busy||this.disposed||this.uncertain||!this.snapshot)return;this.desired=this.snapshot.present;this.status.textContent='Draft reverted; nothing was written.';this.sync();this.changed();}
  reset(){if(this.busy||this.disposed||this.unavailable||this.uncertain||!this.canClose())return;this.snapshot=null;this.status.textContent='Choose a member and check membership before staging another change.';this.sync();this.changed();this.value.focus();}
  async load(){
    if(this.busy||this.disposed||this.unavailable||!this.canClose())return;
    let key,member;try{key={base64:redisBase64(redisBytes(this.key.value,this.keyMode.value))};if(!key.base64)throw Error('Choose a nonempty key.');member={base64:redisBase64(redisBytes(this.value.value,this.activeMode))};}catch(e){this.status.textContent=e.message;return;}
    this.busy=true;this.sync();
    try{
      const reply=await this.run({pipeline:[['TYPE',key],['SISMEMBER',key,member],['SCARD',key],['PTTL',key]]});
      if(this.disposed)return;if(!reply?.ok)throw Error(reply?.error??'Membership check did not complete.');if(!reply.targetRevision)throw Error('Missing target revision; no membership draft was prepared.');
      const snapshot=redisSetSnapshot(reply.result);this.snapshot={...snapshot,key,member,targetRevision:reply.targetRevision};this.desired=snapshot.present;this.uncertain=false;
      this.status.textContent=(snapshot.present?'Member present':'Member absent')+' · '+snapshot.count+' member(s) at read · '+(snapshot.ttl===-1?'no expiry':'TTL '+snapshot.ttl+' ms')+'. Stage a change explicitly. Reads are not a frozen snapshot.';
    }catch(e){if(!this.disposed)this.status.textContent=e.message;}finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  async save(){
    if(this.busy||this.disposed||this.unavailable||this.uncertain||!this.dirty||this.readOnly())return;
    const snapshot=this.snapshot,desired=this.desired;this.busy=true;this.sync();
    try{
      const reply=await this.run({transaction:[[desired?'SADD':'SREM',snapshot.key,snapshot.member]],watch:[{key:snapshot.key,member:snapshot.member,expected:snapshot.present}]},snapshot.targetRevision);
      if(this.disposed)return;const result=reply?.result,entry=result?.entries?.[0];
      if(result?.outcome==='acknowledged'&&result.entries.length===1&&entry?.index===0&&entry.state==='acknowledged'&&entry.value===1){
        this.snapshot=desired?{...snapshot,present:true}:null;this.desired=desired;this.uncertain=false;
        this.status.textContent=desired?'Member added · key TTL preserved. Check membership to observe subsequent changes.':'Member deleted · if it was the last member, Redis removed the set key and TTL. Nothing was recreated.';
      }else{this.uncertain=!!reply?.uncertain||result?.outcome==='acknowledged';this.status.textContent=this.uncertain?'Save outcome uncertain. Draft retained; check membership and reconcile before another Save.':reply?.error??'Membership change was not confirmed; draft retained.';}
    }catch(e){this.uncertain=true;this.status.textContent='Save outcome uncertain. Check membership and reconcile before retrying. '+e.message;}
    finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  invalidate(message){this.unavailable=message;this.status.textContent=message;this.sync();}
  dispose(){this.disposed=true;this.snapshot=null;this.value.value='';this.root.remove();}
}
