import {lucide} from './tree-icons.js';

const MAX_BYTES=8192;
const MAX_EXPIRY_SECONDS=2147483647;
export function redisStringExpiry(mode,seconds,creating=false){
  if(mode==='preserve'&&!creating)return ['KEEPTTL'];
  if(mode==='none')return [];
  if(mode==='duration'&&typeof seconds==='string'&&/^[0-9]{1,10}$/.test(seconds)&&Number(seconds)>=1&&Number(seconds)<=MAX_EXPIRY_SECONDS)return ['EX',String(Number(seconds))];
  throw new Error(mode==='duration'?'Enter expiry as whole seconds from 1 to 2147483647.':'Choose an explicit expiry option; a new string cannot preserve an existing expiry.');
}
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
export function redisRenameKey(original,value,mode){
  const base64=redisBase64(redisBytes(value,mode));
  if(!base64)throw new Error('Enter a nonempty destination key.');
  if(base64===original?.base64)throw new Error('The destination must differ from the loaded key.');
  return {base64};
}
export function redisListIndex(value){if(typeof value!=='string'||!/^[0-9]{1,4}$/.test(value))throw new Error('Enter a zero-based list index from 0 to 9999.');return Number(value);}
export function redisListSnapshot(result,index){
  const entries=result?.entries;
  if(result?.kind!=='pipeline'||result.truncated||entries?.length!==4||entries.some((e,i)=>e.index!==i||e.state!=='acknowledged'||e.valueOmitted))throw new Error('Incomplete list read; no editable snapshot was created.');
  const [type,length,value,ttl]=entries.map(e=>e.value);
  if(type!=='list')throw new Error('Choose an existing list; this editor never creates or replaces a key.');
  if(!Number.isInteger(index)||index<0||!Number.isSafeInteger(length)||length<1||length>10000||index>=length)throw new Error('Choose an existing position in a list of at most 10,000 items.');
  if(value?.truncated||typeof value?.base64!=='string'||!Number.isSafeInteger(ttl)||ttl < -1)throw new Error('Missing, expired or incomplete list item; reload before editing.');
  return{base64:redisBase64(redisBytes(value.base64,'base64')),index,length,ttl};
}
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

export function redisNewHashSnapshot(result){
  const entries=result?.entries;
  if(result?.kind!=='pipeline'||result.truncated||entries?.length!==2||entries.some((e,i)=>e.index!==i||e.state!=='acknowledged'||e.valueOmitted))throw new Error('Incomplete field check; no new-field draft was created.');
  if(entries[0].value!=='hash')throw new Error('Choose an existing hash. This editor never creates or replaces a hash key.');
  if(entries[1].value!==false)throw new Error('Field already exists or its absence could not be verified. Use Load hash field to edit it.');
  return{base64:'',create:true};
}

export function redisNewStringSnapshot(result){
  const entries=result?.entries;
  if(result?.kind!=='pipeline'||result.truncated||entries?.length!==1||entries[0].index!==0||entries[0].state!=='acknowledged'||entries[0].valueOmitted)throw new Error('Incomplete key check; no new-string draft was created.');
  if(entries[0].value!=='none')throw new Error('Key already exists; New string never replaces an existing key. Load an existing string to edit it.');
  return{base64:'',ttl:-1,create:true};
}

