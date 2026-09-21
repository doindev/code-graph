import {lucide} from './tree-icons.js';
import {redisBytes,redisBase64} from './redis-string-editor.js';

const MAX_PAIRS=32,MAX_BYTES=32768,MAX_TEXT=49152;
const el=(tag,text)=>{const n=document.createElement(tag);if(text)n.textContent=text;return n;};
export function redisStreamEntryId(value){
  if(typeof value!=='string'||!/^[0-9]{1,20}-[0-9]{1,20}$/.test(value))return false;
  const parts=value.split('-').map(BigInt),max=(1n<<64n)-1n;
  return parts.every(n=>n<=max)&&parts.some(n=>n!==0n);
}
export function redisStreamSnapshot(result){
  const entries=result?.entries;
  if(result?.kind!=='pipeline'||result.truncated||entries?.length!==3||entries.some((e,i)=>e.index!==i||e.state!=='acknowledged'||e.valueOmitted))throw Error('Incomplete stream metadata; reload before preparing an entry.');
  const [type,length,ttl]=entries.map(e=>e.value);
  if(type!=='stream'||!Number.isSafeInteger(length)||length<0||!Number.isSafeInteger(ttl)||ttl < -1)throw Error('Choose an existing stream. Missing, expired or other key types cannot be used.');
  return{length,ttl};
}

