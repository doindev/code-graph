import {lucide} from './tree-icons.js';
import {DataGridView} from './data-grid.js';

const terminal=new Set(['complete','failed','cancelled']);
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
    Object.assign(this,{api,profile,state,changed,account});this.disposed=false;this.operation=null;
    this.root=el('section',null,'native-workspace');
    const header=el('div',null,'native-workspace-header');
    header.append(el('strong',profile.transport==='mongodb'?'MongoDB · Extended JSON':'Redis · argument vector'));
    header.append(el('span','Exact target · bounded results · writes require review · no automatic retries','muted'));
    this.database=this.field(header,'Database',state.database??profile.nativeOptions?.database??(profile.transport==='redis'?'0':''));
    if(profile.transport==='mongodb')this.collection=this.field(header,'Collection',state.collection??'');
    this.root.append(header);
    const toolbar=el('div',null,'native-workspace-actions');
    this.runButton=this.button(toolbar,'Run native command','play',()=>this.run());
    this.cancelButton=this.button(toolbar,'Cancel current operation','square',()=>this.cancel());this.cancelButton.disabled=true;
    this.status=el('span','Ready · no query has run','native-status');this.status.setAttribute('role','status');toolbar.append(this.status);
    this.root.append(toolbar);
    this.editor=el('textarea',null,'native-command');this.editor.setAttribute('aria-label','Native command JSON');this.editor.spellcheck=false;this.editor.wrap='off';
    if(profile.transport==='redis')this.editor.title='Redis argument array. Keys and values may use {"base64":"AP8="}; commands, flags, numeric controls and patterns stay text. Binary values: 64 KiB maximum; keys/fields: 8 KiB.';
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
  sync(){if(!this.editor)return;this.state.database=this.database.value;this.state.collection=this.collection?.value??'';this.state.commandText=this.editor.value;this.changed();}
  mount(host){host.replaceChildren(this.root);}
  unmount(){this.root.remove();}
  selectView(name){
    if(name==='grid'&&!this.gridModel)return;
    this.viewName=name;this.result.hidden=name!=='json';this.gridHost.hidden=name!=='grid';
    for(const [key,button] of Object.entries(this.viewButtons)){button.setAttribute('aria-selected',String(key===name));button.tabIndex=key===name?0:-1;}
    if(name==='grid'&&!this.grid)this.grid=new DataGridView(this.gridHost,this.gridModel,{displayOnly:true,rowActions:false});
  }
  showResult(result){
    const text=JSON.stringify(result,null,2),bytes=new TextEncoder().encode(text).length;
    this.account(bytes*6+(Array.isArray(result.entries)?result.entries.length*256*16:0)); // Include worst-case sparse grid cell references.
    const model=Array.isArray(result.entries)?nativeGridModel(result.entries):null;
    this.result.textContent=text;this.viewButtons.grid.disabled=!model;
    if(model){
      if(this.gridModel){Object.assign(this.gridModel,model);this.grid?.updateData(null);}
      else this.gridModel=model;
      this.gridHost.title=model.omittedColumns?'Grid limited to 256 fields; inspect JSON for additional fields.':'Typed values; [missing] is distinct from null. Read-only display.';
    }else{this.grid?.destroy();this.grid=null;this.gridModel=null;}
    this.selectView(model?this.viewName:'json');
  }
  async run(){
    if(this.operation||this.disposed||this.unavailable)return;
    const operation={cancelled:false,id:null};this.operation=operation;const started=performance.now();
    this.runButton.disabled=true;this.cancelButton.disabled=false;this.root.setAttribute('aria-busy','true');
    const update=()=>this.status.textContent=(operation.cancelled?'Cancelling… ':'Running · ')+Math.floor((performance.now()-started)/1000)+'s';
    update();const timer=setInterval(update,1000);
    try{
      this.sync();if(new TextEncoder().encode(this.editor.value).length>131072)throw new Error('Command exceeds 128 KiB.');
      const command=JSON.parse(this.editor.value);
      const input={connectionId:this.profile.id,connectionName:this.profile.name,database:this.database.value,command};
      if(this.collection?.value)input.collection=this.collection.value;
      const review=await this.api('/native/prepare','POST',input);operation.reviewId=review.id;
      if(operation.cancelled||this.disposed)throw new Error('Cancelled before execution');
      if(review.mutation&&!await this.confirmMutation(review,operation))throw new Error('Cancelled before execution · no changes submitted');
      if(operation.cancelled||this.disposed)throw new Error('Cancelled before execution');
      const job=review.mutation?await this.api('/native/apply','POST',{planId:review.id}):await this.api('/native/execute','POST',input);
      operation.id=job.id;
      if(operation.cancelled||this.disposed)await this.api('/jobs/'+job.id+'/cancel','POST',{});
      for(;;){
        const current=await this.api('/jobs/'+job.id);
        if(terminal.has(current.state)&&current.finished){
          if(current.state!=='complete')throw new Error(current.error||current.state);
          if(!operation.cancelled&&!this.disposed){
            this.showResult(current.result);this.status.textContent='Complete'+(current.result.truncated?' · truncated':'')+' · '+Math.floor((performance.now()-started)/1000)+'s';
          }else this.status.textContent='Cancelled · previous results retained';
          break;
        }
        await new Promise(resolve=>setTimeout(resolve,250));
      }
    }catch(error){if(operation.id&&!operation.cancelled)await this.cancel();if(!this.disposed)this.status.textContent=error.message;}
    finally{
      clearInterval(timer);
      if(operation.id)await this.api('/jobs/'+operation.id,'DELETE').catch(()=>{});
      if(operation.reviewId)await this.api('/native/reviews/'+operation.reviewId,'DELETE').catch(()=>{});
      this.operation=null;this.runButton.disabled=this.disposed||!!this.unavailable;this.cancelButton.disabled=true;this.root.removeAttribute('aria-busy');
      if(this.unavailable)this.status.textContent=this.unavailable;
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
  invalidate(message){this.unavailable=message;this.runButton.disabled=true;this.cancel();this.status.textContent=message;}
  dispose(){this.disposed=true;this.cancel();this.grid?.destroy();this.grid=null;this.gridModel=null;this.unmount();this.account(0);}
}
