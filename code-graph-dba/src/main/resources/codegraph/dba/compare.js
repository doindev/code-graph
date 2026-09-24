const el=(tag,text,className)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(className)n.className=className;return n;};
const labels={source_only:'Only in source',different:'Different',destination_only:'Only in destination',identical:'Identical',unsupported:'Unsupported / blocked'};
const modes=[['upsert','Insert and update'],['insert','Insert missing'],['replace','Replace all rows'],['mirror','Exact mirror']];
function select(options,value,label){const n=el('select');n.setAttribute('aria-label',label);for(const [id,text]of options){const o=el('option',text);o.value=id;n.append(o);}n.value=value;return n;}
function field(label,input){const n=el('label',undefined,'compare-field');n.append(el('span',label),input);return n;}
function checkbox(label,checked,change){const n=el('label',undefined,'compare-check'),input=el('input');input.type='checkbox';input.checked=checked;input.onchange=()=>change(input.checked);n.append(input,el('span',label));return n;}
function button(text,action,primary=false){const n=el('button',text,primary?'compare-primary':'');n.type='button';n.onclick=action;return n;}
function textArea(text,label){const n=el('textarea');n.value=text;n.readOnly=true;n.wrap='off';n.spellcheck=false;n.setAttribute('aria-label',label);return n;}
function definition(o){if(!o)return 'Object does not exist';if(o.ddl)return o.ddl+(o.oracleBodyDdl?'\n/\n'+o.oracleBodyDdl+'\n/':'');if(o.nativeDdl)return o.nativeDdl;if(o.columns)return ['TABLE '+o.schema+'.'+o.name,...o.columns.map(c=>'  '+c.name+' '+c.type+(c.nullable?'':' NOT NULL')+(c.default?' DEFAULT '+c.default:'')),...(o.constraints??[]).map(c=>c.name+': '+c.definition),...(o.foreignKeys??[]).map(c=>'FOREIGN KEY '+c.name+' REFERENCES '+c.schema+'.'+c.table)].join('\n');return JSON.stringify(o.fields??o,null,2);}

