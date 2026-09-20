import {lucide} from './tree-icons.js';
import {DataGridView} from './data-grid.js';
import {RedisStringEditor} from './redis-string-editor.js';
import {RedisSetEditor} from './redis-set-editor.js';

const terminal=new Set(['complete','failed','cancelled']);
// Enough for the bounded stream delivery/tombstone ID receipt even when payload display is full.
const RECEIPT_RESERVE=512*1024;
const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text)node.textContent=text;if(cls)node.className=cls;return node;};

/** Only a bounded display projection; Extended JSON stays authoritative and is never edited here. */
export function nativeGridModel(entries){
  const records=entries.map(entry=>entry!==null&&typeof entry==='object'&&!Array.isArray(entry)?entry:{value:entry});
  const names=new Set();let omitted=false;
  for(const entry of records)for(const name of Object.keys(entry)){
    if(names.has(name))continue;if(names.size>=256){omitted=true;continue;}names.add(name);
  }
  const keys=[...names];
  return{columns:keys.map(name=>({id:name,label:name,type:'Typed JSON',jdbcType:12})),
    rows:records.map(entry=>keys.map(name=>Object.hasOwn(entry,name)?JSON.stringify(entry[name]):'[missing]')),
    omittedColumns:omitted};
}

/** Native document lifetime is independent of mounting. Never evaluates JavaScript or shell text. */
export class NativeWorkspace {
  constructor({api,profile,state,changed,account}) {
    Object.assign(this,{api,profile,state,changed,account});this.disposed=false;this.operation=null;this.displayBytes=0;
    this.root=el('section',null,'native-workspace');
    const header=el('div',null,'native-workspace-header');
    header.append(el('strong',profile.transport==='mongodb'?'MongoDB · Extended JSON':'Redis · commands & transactions'));
    header.append(el('span','Exact target · bounded results · writes require review · no automatic write retries','muted'));
    this.database=this.field(header,'Database',state.database??profile.nativeOptions?.database??(profile.transport==='redis'?'0':''));
    if(profile.transport==='mongodb')this.collection=this.field(header,'Collection',state.collection??'');
    this.root.append(header);
    const toolbar=el('div',null,'native-workspace-actions');
    this.runButton=this.button(toolbar,'Run native command','play',()=>this.run());
    this.cancelButton=this.button(toolbar,'Cancel current operation','square',()=>this.cancel());this.cancelButton.disabled=true;
    if(profile.transport==='mongodb'){this.nextButton=this.button(toolbar,'Read next change batch','refresh-cw',()=>this.run(true));this.nextButton.disabled=true;}
    if(profile.transport==='redis'){
      this.stringButton=this.button(toolbar,'Edit Redis string value','square-pen',()=>this.openValueEditor('string'));
      this.hashButton=this.button(toolbar,'Edit Redis hash field','square-pen',()=>this.openValueEditor('hash'));
      this.listButton=this.button(toolbar,'Edit Redis list item','square-pen',()=>this.openValueEditor('list'));
      this.setButton=this.button(toolbar,'Edit Redis set member','square-pen',()=>this.openValueEditor('set'));
      this.streamExamples=el('select');this.streamExamples.setAttribute('aria-label','Redis command example');this.streamExamples.title='Insert an example for editing; never executes it. Consumer-group reads and PFCOUNT require write review.';
      const examples=[['Stream examples…',null],['Read stream',['XREAD','COUNT','100','STREAMS','stream','0-0']],['Read consumer group',['XREADGROUP','GROUP','group','consumer','COUNT','100','STREAMS','stream','>']],['Pending messages',['XPENDING','stream','group','-','+','100']],['Acknowledge exact IDs',['XACK','stream','group','1-0']],['Claim pending messages',['XAUTOCLAIM','stream','group','consumer','60000','0-0','COUNT','100']],['Create consumer group',['XGROUP','CREATE','stream','group','0-0']],['Add stream entry',['XADD','stream','*','field','value']]];
      examples.push(['Pipeline: write and inspect',{pipeline:[['SET','{example}:key','value'],['GETRANGE','{example}:key','0','8191'],['TTL','{example}:key']]}],['Pipeline: read key metadata',{pipeline:[['TYPE','{example}:key'],['TTL','{example}:key'],['EXISTS','{example}:key']]}]);
      examples[0][0]='Redis examples…';
      examples.push(['Bitmap: set bit',['SETBIT','{example}:bits','7','1']],['Bitmap: count bits',['BITCOUNT','{example}:bits']],['Bitfield: read integer',['BITFIELD_RO','{example}:bits','GET','i64','0']],['Bitfield: increment',['BITFIELD','{example}:bits','OVERFLOW','FAIL','INCRBY','u8','0','1']],['HyperLogLog: add members',['PFADD','{example}:visitors','member-1','member-2']],['HyperLogLog: count (write review)',['PFCOUNT','{example}:visitors']],['Geo: add location',['GEOADD','{example}:places','13.361389','38.115556','Palermo']],['Geo: nearby locations',['GEOSEARCH','{example}:places','FROMMEMBER','Palermo','BYRADIUS','200','km','ASC','COUNT','100','WITHDIST','WITHCOORD']]);
      for(const [index,[label]] of examples.entries()){const option=el('option',label);option.value=String(index);this.streamExamples.append(option);}
      this.streamExamples.onchange=()=>{const command=examples[Number(this.streamExamples.value)]?.[1];this.streamExamples.value='0';if(!command||this.operation||this.disposed||this.unavailable)return;if(this.editor.value.trim()&&!window.confirm('Replace this command draft with a Redis example? Nothing will execute.'))return;this.editor.value=JSON.stringify(command,null,2);this.sync();this.editor.focus();};
      toolbar.append(this.streamExamples);
    }
    this.status=el('span','Ready · no query has run','native-status');this.status.setAttribute('role','status');toolbar.append(this.status);
    this.root.append(toolbar);
    this.editor=el('textarea',null,'native-command');this.editor.setAttribute('aria-label','Native command JSON');this.editor.spellcheck=false;this.editor.wrap='off';
    if(profile.transport==='redis')this.editor.title='Redis argument array, or {"transaction":[["SET","key","value"]],"watch":[{"key":"key","expected":null}]}. At most 32 mutations, 16 expected string/absent keys, hash fields, or list positions (index + length + complete expected bytes); Cluster requires one hash slot. No rollback or retries. Keys and values may use {"base64":"AP8="}; controls stay text. Binary values: 64 KiB; keys/fields: 8 KiB.';
    if(profile.transport==='mongodb')this.editor.title='MongoDB Extended JSON command, or {"transaction":[{"insert":"items","documents":[{"_id":"example"}]}]}. Transactions require an explicit replica-set/sharded profile and one existing ordinary collection; at most 32 CRUD commands and 100 write entries. Updates/deletes must each match one document. No automatic retries.';
    if(profile.transport==='redis')this.editor.title+=' Pipelines: {"pipeline":[["SET","{example}:key","value"],["TTL","{example}:key"]]}. Up to 32 scalar commands; all reviewed together, not atomic. Later commands can succeed after errors. Cluster requires one slot; no scripts or automatic retry.';
    if(profile.transport==='redis')this.editor.title+=' Bitmap/bitfield writes address at most 64 KiB; at most 32 bitfield subcommands. Geo searches require COUNT 1..100. PFCOUNT may update cached cardinality, so requires write review. Multi-key cardinality/geo storage commands require one Cluster hash slot. These workflows run separately, not inside pipelines or transactions.';
    if(profile.transport==='mongodb')this.editor.title+=' Change streams: {"watch":"items","waitMillis":1000,"limit":100}. Finite batches, no initial snapshot. Read next change batch resumes explicitly; Run without a cursor starts at now and may miss intervening history.';
    if(profile.transport==='redis')this.editor.title+=' Stream examples are finite, single-key commands with COUNT at most 100. No BLOCK/NOACK or automatic acknowledgement. Consumer-group reads/claims change pending delivery state; inspect all delivered IDs and complete payloads before explicitly acknowledging them. No subscriptions retained.';
    this.editor.value=state.commandText??(profile.transport==='mongodb'?'{\n  "find": "collection",\n  "filter": {},\n  "limit": 100\n}':'["SCAN", "0", "MATCH", "*"]');
    this.editor.addEventListener('input',()=>this.sync());
    this.editor.addEventListener('keydown',event=>{if((event.ctrlKey||event.metaKey)&&event.key==='Enter'){event.preventDefault();this.run();}});
    this.root.append(this.editor);
    const views=el('div',null,'native-result-tabs');views.setAttribute('role','tablist');views.setAttribute('aria-label','Native result display');
    this.viewButtons={};this.viewName='json';const prefix=crypto.randomUUID();
    for(const [name,label] of [['json','JSON'],['grid','Grid']]){
      const button=el('button',label);button.type='button';button.id=prefix+'-'+name;button.setAttribute('role','tab');button.setAttribute('aria-controls',prefix+'-'+name+'-panel');
      button.onclick=()=>this.selectView(name);button.onkeydown=event=>{if(['ArrowLeft','ArrowRight','Home','End'].includes(event.key)){event.preventDefault();const next=name==='json'?'grid':'json';if(!this.viewButtons[next].disabled){this.selectView(next);this.viewButtons[next].focus();}}};
      views.append(button);this.viewButtons[name]=button;
    }
    this.root.append(views);
    this.result=el('pre','Run a command to view bounded, typed results. BSON Extended JSON preserves dates, ObjectIds, binary and 64-bit integers. Redis values include lossless base64 previews.','native-result');
    this.result.tabIndex=0;this.result.setAttribute('aria-label','Native command result');this.root.append(this.result);
    this.gridHost=el('div',null,'native-grid');this.root.append(this.gridHost);
    for(const [name,panel] of [['json',this.result],['grid',this.gridHost]]){panel.id=prefix+'-'+name+'-panel';panel.setAttribute('role','tabpanel');panel.setAttribute('aria-labelledby',prefix+'-'+name);}
    this.viewButtons.grid.disabled=true;this.selectView('json');
    this.sync();
  }
  field(parent,label,value){const wrapper=el('label',label),input=el('input');input.value=value;input.setAttribute('aria-label',label);input.autocomplete='off';input.addEventListener('input',()=>this.sync());wrapper.append(input);parent.append(wrapper);return input;}
  button(parent,label,icon,action){const button=el('button');button.type='button';button.title=label;button.setAttribute('aria-label',label);button.append(lucide(icon));button.onclick=action;parent.append(button);return button;}
  sync(){if(!this.editor)return;this.nextBatch=null;if(this.nextButton)this.nextButton.disabled=true;this.state.database=this.database.value;this.state.collection=this.collection?.value??'';this.state.commandText=this.editor.value;this.changed();}
  signature(){return JSON.stringify([this.profile.id,this.profile.name,this.database.value,this.collection?.value??'',this.editor.value]);}
  retain(bytes){this.account(this.disposed?0:bytes+(this.stringEditor?256*1024:0));}
  get dirty(){return !!(this.stringEditor?.dirty||this.stringEditor?.uncertain);}
  canClose(){return this.stringEditor?.canClose()??true;}
  updateProfile(profile){if(this.stringEditor&&JSON.stringify(profile)!==JSON.stringify(this.profile))this.invalidate('Connection configuration changed. Draft retained; reopen the workspace and reload before editing.');this.profile=profile;}
  lockStringTarget(){const locked=!!this.stringEditor||!!this.operation||this.disposed||!!this.unavailable;this.database.disabled=locked;this.runButton.disabled=locked;this.streamExamples&&(this.streamExamples.disabled=locked);for(const button of [this.stringButton,this.hashButton,this.listButton,this.setButton])if(button)button.disabled=locked;}
  openValueEditor(kind){
    if(!['string','hash','list','set'].includes(kind))throw new Error('Unsupported Redis editor');
    const hash=kind==='hash',list=kind==='list',set=kind==='set';
    if(this.stringEditor||this.operation||this.disposed||this.unavailable)return;
    try{this.account(this.displayBytes+256*1024);}catch(e){this.status.textContent=e.message;return;}
    let key='',field='',listIndex='0';try{const command=JSON.parse(this.editor.value);if(Array.isArray(command)&&(list?['LINDEX','LSET','LLEN','LRANGE']:hash?['HGET','HSET','HDEL','HEXISTS','HSCAN','HLEN']:['GET','GETRANGE','TYPE','STRLEN']).includes(command[0]?.toUpperCase())){key=command[1]??'';if(['HGET','HSET','HDEL','HEXISTS'].includes(command[0]?.toUpperCase()))field=command[2]??'';if(list&&['LINDEX','LSET'].includes(command[0]?.toUpperCase()))listIndex=String(command[2]??'0');}}catch{}
    let member='';if(set)try{const command=JSON.parse(this.editor.value);if(Array.isArray(command)&&['SISMEMBER','SADD','SREM','SCARD','SSCAN'].includes(command[0]?.toUpperCase())){key=command[1]??'';if(['SISMEMBER','SADD','SREM'].includes(command[0]?.toUpperCase()))member=command[2]??'';}}catch{}
    const Editor=set?RedisSetEditor:RedisStringEditor;
    this.stringEditor=new Editor({key,...(set?{member}:list?{listIndex}:hash?{field}:{}),run:(command,expectedTargetRevision)=>this.run(false,{command,expectedTargetRevision}),readOnly:()=>this.profile.readOnly!==false,changed:()=>this.changed(),close:()=>{this.stringEditor.dispose();this.stringEditor=null;this.lockStringTarget();this.retain(this.displayBytes);this.changed();}});
    this.editor.after(this.stringEditor.root);this.lockStringTarget();this.stringEditor.key.focus();
  }
  mount(host){host.replaceChildren(this.root);}
  unmount(){this.root.remove();}
  selectView(name){
    if(name==='grid'&&!this.gridModel)return;
    this.viewName=name;this.result.hidden=name!=='json';this.gridHost.hidden=name!=='grid';
    for(const [key,button] of Object.entries(this.viewButtons)){button.setAttribute('aria-selected',String(key===name));button.tabIndex=key===name?0:-1;}
    if(name==='grid'&&!this.grid)this.grid=new DataGridView(this.gridHost,this.gridModel,{displayOnly:true,rowActions:false});
  }
  showResult(result){
    let receiptOnly=false,text=JSON.stringify(result,null,2),cost=new TextEncoder().encode(text).length*6+(Array.isArray(result.entries)?result.entries.length*256*16:0);
    try{this.retain(cost);}catch(error){
      if(!['stream','pipeline','redis_value','transaction'].includes(result.kind))throw error;
      // Reserve was admitted before dispatch. Never lose delivered IDs merely because the UI cannot retain payloads.
      const receipt={kind:'stream',truncated:true,truncationReason:'browser_memory_limit',entries:[],automaticAcknowledgement:false,
        notice:'Payloads omitted because the browser result allowance is full. Delivery IDs/outcome are retained. These messages are not fully processed; inspect pending state and complete values before any explicit ACK. Do not blindly rerun.'};
      for(const key of ['operation','outcome','target','deliveredIds','deletedPendingIds','deletedPendingIdsReported','nextCursor','scanComplete','acknowledgementRequired','pendingDeliveryStateChanged','value'])if(Object.hasOwn(result,key))receipt[key]=result[key];
      if(result.kind==='pipeline'){
        receipt.kind='pipeline';delete receipt.automaticAcknowledgement;receipt.atomic=false;receipt.rollbackSupported=false;
        receipt.notice='Values omitted under the browser memory allowance. Per-command receipts retained. Pipelines are not atomic; reconcile unknown/partial outcomes before any retry.';
        receipt.entries=(result.entries||[]).map(entry=>({index:entry.index,command:entry.command,state:entry.state,valueOmitted:true}));
        for(const key of ['dispatchedCount','unknownCount','rejectedCount'])if(Object.hasOwn(result,key))receipt[key]=result[key];
      }
      if(result.kind==='redis_value'){
        receipt.kind='redis_value';delete receipt.automaticAcknowledgement;
        receipt.notice='Values omitted under the browser memory allowance. Command outcome retained; reconcile uncertain mutations before retrying.';
        receipt.entries=(result.entries||[]).map(entry=>({index:entry.index,valueOmitted:true}));
      }
      if(result.kind==='transaction'){
        receipt.kind='transaction';delete receipt.automaticAcknowledgement;
        receipt.notice='Transaction values omitted under the browser allowance. Inspect per-command outcomes; never retry uncertain writes automatically.';
        receipt.entries=(result.entries||[]).map(entry=>({index:entry.index,state:entry.state,...(entry.value==='OK'?{value:'OK'}:{valueOmitted:true})}));
        for(const key of ['reason','executed','atomic','rollbackSupported'])if(Object.hasOwn(result,key))receipt[key]=result[key];
      }
      receiptOnly=true;result=receipt;text=JSON.stringify(result,null,2);cost=new TextEncoder().encode(text).length*6;
      if(cost>RECEIPT_RESERVE)throw new Error('Stream receipt exceeds its reserved UI allowance; reconcile pending entries before retrying.');
      this.retain(cost);
    }
    this.displayBytes=cost;this.displayTruncated=!!result.truncated;
    const model=!receiptOnly&&Array.isArray(result.entries)?nativeGridModel(result.entries):null;
    this.result.textContent=text;this.viewButtons.grid.disabled=!model;
    if(model){
      if(this.gridModel){Object.assign(this.gridModel,model);this.grid?.updateData(null);}
      else this.gridModel=model;
      this.gridHost.title=model.omittedColumns?'Grid limited to 256 fields; inspect JSON for additional fields.':'Typed values; [missing] is distinct from null. Read-only display.';
    }else{this.grid?.destroy();this.grid=null;this.gridModel=null;}
    this.selectView(model?this.viewName:'json');
  }
  async run(next=false,supplied=null){
    if(this.operation||this.disposed||this.unavailable||(this.stringEditor&&!supplied))return;
    const continuation=next?this.nextBatch:null;if(next&&(!continuation||continuation.signature!==this.signature()))return;
    try{this.retain(this.displayBytes+RECEIPT_RESERVE);}catch(error){this.status.textContent='Not started · '+error.message;return{ok:false,error:error.message};}
    const operation={cancelled:false,id:null};this.operation=operation;const started=performance.now();
    this.runButton.disabled=true;this.cancelButton.disabled=false;this.root.setAttribute('aria-busy','true');
    if(this.streamExamples)this.streamExamples.disabled=true;
    const update=()=>this.status.textContent=(operation.cancelled?'Cancelling… ':'Running · ')+Math.floor((performance.now()-started)/1000)+'s';
    update();const timer=setInterval(update,1000);
    try{
      this.sync();if(new TextEncoder().encode(this.editor.value).length>131072)throw new Error('Command exceeds 128 KiB.');
      const command=supplied?.command??JSON.parse(this.editor.value);
      operation.signature=this.signature();if(continuation)command.cursor=continuation.cursor;
      const input={connectionId:this.profile.id,connectionName:this.profile.name,database:this.database.value,command};
      if(supplied?.expectedTargetRevision)input.expectedTargetRevision=supplied.expectedTargetRevision;
      if(this.collection?.value)input.collection=this.collection.value;
      const review=await this.api('/native/prepare','POST',input);operation.reviewId=review.id;
      operation.targetRevision=review.targetRevision;
      if(supplied){if(!review.targetRevision)throw new Error('Server lacks revision-bound native value editing. Reload an updated application.');input.expectedTargetRevision=review.targetRevision;}
      if(operation.cancelled||this.disposed)throw new Error('Cancelled before execution');
      if(review.mutation&&!await this.confirmMutation(review,operation))throw new Error('Cancelled before execution · no changes submitted');
      if(operation.cancelled||this.disposed)throw new Error('Cancelled before execution');
      operation.writeSubmitted=!!review.mutation;
      const job=review.mutation?await this.api('/native/apply','POST',{planId:review.id}):await this.api('/native/execute','POST',input);
      operation.id=job.id;
      if(operation.cancelled||this.disposed)await this.api('/jobs/'+job.id+'/cancel','POST',{});
      for(;;){
        const current=await this.api('/jobs/'+job.id);
        if(terminal.has(current.state)&&current.finished){
          operation.settled=true;
          operation.result=current.result;
          if(current.state!=='complete'){
            if(current.result&&!this.disposed){this.showResult(current.result);this.selectView('json');}
            throw new Error(current.error||current.state);
          }
          if(!operation.cancelled&&!this.disposed){
            this.showResult(current.result);this.status.textContent='Complete'+(this.displayTruncated?' · truncated':'')+' · '+Math.floor((performance.now()-started)/1000)+'s';
            if(current.result.kind==='change_stream'&&current.result.nextCursor&&!current.result.requiresRestart&&operation.signature===this.signature())this.nextBatch={cursor:current.result.nextCursor,signature:operation.signature};
            if(current.result.kind==='change_stream')this.status.textContent+=' · '+current.result.stopReason+' · '+(current.result.requiresRestart?'History gap: review before starting again':'Read next batch explicitly; no subscription retained');
            if(current.result.kind==='stream'&&current.result.acknowledgementRequired)this.status.textContent+=' · '+current.result.deliveredIds.length+' delivered IDs · acknowledgement is a separate reviewed command; inspect complete payloads first';
          }else if(!this.disposed&&['stream','pipeline','redis_value','transaction'].includes(current.result?.kind)){
            this.showResult(current.result);this.selectView('json');this.status.textContent='Cancellation requested · received command receipts retained; inspect outcomes before retrying';
          }else this.status.textContent='Cancelled · previous results retained';
          return{ok:!operation.cancelled,result:current.result,targetRevision:operation.targetRevision,uncertain:operation.writeSubmitted&&!['acknowledged','conflict','not_started'].includes(current.result?.outcome)};
        }
        await new Promise(resolve=>setTimeout(resolve,250));
      }
    }catch(error){if(operation.id&&!operation.cancelled&&!operation.settled)await this.cancel();if(!this.disposed)this.status.textContent=error.message;return{ok:false,error:error.message,result:operation.result,uncertain:operation.writeSubmitted&&!['acknowledged','conflict','not_started','rollback_acknowledged'].includes(operation.result?.outcome)};}
    finally{
      clearInterval(timer);
      if(operation.id)await this.api('/jobs/'+operation.id,'DELETE').catch(()=>{});
      if(operation.reviewId)await this.api('/native/reviews/'+operation.reviewId,'DELETE').catch(()=>{});
      this.operation=null;this.runButton.disabled=this.disposed||!!this.unavailable;this.cancelButton.disabled=true;this.root.removeAttribute('aria-busy');
      if(this.streamExamples)this.streamExamples.disabled=this.disposed||!!this.unavailable;
      if(this.nextButton)this.nextButton.disabled=this.disposed||!!this.unavailable||!this.nextBatch||this.nextBatch.signature!==this.signature();
      if(this.unavailable)this.status.textContent=this.unavailable;
      this.lockStringTarget();this.retain(this.displayBytes);
    }
  }
  confirmMutation(review,operation){
    return new Promise(resolve=>{
      const dialog=el('dialog',null,'test-result native-review');dialog.setAttribute('aria-label','Review native database change');
      dialog.append(el('h2',review.destructive?'Review destructive database change':'Review database change'));
      dialog.append(el('p',review.transactionNotice));
      dialog.append(el('pre',JSON.stringify(review.target,null,2)),el('pre',JSON.stringify(review.after.nativeCommand,null,2)));
      const countdown=el('p');countdown.setAttribute('role','status');dialog.append(countdown);
      const footer=el('div',null,'actions'),apply=el('button','Apply once'),cancel=el('button','Cancel');footer.append(apply,cancel);dialog.append(footer);
      let completed=false;const finish=approved=>{if(completed)return;completed=true;clearInterval(timer);operation.dismiss=null;dialog.close();dialog.remove();resolve(approved);};
      const update=()=>{const seconds=Math.max(0,Math.ceil((review.expiresAt-Date.now())/1000));countdown.textContent='Review expires in '+seconds+'s';if(!seconds)finish(false);};
      const timer=setInterval(update,1000);operation.dismiss=()=>finish(false);
      apply.onclick=()=>finish(true);cancel.onclick=()=>finish(false);dialog.addEventListener('cancel',event=>{event.preventDefault();finish(false);});
      document.body.append(dialog);dialog.showModal();cancel.focus();update();
    });
  }
  async cancel(){const operation=this.operation;if(!operation||operation.cancelled)return;operation.cancelled=true;operation.dismiss?.();this.cancelButton.disabled=true;if(operation.id)try{await this.api('/jobs/'+operation.id+'/cancel','POST',{});}catch(error){this.status.textContent='Cancellation not confirmed: '+error.message;}}
  invalidate(message){this.unavailable=message;this.nextBatch=null;if(this.nextButton)this.nextButton.disabled=true;if(this.streamExamples)this.streamExamples.disabled=true;this.runButton.disabled=true;this.stringEditor?.invalidate(message);this.lockStringTarget();this.cancel();this.status.textContent=message;}
  dispose(){this.disposed=true;this.nextBatch=null;this.cancel();this.stringEditor?.dispose();this.stringEditor=null;this.grid?.destroy();this.grid=null;this.gridModel=null;this.unmount();this.account(0);}
}
