import {lucide} from './tree-icons.js';
import {redisBytes,redisBase64} from './redis-string-editor.js';
const node=(tag,text)=>{const n=document.createElement(tag);if(text)n.textContent=text;return n;};

export function redisScore(text){
  if(typeof text!=='string'||text.length>64||text.trim()!==text||!/^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$/.test(text))throw Error('Enter a finite decimal score (up to 64 characters).');
  const score=Number(text);
  if(!Number.isFinite(score)||(score===0&&/[1-9]/.test(text.split(/[eE]/)[0])))throw Error('Score overflows or underflows Redis binary64 precision.');
  return score;
}
export function redisMemberSnapshot(result,sorted=false){
  const entries=result?.entries;
  if(result?.kind!=='pipeline'||result.truncated||entries?.length!==4||entries.some((e,i)=>e.index!==i||e.state!=='acknowledged'||e.valueOmitted))throw new Error('Incomplete membership check; no draft was prepared.');
  const [type,value,count,ttl]=entries.map(e=>e.value);
  if(type!==(sorted?'zset':'set'))throw new Error('Choose an existing '+(sorted?'sorted set':'set')+'. This editor never creates or replaces a set key.');
  if(sorted&&(typeof value!=='number'||!Number.isFinite(value)))throw Error('Choose an existing member with a finite score. Missing members and infinite scores cannot be edited.');
  if((!sorted&&typeof value!=='boolean')||!Number.isSafeInteger(count)||count<1||!Number.isSafeInteger(ttl)||ttl < -1)throw new Error('Set changed, expired, or returned incomplete metadata. Check membership again.');
  return{present:sorted?true:value,...(sorted?{score:value}:{}),count,ttl};
}
export const redisSetSnapshot=result=>redisMemberSnapshot(result);