export class DatabaseCompare {
  constructor({api,profiles,close,notify,csrf}){
    Object.assign(this,{api,profiles,close,notify,csrf});this.root=el('section',undefined,'database-compare');this.root.setAttribute('aria-label','Database Compare');
    this.step=0;this.jobs=new Set();this.typeIds=new Set();this.dataOptions=new Map();this.selection=new Map();this.objects=[];this.catalog=[];this.targets={source:{},destination:{}};this.settings={dataMode:'none',sequenceMode:'advance',syncSequences:false,destructiveSchema:false};this.closed=false;this.busy=false;this.detailToken=0;
    this.render();
  }
  mount(host){if(!host.contains(this.root))host.replaceChildren(this.root);}
  unmount(){this.root.classList.remove('compare-expanded');}
  act(action){return async()=>{this.error='';try{await action();}catch(e){if(!this.closed){this.error=e.message;this.render();}}};}
  async job(submitted){
    this.jobs.add(submitted.id);if(submitted.comparisonId)this.id=submitted.comparisonId;
    if(this.closed){if(this.id)await this.api('/compare/'+this.id,'DELETE').catch(()=>{});await this.api('/jobs/'+submitted.id+'/cancel','POST',{}).catch(()=>{});}
    try{for(;;){const state=await this.api('/jobs/'+submitted.id);
      if(this.progressNode){const elapsed=state.started?Math.max(0,Math.floor(((state.finished||Date.now())-state.started)/1000)):0;this.progressNode.textContent=(state.progress||state.state)+(state.started?' · '+elapsed+'s elapsed':'');}
      if(state.finished){if(state.state!=='complete')throw Error(state.error||'Comparison failed');return state.result;}
      await new Promise(r=>setTimeout(r,300));}
    }finally{this.jobs.delete(submitted.id);await this.api('/jobs/'+submitted.id,'DELETE').catch(()=>{});}
  }
  async dispose(keepalive=false){
    if(this.closed)return;this.closed=true;this.detailToken++;this.root.classList.remove('compare-expanded');this.root.replaceChildren();
    if(keepalive){const headers={'Content-Type':'application/json','X-Dba-CSRF':this.csrf()};if(this.id)fetch('/api/dba/compare/'+this.id,{method:'DELETE',headers,credentials:'same-origin',keepalive:true}).catch(()=>{});for(const id of this.jobs)fetch('/api/dba/jobs/'+id+'/cancel',{method:'POST',headers,body:'{}',credentials:'same-origin',keepalive:true}).catch(()=>{});return;}
    await Promise.allSettled([...this.jobs].map(id=>this.api('/jobs/'+id+'/cancel','POST',{})));if(this.id)await this.api('/compare/'+this.id,'DELETE').catch(()=>{});
  }
  render(){
    if(this.closed)return;this.detailToken++;this.root.replaceChildren();const head=el('header',undefined,'compare-heading');head.append(el('h2','Database Compare'));
    const expand=button(this.root.classList.contains('compare-expanded')?'Restore':'Expand',()=>{this.root.classList.toggle('compare-expanded');expand.textContent=this.root.classList.contains('compare-expanded')?'Restore':'Expand';});head.append(expand);
    const steps=el('nav',undefined,'compare-steps');steps.setAttribute('aria-label','Comparison steps');
    ['Databases','Objects & options','Review differences','Generated script'].forEach((name,index)=>{const b=button((index+1)+'. '+name,this.act(async()=>{if(index>this.step)return;if(index<2&&this.id){await this.api('/compare/'+this.id,'DELETE');this.id=null;this.objects=[];this.selection.clear();}this.step=index;this.render();}));b.disabled=this.busy||index>this.step;b.setAttribute('aria-current',index===this.step?'step':'false');steps.append(b);});
    this.progressNode=el('p',this.busy?'Working…':'','compare-progress');this.progressNode.setAttribute('role','status');this.progressNode.setAttribute('aria-live','polite');
    const error=el('p',this.error??'','compare-error');error.setAttribute('role','alert');error.hidden=!this.error;
    this.body=el('div',undefined,'compare-body');this.footer=el('footer',undefined,'compare-footer');this.root.append(head,steps,this.progressNode,error,this.body,this.footer);
    if(this.step===0)this.targetsScreen();else if(this.step===1)this.optionsScreen();else if(this.step===2)this.resultsScreen();else this.scriptScreen();
    if(this.busy)for(const control of this.body.querySelectorAll('input,select,button'))control.disabled=true;
    this.footer.append(button('Cancel',this.act(async()=>{await this.dispose();this.close();})));
  }
  targetsScreen(){
    const grid=el('div',undefined,'compare-targets');
    for(const side of ['source','destination']){
      const state=this.targets[side],panel=el('section',undefined,'compare-card');panel.append(el('h3',side==='source'?'Source':'Destination'));
      const profiles=this.profiles().filter(p=>!p.transport||p.transport==='jdbc');const connection=select([['','Choose connection…'],...profiles.map(p=>[p.id,p.name])],state.connectionId??'',side+' connection');
      const database=select([['','Connection default'],...(state.databases??[]).map(n=>[n,n])],state.database??'',side+' database');
      const schema=select([['','Choose schema…'],...(state.schemas??[]).map(n=>[n,n])],state.schema??'',side+' schema');schema.disabled=!!state.allSchemas;
      const status=el('p',state.testing?(state.validating?'Testing connectivity…':'Loading databases and schemas…'):state.error||(state.verified?'Connected · '+state.engine:(state.schema||state.allSchemas?'Ready to validate.':state.engine?'Choose a schema.':'Select a connection.')),'compare-target-status');status.setAttribute('role','status');
      const changed=async(name,value)=>{if(state[name]!==value){this.catalog=[];this.dataOptions.clear();this.sequenceOptions?.clear();this.typesInitialized=false;}state[name]=value;state.receipt=null;state.error='';if(name==='connectionId'){state.database='';state.schema='';state.databases=[];state.schemas=[];}if(name==='database')state.schema='';await this.testTarget(side);};
      connection.onchange=this.act(()=>changed('connectionId',connection.value));database.onchange=this.act(()=>changed('database',database.value));schema.onchange=this.act(()=>changed('schema',schema.value));
      panel.append(field('Connection',connection),field('Database / catalog',database),field('Schema',schema),checkbox('All user schemas',!!state.allSchemas,this.actValue(value=>changed('allSchemas',value))),status);
      grid.append(panel);
    }
    this.body.append(grid,el('p','Choose object types will test both selected targets before continuing. Changes flow from source to destination; comparison generates a script for you to review.','compare-note'));
    const next=button('Choose object types',this.act(()=>this.chooseObjectTypes()),true);
    next.disabled=this.busy||!this.targets.source.connectionId||!this.targets.destination.connectionId||this.targets.source.testing||this.targets.destination.testing;this.footer.append(next);
  }
  async chooseObjectTypes(){
    this.busy=true;this.render();
    try{
      const verified=await Promise.all(['source','destination'].map(side=>this.testTarget(side,true)));if(this.closed)return;
      const a=this.targets.source,b=this.targets.destination;
      if(!verified.every(Boolean)){this.error=['source','destination'].filter((side,i)=>!verified[i]).map(side=>(side==='source'?'Source: ':'Destination: ')+(this.targets[side].error||'Choose a schema or All user schemas.')).join(' ');return;}
      if(a.engine!==b.engine)throw Error('Choose connections using the same database engine.');
      if(!!a.allSchemas!==!!b.allSchemas)throw Error('Choose the same scope mode on both sides.');
      if(!this.typesInitialized){this.typeIds=new Set((a.objectTypes??[]).map(x=>x.id));this.typesInitialized=true;}this.step=1;
    }finally{this.busy=false;this.render();}
  }
  actValue(action){return value=>this.act(()=>action(value))();}
  async testTarget(side,validate=false){
    const state=this.targets[side];if(!state.connectionId)return;const token=(state.token??0)+1;state.token=token;state.testing=true;state.validating=validate;state.verified=false;state.receipt=null;state.error='';this.render();
    try{const result=await this.job(await this.api('/compare/test','POST',{connectionId:state.connectionId,database:state.database??'',schema:state.schema??'',allSchemas:!!state.allSchemas}));if(this.closed||state.token!==token)return;Object.assign(state,result);state.verified=validate&&!!state.receipt;if(!state.supported)state.error='This engine has no enabled script-generation adapter. Its objects can be inspected for coverage.';return !!state.receipt;}
    catch(e){if(state.token===token)state.error=e.message;return false;}finally{if(state.token===token){state.testing=false;this.render();}}
  }
  optionsScreen(){
    const layout=el('div',undefined,'compare-options'),types=el('section',undefined,'compare-card');types.append(el('h3','Object types'));
    types.append(button('Select all',()=>{this.typeIds=new Set(this.targets.source.objectTypes.map(x=>x.id));this.render();}),button('Clear all',()=>{this.typeIds.clear();this.render();}));
    for(const kind of this.targets.source.objectTypes??[])types.append(checkbox(kind.label,this.typeIds.has(kind.id),value=>{value?this.typeIds.add(kind.id):this.typeIds.delete(kind.id);this.catalog=[];this.render();}));
    const options=el('section',undefined,'compare-card');options.append(el('h3','Data and sequence options'));
    const mode=select([['none','Structure only — no data'],...modes],this.settings.dataMode,'Default table data mode');mode.onchange=()=>{this.settings.dataMode=mode.value;this.render();};
    options.append(field('Default data mode',mode),checkbox('Sync sequence values',this.settings.syncSequences,value=>this.settings.syncSequences=value));
    const seq=select([['advance','Safe advancement'],['exact','Exact captured source state']],this.settings.sequenceMode,'Default sequence mode');seq.onchange=()=>this.settings.sequenceMode=seq.value;options.append(field('Sequence mode',seq),el('p','Structure only compares object definitions without table data. Choose a data mode to include selected tables. Sequence definitions are included when Sequences is selected; synchronizing their values is a separate option.','compare-note'));
    const loadData=button('Load objects and choose table data',this.act(async()=>{this.busy=true;this.render();try{const result=await this.job(await this.api('/compare/catalog','POST',{sourceReceipt:this.targets.source.receipt,objectTypes:[...this.typeIds]}));this.catalog=result.objects;this.optionPage=0;}finally{this.busy=false;this.render();}}));loadData.disabled=this.busy||this.settings.dataMode==='none';options.append(loadData);
    layout.append(types,options);this.body.append(layout);
    if(this.catalog.length){const list=el('section',undefined,'compare-card');list.append(el('h3','Objects · Include data per table'));
      const offset=this.optionPage??0;for(const object of this.catalog.slice(offset,offset+100)){const row=el('div',undefined,'compare-object-option');row.append(el('span',object.schema+'.'+object.name+' · '+object.kind));
        if(object.kind==='tables'){
          let option=this.dataOptions.get(object.id);if(!option){option={id:object.id,includeData:false,dataMode:''};this.dataOptions.set(object.id,option);}
          const include=checkbox('Include data',option.includeData,v=>option.includeData=v);include.querySelector('input').disabled=object.dataSupported===false||this.settings.dataMode==='none';include.title=object.dataReason??'';if(object.dataSupported===false)option.includeData=false;row.append(include);
          const key=select([['','Automatic matching key'],...(object.keys??[]).map(k=>[k.name,(k.primary?'Primary key: ':'Unique key: ')+k.columns.join(', ')])],option.key??'','Matching key for '+object.name);key.disabled=this.settings.dataMode==='none';key.onchange=()=>option.key=key.value;row.append(key);const mode=select([['','Use default'],...modes],option.dataMode??'','Data mode for '+object.name);mode.disabled=this.settings.dataMode==='none';mode.onchange=()=>option.dataMode=mode.value;row.append(mode);
        }list.append(row);}
      const pages=el('div');if(offset>0)pages.append(button('Previous objects',()=>{this.optionPage=offset-100;this.render();}));if(offset+100<this.catalog.length)pages.append(button('Next objects',()=>{this.optionPage=offset+100;this.render();}));list.append(pages);this.body.append(list);
    }
    const compare=button('Compare',this.act(()=>this.compare()),true);compare.disabled=this.busy||!this.typeIds.size;this.footer.append(compare);
  }
  async compare(){
    this.busy=true;this.render();
    try{if(this.id)await this.api('/compare/'+this.id,'DELETE');this.id=null;this.selection.clear();this.objects=[];
      const result=await this.job(await this.api('/compare/start','POST',{sourceReceipt:this.targets.source.receipt,destinationReceipt:this.targets.destination.receipt,objectTypes:[...this.typeIds],dataMode:this.settings.dataMode,syncSequences:this.settings.syncSequences,tableData:this.settings.dataMode!=='none'&&this.typeIds.has('tables')?[...this.dataOptions.values()]:[]}));
      if(this.closed)return;this.id=result.comparisonId;this.revision=result.revision;let offset=0;
      do{const page=await this.api('/compare/'+this.id+'/results?offset='+offset+'&limit=200');this.counts=page.counts;this.objects.push(...page.objects);offset=page.nextOffset;}while(offset!==undefined&&!this.closed);
      for(const object of this.objects){this.selection.set(object.id,new Set(object.supported?object.changes.filter(c=>!c.destructive).map(c=>c.id):[]));}
      this.step=2;
    }finally{this.busy=false;this.render();}
  }
  request(){return{revision:this.revision,...this.settings,objects:this.objects.filter(o=>o.supported&&o.status!=='destination_only'&&(this.selection.get(o.id)?.size||this.settings.dataMode!=='none'&&this.dataOptions.get(o.id)?.includeData||o.kind==='sequences'&&(this.sequenceOptions?.get(o.id)?.syncValues??this.settings.syncSequences))).map(o=>{const option={id:o.id,changes:[...(this.selection.get(o.id)??[])],...(this.dataOptions.get(o.id)??{}),...(this.sequenceOptions?.get(o.id)??{}),...(this.settings.dataMode==='none'?{includeData:false}:{})};if(!option.dataMode)delete option.dataMode;return option;})};}
  resultsScreen(){
    const summary=el('div',undefined,'compare-summary');for(const [status,count]of Object.entries(this.counts??{}))summary.append(el('span',labels[status]+': '+count));
    const tools=el('div',undefined,'compare-result-tools'),search=el('input');search.type='search';search.placeholder='Find an object…';search.value=this.search??'';search.setAttribute('aria-label','Search comparison objects');
    const filter=select([['','All results'],...Object.entries(labels)],this.filter??'','Result status');filter.onchange=()=>{this.filter=filter.value;this.resultOffset=0;this.render();};search.oninput=()=>{this.search=search.value;this.resultOffset=0;this.populateTree();};
    tools.append(search,filter,checkbox('Allow destructive schema changes',this.settings.destructiveSchema,v=>{this.settings.destructiveSchema=v;this.render();}));
    const layout=el('div',undefined,'compare-review'),tree=el('div',undefined,'compare-tree');tree.setAttribute('aria-label','Comparison differences');this.tree=tree;
    const divider=el('div',undefined,'compare-divider');divider.tabIndex=0;divider.setAttribute('role','separator');divider.setAttribute('aria-label','Resize comparison object tree');divider.setAttribute('aria-orientation','vertical');
    const resize=x=>{const box=layout.getBoundingClientRect();layout.style.setProperty('--compare-tree-width',Math.max(200,Math.min(box.width*.65,x-box.left))+'px');};
    divider.onpointerdown=e=>divider.setPointerCapture(e.pointerId);divider.onpointermove=e=>{if(divider.hasPointerCapture(e.pointerId))resize(e.clientX);};divider.onkeydown=e=>{if(['ArrowLeft','ArrowRight'].includes(e.key)){e.preventDefault();resize(divider.getBoundingClientRect().left+(e.key==='ArrowLeft'?-20:20));}};
    this.details=el('section',undefined,'compare-details');this.details.append(el('p','Select an object to inspect its differences.'));layout.append(tree,divider,this.details);
    this.body.append(summary,el('p','Review definition and data changes below. Destination-only objects are preserved; ownership, grants and server configuration are outside this comparison.','compare-note'),tools,layout);this.populateTree();
    this.footer.append(el('span',this.request().objects.reduce((n,o)=>n+o.changes.length,0)+' selected changes'),button('Compare again',this.act(()=>this.compare())),button('Generate script',this.act(()=>this.generate()),true));if(this.busy)for(const b of this.footer.querySelectorAll('button'))b.disabled=true;
  }
  populateTree(){
    if(!this.tree)return;this.tree.replaceChildren();const matching=this.objects.filter(o=>(!this.filter||o.status===this.filter)&&(!this.search||(o.schema+'.'+o.name).toLowerCase().includes(this.search.toLowerCase()))),offset=this.resultOffset??0,visible=new Set(matching.slice(offset,offset+250).map(o=>o.id));
    if(matching.length>250){const pages=el('div',undefined,'compare-tree-pages');if(offset>0)pages.append(button('Previous objects',()=>{this.resultOffset=offset-250;this.populateTree();}));pages.append(el('span',(offset+1)+'–'+Math.min(offset+250,matching.length)+' of '+matching.length));if(offset+250<matching.length)pages.append(button('Next objects',()=>{this.resultOffset=offset+250;this.populateTree();}));this.tree.append(pages);}
    for(const status of Object.keys(labels)){
      if(this.filter&&this.filter!==status)continue;
      const objects=this.objects.filter(o=>visible.has(o.id)&&o.status===status&&(!this.search||(o.schema+'.'+o.name).toLowerCase().includes(this.search.toLowerCase())));if(!objects.length)continue;
      const group=el('details');group.open=status!=='identical';group.append(el('summary',labels[status]+' ('+objects.length+')'));
      for(const kind of [...new Set(objects.map(o=>o.kind))]){
        const category=el('details');category.open=true;category.append(el('summary',kind.replaceAll('_',' ')));
        for(const object of objects.filter(o=>o.kind===kind)){
          const row=el('div',undefined,'compare-object-row'),chosen=this.selection.get(object.id)??new Set();
          const check=el('input');check.type='checkbox';check.setAttribute('aria-label','Include '+object.schema+'.'+object.name);check.checked=object.changes.length>0&&object.changes.every(c=>chosen.has(c.id));check.indeterminate=chosen.size>0&&!check.checked;check.disabled=!object.supported||!object.changes.length;
          check.onchange=()=>{this.selection.set(object.id,new Set(check.checked?object.changes.filter(c=>this.settings.destructiveSchema||!c.destructive).map(c=>c.id):[]));this.invalidateScript();this.populateTree();this.showObject(object.id);};
          const open=button(object.schema+'.'+object.name,this.act(()=>this.showObject(object.id)));open.title=object.reason||labels[object.status];row.append(check,open);if(object.data)row.append(el('span','Data','compare-badge'));category.append(row);
        }group.append(category);
      }this.tree.append(group);
    }
  }
  invalidateScript(){if(this.artifact)this.api('/compare/artifacts/'+this.artifact.artifactId,'DELETE').catch(()=>{});this.artifact=null;}
  async showObject(id){
    const token=++this.detailToken;const object=await this.api('/compare/'+this.id+'/objects/'+id);if(this.closed||token!==this.detailToken)return;this.currentObject=object;this.details.replaceChildren();this.details.append(el('h3',object.schema+'.'+object.name));
    if(object.reason)this.details.append(el('p',object.reason,'compare-note'));
    const tabs=el('nav',undefined,'compare-detail-tabs'),content=el('div',undefined,'compare-detail-content');tabs.setAttribute('aria-label','Object detail views');
    const show=async name=>{content.replaceChildren();for(const b of tabs.children)b.setAttribute('aria-selected',String(b.textContent===name));
      if(name==='Details')this.changeDetails(object,content);
      else if(name==='Source / Destination')this.definitionDiff(object,content);
      else if(name==='Data differences')await this.dataDetails(object,content);
      else{const result=await this.api('/compare/'+this.id+'/plan','POST',this.request());if(token!==this.detailToken)return;content.append(textArea(result.statements.join(';\n\n'),'Planned destination changes'));}
    };
    for(const name of ['Details','Source / Destination',...(object.data?['Data differences']:[]),'Planned changes']){const b=button(name,this.act(()=>show(name)));b.setAttribute('role','tab');tabs.append(b);}this.details.append(tabs,content);await show('Details');
  }
  changeDetails(object,content){
    const selected=this.selection.get(object.id)??new Set();if(!object.changes.length)content.append(el('p',object.status==='destination_only'?'Destination-only object will be preserved.':'No definition changes.'));
    for(const change of object.changes){const row=el('div',undefined,'compare-change');const pick=checkbox(change.action+' '+change.section+' · '+change.name+(change.attribute?' · '+change.attribute:''),selected.has(change.id),value=>{value?selected.add(change.id):selected.delete(change.id);this.selection.set(object.id,selected);this.invalidateScript();this.populateTree();});
      pick.querySelector('input').disabled=!object.supported||change.destructive&&!this.settings.destructiveSchema;
      row.append(pick);if(change.destructive)row.append(el('span','Destructive schema change','compare-badge'));const detail=el('div',undefined,'compare-before-after');detail.append(el('pre',this.describe(change.before)),el('pre',this.describe(change.after)));row.append(detail);content.append(row);}
    if(object.data){const option=this.dataOptions.get(object.id);content.append(checkbox('Include reviewed table data',option.includeData,v=>{option.includeData=v;this.invalidateScript();}));
      const mode=select([['','Use default'],...modes],option.dataMode??'','Data mode');mode.onchange=()=>{option.dataMode=mode.value;this.invalidateScript();};content.append(field('Data action',mode),checkbox('Synchronize identity values where supported',!!option.syncIdentity,v=>option.syncIdentity=v));}
    if(object.kind==='sequences'){if(!this.sequenceOptions)this.sequenceOptions=new Map();let option=this.sequenceOptions.get(object.id);if(!option){option={syncValues:this.settings.syncSequences,sequenceMode:this.settings.sequenceMode};this.sequenceOptions.set(object.id,option);}
      if(object.stateReason)content.append(el('p',object.stateReason,'compare-note'));
      const modes=object.stateModes??[];const sync=checkbox('Sync sequence values',option.syncValues,v=>{option.syncValues=v;this.invalidateScript();});sync.querySelector('input').disabled=!modes.length;content.append(sync);
      if(modes.length){const mode=select(modes.map(m=>[m,m==='advance'?'Safe advancement':'Exact captured state']),modes.includes(option.sequenceMode)?option.sequenceMode:modes[0],'Sequence synchronization mode');option.sequenceMode=mode.value;mode.onchange=()=>{option.sequenceMode=mode.value;this.invalidateScript();};content.append(mode);}
    }
  }
  describe(value){if(value===undefined||value===null)return 'Absent';if(typeof value==='object')return Object.entries(value).filter(([k])=>!['id','oid','selection','dependencies'].includes(k)).map(([k,v])=>k+': '+(typeof v==='object'?JSON.stringify(v):v)).join('\n');return String(value);}
  definitionDiff(object,content){
    const pair=el('div',undefined,'compare-code-pair'),left=el('div'),right=el('div');left.append(el('h4','Source'));right.append(el('h4','Destination'));const a=el('pre'),b=el('pre');a.tabIndex=b.tabIndex=0;
    const linesA=definition(object.source).split('\n'),linesB=definition(object.destination).split('\n'),changed=[];
    for(let i=0;i<Math.max(linesA.length,linesB.length);i++){const differs=linesA[i]!==linesB[i];const lineA=el('span',(i+1)+'  '+(linesA[i]??'')+'\n',differs?'compare-diff-line':''),lineB=el('span',(i+1)+'  '+(linesB[i]??'')+'\n',differs?'compare-diff-line':'');a.append(lineA);b.append(lineB);if(differs)changed.push(lineA);}
    let syncing=false;for(const [x,y]of [[a,b],[b,a]])x.onscroll=()=>{if(syncing)return;syncing=true;y.scrollTop=x.scrollTop;y.scrollLeft=x.scrollLeft;requestAnimationFrame(()=>syncing=false);};
    let index=-1;const toolbar=el('div');toolbar.append(button('Previous difference',()=>{if(changed.length){index=(index-1+changed.length)%changed.length;a.scrollTop=changed[index].offsetTop-a.offsetTop;}}),button('Next difference',()=>{if(changed.length){index=(index+1)%changed.length;a.scrollTop=changed[index].offsetTop-a.offsetTop;}}));
    left.append(a);right.append(b);pair.append(left,right);content.append(toolbar,pair);
  }
  async dataDetails(object,content,offset=0,status=''){
    const data=await this.api('/compare/'+this.id+'/objects/'+object.id+'/data?offset='+offset+'&limit=50&status='+encodeURIComponent(status));if(this.closed||this.currentObject?.id!==object.id||!content.isConnected)return;content.replaceChildren();
    const counts=el('p',Object.entries(data.counts).map(([k,v])=>labels[k]+': '+v).join(' · '));content.append(counts);if(data.note)content.append(el('p',data.note));
    const mode=this.dataOptions.get(object.id)?.dataMode||this.settings.dataMode;
    content.append(el('p','Planned: '+(mode==='replace'?data.counts.source_only+data.counts.different+data.counts.identical:data.counts.source_only)+' inserts · '+(['upsert','mirror'].includes(mode)?data.counts.different:0)+' updates · '+(mode==='replace'?data.counts.destination_only+data.counts.different+data.counts.identical:mode==='mirror'?data.counts.destination_only:0)+' deletes'));
    const filter=select([['','All rows'],...Object.entries(labels).filter(([k])=>k!=='unsupported')],status,'Row difference filter');filter.onchange=this.act(()=>this.dataDetails(object,content,0,filter.value));content.append(filter);
    const scroll=el('div',undefined,'compare-data-scroll'),table=el('table'),head=el('thead'),tr=el('tr');tr.append(el('th','Status'),el('th','Side'));for(const col of data.columns)tr.append(el('th',col));head.append(tr);table.append(head);const body=el('tbody');
    for(const row of data.rows){for(const side of ['source','destination']){const tr=el('tr');tr.append(el('td',labels[row.status]),el('td',side));for(let i=0;i<data.columns.length;i++){const cell=row[side]?.[i],other=row[side==='source'?'destination':'source']?.[i];const value=cell?cell.value===null?'NULL':cell.value:'—';const td=el('td',String(value).slice(0,500),JSON.stringify(cell)!==JSON.stringify(other)?'compare-diff-cell':'');td.title=String(value)+(cell?.truncated?'… (preview; complete value retained for generation)':'');if(cell?.truncated)td.textContent+='…';tr.append(td);}body.append(tr);}}table.append(body);scroll.append(table);content.append(scroll);
    if(offset>0)content.append(button('Previous rows',this.act(()=>this.dataDetails(object,content,Math.max(0,offset-50),status))));if(data.nextOffset!==undefined)content.append(button('Next rows',this.act(()=>this.dataDetails(object,content,data.nextOffset,status))));
  }
  async generate(){
    this.busy=true;this.render();try{const request=this.request();if(!request.objects.length)throw Error('Select at least one change or data operation.');this.artifact=await this.job(await this.api('/compare/'+this.id+'/generate','POST',request));if(this.closed)return;this.preview=await this.api('/compare/artifacts/'+this.artifact.artifactId+'/preview');this.step=3;}finally{this.busy=false;this.render();}
  }
  scriptScreen(){
    this.body.classList.add('compare-script');this.body.append(el('p',this.preview.previewTruncated?'Preview only · '+this.preview.bytes+' bytes in complete script. Copy and Save use the complete file.':'Complete script · '+this.preview.bytes+' bytes'),textArea(this.preview.sql,'Generated destination SQL'));
    this.footer.append(button('Copy',this.act(async()=>{const response=await fetch('/api/dba/compare/artifacts/'+this.artifact.artifactId+'/download',{credentials:'same-origin'});if(!response.ok)throw Error('Script expired or could not be downloaded.');const text=await response.text();if(new TextEncoder().encode(text).length!==this.artifact.bytes)throw Error('Incomplete script download; use Save.');await navigator.clipboard.writeText(text);this.notify('Complete script copied.');})),button('Save',()=>{const a=el('a');a.href='/api/dba/compare/artifacts/'+this.artifact.artifactId+'/download';a.download='database-compare.sql';document.body.append(a);a.click();a.remove();},true));
  }
}