/** Ordered, bounded draft only. The owning workspace supplies review/jobs and 512 KiB accounting. */
export class RedisStreamEditor{
  constructor({run,readOnly,changed,close,key=''}){
    Object.assign(this,{run,readOnly,changed,close});this.rows=[];this.snapshot=null;this.staged=false;this.busy=false;this.uncertain=false;
    this.root=el('section');this.root.className='redis-string-editor redis-stream-editor';this.root.setAttribute('aria-label','Redis stream entry composer');
    const header=el('div');header.className='redis-string-actions';header.append(el('strong','Append stream entry · existing stream only'));
    this.key=this.input(header,'Key',typeof key==='string'?key:key.base64??'');this.keyMode=this.encoding(header,'Key encoding');if(typeof key==='object')this.keyMode.value='base64';
    this.loadButton=this.button(header,'Load stream','refresh-cw',()=>this.load());this.closeButton=this.button(header,'Close stream composer','x',()=>{if(this.canClose())this.close();});this.root.append(header);
    this.root.append(el('p','Entries are append-only, not edited in place. Save reviews exact ordered field/value pairs and uses XADD NOMKSTREAM with an automatic ID. It never creates a missing key or trims entries. Existing TTL is preserved. Same-name key recreation cannot be detected; metadata is not a frozen snapshot. No automatic retries.'));
    const actions=el('div');actions.className='redis-string-actions';
    this.stageButton=this.button(actions,'Stage new stream entry','list-plus',()=>this.stage());
    this.addButton=this.button(actions,'Add field/value pair','plus',()=>this.addPair());
    this.root.append(actions);this.fields=el('div');this.fields.className='redis-stream-fields';this.fields.setAttribute('aria-label','Ordered stream fields');this.root.append(this.fields);
    const footer=el('div');footer.className='redis-string-actions';this.saveButton=this.button(footer,'Save stream entry','save',()=>this.save());this.revertButton=this.button(footer,'Revert stream entry draft','undo-2',()=>this.revert());
    this.reconcileButton=this.button(footer,'Discard reconciled stream draft','check',()=>this.reconcile());
    this.status=el('span','Load an existing stream; opening this composer does not query or write.');this.status.setAttribute('role','status');footer.append(this.status);this.root.append(footer);this.sync();
  }
  button(parent,label,icon,action){const b=el('button',label);b.type='button';b.title=label;b.setAttribute('aria-label',label);b.prepend(lucide(icon));b.onclick=action;parent.append(b);return b;}
  input(parent,label,value=''){const wrap=el('label',label),input=el('input');input.value=value;input.maxLength=10924;input.autocomplete='off';input.spellcheck=false;input.setAttribute('aria-label',label);wrap.append(input);parent.append(wrap);this.bound(input);return input;}
  encoding(parent,label){const s=el('select');s.setAttribute('aria-label',label);s.title='Interpret this input as UTF-8 text or canonical base64; changing this selector does not convert the text.';for(const mode of ['text','base64']){const o=el('option',mode);o.value=mode;s.append(o);}s.onchange=()=>{this.sync();this.changed();};parent.append(s);return s;}
  bound(input){
    input.lastBoundedValue=input.value;
    input.oninput=()=>{
      const inputs=[...this.root.querySelectorAll('input,textarea')];
      if(inputs.reduce((n,x)=>n+x.value.length,0)>MAX_TEXT){input.value=input.lastBoundedValue;this.status.textContent='Draft text exceeds the 48 Ki-character allowance.';}
      input.lastBoundedValue=input.value;this.sync();this.changed();
    };
  }
  get dirty(){return this.staged;}
  blocked(){return this.busy||this.disposed||!!this.unavailable;}
  canClose(){return !(this.dirty||this.uncertain)||window.confirm(this.uncertain?'Append outcome is uncertain. Reconcile stream entries before another append. Close this retained draft?':'Discard this unsaved stream entry? Nothing will be written.');}
  pairs(){
    if(!this.rows.length||this.rows.length>MAX_PAIRS)throw Error('Use 1–32 field/value pairs.');
    let size=0;return this.rows.map(row=>['field','value'].map(kind=>{const bytes=redisBytes(row[kind].value,row[kind+'Mode'].value);size+=bytes.length;if(size>MAX_BYTES)throw Error('Combined field/value bytes exceed 32 KiB.');return{base64:redisBase64(bytes)};}));
  }
  sync(){
    if(!this.status)return;const blocked=this.blocked(),locked=blocked||this.uncertain,readonly=this.readOnly();
    this.key.disabled=this.keyMode.disabled=locked||!!this.snapshot||this.staged;this.loadButton.disabled=locked;
    this.closeButton.disabled=this.busy;this.stageButton.disabled=locked||readonly||!this.snapshot||this.staged;
    this.addButton.disabled=locked||readonly||!this.staged||this.rows.length>=MAX_PAIRS;
    this.saveButton.disabled=locked||readonly||!this.snapshot||!this.staged;this.revertButton.disabled=locked||!this.staged;
    this.reconcileButton.hidden=!this.uncertain;this.reconcileButton.disabled=blocked;
    for(const row of this.rows){row.field.disabled=row.value.disabled=row.fieldMode.disabled=row.valueMode.disabled=locked||readonly;row.remove.disabled=locked||readonly;}
    this.root.setAttribute('aria-busy',String(this.busy));
  }
  stage(){if(this.blocked()||this.uncertain||this.readOnly()||!this.snapshot||this.staged)return;this.staged=true;this.addPair();this.status.textContent='Entry staged. Empty fields/values and duplicate names are valid; order is preserved. Save opens review.';this.sync();this.changed();}
  addPair(){
    if(this.blocked()||this.uncertain||this.readOnly()||!this.staged||this.rows.length>=MAX_PAIRS)return;
    const row={root:el('fieldset')};row.root.className='redis-stream-pair';row.legend=el('legend');row.root.append(row.legend);
    row.field=this.input(row.root,'Field');row.fieldMode=this.encoding(row.root,'Field encoding');
    const label=el('label','Value');row.value=el('textarea');row.value.rows=2;row.value.maxLength=10924;row.value.spellcheck=false;row.value.wrap='off';row.value.setAttribute('aria-label','Value');label.append(row.value);row.root.append(label);this.bound(row.value);
    row.valueMode=this.encoding(row.root,'Value encoding');row.remove=this.button(row.root,'Remove field/value pair','trash-2',()=>{if(this.blocked()||this.uncertain||this.readOnly())return;this.rows=this.rows.filter(r=>r!==row);row.root.remove();this.numberRows();this.sync();this.changed();});
    this.rows.push(row);this.fields.append(row.root);this.numberRows();this.sync();this.changed();row.field.focus();
  }
  numberRows(){this.rows.forEach((r,i)=>{r.legend.textContent='Pair '+(i+1);});}
  clearDraft(){for(const row of this.rows){row.field.value=row.value.value=row.field.lastBoundedValue=row.value.lastBoundedValue='';}this.rows=[];this.fields.replaceChildren();this.staged=false;}
  revert(){if(this.blocked()||this.uncertain)return;this.clearDraft();this.status.textContent='Draft reverted; nothing was written.';this.sync();this.changed();}
  reconcile(){if(this.blocked()||!this.uncertain||!window.confirm('Have you reconciled the previous append using authorized stream reads? This only discards the draft; it does not undo or retry any write.'))return;this.clearDraft();this.snapshot=null;this.uncertain=false;this.status.textContent='Reconciled draft discarded. Load the target again before a new entry.';this.sync();this.changed();}
  async load(){
    if(this.blocked()||this.uncertain)return;
    let key;try{key={base64:redisBase64(redisBytes(this.key.value,this.keyMode.value))};if(!key.base64)throw Error('Choose a nonempty stream key.');}catch(e){this.status.textContent=e.message;return;}
    this.busy=true;this.sync();
    try{
      const reply=await this.run({pipeline:[['TYPE',key],['XLEN',key],['PTTL',key]]});
      if(this.disposed)return;if(!reply?.ok)throw Error(reply?.error??'Stream metadata load failed.');if(!reply.targetRevision)throw Error('Missing target revision; no append draft can be prepared.');
      this.snapshot={...redisStreamSnapshot(reply.result),key,targetRevision:reply.targetRevision};
      this.status.textContent='Loaded stream · '+this.snapshot.length+' entries at read · '+(this.snapshot.ttl===-1?'no expiry':'TTL '+this.snapshot.ttl+' ms')+'. Existing staged fields are retained.';
    }catch(e){if(!this.disposed){this.snapshot=null;this.status.textContent=e.message;}}finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  async save(){
    if(this.blocked()||this.uncertain||this.readOnly()||!this.staged||!this.snapshot)return;
    let command;try{command=['XADD',this.snapshot.key,'NOMKSTREAM','*',...this.pairs().flat()];}catch(e){this.status.textContent=e.message;return;}
    const snapshot=this.snapshot;this.busy=true;this.sync();
    try{
      const reply=await this.run(command,snapshot.targetRevision);if(this.disposed)return;const result=reply?.result;
      if(result?.kind==='stream'&&result.operation==='XADD'&&result.outcome==='acknowledged'&&result.existingStreamOnly===true){
        if(result.applied===true&&redisStreamEntryId(result.entryId)&&result.value===result.entryId){this.clearDraft();this.snapshot=null;this.status.textContent='Appended entry '+result.entryId+' · TTL preserved. Load again before preparing a new entry.';}
        else if(result.applied===false&&result.entryId===null&&result.value===null){this.snapshot=null;this.status.textContent='No entry added: the stream no longer exists. Draft retained; no key was created. Reload or Revert.';}
        else{this.uncertain=true;this.status.textContent='Append receipt is incomplete or invalid. Draft retained; reconcile before another append.';}
      }else{this.uncertain=!!reply?.uncertain||result?.outcome==='acknowledged';this.status.textContent=this.uncertain?'Append outcome uncertain. Draft retained; reconcile stream entries before another append.':reply?.error??'Append was not confirmed; draft retained.';}
    }catch(e){this.uncertain=true;this.status.textContent='Append outcome uncertain. Reconcile before another append. '+e.message;}
    finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  invalidate(message){this.unavailable=message;this.status.textContent=message;this.sync();}
  dispose(){this.disposed=true;this.clearDraft();this.snapshot=null;this.key.value=this.key.lastBoundedValue='';this.root.remove();}
}