/** One exact member, not a set inventory. Shared binary identity and draft lifecycle. */
export class RedisMemberEditor{
  constructor({run,readOnly,changed,close,key='',member='',sorted=false}){
    Object.assign(this,{run,readOnly,changed,close,sorted});this.snapshot=null;this.busy=false;this.uncertain=false;this.disposed=false;
    this.root=node('section');this.root.className='redis-string-editor';this.root.setAttribute('aria-label',sorted?'Redis sorted-set score editor':'Redis set member editor');
    const header=node('div');header.className='redis-string-actions';header.append(node('strong',sorted?'Sorted-set score · one existing member':'Set member · 8 KiB maximum'));
    const label=node('label','Key');this.key=node('input');this.key.setAttribute('aria-label','Key');this.key.autocomplete='off';this.key.spellcheck=false;this.key.maxLength=10924;label.append(this.key);header.append(label);
    this.keyMode=this.select(header,'Key encoding');this.key.value=typeof key==='string'?key:key?.base64??'';this.keyMode.value=typeof key==='string'?'text':'base64';
    this.loadButton=this.button(header,sorted?'Load member score':'Check set membership','refresh-cw',()=>this.load());
    this.resetButton=this.button(header,'Choose another member','plus',()=>this.reset());
    this.closeButton=this.button(header,'Close member editor','x',()=>{if(this.canClose())this.close();});this.root.append(header);
    this.root.append(node('p',sorted?'Edit one existing member score; key/member up to 8 KiB. Redis binary64 precision, not arbitrary decimal precision. GEO indexes also use zset: TYPE does not prove score semantics. Save preserves key TTL but may change rank. No creation, deletion or rename.':'One member in an existing set · current membership WATCH checks, not change history. Save preserves key TTL unless deletion removes the last member and its key. No set inventory, renaming or automatic retries.'));
    const format=node('div');format.className='redis-string-actions';this.mode=this.select(format,'Member encoding');this.mode.onchange=()=>this.changeMode();this.root.append(format);
    this.value=node('textarea');this.value.setAttribute('aria-label',sorted?'Sorted-set member':'Redis set member');this.value.spellcheck=false;this.value.wrap='off';this.value.maxLength=10924;
    this.value.value=typeof member==='string'?member:member?.base64??'';this.activeMode=typeof member==='string'?'text':'base64';this.mode.value=this.activeMode;this.value.oninput=()=>this.sync();this.root.append(this.value);
    const scoreLabel=node('label','Score');scoreLabel.hidden=!sorted;this.score=node('input');this.score.setAttribute('aria-label','Sorted-set score');this.score.type='text';this.score.inputMode='decimal';this.score.spellcheck=false;this.score.autocomplete='off';this.score.maxLength=64;this.score.oninput=()=>{this.sync();this.changed();};scoreLabel.append(this.score);this.root.append(scoreLabel);
    const footer=node('div');footer.className='redis-string-actions';
    this.stageButton=this.button(footer,'Stage membership change','plus',()=>this.stage());this.stageButton.hidden=sorted;
    this.saveButton=this.button(footer,sorted?'Save member score':'Save set member','save',()=>this.save());this.revertButton=this.button(footer,sorted?'Revert score draft':'Revert membership draft','undo-2',()=>this.revert());
    this.status=node('span',sorted?'Enter an existing member (empty is valid), then load its score. Nothing has been written.':'Enter a member (empty is valid), then check its membership. Nothing has been written.');this.status.setAttribute('role','status');footer.append(this.status);this.root.append(footer);this.sync();
  }
  select(parent,label){const s=node('select');s.setAttribute('aria-label',label);for(const value of ['text','base64']){const o=node('option',value);o.value=value;s.append(o);}parent.append(s);return s;}
  button(parent,label,icon,action){const b=node('button',label);b.type='button';b.title=label;b.setAttribute('aria-label',label);b.prepend(lucide(icon));b.onclick=action;parent.append(b);return b;}
  get dirty(){if(!this.snapshot)return false;if(!this.sorted)return this.desired!==this.snapshot.present;try{return redisScore(this.score.value)!==this.snapshot.score;}catch{return true;}}
  canClose(){return !(this.dirty||this.uncertain)||window.confirm(this.uncertain?'Save outcome uncertain. Close this draft? Reconcile the member before another write.':'Discard this unsaved Redis member change?');}
  sync(){
    const blocked=this.busy||this.disposed||!!this.unavailable;let valid=true,validScore=true;try{redisBytes(this.value.value,this.activeMode);}catch{valid=false;}if(this.sorted)try{redisScore(this.score.value);}catch{validScore=false;}
    this.key.disabled=this.keyMode.disabled=blocked||!!this.snapshot;
    this.value.readOnly=blocked||!!this.snapshot;this.mode.disabled=blocked||!!this.snapshot;
    this.value.setAttribute('aria-invalid',String(!valid));this.loadButton.disabled=blocked||(!this.snapshot&&!valid);
    this.score.disabled=blocked||this.uncertain||!this.snapshot||this.readOnly();this.score.setAttribute('aria-invalid',String(!!this.snapshot&&!validScore));
    this.resetButton.disabled=blocked||this.uncertain||!this.snapshot;this.closeButton.disabled=this.busy;
    this.stageButton.disabled=blocked||this.sorted||this.uncertain||!this.snapshot||this.readOnly();
    const deleting=!this.sorted&&this.snapshot?.present===true,label=deleting?'Mark member for deletion':'Stage member insertion';
    this.stageButton.replaceChildren(lucide(deleting?'trash-2':'plus'),document.createTextNode(label));this.stageButton.title=label;this.stageButton.setAttribute('aria-label',label);this.stageButton.setAttribute('aria-pressed',String(this.dirty));
    this.saveButton.disabled=blocked||this.uncertain||!this.dirty||!validScore||this.readOnly();this.revertButton.disabled=blocked||this.uncertain||!this.dirty;
    this.root.classList.toggle('redis-pending-delete',this.dirty&&deleting);this.root.setAttribute('aria-busy',String(this.busy));
  }
  changeMode(){
    try{const bytes=redisBytes(this.value.value,this.activeMode);const text=this.mode.value==='base64'?redisBase64(bytes):new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);if(this.mode.value==='text'&&text.includes('\r'))throw Error('Use base64 to preserve carriage returns exactly.');this.value.value=text;this.activeMode=this.mode.value;}
    catch(e){this.mode.value=this.activeMode;this.status.textContent=e.message;}this.sync();
  }
  stage(){if(this.sorted||this.busy||this.disposed||this.unavailable||this.uncertain||!this.snapshot||this.readOnly())return;this.desired=this.dirty?this.snapshot.present:!this.snapshot.present;this.status.textContent=this.dirty?(this.desired?'Insertion staged; Save opens review.':'Deletion staged; deleting the last member removes its set key and TTL. Save opens destructive review.'):'Change unstaged; nothing was written.';this.sync();this.changed();}
  revert(){if(this.busy||this.disposed||this.uncertain||!this.snapshot)return;this.desired=this.snapshot.present;if(this.sorted)this.score.value=String(this.snapshot.score);this.status.textContent='Draft reverted; nothing was written.';this.sync();this.changed();}
  reset(){if(this.busy||this.disposed||this.unavailable||this.uncertain||!this.canClose())return;this.snapshot=null;this.score.value='';this.status.textContent='Choose a member and load its current state before editing.';this.sync();this.changed();this.value.focus();}
  async load(){
    if(this.busy||this.disposed||this.unavailable||!this.canClose())return;
    let key,member;try{key={base64:redisBase64(redisBytes(this.key.value,this.keyMode.value))};if(!key.base64)throw Error('Choose a nonempty key.');member={base64:redisBase64(redisBytes(this.value.value,this.activeMode))};}catch(e){this.status.textContent=e.message;return;}
    this.busy=true;this.sync();
    try{
      const reply=await this.run({pipeline:[['TYPE',key],[this.sorted?'ZSCORE':'SISMEMBER',key,member],[this.sorted?'ZCARD':'SCARD',key],['PTTL',key]]});
      if(this.disposed)return;if(!reply?.ok)throw Error(reply?.error??'Membership check did not complete.');if(!reply.targetRevision)throw Error('Missing target revision; no member draft was prepared.');
      const snapshot=redisMemberSnapshot(reply.result,this.sorted);this.snapshot={...snapshot,key,member,targetRevision:reply.targetRevision};this.desired=snapshot.present;this.uncertain=false;if(this.sorted)this.score.value=String(snapshot.score);
      this.status.textContent=(this.sorted?'Score loaded: '+snapshot.score:snapshot.present?'Member present':'Member absent')+' · '+snapshot.count+' member(s) at read · '+(snapshot.ttl===-1?'no expiry':'TTL '+snapshot.ttl+' ms')+'. Reads are not a frozen snapshot; Save rechecks current state.';
    }catch(e){if(!this.disposed)this.status.textContent=e.message;}finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  async save(){
    if(this.busy||this.disposed||this.unavailable||this.uncertain||!this.dirty||this.readOnly())return;
    const snapshot=this.snapshot;let desired;try{desired=this.sorted?redisScore(this.score.value):this.desired;}catch(e){this.status.textContent=e.message;return;}
    const command=this.sorted?['ZADD',snapshot.key,String(desired),snapshot.member]:[desired?'SADD':'SREM',snapshot.key,snapshot.member];
    const watch=this.sorted?{key:snapshot.key,scoreMember:snapshot.member,expected:String(snapshot.score)}:{key:snapshot.key,member:snapshot.member,expected:snapshot.present};
    this.busy=true;this.sync();
    try{
      const reply=await this.run({transaction:[command],watch:[watch]},snapshot.targetRevision);
      if(this.disposed)return;const result=reply?.result,entry=result?.entries?.[0];
      if(result?.outcome==='acknowledged'&&result.entries.length===1&&entry?.index===0&&entry.state==='acknowledged'&&entry.value===(this.sorted?0:1)){
        this.snapshot=this.sorted?{...snapshot,score:desired}:desired?{...snapshot,present:true}:null;this.desired=desired;this.uncertain=false;
        this.status.textContent=this.sorted?'Score updated · key TTL preserved. Member rank may change.':desired?'Member added · key TTL preserved. Check membership to observe subsequent changes.':'Member deleted · if it was the last member, Redis removed the set key and TTL. Nothing was recreated.';
      }else{this.uncertain=!!reply?.uncertain||result?.outcome==='acknowledged';this.status.textContent=this.uncertain?'Save outcome uncertain. Draft retained; reload the member and reconcile before another Save.':reply?.error??'Member change was not confirmed; draft retained.';}
    }catch(e){this.uncertain=true;this.status.textContent='Save outcome uncertain. Reload the member and reconcile before retrying. '+e.message;}
    finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  invalidate(message){this.unavailable=message;this.status.textContent=message;this.sync();}
  dispose(){this.disposed=true;this.snapshot=null;this.value.value='';this.score.value='';this.root.remove();}
}
export {RedisMemberEditor as RedisSetEditor};
