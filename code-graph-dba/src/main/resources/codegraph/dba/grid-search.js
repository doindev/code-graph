import {lucide} from './tree-icons.js';

let sequence=0,workerBytes=0,workerCount=0;
function remember(history,value){return [value,...history.filter(item=>item!==value)].slice(0,10);}
function button(label,icon,action,text){
  const node=document.createElement('button');node.type='button';node.className='grid-icon';
  node.title=label;node.setAttribute('aria-label',label);node.onclick=action;
  if(text)node.textContent=text;else node.append(lucide(icon));return node;
}
function divider(){const node=document.createElement('span');node.className='grid-find-divider';node.setAttribute('role','separator');node.append(lucide('ellipsis-vertical'));return node;}
function work(payload){
  const bytes=payload.cells.reduce((n,c)=>n+c.text.length*2+80,0);
  if(bytes>8*1024*1024||workerBytes+bytes>16*1024*1024||workerCount>=4)throw Error('Search allowance busy or full. Reduce the row limit or finish another grid search.');
  const worker=new Worker('/dba/grid-search-worker.js',{type:'module'});workerBytes+=bytes;workerCount++;
  let settled=false,timer,rejectTask;
  const cleanup=()=>{if(settled)return false;settled=true;clearTimeout(timer);worker.terminate();workerBytes-=bytes;workerCount--;return true;};
  const promise=new Promise((resolve,reject)=>{
    rejectTask=reject;timer=setTimeout(()=>{if(cleanup())reject(Error('Search took too long. Simplify the regular expression or reduce the row limit.'));},1500);
    worker.onmessage=({data})=>{if(cleanup())data.error?reject(Error(data.error)):resolve(data.result);};
    worker.onerror=()=>{if(cleanup())reject(Error('Search worker failed. Close and reopen Find and Replace to retry.'));};
    worker.postMessage(payload);
  });
  return{promise,cancel(){if(cleanup())rejectTask(new DOMException('Search cancelled','AbortError'));}};
}

