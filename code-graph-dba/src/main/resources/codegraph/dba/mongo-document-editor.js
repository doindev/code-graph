import {lucide} from './tree-icons.js';

const MAX_BYTES=32768;
const el=(tag,text)=>{const node=document.createElement(tag);if(text)node.textContent=text;return node;};
const bytes=value=>new TextEncoder().encode(value).length;
function newObjectId(){const value=crypto.getRandomValues(new Uint8Array(12));new DataView(value.buffer).setUint32(0,Math.floor(Date.now()/1000));return {$oid:Array.from(value,n=>n.toString(16).padStart(2,'0')).join('')};}
function supportedId(value){
  if(typeof value==='string')return true;
  if(!value||Array.isArray(value)||Object.keys(value).length!==1)return false;
  if(typeof value.$oid==='string')return /^[0-9a-f]{24}$/i.test(value.$oid);
  const width=Object.hasOwn(value,'$numberInt')?32:Object.hasOwn(value,'$numberLong')?64:0,text=width===32?value.$numberInt:value.$numberLong;
  if(!width||typeof text!=='string'||!/^-(?:[1-9][0-9]*)$|^(?:0|[1-9][0-9]*)$/.test(text)||text.length>20)return false;
  const n=BigInt(text),bound=1n<<BigInt(width-1);return n>=-bound&&n<bound;
}
// JSON.parse alone silently keeps the last duplicate field. Reject duplicates before
// submitting a complete replacement; no eval or project-provided parser is involved.
function uniqueKeys(text){
  let position=0,count=0;const space=()=>{while(/\s/.test(text[position]??'')&&position<text.length)position++;};
  const string=()=>{const start=position++;while(position<text.length){const c=text[position++];if(c==='\\')position++;else if(c==='"')return JSON.parse(text.slice(start,position));}throw Error('Unterminated JSON string.');};
  const value=depth=>{
    if(depth>32||++count>16384)throw Error('Document exceeds structural limits.');space();
    if(text[position]==='{'){position++;space();const keys=new Set();if(text[position]==='}'){position++;return;}
      for(;;){space();const key=string();if(keys.has(key))throw Error('Duplicate JSON field names are not editable.');keys.add(key);space();position++;value(depth+1);space();if(text[position++]==='}')return;}
    }else if(text[position]==='['){position++;space();if(text[position]===']'){position++;return;}for(;;){value(depth+1);space();if(text[position++]===']')return;}}
    else if(text[position]==='"')string();else while(position<text.length&&!/[\s,\]}]/.test(text[position]))position++;
  };value(0);
}
export function mongoDocumentJson(text){
  if(bytes(text)>MAX_BYTES)throw Error('Document exceeds 32 KiB of canonical Extended JSON.');
  const document=JSON.parse(text);
  uniqueKeys(text);
  if(!document||Array.isArray(document)||typeof document!=='object'||!Object.hasOwn(document,'_id'))throw Error('A complete document with an immutable _id is required.');
  if(!supportedId(document._id))throw Error('_id must be a string or canonical ObjectId, Int32 or Int64 wrapper.');
  if(Object.keys(document).some(name=>name.startsWith('$')))throw Error('Replacement documents cannot contain top-level operators.');
  return document;
}
function entries(reply){
  if(!reply?.ok||reply.result?.kind!=='documents'||reply.result.truncated||!Array.isArray(reply.result.entries))throw Error(reply?.error??'Incomplete document/collection response; no editable snapshot loaded.');
  return reply.result.entries;
}

