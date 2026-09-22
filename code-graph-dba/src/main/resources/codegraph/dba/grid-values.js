// Distinct-value picker shared by every Data Grid host. Values never become SQL text here.
const numeric=new Set([-6,5,4,-5,2,3]),boolean=new Set([-7,16]);
export function decimalKey(value){
  const match=String(value).trim().match(/^([+-]?)(\d*)(?:\.(\d*))?(?:e([+-]?\d+))?$/i);
  if(!match||!(match[2]+(match[3]??'')))return String(value);
  let digits=(match[2]+(match[3]??'')).replace(/^0+/,'')||'0',scale=(match[3]?.length??0)-Number(match[4]??0);
  if(!Number.isSafeInteger(scale)||Math.abs(scale)>8192)return String(value);
  if(digits==='0')return '0';
  if(scale<0){digits+='0'.repeat(-scale);scale=0;}
  if(scale>=digits.length)digits='0'.repeat(scale-digits.length+1)+digits;
  const cut=digits.length-scale;let text=scale?digits.slice(0,cut)+'.'+digits.slice(cut):digits;
  text=text.replace(/(\.\d*?)0+$/,'$1').replace(/\.$/,'');
  return(match[1]==='-'?'-':'')+text;
}
export function valueKey(value,type){return value===null?'null':JSON.stringify(numeric.has(type)?decimalKey(value):boolean.has(type)?['true','1'].includes(String(value).toLowerCase())?true:false:String(value));}
function compare(a,b,type){
  if(a===null||b===null)return a===b?0:a===null?-1:1;
  if(numeric.has(type)){
    let x=decimalKey(a),y=decimalKey(b),negative=x.startsWith('-'),other=y.startsWith('-');
    if(negative!==other)return negative?-1:1;if(negative){x=x.slice(1);y=y.slice(1);}
    const [xi,xf='']=x.split('.'),[yi,yf='']=y.split('.'),width=Math.max(xf.length,yf.length);
    const order=xi.length-yi.length||(xi<yi?-1:xi>yi?1:0)||(xf.padEnd(width,'0')<yf.padEnd(width,'0')?-1:xf.padEnd(width,'0')>yf.padEnd(width,'0')?1:0);
    return negative?-order:order;
  }
  if(boolean.has(type))return Number(['true','1'].includes(String(a).toLowerCase()))-Number(['true','1'].includes(String(b).toLowerCase()));
  return String(a).localeCompare(String(b));
}
function display(value){return value===null?'NULL':value===''?'(empty string)':value==='NULL'?'"NULL"':String(value);}
export function retainedValues(result,column){
  if(result.cellsTruncated)throw Error('The retained result contains truncated previews. Read complete values from the server.');
  const values=new Map();
  for(const row of result.rows??[]){const value=row[column.source]??null,key=valueKey(value,column.jdbcType),item=values.get(key);if(item)item.count++;else values.set(key,{value,count:1});}
  return [...values.values()].sort((a,b)=>compare(a.value,b.value,column.jdbcType));
}

// Serialize lookup jobs so cancellation is acknowledged before a replacement uses the same grid.
class ValueRequests{
  constructor(api){this.api=api;this.generation=0;this.tail=Promise.resolve();}
  invalidate(){this.generation++;if(this.job)void this.api('/jobs/'+this.job+'/cancel','POST',{}).catch(()=>{});return this.generation;}
  run(path,body,generation){
    const work=async()=>{
      if(generation!==this.generation)return null;
      const job=await this.api(path,'POST',body);this.job=job.id;const started=Date.now();let terminal=false,cancelled=false,cancelledAt=0;
      try{
        let current=job;
        while(!current.finished){
          if(generation!==this.generation&&!cancelled){cancelled=true;cancelledAt=Date.now();await this.api('/jobs/'+job.id+'/cancel','POST',{});}
          if(Date.now()-started>330000||cancelledAt&&Date.now()-cancelledAt>30000)throw Error('Value lookup completion could not be confirmed. Wait for the connection before retrying.');
          await new Promise(resolve=>setTimeout(resolve,150));current=await this.api('/jobs/'+job.id);
        }
        terminal=true;
        if(generation!==this.generation)return null;
        if(current.state!=='complete')throw Error(current.error||'Value lookup failed.');
        return current.result;
      }finally{
        this.job=null;
        if(terminal)await this.api('/jobs/'+job.id,'DELETE').catch(()=>{});
        else await this.api('/jobs/'+job.id+'/cancel','POST',{}).catch(()=>{});
      }
    };
    const result=this.tail.then(work);this.tail=result.catch(()=>{});return result;
  }
  async stop(){this.invalidate();await this.tail;}
}

