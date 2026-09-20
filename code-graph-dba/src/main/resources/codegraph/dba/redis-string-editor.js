import {lucide} from './tree-icons.js';

const MAX_BYTES=8192;
const node=(tag,text)=>{const n=document.createElement(tag);if(text)n.textContent=text;return n;};
export function redisBytes(value,mode='text'){
  if(mode==='text'){
    if(!value.isWellFormed())throw new Error('Text contains an unmatched Unicode surrogate. Use valid text or base64.');
    const bytes=new TextEncoder().encode(value);if(bytes.length>MAX_BYTES)throw new Error('Value exceeds the 8 KiB editor limit.');return bytes;
  }
  if(value.length>10924||!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value))throw new Error('Enter canonical base64 without whitespace.');
  const raw=atob(value);if(btoa(raw)!==value||raw.length>MAX_BYTES)throw new Error('Invalid base64 or value exceeds 8 KiB.');
  return Uint8Array.from(raw,c=>c.charCodeAt(0));
}
export const redisBase64=bytes=>btoa(String.fromCharCode(...bytes));
export function redisStringSnapshot(result){
  const entries=result?.entries;
  if(result?.kind!=='pipeline'||result.truncated||entries?.length!==4||entries.some((e,i)=>e.index!==i||e.state!=='acknowledged'||e.valueOmitted))throw new Error('Incomplete key read; no editable snapshot was created.');
  const [type,length,value,ttl]=entries.map(e=>e.value);
  if(type!=='string')throw new Error(type==='none'?'Key does not exist. This editor only updates existing strings.':'Only existing Redis strings can be edited here.');
  if(!Number.isSafeInteger(length)||length<0||length>MAX_BYTES||value?.truncated||typeof value?.base64!=='string')throw new Error('Only complete string values of at most 8 KiB can be edited.');
  const bytes=redisBytes(value.base64,'base64');
  if(bytes.length!==length||!Number.isSafeInteger(ttl)||ttl < -1)throw new Error('Key changed or expired during loading. Reload it before editing.');
  return{base64:redisBase64(bytes),ttl};
}

export function redisHashSnapshot(result){
  const value=result?.entries?.[0];
  if(result?.kind!=='values'||result.truncated||result.entries?.length!==1||value?.truncated||typeof value?.base64!=='string')throw new Error('Load an existing, complete hash field of at most 8 KiB; missing fields and truncated values cannot be edited.');
  return{base64:redisBase64(redisBytes(value.base64,'base64'))};
}