/** Small, bounded, memory-only draft. Services own review, jobs, cancellation and accounting. */
export class RedisStringEditor{
  constructor({run,readOnly,changed,close,key='',field,listIndex}){
    Object.assign(this,{run,readOnly,changed,close});this.snapshot=null;this.busy=false;this.uncertain=false;this.disposed=false;
    this.isHash=field!==undefined;this.isList=listIndex!==undefined;if(this.isHash&&this.isList)throw new Error('Choose one Redis editor type.');const noun=this.isList?'list item':this.isHash?'hash field':'string';
    this.root=node('section');this.root.className='redis-string-editor';this.root.setAttribute('aria-label','Redis '+noun+' value editor');
    const header=node('div');header.className='redis-string-actions';header.append(node('strong',(this.isList?'List item':this.isHash?'Hash field':'String value')+' · 8 KiB maximum'));
    this.key=this.input(header,'Key',key);this.key.maxLength=10924;
    this.keyMode=this.select(header,'Key encoding',['text','base64']);
    if(key&&typeof key==='object'){this.key.value=key.base64??'';this.keyMode.value='base64';}
    if(this.isHash){
      this.field=this.input(header,'Field',field);this.field.maxLength=10924;
      this.fieldMode=this.select(header,'Field encoding',['text','base64']);
      if(field&&typeof field==='object'){this.field.value=field.base64??'';this.fieldMode.value='base64';}
    }
    this.loadButton=this.button(header,'Load '+noun,'refresh-cw',()=>this.load());
    if(this.isList){this.listIndex=this.input(header,'Index (zero-based)',String(listIndex));this.listIndex.maxLength=4;this.listIndex.inputMode='numeric';header.insertBefore(this.listIndex.parentElement,this.loadButton);}
    if(this.isList)this.resetButton=this.button(header,'Choose another position','refresh-cw',()=>this.resetList());
    if(!this.isList)this.newButton=this.button(header,this.isHash?'Prepare new hash field':'Prepare new string','plus',()=>this.load(true));
    this.closeButton=this.button(header,'Close value editor','x',()=>{if(this.canClose())this.close();});
    this.root.append(header);
    this.notice=node('p',this.isHash?'One field in an existing hash · exact-value/absence WATCH checks · Redis 7.4+ required for Save. Expiring fields are unsupported. Changes are staged until reviewed Save; deleting the last field removes its hash key. No automatic retries.':'Exact-value conflict checks · existing TTL is preserved · no automatic retries. Binary/string type does not identify application semantics (for example bitmaps or HyperLogLogs).');
    this.root.append(this.notice);
    if(!this.isHash&&!this.isList)this.notice.textContent='Load an existing string, or prepare a new absent key. Existing saves preserve expiry by default; new strings default to no expiry. An expiry change rewrites the string in one reviewed SET. Save checks exact bytes/absence, not the TTL observed at Load; no automatic retries. Binary strings may be application formats, not ordinary text.';
    if(this.isList)this.notice.textContent='One existing list position · exact length/value WATCH checks. Stage replacement, prepend/append, or deletion of the loaded first/last item. At most 10,000 items; no key creation, interior deletion or reordering. Positions are not stable identities. TTL is preserved unless the last item is deleted. No automatic retries.';
    const format=node('div');format.className='redis-string-actions';this.mode=this.select(format,'Value encoding',['text','base64']);this.mode.onchange=()=>this.changeMode();this.root.append(format);
    if(!this.isHash&&!this.isList){
      this.expiry=this.select(format,'Expiry',['preserve','none','duration']);
      ['Preserve current expiry','No expiry','Expire after'].forEach((label,index)=>{this.expiry.options[index].textContent=label;});
      this.expirySeconds=this.input(format,'Expiry seconds','');this.expirySeconds.maxLength=10;this.expirySeconds.inputMode='numeric';
      this.expiryHint=node('span');format.append(this.expiryHint);
      this.expiry.onchange=this.expirySeconds.oninput=()=>{this.sync();this.changed();};
      this.renameRow=node('div');this.renameRow.className='redis-string-actions';this.root.append(this.renameRow);
      this.renameKey=this.input(this.renameRow,'New key name','');this.renameKey.maxLength=10924;
      this.renameMode=this.select(this.renameRow,'New key encoding',['text','base64']);
      this.renameHint=node('span');this.renameRow.append(this.renameHint);
      this.renameKey.oninput=this.renameMode.onchange=()=>{this.sync();this.changed();};
    }
    this.value=node('textarea');this.value.setAttribute('aria-label','Redis '+noun+' value');this.value.spellcheck=false;this.value.wrap='off';this.value.maxLength=10924;this.value.oninput=()=>{this.sync();this.changed();};this.root.append(this.value);
    const footer=node('div');footer.className='redis-string-actions';this.saveButton=this.button(footer,'Save '+noun,'save',()=>this.save());this.revertButton=this.button(footer,'Revert '+noun+' draft','undo-2',()=>this.revert());
    if(!this.isList)this.deleteButton=this.button(footer,this.isHash?'Mark hash field for deletion':'Mark string key for deletion','trash-2',()=>this.toggleDelete());
    if(!this.isHash&&!this.isList)this.renameButton=this.button(footer,'Rename string key','pencil',()=>this.stageRename());
    if(this.isList){this.prependButton=this.button(footer,'Stage prepend item','list-plus',()=>this.stageListEnd('prepend'));this.appendButton=this.button(footer,'Stage append item','list-plus',()=>this.stageListEnd('append'));this.deleteButton=this.button(footer,'Mark list end item for deletion','trash-2',()=>this.toggleDelete());}
    this.status=node('span','Load an existing key. Draft values are never saved in workspace state.');this.status.setAttribute('role','status');footer.append(this.status);this.root.append(footer);
    this.key.oninput=this.keyMode.onchange=()=>{this.sync();};this.activeMode='text';this.sync();
  }
  input(parent,label,value){const wrap=node('label',label),input=node('input');input.value=typeof value==='string'?value:'';input.setAttribute('aria-label',label);input.autocomplete='off';input.spellcheck=false;wrap.append(input);parent.append(wrap);return input;}
  select(parent,label,values){const select=node('select');select.setAttribute('aria-label',label);for(const v of values){const o=node('option',v);o.value=v;select.append(o);}parent.append(select);return select;}
  button(parent,label,icon,action){const b=node('button',label);b.type='button';b.title=label;b.setAttribute('aria-label',label);b.prepend(lucide(icon));b.onclick=action;parent.append(b);return b;}
  get dirty(){if(!this.snapshot)return false;if(this.snapshot.create||this.pendingDelete||this.pendingRename||this.listAction||(this.expiry&&this.expiry.value!=='preserve'))return true;try{return redisBase64(redisBytes(this.value.value,this.activeMode))!==this.snapshot.base64;}catch{return true;}}
  resetRename(){this.pendingRename=false;if(this.renameKey){this.renameKey.value='';this.renameMode.value='text';}}
  renameDestination(){return redisRenameKey(this.snapshot?.key,this.renameKey.value,this.renameMode.value);}
  stageRename(){
    if(!this.renameButton||this.busy||this.disposed||this.unavailable||this.uncertain||!this.snapshot||this.snapshot.create||this.readOnly()||this.pendingRename)return;
    if(this.dirty&&!window.confirm('Discard the unsaved value, expiry or deletion draft and stage a rename instead? Nothing will be written.'))return;
    this.pendingDelete=false;this.setValue(this.snapshot.base64);this.resetExpiry();this.resetRename();this.pendingRename=true;
    this.status.textContent='Rename staged · Save reviews source bytes and destination absence. Existing keys are never overwritten; current TTL moves with the key. Cluster requires both keys in one hash slot. Revert cancels this draft.';
    this.sync();this.changed();this.renameKey.focus();
  }
  resetExpiry(){if(this.expiry){this.expiry.value=this.snapshot?.create?'none':'preserve';this.expirySeconds.value='';}}
  expiryOptions(){return this.expiry?redisStringExpiry(this.expiry.value,this.expirySeconds.value,!!this.snapshot?.create):[];}
  get atListEnd(){return this.isList&&!!this.snapshot&&(this.snapshot.index===0||this.snapshot.index===this.snapshot.length-1);}
  canClose(){return !(this.dirty||this.uncertain)||window.confirm(this.uncertain?'Save outcome is uncertain. Close this draft? Reconcile the key before another write.':'Discard this unsaved Redis value draft?');}
  sync(){
    const blocked=this.busy||this.disposed||!!this.unavailable;let valid=true;try{redisBytes(this.value.value,this.activeMode);}catch{valid=false;}
    this.key.disabled=this.keyMode.disabled=blocked||!!this.snapshot;
    if(this.field)this.field.disabled=this.fieldMode.disabled=blocked||!!this.snapshot;
    if(this.listIndex)this.listIndex.disabled=blocked||!!this.snapshot;
    this.value.readOnly=blocked||!this.snapshot||this.pendingDelete||this.pendingRename||this.readOnly();this.mode.disabled=blocked||!this.snapshot||this.pendingDelete||this.pendingRename;
    this.loadButton.disabled=blocked;this.closeButton.disabled=this.busy;
    if(this.resetButton)this.resetButton.disabled=blocked||this.uncertain||!this.snapshot;
    if(this.newButton)this.newButton.disabled=blocked||this.uncertain||this.readOnly();
    if(this.deleteButton){this.deleteButton.disabled=blocked||this.uncertain||!this.snapshot||!!this.snapshot.create||this.pendingRename||this.readOnly()||(this.isList&&(!this.atListEnd||!!this.listAction));this.deleteButton.setAttribute('aria-pressed',String(!!this.pendingDelete));if(this.isList)this.deleteButton.title=this.atListEnd?'Stage deletion of the loaded end item; last-item deletion removes its key/TTL':'Load the first or last item; interior deletion is unsupported';}
    for(const [button,action] of [[this.prependButton,'prepend'],[this.appendButton,'append']])if(button){button.disabled=blocked||this.uncertain||!this.snapshot||this.snapshot.length>=10000||this.readOnly();button.setAttribute('aria-pressed',String(this.listAction===action));button.title=this.snapshot?.length>=10000?'List is at the 10,000-item editor limit':'Stage one '+action+'; Save reviews it before execution';}
    let expiryError='';
    if(this.expiry){
      const locked=blocked||this.uncertain||!this.snapshot||this.pendingDelete||this.pendingRename||this.readOnly();
      this.expiry.disabled=locked;this.expiry.options[0].disabled=!!this.snapshot?.create;
      this.expirySeconds.parentElement.hidden=this.expiry.value!=='duration';this.expirySeconds.disabled=locked;
      if(this.snapshot&&!this.pendingDelete)try{this.expiryOptions();}catch(e){expiryError=e.message;}
      this.expirySeconds.setCustomValidity(expiryError);this.expirySeconds.setAttribute('aria-invalid',String(!!expiryError));
      this.expirySeconds.title=expiryError||'Whole seconds, 1–2147483647; starts when Redis executes the reviewed SET';
      this.expiryHint.textContent=expiryError||(this.pendingDelete?'Deletion removes the key and expiry.':this.expiry.value==='duration'?'Starts at execution, not at Load or review.':this.expiry.value==='none'?'Save removes any expiry.':'Keeps expiry current at execution, not the earlier TTL reading.');
    }
    let renameError='';
    if(this.renameButton){
      this.renameButton.disabled=blocked||this.uncertain||!this.snapshot||!!this.snapshot.create||this.readOnly();this.renameButton.setAttribute('aria-pressed',String(!!this.pendingRename));
      this.renameRow.hidden=!this.pendingRename;this.renameKey.disabled=this.renameMode.disabled=blocked||this.uncertain||this.readOnly();
      if(this.pendingRename)try{this.renameDestination();}catch(e){renameError=e.message;}
      this.renameKey.setCustomValidity(renameError);this.renameKey.setAttribute('aria-invalid',String(!!renameError));
      this.renameHint.textContent=renameError||'Destination must be absent; source and destination must share a Cluster hash slot.';
    }
    this.saveButton.disabled=blocked||this.uncertain||!this.dirty||((!valid||!!expiryError||!!renameError)&&!this.pendingDelete)||this.readOnly();this.revertButton.disabled=blocked||!this.dirty||this.uncertain;
    this.root.classList.toggle('redis-pending-delete',!!this.pendingDelete);
    this.root.setAttribute('aria-busy',String(this.busy));this.value.setAttribute('aria-invalid',String(!valid));
    this.saveButton.title=this.readOnly()?'This profile does not allow writes':this.uncertain?'Reload and reconcile the uncertain outcome before another Save':this.pendingDelete?'Review deletion; deleting the last item or whole string removes its key and TTL':renameError||expiryError||(this.pendingRename?'Review source bytes and absent destination before renaming':this.expiry?'Review exact bytes/absence and the selected expiry before saving':'Review exact old/new bytes before saving; preserve key TTL');
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
  revert(){if(this.busy||this.disposed||this.uncertain)return;this.pendingDelete=false;this.listAction=null;this.resetRename();if(this.snapshot?.create){this.snapshot=null;this.setValue('');this.status.textContent=(this.isHash?'New-field':'New-string')+' draft discarded; nothing was written.';}else if(this.snapshot){this.setValue(this.snapshot.base64);this.status.textContent='Draft reverted; nothing was written.';}this.resetExpiry();this.sync();this.changed();}
  stageListEnd(action){
    // The source position stays fixed until this draft is explicitly discarded or saved.
    if(!this.isList||!['prepend','append'].includes(action)||this.busy||this.disposed||this.unavailable||this.uncertain||!this.snapshot||this.snapshot.length>=10000||this.readOnly()||this.listAction===action)return;
    if(this.dirty&&!window.confirm('Replace the unsaved list draft with a new '+action+' draft? Nothing will be written.'))return;
    this.listAction=action;this.pendingDelete=false;this.setValue('');this.status.textContent=(action==='prepend'?'Prepend':'Append')+' staged · enter a value (empty is valid). Save reviews exact current length/value; positions may shift. Revert restores the loaded item.';this.sync();this.changed();
  }
  toggleDelete(){
    // Interior removal would require a different, separately verified workflow.
    if(this.busy||this.disposed||this.unavailable||this.uncertain||!this.snapshot||this.snapshot.create||this.pendingRename||this.readOnly())return;
    if(this.isList&&(!this.atListEnd||this.listAction))return;
    this.pendingDelete=!this.pendingDelete;this.status.textContent=this.pendingDelete?'Deletion staged · Save opens destructive review. Deleting the last item or whole string removes its key and TTL. Revert undoes this draft.':'Deletion unmarked; value draft retained.';this.sync();this.changed();
  }
  async load(create=false){
    // Loading reconciles the current state; it is never an automatic write retry.
    if(this.busy||this.disposed||this.unavailable||(create&&(this.isList||this.uncertain||this.readOnly()))||!this.canClose())return;
    let key,field,index;try{key={base64:redisBase64(redisBytes(this.key.value,this.keyMode.value))};if(!key.base64)throw new Error('Choose a nonempty key.');if(this.isHash)field={base64:redisBase64(redisBytes(this.field.value,this.fieldMode.value))};if(this.isList)index=redisListIndex(this.listIndex.value);}catch(e){this.status.textContent=e.message;return;}
    this.busy=true;this.sync();
    try{
      const reply=await this.run(create?{pipeline:this.isHash?[['TYPE',key],['HEXISTS',key,field]]:[['TYPE',key]]}:this.isList?{pipeline:[['TYPE',key],['LLEN',key],['LINDEX',key,String(index)],['PTTL',key]]}:this.isHash?['HGET',key,field]:{pipeline:[['TYPE',key],['STRLEN',key],['GETRANGE',key,'0','8191'],['PTTL',key]]});
      if(this.disposed)return;if(!reply?.ok)throw new Error(reply?.error??'Key load did not complete.');
      if(!reply.targetRevision)throw new Error('Missing target revision; no editable snapshot was created.');
      const snapshot=create?(this.isHash?redisNewHashSnapshot(reply.result):redisNewStringSnapshot(reply.result)):this.isList?redisListSnapshot(reply.result,index):this.isHash?redisHashSnapshot(reply.result):redisStringSnapshot(reply.result);this.snapshot={...snapshot,key,...(this.isHash?{field}:{}),targetRevision:reply.targetRevision};this.uncertain=false;this.pendingDelete=false;this.listAction=null;this.resetRename();this.setValue(snapshot.base64);this.resetExpiry();
      this.status.textContent=create?(this.isHash?'New field draft · empty values are valid. Save rechecks field absence and existing hash; nothing has been written.':'New string draft · empty values are valid; no expiry. Save rechecks absence and reviews SET NX. Nothing has been written.'):'Loaded '+redisBytes(snapshot.base64,'base64').length+' bytes · '+(this.isHash?'Save verifies persistent field and preserves key TTL':snapshot.ttl===-1?'no expiry':'TTL at read: '+snapshot.ttl+' ms')+' · Save rechecks exact bytes. Reads are not a frozen snapshot.';
    }catch(e){if(!this.disposed)this.status.textContent=e.message;}
    finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  async save(){
    // Review dispatch stays shared; each editor kind builds only its exact command below.
    if(this.busy||this.disposed||this.unavailable||this.uncertain||!this.dirty||this.readOnly())return;
    const deleting=!!this.pendingDelete;let next;try{next=deleting?this.snapshot.base64:redisBase64(redisBytes(this.value.value,this.activeMode));}catch(e){this.status.textContent=e.message;return;}
    const renaming=!!this.pendingRename;let destination;
    if(renaming)try{destination=this.renameDestination();}catch(e){this.status.textContent=e.message;this.renameKey.focus();return;}
    if(!deleting)try{this.expiryOptions();}catch(e){this.status.textContent=e.message;this.expirySeconds?.focus();return;}
    if(this.isList&&((deleting&&(!this.atListEnd||this.listAction))||(this.listAction&&(!['prepend','append'].includes(this.listAction)||this.snapshot.length>=10000)))){this.status.textContent='Unsupported list draft; reload a valid bounded position.';return;}
    const snapshot=this.snapshot;this.busy=true;this.sync();
    try{
      const adding=this.isList&&!!this.listAction;
      const command=this.draftCommand(snapshot,next,deleting);
      const expectedReceipt=renaming?true:adding?snapshot.length+1:this.isHash?(deleting||snapshot.create?1:0):deleting&&!this.isList?1:'OK';
      const watch=[{key:snapshot.key,...(this.isList?{index:snapshot.index,length:snapshot.length}:this.isHash?{field:snapshot.field}:{}),expected:snapshot.create?null:{base64:snapshot.base64}}];
      if(renaming)watch.push({key:destination,expected:null});
      const reply=await this.run({transaction:[command],watch},snapshot.targetRevision);
      if(this.disposed)return;
      // A job can fail/cancel after EXEC. Require the exact receipt, not just terminal job status.
      const result=reply?.result,entry=result?.entries?.[0];
      const completeReceipt=result?.outcome==='acknowledged'&&result.entries.length===1&&entry?.index===0&&entry.state==='acknowledged';
      if(completeReceipt&&entry.value===expectedReceipt){
        if(this.isList&&(adding||deleting))this.listIndex.value=String(adding?(this.listAction==='prepend'?0:snapshot.length):Math.min(snapshot.index,Math.max(0,snapshot.length-2)));
        const explicitExpiry=this.expiry&&this.expiry.value!=='preserve';
        this.snapshot=deleting||adding||renaming||(snapshot.create&&!this.isHash)||explicitExpiry?null:{...snapshot,base64:next,create:false};this.pendingDelete=false;this.listAction=null;this.uncertain=false;
        if(deleting||adding)this.setValue('');
        this.status.textContent=adding?'Added list item · key TTL preserved. Positions changed; reload before another edit.':deleting?(this.isList?'Deleted list end item':'Deleted field')+' · if it was the last item, Redis also removed the key and TTL. Nothing was recreated.':snapshot.create?'Created field · key TTL preserved. Reload to observe subsequent changes.':'Saved · '+(this.isHash?'key TTL':'TTL')+' preserved. Reload to observe subsequent changes.';
        if(!this.isHash&&!this.isList&&deleting)this.status.textContent='Deleted string key and its TTL. Reload before another edit; nothing was recreated.';
        if(!this.isHash&&!this.isList&&!deleting&&explicitExpiry)this.status.textContent=(snapshot.create?'Created string':'Saved string')+(this.expiry.value==='none'?' with no expiry.':' with expiry '+this.expirySeconds.value+' seconds from execution.')+' Displayed draft is not a fresh read; Load string before editing again.';
        if(renaming){this.key.value=destination.base64;this.keyMode.value='base64';this.status.textContent='Renamed string key · current TTL preserved; destination was absent. Load string at the new key before editing again. Native command text is unchanged.';}
        this.resetExpiry();this.resetRename();
      }else if(renaming&&completeReceipt&&entry.value===false){
        this.status.textContent='Rename was not applied. Destination may already exist; inspect both keys before a new reviewed attempt. Draft retained; nothing was retried.';
      }else{
        this.uncertain=!!reply?.uncertain||result?.outcome==='acknowledged';
        this.status.textContent=this.uncertain?'Save outcome uncertain. Draft retained; '+(renaming?'reconcile both source and destination keys':'reload and reconcile')+' before another Save.':reply?.error??'Value update was not confirmed. Reload before retrying.';
      }
    }catch(e){this.uncertain=true;this.status.textContent='Save outcome uncertain. '+(renaming?'Reconcile both source and destination keys':'Reload and reconcile')+' before retrying. '+e.message;}
    finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  invalidate(message){this.unavailable=message;this.status.textContent=message;this.sync();}
  draftCommand(snapshot,next,deleting){
    if(this.isList){
      if(deleting)return ['LTRIM',snapshot.key,snapshot.index===0?'1':'0',snapshot.index===0?'-1':'-2'];
      if(this.listAction)return [this.listAction==='prepend'?'LPUSH':'RPUSH',snapshot.key,{base64:next}];
      return ['LSET',snapshot.key,String(snapshot.index),{base64:next}];
    }
    if(this.isHash)return deleting?['HDEL',snapshot.key,snapshot.field]:['HSET',snapshot.key,snapshot.field,{base64:next}];
    if(this.pendingRename)return ['RENAMENX',snapshot.key,this.renameDestination()];
    if(deleting)return ['DEL',snapshot.key];
    return ['SET',snapshot.key,{base64:next},snapshot.create?'NX':'XX',...this.expiryOptions()];
  }
  resetList(){if(!this.isList||this.busy||this.disposed||this.unavailable||this.uncertain||!this.snapshot||!this.canClose())return;this.snapshot=null;this.pendingDelete=false;this.listAction=null;this.setValue('');this.status.textContent='Choose an existing list position and load it before editing.';this.sync();this.changed();this.listIndex.focus();}
  dispose(){this.disposed=true;this.snapshot=null;this.value.value='';this.root.remove();}
}