export class GridValuePicker{
  constructor(view,column){
    this.view=view;this.column=column;this.context=view.result.grid;this.revision=this.context?.revision;this.closed=false;this.rowHeight=34;this.entries=[];this.selected=new Map();
    for(const value of view.state.valueSelections?.get(column.id)??[])this.selected.set(valueKey(value,column.jdbcType),value);
    this.hadAppliedValues=this.selected.size>0;
    this.preferences=(view.state.valuePreferences??=new Map()).get(column.id)??{server:true,rows:false,counts:false};
    view.state.valuePreferences.set(column.id,this.preferences);
    this.requests=new ValueRequests(view.controller?.api);this.anchor=view.host.querySelector('[data-column-id="'+CSS.escape(column.id)+'"] .grid-column-toggle');
    this.render();void this.reload();
  }
  render(){
    const dialog=document.createElement('dialog');dialog.className='grid-values-dialog';dialog.setAttribute('aria-label','Filter by value: '+this.column.label);this.dialog=dialog;
    const heading=document.createElement('div');heading.className='grid-values-heading';const title=document.createElement('h2');title.textContent='Filter by value: '+this.column.label;
    const close=document.createElement('button');close.type='button';close.textContent='×';close.setAttribute('aria-label','Close value filter');close.onclick=()=>this.close();heading.append(title,close);
    const search=document.createElement('input');search.type='search';search.placeholder='Filter values';search.setAttribute('aria-label','Filter values');search.maxLength=512;this.search=search;
    search.oninput=()=>{clearTimeout(this.timer);this.requests.invalidate();this.loading=true;this.sync();this.timer=setTimeout(()=>void this.reload(),this.preferences.server?250:0);};
    const header=document.createElement('div');header.className='grid-values-row grid-values-header';for(const text of ['','Value','Count']){const cell=document.createElement('span');cell.textContent=text;header.append(cell);}
    const list=document.createElement('div');list.className='grid-values-list';list.setAttribute('aria-label','Distinct values');list.tabIndex=0;this.list=list;list.onscroll=()=>{this.paint();if(this.more&&!this.loading&&list.scrollHeight-list.scrollTop-list.clientHeight<180)void this.loadMore();};
    const summary=document.createElement('div');summary.className='grid-values-summary';summary.setAttribute('role','status');summary.setAttribute('aria-live','polite');this.summary=summary;
    const status=document.createElement('div');status.className='grid-values-status';status.setAttribute('role','alert');this.status=status;
    const actions=document.createElement('div');actions.className='grid-values-actions';const clear=document.createElement('button');clear.type='button';clear.textContent='Clear All';this.clearButton=clear;clear.onclick=()=>{this.selected.clear();this.paint();this.sync();};
    const right=document.createElement('div');const cancel=document.createElement('button');cancel.type='button';cancel.textContent='Cancel';cancel.onclick=()=>this.close();
    const apply=document.createElement('button');apply.type='button';apply.textContent='Apply';apply.onclick=()=>void this.apply();this.applyButton=apply;right.append(cancel,apply);actions.append(clear,right);
    const options=document.createElement('div');options.className='grid-values-options';this.optionInputs=[];
    for(const [key,text] of [['server','Read from server'],['rows','Show row count'],['counts','Show distinct values count']]){
      const label=document.createElement('label'),check=document.createElement('input');check.type='checkbox';check.checked=this.preferences[key];check.onchange=()=>{this.preferences[key]=check.checked;clearTimeout(this.timer);void this.reload();};label.append(check,document.createTextNode(text));options.append(label);this.optionInputs.push(check);
    }
    dialog.append(heading,search,header,list,summary,status,actions,options);dialog.addEventListener('cancel',event=>{event.preventDefault();this.close();});
    dialog.addEventListener('close',()=>this.dispose());document.body.append(dialog);dialog.showModal();search.focus();this.sync();
    this.resizeObserver=new ResizeObserver(()=>{const width=this.list.clientWidth;if(width===this.listWidth)return;this.listWidth=width;this.resizeRows(34);this.paint();});this.resizeObserver.observe(list);
  }
  valid(){return !this.closed&&!this.view.disposed&&this.view.result.grid===this.context&&this.view.result.grid?.revision===this.revision;}
  async reload(){
    if(this.closed)return;const generation=this.requests.invalidate();this.entries=[];this.more=false;this.nextOffset=0;this.bytes=0;this.total=null;this.matching=null;this.list.scrollTop=0;this.status.textContent='';this.loading=true;this.paint();this.sync();
    try{
      if(this.preferences.server){
        if(!this.context||!this.requests.api)throw Error('Server values need an executable result context. Uncheck Read from server to use retained grid rows.');
        await this.fetch(generation);
      }else{
        const all=retainedValues(this.view.result,this.column),search=this.search.value.toLocaleLowerCase();this.total=String(all.length);
        this.entries=all.filter(entry=>display(entry.value).toLocaleLowerCase().includes(search));this.matching=String(this.entries.length);
      }
    }catch(error){if(generation===this.requests.generation&&!this.closed)this.status.textContent=error.message;}
    finally{if(generation===this.requests.generation&&!this.closed){this.loading=false;this.paint();this.sync();this.fill();}}
  }
  async fetch(generation){
    const response=await this.requests.run('/grids/'+this.context.id+'/values',{revision:this.revision,columnId:this.column.id,search:this.search.value,offset:this.nextOffset,showRowCount:this.preferences.rows,showDistinctValuesCount:this.preferences.counts},generation);
    if(!response||generation!==this.requests.generation||!this.valid())return;
    this.bytes+=JSON.stringify(response.values).length*6;
    if(this.bytes>4*1024*1024){this.more=false;this.status.textContent='The value list reached its memory allowance. Refine the search to find more values.';return;}
    const known=new Set(this.entries.map(entry=>valueKey(entry.value,this.column.jdbcType)));
    for(const entry of response.values)if(!known.has(valueKey(entry.value,this.column.jdbcType)))this.entries.push(entry);
    this.nextOffset=response.nextOffset;this.more=response.hasMore;this.total=response.totalDistinct??null;this.matching=response.matchingDistinct??null;
  }
  fill(){if(!this.closed&&this.more&&!this.loading&&this.list.scrollHeight<=this.list.clientHeight+80)void this.loadMore();}
  async loadMore(){
    if(this.loading||!this.more||this.closed)return;this.loading=true;this.sync();const generation=this.requests.generation;
    try{await this.fetch(generation);}catch(error){if(!this.closed&&generation===this.requests.generation){this.status.textContent=error.message;this.more=false;}}
    finally{if(!this.closed&&generation===this.requests.generation){this.loading=false;this.paint();this.sync();this.fill();}}
  }
  resizeRows(height){
    const row=(this.pendingScrollTop??this.list.scrollTop)/this.rowHeight;this.rowHeight=height;this.list.style.setProperty('--grid-values-row-height',height+'px');this.pendingScrollTop=row*height;
  }
  paint(){
    if(this.closed)return;
    const focus=this.list.querySelector('input:focus')?.dataset.key,top=this.pendingScrollTop??this.list.scrollTop,rowHeight=this.rowHeight,start=Math.max(0,Math.floor(top/rowHeight)-6),end=Math.min(this.entries.length,start+Math.max(30,Math.ceil(this.list.clientHeight/rowHeight)+12)),fragment=document.createDocumentFragment();
    this.pendingScrollTop=null;
    const spacer=height=>{const space=document.createElement('div');space.style.height=height+'px';space.setAttribute('aria-hidden','true');fragment.append(space);};
    spacer(start*rowHeight);
    for(let index=start;index<end;index++){
      const entry=this.entries[index],key=valueKey(entry.value,this.column.jdbcType),row=document.createElement('label');row.className='grid-values-row';row.dataset.index=index;
      const check=document.createElement('input');check.type='checkbox';check.checked=this.selected.has(key);check.dataset.key=key;check.setAttribute('aria-label','Select '+display(entry.value));
      check.onchange=()=>{if(check.checked){if(this.selected.size>=128){check.checked=false;this.status.textContent='Select at most 128 values. Existing query parameters also count toward the query limit.';return;}this.selected.set(key,entry.value);}else this.selected.delete(key);this.sync();};
      check.onkeydown=event=>{if(!['ArrowDown','ArrowUp'].includes(event.key))return;const next=index+(event.key==='ArrowDown'?1:-1);if(next<0||next>=this.entries.length)return;event.preventDefault();this.list.scrollTop=Math.max(0,(next-3)*rowHeight);this.paint();this.list.querySelector('[data-index="'+next+'"] input')?.focus({preventScroll:true});};
      const value=document.createElement('span');value.className='grid-values-text';value.textContent=display(entry.value);value.title=value.textContent;
      if([...value.textContent].length<30)value.classList.add('grid-values-text-short');
      if(entry.value===null||entry.value==='')value.classList.add('grid-values-special');
      const count=document.createElement('span');count.className='grid-values-count';count.textContent=this.preferences.counts?String(entry.count??''):'';
      row.append(check,value,count);fragment.append(row);
    }
    spacer((this.entries.length-end)*rowHeight);this.list.replaceChildren(fragment);this.list.scrollTop=top;
    // Keep a uniform measured height so wrapped short values and virtual scrolling agree.
    const height=Math.max(rowHeight,...[...this.list.querySelectorAll('.grid-values-text-short')].map(value=>Math.ceil(value.getBoundingClientRect().height)+8));
    if(height>rowHeight){this.resizeRows(height);this.paint();}
    if(focus)this.list.querySelector('input[data-key="'+CSS.escape(focus)+'"]')?.focus({preventScroll:true});
  }
  sync(){
    if(this.closed)return;
    this.applyButton.disabled=(!this.selected.size&&!this.hadAppliedValues)||this.loading||this.applying||!this.view.transform||!this.view.sourceSql;
    this.search.disabled=!!this.applying;for(const input of this.optionInputs)input.disabled=!!this.applying;
    this.list.inert=!!this.applying;this.clearButton.disabled=!!this.applying;
    const messages=[];
    if(this.preferences.rows&&this.total!==null)messages.push(this.total+' distinct values'+(this.search.value?' · '+this.matching+' matching':''));
    if(!this.preferences.server)messages.push('Retained grid rows only; unloaded rows and staged edits are excluded.');
    if(this.applying)messages.push('Applying filter…');else if(this.loading)messages.push('Loading values…');else if(!this.entries.length&&!this.status.textContent)messages.push('No values found.');
    if(this.more)messages.push('Scroll for more values.');messages.push(this.selected.size+' selected');
    this.summary.textContent=messages.join(' · ');
  }
  async apply(){
    if(this.applyButton.disabled)return;this.applying=true;this.status.textContent='';this.sync();
    try{
      await this.requests.stop();if(!this.valid())throw Error('The result changed. Reopen the value filter.');
      const values=[...this.selected.values()];
      const applied=await this.view.transform({action:'filter_values',columnId:this.column.id,columnIndex:this.column.source,columnLabel:this.column.rawLabel,jdbcType:this.column.jdbcType,columns:this.view.result.columns,values});
      if(applied===true){const selections=this.view.state.valueSelections??=new Map();if(values.length)selections.set(this.column.id,values);else selections.delete(this.column.id);this.applying=false;this.close();}
    }catch(error){if(!this.closed)this.status.textContent=error.message;}
    finally{this.applying=false;if(this.closeRequested)this.close();else this.sync();}
  }
  contextChanged(){if(!this.applying&&!this.valid())this.close();}
  close(){if(this.applying){this.closeRequested=true;this.status.textContent='Cancelling filter…';void this.view.controller?.cancel();return;}this.dialog.close();this.dispose();}
  dispose(){
    if(this.closed)return;this.closed=true;this.resizeObserver?.disconnect();clearTimeout(this.timer);void this.requests.stop();this.dialog.remove();
    if(this.view.valuePicker===this)this.view.valuePicker=null;
    if(this.anchor?.isConnected&&!this.view.disposed)this.anchor.focus({preventScroll:true});
  }
}