/** Memory-only complete-document draft; NativeWorkspace owns admission, exact review and jobs. */
export class MongoDocumentEditor{
  constructor({run,readOnly,changed,close,collection,id='""',topology}){
    Object.assign(this,{run,readOnly,changed,close,collection,topology});this.snapshot=null;this.busy=false;this.uncertain=false;
    this.root=el('section');this.root.className='mongo-document-editor';this.root.setAttribute('aria-label','MongoDB document editor');
    const header=el('div');header.className='redis-string-actions';header.append(el('strong','Complete document · canonical Extended JSON'));
    const label=el('label','_id');this.key=el('input');this.key.value=id;this.key.maxLength=8192;this.key.spellcheck=false;this.key.setAttribute('aria-label','Document _id as Extended JSON');label.append(this.key);header.append(label);
    this.loadButton=this.button(header,'Load document','refresh-cw',()=>this.load());
    this.newButton=this.button(header,'New document','plus',()=>this.load(true));
    this.closeButton=this.button(header,'Close document editor','x',()=>{if(this.canClose())this.close();});this.root.append(header);
    this.root.append(el('p','New stages an insert; Save on a loaded document replaces it, removing omitted fields. Delete stages removal. All writes need Save and exact review.'));
    const details=el('details');details.append(el('summary','BSON types and editing requirements'),el('p','Use canonical BSON type wrappers ($numberInt, $numberLong, $numberDecimal, $date, $binary). Existing _id is immutable; new drafts may change it before review. Saves require a replica set and an ordinary collection; other topologies are inspect-only. No retries, upserts or bulk writes.'));this.root.append(details);
    this.value=el('textarea');this.value.className='mongo-document-value';this.value.wrap='off';this.value.spellcheck=false;this.value.maxLength=32768;this.value.setAttribute('aria-label','Complete document as canonical Extended JSON');this.value.rows=14;
    this.value.oninput=()=>{if(bytes(this.value.value)>MAX_BYTES){this.value.value=this.lastBounded??'';this.status.textContent='Document exceeds 32 KiB; last bounded draft retained.';}this.lastBounded=this.value.value;if(this.snapshot?.creating)try{this.key.value=JSON.stringify(mongoDocumentJson(this.value.value)._id);}catch{}this.sync();this.changed();};this.root.append(this.value);
    const footer=el('div');footer.className='redis-string-actions';this.saveButton=this.button(footer,'Save document','save',()=>this.save());this.revertButton=this.button(footer,'Revert document draft','undo-2',()=>this.revert());this.deleteButton=this.button(footer,'Delete document','trash-2',()=>this.stageDelete());this.reconcileButton=this.button(footer,'Discard reconciled document draft','check',()=>this.reconcile());
    this.status=el('span','Choose an exact _id. Opening this editor does not query or write.');this.status.setAttribute('role','status');footer.append(this.status);this.root.append(footer);this.sync();
  }
  button(parent,label,icon,action){const b=el('button',label);b.type='button';b.title=label;b.setAttribute('aria-label',label);b.prepend(lucide(icon));b.onclick=action;parent.append(b);return b;}
  get dirty(){return !!this.snapshot&&(this.snapshot.creating||this.pendingDelete||this.value.value!==this.snapshot.text);}
  blocked(){return this.busy||this.disposed||!!this.unavailable;}
  editable(){return !this.readOnly()&&this.topology==='replica_set'&&!!this.snapshot?.ordinary;}
  canClose(){return !(this.dirty||this.uncertain)||window.confirm(this.uncertain?'Reconcile the previous document change before another write. Close this retained document draft?':'Discard this unsaved document draft? Nothing will be written.');}
  sync(){
    const locked=this.blocked()||this.uncertain;
    this.key.disabled=locked||!!this.snapshot;this.loadButton.disabled=locked;this.closeButton.disabled=this.busy;
    this.newButton.disabled=locked||this.readOnly()||this.topology!=='replica_set';
    this.value.readOnly=locked||this.pendingDelete||!this.snapshot||!this.editable();this.saveButton.disabled=locked||!this.editable()||!this.dirty;this.revertButton.disabled=locked||!this.dirty;
    this.deleteButton.disabled=locked||!this.editable()||!!this.pendingDelete||!!this.snapshot?.creating;this.deleteButton.setAttribute('aria-pressed',String(!!this.pendingDelete));
    this.reconcileButton.hidden=!this.uncertain;this.reconcileButton.disabled=this.blocked();this.root.setAttribute('aria-busy',String(this.busy));
  }
  stageDelete(){if(this.blocked()||this.uncertain||!this.editable()||this.snapshot?.creating)return;this.pendingDelete=true;this.status.textContent='Deletion staged for this complete document. Nothing written; Save opens exact destructive review, Revert cancels the draft.';this.sync();this.changed();}
  revert(){if(this.blocked()||this.uncertain||!this.snapshot)return;this.pendingDelete=false;this.value.value=this.lastBounded=this.snapshot.text;this.key.value=JSON.stringify(this.snapshot.original._id);this.status.textContent=this.snapshot.creating?'New draft reset to its initial _id; no write was sent.':'Draft reverted; no write was sent.';this.sync();this.changed();}
  reconcile(){if(this.blocked()||!this.uncertain||!window.confirm('Have you reconciled the previous write using authorized reads? Discarding this draft neither retries nor undoes it.'))return;this.snapshot=null;this.pendingDelete=false;this.value.value=this.lastBounded='';this.uncertain=false;this.status.textContent='Reconciled draft discarded. Load the document again.';this.sync();this.changed();}
  async load(creating=false){
    if(this.blocked()||this.uncertain||this.dirty&&!window.confirm('Discard the current document draft and load authoritative data?'))return;
    if(creating&&(this.readOnly()||this.topology!=='replica_set'))return;
    let id;try{id=creating?newObjectId():mongoDocumentJson('{"_id":'+this.key.value+'}')._id;}catch(e){this.status.textContent=e.message;return;}
    this.busy=true;this.sync();
    try{
      const metadata=await this.run({listCollections:1,filter:{name:this.collection}});if(this.disposed)return;
      const definitions=entries(metadata),definition=definitions[0];
      if(definitions.length!==1||definition.name!==this.collection)throw Error('Exact collection metadata is unavailable.');
      const uuid=definition.info?.uuid?.$binary;
      const ordinary=definition.type==='collection'&&!definition.options?.capped&&!definition.options?.timeseries&&uuid?.subType==='04'&&typeof uuid.base64==='string';
      if(creating&&!ordinary)throw Error('New documents require an existing ordinary collection with its UUID.');
      const reply=creating?metadata:await this.run({find:this.collection,filter:{_id:{$eq:id}},collation:{locale:'simple'},limit:2},metadata.targetRevision);if(this.disposed)return;
      const documents=creating?[{_id:id}]:entries(reply);if(documents.length!==1)throw Error(documents.length?'Ambiguous document identity; no editable snapshot loaded.':'Document not found.');
      if(!metadata.targetRevision||metadata.targetRevision!==reply.targetRevision)throw Error('Connection changed during document load.');
      const original=documents[0],compact=JSON.stringify(original);mongoDocumentJson(compact);
      // Avoid pretty-print expansion exceeding the same retained-draft bound.
      const pretty=JSON.stringify(original,null,2),text=bytes(pretty)<=MAX_BYTES?pretty:compact;
      this.snapshot={original,text,collectionUuid:uuid?.base64,targetRevision:reply.targetRevision,ordinary,creating};
      this.pendingDelete=false;this.value.value=this.lastBounded=text;this.key.value=JSON.stringify(original._id);
      this.status.textContent=creating?'New document draft; no write sent. Edit fields or the suggested random ObjectId. Save reviews an insert with an _id absence check.':this.editable()?'Loaded complete document. Save checks collection UUID and byte-exact original BSON inside a transaction.':'Document loaded for inspection only: saves need a writable replica-set profile and ordinary collection metadata.';
    }catch(e){if(!this.disposed){this.snapshot=null;this.status.textContent=e.message;}}finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  async save(){
    if(this.blocked()||this.uncertain||!this.editable()||!this.dirty)return;
    const snapshot=this.snapshot,deleting=!!this.pendingDelete,creating=!!snapshot.creating;let replacement;
    if(!deleting)try{replacement=mongoDocumentJson(this.value.value);if(!creating&&JSON.stringify(replacement._id)!==JSON.stringify(snapshot.original._id))throw Error('_id is immutable; restore its original typed value.');}catch(e){this.status.textContent=e.message;return;}
    const change=creating?{insert:this.collection,documents:[replacement]}:deleting?{delete:this.collection,deletes:[{q:{_id:snapshot.original._id},limit:1}]}:{update:this.collection,updates:[{q:{_id:snapshot.original._id},u:replacement,multi:false,upsert:false}]};
    const command={transaction:[change],documentGuard:{collectionUuid:snapshot.collectionUuid,...(creating?{absentId:replacement._id}:{expected:snapshot.original})}};
    this.busy=true;this.sync();
    try{
      const reply=await this.run(command,snapshot.targetRevision);if(this.disposed)return;
      const result=reply?.result,entry=result?.entries?.[0];
      if(result?.kind==='transaction'&&result.atomic===true&&result.documentGuard===true&&result.outcome==='commit_acknowledged'&&result.entries?.length===1&&entry.index===0&&entry.state==='committed'&&entry.operation===(creating?'insert':deleting?'delete':'update')&&entry.matchedOrInserted===1){
        this.snapshot=null;this.pendingDelete=false;if(deleting)this.value.value=this.lastBounded='';this.status.textContent=creating?'Document created; transaction commit confirmed. Load document before editing again.':deleting?'Document deleted; transaction commit confirmed.':'Document saved. Displayed draft is not a fresh read; Load document before editing again.';
      }else{
        this.uncertain=!!reply?.uncertain||result?.outcome==='commit_acknowledged';
        this.status.textContent=this.uncertain?'Document change outcome uncertain. Draft retained; reconcile before another write.':reply?.error??'Document change not committed. Draft retained; reload after a conflict.';
      }
    }catch(e){this.uncertain=true;this.status.textContent='Document change outcome uncertain. Reconcile before another write. '+e.message;}
    finally{this.busy=false;if(!this.disposed){this.sync();this.changed();}}
  }
  invalidate(message){this.unavailable=message;this.status.textContent=message;this.sync();}
  dispose(){this.disposed=true;this.snapshot=null;this.pendingDelete=false;this.value.value=this.lastBounded=this.key.value='';this.root.remove();}
}