/** Small, bounded, memory-only draft. Services own review, jobs, cancellation and accounting. */
export class RedisStringEditor{
  constructor({run,readOnly,changed,close,key='',field}){
    Object.assign(this,{run,readOnly,changed,close});this.snapshot=null;this.busy=false;this.uncertain=false;this.disposed=false;
    this.isHash=field!==undefined;const noun=this.isHash?'hash field':'string';
    this.root=node('section');this.root.className='redis-string-editor';this.root.setAttribute('aria-label','Redis '+noun+' value editor');
    const header=node('div');header.className='redis-string-actions';header.append(node('strong',(this.isHash?'Hash field':'String value')+' · 8 KiB maximum'));
    this.key=this.input(header,'Key',key);this.key.maxLength=10924;
    this.keyMode=this.select(header,'Key encoding',['text','base64']);
    if(key&&typeof key==='object'){this.key.value=key.base64??'';this.keyMode.value='base64';}
    if(this.isHash){
      this.field=this.input(header,'Field',field);this.field.maxLength=10924;
      this.fieldMode=this.select(header,'Field encoding',['text','base64']);
      if(field&&typeof field==='object'){this.field.value=field.base64??'';this.fieldMode.value='base64';}
    }
    this.loadButton=this.button(header,'Load '+noun,'refresh-cw',()=>this.load());
    this.closeButton=this.button(header,'Close value editor','x',()=>{if(this.canClose())this.close();});
    this.root.append(header);
    this.notice=node('p',this.isHash?'Existing fields only · exact-value WATCH conflicts · key TTL preserved · Redis 7.4+ required for Save. Expiring fields are rejected because HSET clears field expiry. No automatic retries.':'Exact-value conflict checks · existing TTL is preserved · no automatic retries. Binary/string type does not identify application semantics (for example bitmaps or HyperLogLogs).');
    this.root.append(this.notice);
    const format=node('div');format.className='redis-string-actions';this.mode=this.select(format,'Value encoding',['text','base64']);this.mode.onchange=()=>this.changeMode();this.root.append(format);
    this.value=node('textarea');this.value.setAttribute('aria-label','Redis '+noun+' value');this.value.spellcheck=false;this.value.wrap='off';this.value.maxLength=10924;this.value.oninput=()=>{this.sync();this.changed();};this.root.append(this.value);
    const footer=node('div');footer.className='redis-string-actions';this.saveButton=this.button(footer,'Save '+noun,'save',()=>this.save());this.revertButton=this.button(footer,'Revert '+noun+' draft','undo-2',()=>this.revert());
    this.status=node('span','Load an existing key. Draft values are never saved in workspace state.');this.status.setAttribute('role','status');footer.append(this.status);this.root.append(footer);
    this.key.oninput=this.keyMode.onchange=()=>{this.sync();};this.activeMode='text';this.sync();
  }
  input(parent,label,value){const wrap=node('label',label),input=node('input');input.value=typeof value==='string'?value:'';input.setAttribute('aria-label',label);input.autocomplete='off';input.spellcheck=false;wrap.append(input);parent.append(wrap);return input;}
  select(parent,label,values){const select=node('select');select.setAttribute('aria-label',label);for(const v of values){const o=node('option',v);o.value=v;select.append(o);}parent.append(select);return select;}
  button(parent,label,icon,action){const b=node('button',label);b.type='button';b.title=label;b.setAttribute('aria-label',label);b.prepend(lucide(icon));b.onclick=action;parent.append(b);return b;}
  get dirty(){if(!this.snapshot)return false;try{return redisBase64(redisBytes(this.value.value,this.activeMode))!==this.snapshot.base64;}catch{return true;}}
  canClose(){return !(this.dirty||this.uncertain)||window.confirm(this.uncertain?'Save outcome is uncertain. Close this draft? Reconcile the key before another write.':'Discard this unsaved Redis value draft?');}
  sync(){
    const blocked=this.busy||this.disposed||!!this.unavailable;let valid=true;try{redisBytes(this.value.value,this.activeMode);}catch{valid=false;}
    this.key.disabled=this.keyMode.disabled=blocked||!!this.snapshot;
    if(this.field)this.field.disabled=this.fieldMode.disabled=blocked||!!this.snapshot;
    this.value.readOnly=blocked||!this.snapshot||this.readOnly();this.mode.disabled=blocked||!this.snapshot;
    this.loadButton.disabled=blocked;this.closeButton.disabled=this.busy;
    this.saveButton.disabled=blocked||this.uncertain||!this.dirty||!valid||this.readOnly();this.revertButton.disabled=blocked||!this.dirty;
    this.root.setAttribute('aria-busy',String(this.busy));this.value.setAttribute('aria-invalid',String(!valid));
    this.saveButton.title=this.readOnly()?'This profile does not allow writes':this.uncertain?'Reload and reconcile the uncertain outcome before another Save':'Review exact old/new bytes before saving; preserve TTL';
  }
  setValue(base64){
    const bytes=redisBytes(base64,'base64');let text;
    try{text=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);if(text.includes('\r'))text=undefined;}catch{}
    this.activeMode=text===undefined?'base64':'text';this.mode.value=this.activeMode;this.value.value=text??base64;
  }
  changeMode(){
    try{const bytes=redisBytes(this.value.value,this.activeMode);let text=this.mode.value==='base64'?redisBase64(bytes):new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);
      if(this.mode.value==='text'&&text.includes('\r'))throw new Error('Use base64 to preserve carriage returns exactly.');this.value.value=text;this.activeMode=this.mode.value;
    }catch(e){this.mode.value=this.activeMode;this.status.textContent=e.message;}this.sync();
  }
  revert(){if(this.snapshot)this.setValue(this.snapshot.base64);this.sync();this.changed();}
  async load(){
    if(this.busy||this.disposed||this.unavailable||!this.canClose())return;
    let key,field;try{key={base64:redisBase64(redisBytes(this.key.value,this.keyMode.value))};if(!key.base64)throw new Error('Choose a nonempty key.');if(this.isHash)field={base64:redisBase64(redisBytes(this.field.value,this.fieldMode.value))};}catch(e){this.status.textContent=e.message;return;}
    this.busy=true;this.sync();
    try{
      const reply=await this.run(this.isHash?['HGET',key,field]:{pipeline:[['TYPE',key],['STRLEN',key],['GETRANGE',key,'0','8191'],['PTTL',key]]});
      if(this.disposed)return;if(!reply?.ok)throw new Error(reply?.error??'Key load did not complete.');
      if(!reply.targetRevision)throw new Error('Missing target revision; no editable snapshot was created.');
      const snapshot=this.isHash?redisHashSnapshot(reply.result):redisStringSnapshot(reply.result);this.snapshot={...snapshot,key,...(this.isHash?{field}:{}),targetRevision:reply.targetRevision};this.uncertain=false;this.setValue(snapshot.base64);
      this.status.textContent='Loaded '+redisBytes(snapshot.base64,'base64').length+' bytes · '+(this.isHash?'Save verifies persistent field and preserves key TTL':snapshot.ttl===-1?'no expiry':'TTL at read: '+snapshot.ttl+' ms')+' · Save rechecks exact bytes. Reads are not a frozen snapshot.';
    }catch(e){if(!this.disposed)this.status.textContent=e.message;}
    finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  async save(){
    if(this.busy||this.disposed||this.unavailable||this.uncertain||!this.dirty||this.readOnly())return;
    let next;try{next=redisBase64(redisBytes(this.value.value,this.activeMode));}catch(e){this.status.textContent=e.message;return;}
    const snapshot=this.snapshot;this.busy=true;this.sync();
    try{
      const command=this.isHash?['HSET',snapshot.key,snapshot.field,{base64:next}]:['SET',snapshot.key,{base64:next},'XX','KEEPTTL'];
      const reply=await this.run({transaction:[command],watch:[{key:snapshot.key,...(this.isHash?{field:snapshot.field}:{}),expected:{base64:snapshot.base64}}]},snapshot.targetRevision);
      if(this.disposed)return;
      // A job can fail/cancel after EXEC. Require the exact receipt, not just terminal job status.
      const result=reply?.result,entry=result?.entries?.[0];
      if(result?.outcome==='acknowledged'&&entry?.state==='acknowledged'&&(this.isHash?entry.value===0:entry.value==='OK')){
        this.snapshot={...snapshot,base64:next};this.uncertain=false;this.status.textContent='Saved · '+(this.isHash?'key TTL':'TTL')+' preserved. Reload to observe subsequent changes.';
      }else{
        this.uncertain=!!reply?.uncertain||(this.isHash&&result?.outcome==='acknowledged');
        this.status.textContent=this.uncertain?'Save outcome uncertain. Draft retained; reload and reconcile before another Save.':reply?.error??'Value update was not confirmed. Reload before retrying.';
      }
    }catch(e){this.uncertain=true;this.status.textContent='Save outcome uncertain. Reload and reconcile before retrying. '+e.message;}
    finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  invalidate(message){this.unavailable=message;this.status.textContent=message;this.sync();}
  dispose(){this.disposed=true;this.snapshot=null;this.value.value='';this.root.remove();}
}