// Result-lifetime preferences live in view.state; workers/listeners belong to the mounted view.
export class GridSearch {
  constructor(view){
    this.view=view;this.state=view.state.search??= {open:false,expanded:false,text:'',query:'',replacement:'',searchHistory:[],replacementHistory:[],caseSensitive:false,regex:false,wholeWord:false,selectedOnly:false,filterMatches:false};
    this.matches=[];this.byCell=new Map();this.current=-1;this.revision=0;
    this.build();this.outside=e=>{if(!this.panel.contains(e.target)&&!this.history?.list.contains(e.target))this.closeHistory();};
    document.addEventListener('pointerdown',this.outside,true);
    this.observer=new ResizeObserver(()=>this.position());this.observer.observe(view.scroll);this.observer.observe(this.panel);
    this.signature=this.snapshot();this.update();if(this.state.open&&this.state.query)this.queue();
  }
  build(){
    const s=this.state,panel=document.createElement('section');panel.className='grid-find';panel.setAttribute('role','region');panel.setAttribute('aria-label','Find and Replace in loaded rows');this.panel=panel;
    this.expand=button('Show replacement controls','chevron-right',()=>{s.expanded=!s.expanded;this.update();});
    this.expand.classList.add('grid-find-expand');
    const rows=document.createElement('div');rows.className='grid-find-rows';
    const top=document.createElement('div');top.className='grid-find-row';
    this.search=this.combo('Find in loaded rows','Search history','text','searchHistory',()=>this.searchNow());
    top.append(this.search.host,divider());this.toggles={};
    for(const [key,label,icon,text] of [
      ['caseSensitive','Match case',null,'Aa'],['regex','Use regular expression',null,'.*'],
      ['wholeWord','Match whole word',null,'ab'],['selectedOnly','Search selected rows only','list']]){
      const control=button(label,icon,()=>{s[key]=!s[key];this.searchNow(false);},text);
      if(key==='wholeWord')control.classList.add('grid-find-word');this.toggles[key]=control;top.append(control);
    }
    this.previous=button('Previous match','chevron-up',()=>this.move(-1));
    this.next=button('Next match','chevron-down',()=>this.move(1));
    this.only=button('Show matching rows only','filter',()=>{s.filterMatches=!s.filterMatches;this.update();this.view.renderTable();if(!s.query&&s.text)this.searchNow(false);});
    const close=button('Close Find and Replace','x',()=>this.close());
    top.append(divider(),this.previous,this.next,this.only,close);
    this.bottom=document.createElement('div');this.bottom.className='grid-find-row grid-find-replacement';
    this.replacement=this.combo('Replacement text','Replacement history','replacement','replacementHistory',()=>this.replace(false));
    this.replaceOne=button('Replace current match','replace',()=>this.replace(false));
    this.replaceAll=button('Replace all matches','replace-all',()=>this.replace(true));
    this.bottom.append(this.replacement.host,divider(),this.replaceOne,this.replaceAll);
    this.status=document.createElement('div');this.status.className='grid-find-status';this.status.setAttribute('role','status');this.status.setAttribute('aria-live','polite');
    rows.append(top,this.bottom,this.status);panel.append(this.expand,rows);this.view.stage.append(panel);
    panel.onkeydown=event=>{
      if(event.key==='Escape'){event.preventDefault();event.stopPropagation();if(this.history)this.closeHistory();else this.close();}
      if(event.key==='F3'){event.preventDefault();this.move(event.shiftKey?-1:1);}
    };
  }
  combo(label,historyLabel,key,historyKey,submit){
    const host=document.createElement('div');host.className='grid-find-combo';
    const input=document.createElement('input');input.type='text';input.maxLength=key==='text'?512:8192;input.spellcheck=false;input.autocomplete='off';input.value=this.state[key];
    input.setAttribute('role','combobox');input.setAttribute('aria-label',label);input.setAttribute('aria-autocomplete','list');input.setAttribute('aria-expanded','false');input.placeholder=label;
    const id='grid-find-history-'+ ++sequence;input.setAttribute('aria-controls',id);
    const caret=button(historyLabel,'chevron-down',()=>this.toggleHistory({host,input,caret,id,key,historyKey,submit}));
    caret.setAttribute('aria-haspopup','listbox');caret.setAttribute('aria-expanded','false');
    input.oninput=()=>{this.state[key]=input.value;this.closeHistory();if(key==='text'){this.stop();this.state.query='';this.clearMatches();this.message='';this.view.renderTable();this.update();}else if(this.replacing){this.stop();this.message='Replacement changed; apply it again.';this.update();}};
    input.onkeydown=event=>{
      if(event.key==='ArrowDown'&&(!this.history||event.altKey)){event.preventDefault();this.toggleHistory({host,input,caret,id,key,historyKey,submit});return;}
      if(this.history?.input===input&&['ArrowDown','ArrowUp','Enter'].includes(event.key)){
        event.preventDefault();const buttons=[...this.history.list.children];
        if(event.key==='Enter'){buttons[this.history.index]?.click();return;}
        this.history.index=(this.history.index+(event.key==='ArrowDown'?1:buttons.length-1))%buttons.length;
        for(const [index,node]of buttons.entries())node.setAttribute('aria-selected',String(index===this.history.index));
        input.setAttribute('aria-activedescendant',buttons[this.history.index].id);buttons[this.history.index].scrollIntoView({block:'nearest'});return;
      }
      if(event.key==='Enter'){event.preventDefault();submit();}
    };
    host.append(input,caret);return{host,input,caret};
  }
  toggleHistory(combo){
    const was=this.history?.input===combo.input;this.closeHistory();if(was)return;
    const entries=this.state[combo.historyKey];if(!entries.length){this.message='No previous entries for this grid.';this.update();return;}
    const list=document.createElement('div');list.className='grid-find-history';list.id=combo.id;list.setAttribute('role','listbox');list.setAttribute('aria-label',combo.caret.title);
    for(const [index,value] of entries.entries()){
      const item=document.createElement('button');item.type='button';item.id=combo.id+'-'+index;item.setAttribute('role','option');item.setAttribute('aria-selected',String(index===0));item.textContent=value||'(empty string)';item.title=value;
      item.onclick=()=>{this.state[combo.key]=value;combo.input.value=value;this.closeHistory();combo.input.focus();if(combo.key==='text')combo.submit();else if(this.replacing){this.stop();this.message='Replacement changed; apply it again.';this.update();}};list.append(item);
    }
    document.body.append(list);this.history={...combo,list,index:0};combo.input.setAttribute('aria-expanded','true');combo.caret.setAttribute('aria-expanded','true');combo.input.setAttribute('aria-activedescendant',list.firstChild.id);combo.input.focus();
    list.onkeydown=event=>{if(event.key==='Escape'){event.preventDefault();this.closeHistory();combo.input.focus();}};this.position();
  }
  closeHistory(){if(!this.history)return;this.history.list.remove();this.history.input.setAttribute('aria-expanded','false');this.history.input.removeAttribute('aria-activedescendant');this.history.caret.setAttribute('aria-expanded','false');this.history=null;}
  snapshot(){
    const {draft}=this.view.state;
    return{rows:this.view.result.rows,changes:draft.changes,added:draft.added,size:draft.changes.size,length:draft.length,columns:this.view.columns().map(c=>c.id).join(','),selection:this.state.selectedOnly?[...draft.selection].sort((a,b)=>a-b).join(','):''};
  }
  changed(snapshot=this.signature){const now=this.snapshot();return !snapshot||Object.keys(now).some(key=>now[key]!==snapshot[key]);}
  refreshIfChanged(){if(!this.state.open||!this.state.query||!this.changed())return;this.signature=this.snapshot();this.message='';this.queue();}
  queue(){clearTimeout(this.queued);this.queued=setTimeout(()=>{this.queued=null;void this.scan(false);},100);}
  cells(){
    const view=this.view,draft=view.state.draft,columns=view.columns(),cells=[];let bytes=0;
    for(let row=0;row<draft.length;row++){
      if(draft.deleted(row)||this.state.selectedOnly&&!draft.selection.has(row))continue;
      for(const column of columns){
        const value=draft.cell(row,column);if(value.kind!=='value'||value.value==null)continue;
        const text=view.cellText(row,column);bytes+=text.length*2+80;
        if(bytes>8*1024*1024)throw Error('Search allowance exceeded. Reduce the row limit.');
        cells.push({row,column:column.id,text,editable:this.canReplace(row,column)&&text===String(value.value)});
      }
    }
    return cells;
  }
  canReplace(row,column){return this.view.rowActions&&!this.view.draftActions&&!this.view.displayOnly&&!this.view.result.cellsTruncated&&!this.view.result.grid?.uncertain&&this.view.state.draft.canEdit(row,column.id);}
  stop(){this.revision++;clearTimeout(this.queued);this.queued=null;this.task?.cancel();this.task=null;this.busy=false;this.replacing=false;}
  clearMatches(){this.matches=[];this.byCell.clear();this.current=-1;this.view.searchRows=null;}
  searchNow(navigate=true){this.state.query=this.state.text;this.message='';return this.scan(navigate);}
  async scan(navigate=false,replacing=false,all=false){
    // A Tab move may already have opened the next editor. Recompute when it closes.
    if(this.view.finishCell&&!navigate&&!replacing){this.signature=null;return;}
    this.stop();const generation=this.revision,s=this.state;this.closeHistory();this.view.finishCell?.();this.signature=this.snapshot();const signature=this.signature;
    if(!s.query){this.clearMatches();this.update();this.view.renderTable();return;}
    this.busy=true;this.replacing=replacing;this.error='';this.update();
    try{
      const current=replacing&&!all?this.matches[this.current]:null;
      if(replacing&&!all&&!current)throw Error('Find a match before replacing it.');
      const task=work({query:s.query,regex:s.regex,caseSensitive:s.caseSensitive,wholeWord:s.wholeWord,cells:this.cells(),replace:replacing,replacement:s.replacement,current});this.task=task;
      const found=await task.promise;if(this.disposed||generation!==this.revision)return;
      this.task=null;this.busy=false;this.replacing=false;
      if(this.changed(signature)||this.view.finishCell){this.signature=null;if(!this.view.finishCell)this.queue();this.update();return;}
      s.searchHistory=remember(s.searchHistory,s.query);
      if(replacing){
        const columns=new Map(this.view.columns().map(c=>[c.id,c]));
        this.view.state.draft.setMany(found.changes.map(c=>({index:c.row,column:columns.get(c.column),value:{kind:'value',value:c.text}})));
        s.replacementHistory=remember(s.replacementHistory,s.replacement);
        this.message=found.changes.length+' cell'+(found.changes.length===1?'':'s')+' staged. Use Save to write; Cancel to revert.'+(found.skipped?' '+found.skipped+' read-only matches skipped.':'');
        this.signature=this.snapshot();await this.scan(false);return;
      }
      const previous=this.matches[this.current];this.matches=found.matches;this.byCell.clear();
      for(const match of this.matches){const key=match.row+':'+match.column;if(!this.byCell.has(key))this.byCell.set(key,[]);this.byCell.get(key).push(match);}
      this.current=previous?this.matches.findIndex(m=>m.row===previous.row&&m.column===previous.column&&m.start===previous.start):-1;
      if(this.current<0&&this.matches.length)this.current=0;
      this.project();this.view.renderTable();if(navigate)this.reveal();this.update();
    }catch(error){if(generation!==this.revision||this.disposed)return;this.busy=false;this.replacing=false;this.task=null;if(error.name!=='AbortError'){this.error=error.message;this.clearMatches();this.view.renderTable();}this.update();}
  }
  project(){this.view.searchRows=this.state.open&&this.state.query&&this.state.filterMatches&&!this.error?[...new Set(this.matches.map(m=>m.row))]:null;}
  move(direction){if(this.busy)return;if(this.state.text!==this.state.query){void this.searchNow();return;}if(!this.matches.length)return;this.current=(this.current+direction+this.matches.length)%this.matches.length;this.message='';this.reveal();this.update();}
  reveal(){
    const match=this.matches[this.current];if(!match)return;this.view.revealRow(match.row);
    const columns=this.view.columns(),at=columns.findIndex(c=>c.id===match.column),left=52+columns.slice(0,at).reduce((n,c)=>n+(this.view.state.widths.get(c.id)??180),0),width=this.view.state.widths.get(match.column)??180,scroll=this.view.scroll;
    if(left<scroll.scrollLeft+52)scroll.scrollLeft=left-52;else if(left+width>scroll.scrollLeft+scroll.clientWidth)scroll.scrollLeft=Math.max(0,left+Math.min(width,scroll.clientWidth-52)-scroll.clientWidth);
    // Keep the active cell above the floating panel, without moving keyboard focus.
    const cell=scroll.querySelector('[data-row-index="'+match.row+'"] [data-column-id="'+CSS.escape(match.column)+'"]');
    if(cell){const rect=cell.getBoundingClientRect(),panel=this.panel.getBoundingClientRect();if(rect.right>panel.left&&rect.bottom>panel.top)scroll.scrollTop+=rect.bottom-panel.top+4;}
    this.view.paintRows?.();
  }
  highlight(cell,row,column,text){
    const ranges=this.byCell.get(row+':'+column.id);if(!this.state.open||!ranges?.length)return;
    cell.replaceChildren();let end=0;const current=this.matches[this.current];
    for(const range of ranges){cell.append(document.createTextNode(text.slice(end,range.start)));const mark=document.createElement('mark');mark.className='grid-find-match';mark.classList.toggle('grid-find-current',range===current);mark.textContent=text.slice(range.start,range.end);if(range.start===range.end)mark.classList.add('grid-find-empty-match');cell.append(mark);end=range.end;}
    cell.append(document.createTextNode(text.slice(end)));
  }
  replace(all){
    if(this.busy||this.view.controller?.busy)return;
    this.view.controller?.stopRefresh();if(!this.view.controller&&this.view.refresh){this.view.refresh.clear();this.view.refresh.seconds=0;this.view.refresh.requested=false;this.view.refresh.emit();}
    return this.scan(false,true,all);
  }
  toggle(){if(this.state.open)this.close();else this.open();}
  open(){this.state.open=true;this.update();this.search.input.focus();this.search.input.select();if(this.state.text)this.searchNow(false);}
  close(){
    this.stop();this.closeHistory();Object.assign(this.state,{open:false,query:'',expanded:false,caseSensitive:false,regex:false,wholeWord:false,selectedOnly:false,filterMatches:false});
    this.clearMatches();this.error=this.message='';this.update();this.view.renderTable();this.view.findButton?.focus({preventScroll:true});
  }
  position(){
    if(this.disposed)return;const scroll=this.view.scroll,right=Math.max(0,scroll.offsetWidth-scroll.clientWidth)+8,bottom=Math.max(0,scroll.offsetHeight-scroll.clientHeight)+8;
    this.panel.style.right=right+'px';this.panel.style.bottom=bottom+'px';this.panel.style.maxWidth='calc(100% - '+(right+8)+'px)';
    scroll.style.paddingBottom=this.state.open?this.panel.offsetHeight+12+'px':'';
    if(this.history){const {input,list}=this.history,rect=input.getBoundingClientRect();list.style.width=Math.min(Math.max(160,rect.width),innerWidth-16)+'px';list.style.left=Math.max(8,Math.min(rect.left,innerWidth-list.offsetWidth-8))+'px';list.style.top=Math.max(8,rect.top-list.offsetHeight-3)+'px';}
  }
  update(){
    const s=this.state;this.panel.hidden=!s.open;this.panel.setAttribute('aria-busy',String(this.busy));this.view.findButton?.setAttribute('aria-expanded',String(s.open));
    if(this.view.controller?.busy)this.closeHistory();
    this.bottom.hidden=!s.expanded;this.expand.replaceChildren(lucide(s.expanded?'chevron-down':'chevron-right'));this.expand.setAttribute('aria-expanded',String(s.expanded));this.expand.title=s.expanded?'Hide replacement controls':'Show replacement controls';this.expand.setAttribute('aria-label',this.expand.title);
    for(const [key,control] of Object.entries(this.toggles))control.setAttribute('aria-pressed',String(s[key]));
    this.only.setAttribute('aria-pressed',String(s.filterMatches));this.project();
    this.previous.disabled=this.next.disabled=this.busy||!this.matches.length;
    const columns=new Map(this.view.columns().map(c=>[c.id,c])),editable=m=>{const c=columns.get(m.column);return c&&this.canReplace(m.row,c)&&this.view.cellText(m.row,c)===String(this.view.state.draft.cell(m.row,c).value);};
    this.replaceOne.disabled=this.busy||!!this.view.controller?.busy||!this.matches[this.current]||!editable(this.matches[this.current]);
    this.replaceAll.disabled=this.busy||!!this.view.controller?.busy||!this.matches.some(editable);
    const reason='Only verified editable cells can be replaced; changes remain staged until Save.';
    this.replaceOne.title=this.replaceOne.disabled?reason:'Replace current match';this.replaceAll.title=this.replaceAll.disabled?reason:'Replace all matches';
    this.status.classList.toggle('error',!!this.error);this.status.textContent=this.busy?'Searching loaded rows…':this.error||this.message||(s.query?(this.current<0?'0':this.current+1)+' of '+this.matches.length+' matches · loaded rows, visible columns'+(this.view.result.cellsTruncated?' · previews only':''):'Search loaded rows and visible columns. Replacements are staged, not saved.');
    this.position();
  }
  destroy(){this.disposed=true;this.stop();this.closeHistory();this.observer.disconnect();document.removeEventListener('pointerdown',this.outside,true);this.panel.remove();this.view.searchRows=null;}
}
