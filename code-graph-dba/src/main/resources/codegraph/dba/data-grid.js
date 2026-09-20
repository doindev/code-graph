import {lucide} from './tree-icons.js';
import {GridDraft,pageSize} from './grid-state.js';
import {chooseDirty,gridSettings,exportDialog} from './grid-interactions.js';
const states=new WeakMap();
if(!document.querySelector('link[data-grid-data-style]')){const style=document.createElement('link');style.rel='stylesheet';style.href='/dba/grid-data.css';style.dataset.gridDataStyle='true';document.head.append(style);}

// Owned by the result, not its replaceable DOM view. At most one refresh is in flight.
export class ResultRefreshSchedule {
  constructor(run,notice=()=>{},clock=globalThis){this.run=run;this.notice=notice;this.clock=clock;this.seconds=0;this.pending=false;this.requested=false;this.timer=null;this.listeners=new Set();}
  subscribe(listener){this.listeners.add(listener);listener(this);return()=>this.listeners.delete(listener);}
  emit(){for(const listener of this.listeners)listener(this);}
  clear(){if(this.timer!==null)this.clock.clearTimeout(this.timer);this.timer=null;}
  arm(){this.clear();if(!this.disposed&&this.seconds)this.timer=this.clock.setTimeout(()=>{this.timer=null;this.requested=true;this.automatic=true;void this.drain();},this.seconds*1000);}
  choose(seconds){if(![0,1,5,10,15,30,60].includes(seconds))throw new Error('Unsupported refresh interval');if(this.disposed)return;this.clear();this.seconds=seconds;this.requested=true;this.automatic=false;this.emit();return this.drain();}
  async drain(){
    if(this.disposed||this.pending||!this.requested)return;this.requested=false;this.pending=true;const automatic=this.automatic;this.emit();
    try{await this.run({automatic});}catch(error){this.seconds=0;this.requested=false;if(!this.disposed)this.notice('Refresh stopped: '+error.message);}
    finally{this.pending=false;this.emit();if(!this.disposed){if(this.requested)void this.drain();else this.arm();}}
  }
  dispose(){this.disposed=true;this.clear();this.seconds=0;this.requested=false;this.listeners.clear();}
}

export class GridQueryCancelled extends Error {}

// Result-owned execution; API and result application are injected by the host.
// Unmounting a view never disposes this controller.
export class GridQueryController {
  constructor({run,api,canRun=()=>true,clock=globalThis,now=()=>performance.now(),pollMs=250,cancellationTimeoutMs=30000}){
    Object.assign(this,{run,api,canRun,clock,now,pollMs,cancellationTimeoutMs});this.listeners=new Set();this.state='idle';this.error='';this.started=0;this.busy=false;this.disposed=false;this.job=null;
    this.refresh=new ResultRefreshSchedule(({automatic})=>{if(!this.canRun(automatic))return;return this.execute({action:'refresh',automatic});},()=>{},clock);
  }
  subscribe(listener){this.listeners.add(listener);listener(this);return()=>this.listeners.delete(listener);}
  emit(){for(const listener of this.listeners)listener(this);}
  get elapsed(){return this.busy?Math.floor((this.now()-this.started)/1000):0;}
  check(){if(this.cancelRequested||this.disposed)throw new GridQueryCancelled('Query cancelled');}
  stopRefresh(){this.refresh.clear();this.refresh.seconds=0;this.refresh.requested=false;this.refresh.emit();}
  async cancel(){if(!this.busy)return;this.cancelRequested=true;this.state='cancelling';this.emit();if(!this.cancelTimer)this.cancelTimer=this.clock.setTimeout(()=>{this.uncertain=true;this.rejectCancellation?.(new Error('Cancellation could not be confirmed. Check the connection before running another query.'));},this.cancellationTimeoutMs);if(this.job&&!this.cancelSent){this.cancelSent=true;try{await this.api('/jobs/'+this.job+'/cancel','POST',{});}catch(error){this.cancelSent=false;this.error='Cancellation request failed: '+error.message;this.emit();}}}
  execute(change){
    if(this.disposed||this.busy)return Promise.resolve();if(change.action!=='refresh')this.stopRefresh();if(this.uncertain&&change.action!=='reconcile')return Promise.reject(new Error('The previous query completion is unknown. Check the connection before running another query.'));if(change.action==='reconcile')this.uncertain=false;this.refresh.clear();this.busy=true;this.cancelRequested=false;this.cancelSent=false;this.queryError=null;this.job=null;this.error='';this.state='running';this.started=this.now();this.automatic=!!change.automatic;this.emit();
    this.tick=this.clock.setInterval(()=>this.emit(),1000);
    const cancelled=new Promise((_,reject)=>this.rejectCancellation=reject);
    this.completion=(async()=>{try{await Promise.race([this.run(change,this),cancelled]);this.check();}catch(error){if(!(error instanceof GridQueryCancelled)){this.error=error.message;this.refresh.seconds=0;this.refresh.requested=false;throw error;}}finally{this.clock.clearInterval(this.tick);this.clock.clearTimeout(this.cancelTimer);this.cancelTimer=null;this.rejectCancellation=null;this.tick=null;this.busy=false;this.state='idle';this.emit();if(!this.refresh.pending)this.refresh.arm();}})();
    return this.completion;
  }
  async waitJob(job){
    this.job=job.id;let finished=false;
    try{
      if(this.cancelRequested||this.disposed)await this.cancel();
      while(true){
        if(this.uncertain)throw new Error('Query outcome is unknown');
        await new Promise(resolve=>this.clock.setTimeout(resolve,this.pollMs));
        const current=await this.api('/jobs/'+job.id);
        if(current.state==='awaiting_decision'&&!this.cancelRequested){this.queryError=current.decision?.message||'Query failed';await this.cancel();}
        if(!current.finished)continue;finished=true;
        if(this.queryError){const message=this.queryError;this.queryError=null;throw new Error(message);}
        // An acknowledged save wins a racing Cancel. The host must clear its draft.
        if(current.outcome==='commit_acknowledged'&&current.result?.saved)return current.result;
        if(current.outcome==='unknown')throw Object.assign(new Error(current.error||'Commit outcome is unknown'),{outcome:'unknown'});
        this.check();
        if(current.state!=='complete')throw Object.assign(new Error(current.error||current.state),{outcome:current.outcome});
        return current.result;
      }
    }catch(error){if(!finished){this.uncertain=true;throw new Error('Cannot confirm query completion: '+error.message);}throw error;}
    finally{if(finished){try{await this.api('/jobs/'+job.id,'DELETE');}catch{}}this.job=null;}
  }
  async submit(body,path='/query/execute'){let job;try{job=await this.api(path,'POST',body);}catch(error){if(!error.status||error.status>=500)this.uncertain=true;throw error;}return this.waitJob(job);}
  dispose(){this.disposed=true;this.refresh.dispose();this.clock.clearInterval(this.tick);this.tick=null;void this.cancel();this.listeners.clear();return(this.completion??Promise.resolve()).catch(()=>{}).then(()=>{if(this.uncertain)throw new Error('Cannot confirm grid query cancellation; retry after checking the connection.');});}
}

function svg(path,transform=''){const icon=document.createElementNS('http://www.w3.org/2000/svg','svg');icon.setAttribute('viewBox','0 0 24 24');icon.setAttribute('aria-hidden','true');icon.setAttribute('focusable','false');const p=document.createElementNS(icon.namespaceURI,'path');p.setAttribute('d',path);if(transform)p.setAttribute('transform',transform);icon.append(p);return icon;}
function iconButton(label,path,action,transform=''){const button=document.createElement('button');button.type='button';button.className='grid-icon';button.title=label;button.setAttribute('aria-label',label);button.append(svg(path,transform));button.addEventListener('click',action);return button;}
function stableColumns(result){const used=new Set();return(result.columns??[]).map((column,index)=>{let id=String(column.id??`c${index+1}`);while(used.has(id))id+='-'+(index+1);used.add(id);return{id,label:String(column.displayLabel??column.label??id),rawLabel:String(column.label??id),type:String(column.type??''),jdbcType:column.jdbcType??12,source:index};});}
function stateFor(result){let state=states.get(result);if(state)return state;const columns=stableColumns(result);state={columns,order:columns.map(c=>c.id),widths:new Map(columns.map(c=>[c.id,180])),hidden:new Set(),nullText:'NULL',dates:'database',draft:new GridDraft(result),filter:'',expanded:false,selectedRow:null,popup:{width:560,height:170}};states.set(result,state);return state;}

export class DataGridView {
  // Initial schema discovery only; ordinary refreshes preserve the existing layout.
  static resetLayout(result){states.delete(result);}
  updateData(sourceSql){this.sourceSql=sourceSql;const preview=this.command.querySelector('.grid-source-preview');preview.value=sourceSql?.replace(/[\r\n]/g,' ').trim()||'SQL unavailable — JDBC metadata result';preview.title=preview.value;if(this.viewer)this.viewer.querySelector('textarea').value=sourceSql??'';if(this.filter)this.filter.value=this.state.filter;this.sourceButton.disabled=!sourceSql;this.syncColumns();const scroll={left:this.scroll.scrollLeft,top:this.scroll.scrollTop};this.renderTable();this.scroll.scrollLeft=scroll.left;this.scroll.scrollTop=scroll.top;}
  syncColumns(){const columns=stableColumns(this.result);const ids=new Set(columns.map(c=>c.id));this.state.order=this.state.order.filter(id=>ids.has(id));for(const column of columns)if(!this.state.order.includes(column.id)){this.state.order.push(column.id);this.state.widths.set(column.id,180);}this.state.columns=columns;}
  renderQueryState(){
    const query=this.controller;if(this.disposed||!query)return;
    this.host.setAttribute('aria-busy',String(query.busy));this.interactive.inert=query.busy;this.rowControls();this.queryError.hidden=!query.error;this.queryError.textContent=query.error;if(this.result.grid?.uncertain){const reconcile=document.createElement('button');reconcile.type='button';reconcile.textContent='Reconcile with database';reconcile.disabled=query.busy;reconcile.onclick=()=>void this.transform({action:'reconcile'}).catch(error=>this.notice(error.message));this.queryError.append(reconcile);this.queryError.hidden=false;}
    if(query.busy){
      if(!this.overlay){
        this.focusBeforeQuery=document.activeElement;const focusedInside=this.host.contains(document.activeElement);
        this.closeColumnMenu();this.closeMenu();this.closeRefreshMenu();this.closeViewer();
        const overlay=document.createElement('div');overlay.className='grid-query-overlay';const panel=document.createElement('div');panel.className='grid-query-progress';
        const elapsed=document.createElement('div');elapsed.className='grid-query-elapsed';elapsed.title='Elapsed time including preparation and queueing';
        const bar=document.createElement('div');bar.className='grid-query-bar';bar.setAttribute('role','progressbar');bar.setAttribute('aria-label','Database query in progress');
        const cancel=document.createElement('button');cancel.type='button';cancel.className='grid-query-cancel';cancel.textContent='Cancel';cancel.onclick=()=>void query.cancel();panel.append(elapsed,bar,cancel);overlay.append(panel);this.view.append(overlay);this.overlay=overlay;
        if(focusedInside||!query.automatic&&document.activeElement===document.body)cancel.focus();
      }
      this.overlay.querySelector('.grid-query-elapsed').textContent=query.elapsed+'s';const cancel=this.overlay.querySelector('button');cancel.disabled=query.state==='cancelling';cancel.textContent=cancel.disabled?'Cancelling…':'Cancel';
    }else if(this.overlay){const restore=this.overlay.contains(document.activeElement);this.overlay.remove();this.overlay=null;if(restore)(this.focusBeforeQuery?.isConnected?this.focusBeforeQuery:this.sourceButton)?.focus();}
  }
  static setFilter(result,text){stateFor(result).filter=text;}
  static clearSelection(result){const state=states.get(result);if(state){state.selectedRow=null;state.draft.clearSelection();}}
  static draft(result){return stateFor(result).draft;}
  static dirty(result){return states.get(result)?.draft.dirty??false;}
  static cancelDraft(result){states.get(result)?.draft.cancel();}
  static async guard(result,save){const draft=states.get(result)?.draft;return !draft?.dirty||chooseDirty(async()=>{await save();if(draft.dirty)throw Error('Changes were not saved.');},()=>draft.cancel());}
  async saveRows(){if(!this.state.draft.dirty)return;if(!this.transform)throw Error('This result cannot save changes.');await this.transform({action:'save_rows',...this.state.draft.payload()});this.renderTable();if(this.state.draft.dirty)throw Error('Changes were not saved.');}
  openGridSettings(){gridSettings(this);}
  async exportData(){if(!this.transform||!this.result.grid)throw Error('Refresh this result to establish an export context.');if(await DataGridView.guard(this.result,()=>this.saveRows()))exportDialog(this);}
  async page(direction,limit=Number(this.pageInput.value)){pageSize(String(limit),this.result.grid?.rowCeiling??1000);if(!this.result.grid?.capabilities.page)throw Error(this.result.grid?.capabilities.pageReason||'Server paging is unavailable for this query.');await this.transform({action:'page',direction,limit});}
  async navigate(direction){
    const draft=this.state.draft,page=this.result.grid?.page??{},n=draft.length;
    if(direction==='first'){if((page.offset??0)>0)await this.page('first');this.scroll.scrollTop=0;this.paintRows?.();return;}
    if(direction==='last'){if(page.hasMore)await this.page('last');this.revealRow(draft.length-1);return;}
    if(draft.active===null)return;let index=draft.active+(direction==='previous'?-1:1);
    if(index<0||(index>=n&&page.hasMore)){await this.page(direction);index=direction==='previous'?draft.length-1:0;}
    if(index>=0&&index<draft.length){this.selectRow(index);this.revealRow(index);}
  }
  revealRow(index){const top=index*28;if(top<this.scroll.scrollTop)this.scroll.scrollTop=top;else if(top+56>this.scroll.scrollTop+this.scroll.clientHeight)this.scroll.scrollTop=Math.max(0,top+56-this.scroll.clientHeight);this.paintRows?.();}
  rowControls(){
    if(!this.navigation)return;const draft=this.state.draft,page=this.result.grid?.page??{},n=draft.length,cap=draft.capabilities;
    this.navigation.first.disabled=!n||!(this.scroll.scrollTop>1||(page.offset??0)>0);
    this.navigation.previous.disabled=draft.active===null||draft.active===0&&!(page.offset>0&&cap.page);
    this.navigation.next.disabled=draft.active===null||draft.active>=n-1&&!(page.hasMore&&cap.page);
    this.navigation.last.disabled=!n||!(page.hasMore&&cap.page||this.scroll.scrollHeight-this.scroll.scrollTop-this.scroll.clientHeight>1);
    this.addButton.disabled=!cap.insert||!!this.draftActions;this.deleteButton.disabled=!cap.delete||!draft.selection.size||!!this.draftActions;
    this.editButton.disabled=draft.active===null||!cap.edit||!!this.draftActions;
    for(const button of [this.addButton,this.deleteButton,this.editButton])if(button.disabled)button.title=cap.reason||button.getAttribute('aria-label')+' is unavailable for this result';else button.title=button.getAttribute('aria-label');
    if(!this.draftActions){this.draftSave.disabled=!draft.dirty||!!this.controller?.busy||!!this.result.grid?.uncertain;this.draftRevert.disabled=!draft.dirty||!!this.controller?.busy;}
    this.pageInput.disabled=!cap.page;this.pageInput.title=cap.page?'Rows per page (maximum '+this.result.grid.rowCeiling+')':cap.pageReason||this.result.gridUnavailable||'Server paging is unavailable for this result';this.exportButton.disabled=!n||!this.transform;this.rowStatus.title=cap.reason||this.result.gridUnavailable||'';
    this.rowStatus.textContent=(n?(page.offset??0)+1:0)+'–'+((page.offset??0)+(this.result.rows?.length??0))+(page.total!==undefined?' of '+page.total:'')+' · '+draft.selection.size+' selected'+(draft.dirty?' · '+(draft.changes.size+draft.added.length)+' pending':'');
  }
  editActiveCell(){const draft=this.state.draft,index=draft.active;if(index===null)return;const column=this.columns().find(c=>c.id===draft.activeColumn&&draft.canEdit(index,c.id))??this.columns().find(c=>draft.canEdit(index,c.id));if(!column)return;this.revealRow(index);const cell=this.scroll.querySelector('[data-row-index="'+index+'"] [data-column-id="'+CSS.escape(column.id)+'"]');if(cell)this.editCell(cell,index,column);}
  editCell(cell,index,column){
    if(!this.state.draft.canEdit(index,column.id)||this.draftActions)return;this.finishCell?.();this.controller?.stopRefresh();
    const draft=this.state.draft,original={...draft.cell(index,column)},input=document.createElement('textarea'),kind=document.createElement('select');
    input.rows=1;input.wrap='off';input.spellcheck=false;input.maxLength=8192;input.value=original.value??'';input.setAttribute('aria-label','Edit '+column.label);
    for(const value of ['value','null','default']){const option=document.createElement('option');option.value=value;option.textContent=value==='value'?'Value':value.toUpperCase();option.disabled=value==='null'&&!draft.column(column.id).nullable||value==='default'&&!draft.column(column.id).hasDefault;kind.append(option);}kind.value=original.kind;kind.setAttribute('aria-label','Value mode');
    const changed=()=>{try{draft.set(index,column,{kind:kind.value,...(kind.value==='value'?{value:input.value}:{})});input.disabled=kind.value!=='value';this.rowControls();input.setCustomValidity('');}catch(error){input.setCustomValidity(error.message);input.reportValidity();}};
    input.oninput=kind.onchange=changed;cell.classList.add('grid-cell-editor');cell.replaceChildren(input);
    if(draft.column(column.id).nullable||draft.column(column.id).hasDefault)cell.append(kind);
    let ended=false;
    const finish=()=>{if(ended)return;ended=true;this.finishCell=null;this.renderTable();};this.finishCell=finish;
    cell.onkeydown=event=>{if(event.key==='Escape'){event.preventDefault();draft.set(index,column,original);finish();}else if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();changed();finish();}else if(event.key==='Tab'){event.preventDefault();changed();const columns=this.columns().filter(c=>draft.canEdit(index,c.id)),at=columns.findIndex(c=>c.id===column.id)+(event.shiftKey?-1:1);finish();let nextColumn=columns[at],nextRow=index;if(!nextColumn){nextRow+=event.shiftKey?-1:1;while(nextRow>=0&&nextRow<draft.length&&draft.deleted(nextRow))nextRow+=event.shiftKey?-1:1;if(nextRow>=0&&nextRow<draft.length){const candidates=this.columns().filter(c=>draft.canEdit(nextRow,c.id));nextColumn=event.shiftKey?candidates.at(-1):candidates[0];}}if(nextColumn){draft.select(nextRow);draft.activeColumn=nextColumn.id;this.editActiveCell();}}};cell.addEventListener('focusout',()=>queueMicrotask(()=>{if(!cell.contains(document.activeElement))finish();}));input.disabled=kind.value!=='value';(input.disabled?kind:input).focus({preventScroll:true});
  }
  updateDraftActions(){if(!this.draftActions||!this.draftSave)return;const state=this.draftActions.state();this.draftSave.disabled=!state.dirty||state.busy;this.draftRevert.disabled=!state.dirty||state.busy;}
  refreshToolbar(){
    const toolbar=document.createElement('div');toolbar.className='data-grid-footer';toolbar.setAttribute('role','toolbar');toolbar.setAttribute('aria-label','Result refresh');
    toolbar.addEventListener('click',event=>{const pressed=event.target.closest('button');if(!pressed||pressed.disabled)return;if(this.controller)this.controller.stopRefresh();else if(this.refresh){this.refresh.clear();this.refresh.seconds=0;this.refresh.requested=false;this.refresh.emit();}},true);
    const split=document.createElement('div');split.className='grid-refresh-split';
    const button=(label,name,action)=>{const control=iconButton(label,'',action);control.replaceChildren(lucide(name));return control;};
    const divider=()=>{const separator=document.createElement('span');separator.className='grid-footer-divider';separator.setAttribute('role','separator');separator.setAttribute('aria-orientation','vertical');separator.append(lucide('ellipsis-vertical'));return separator;};
    const primary=button('Refresh','refresh-cw',()=>this.refresh?.choose(0));primary.classList.add('grid-refresh-primary');
    const label=document.createElement('span');label.textContent='Refresh';primary.append(label);
    const menuButton=button('Choose result refresh interval','chevron-down',()=>this.toggleRefreshMenu());menuButton.classList.add('grid-refresh-caret');menuButton.setAttribute('aria-haspopup','menu');menuButton.setAttribute('aria-expanded','false');this.refreshMenuButton=menuButton;
    const status=document.createElement('span');status.className='grid-refresh-status';status.setAttribute('role','status');status.hidden=true;
    const invoke=action=>Promise.resolve().then(action).catch(error=>{if(this.result.grid?.uncertain)this.renderQueryState();else{this.queryError.hidden=false;this.queryError.textContent=error.message;}});const save=button('Save','save',()=>invoke(()=>this.draftActions?this.draftActions.save():this.saveRows())),cancel=button(this.draftActions?'Revert':'Cancel',this.draftActions?'undo-2':'x',()=>invoke(()=>{if(this.draftActions)return this.draftActions.revert();this.state.draft.cancel();this.renderTable();}));this.draftSave=save;this.draftRevert=cancel;
    for(const control of [save,cancel]){control.disabled=true;control.classList.add('grid-footer-labeled');control.append(document.createTextNode(control.title));}
    const edit=button('Edit','square-pen',()=>this.editActiveCell());this.editButton=edit;
    const add=button('Add row','list-plus',()=>invoke(()=>{this.controller?.stopRefresh();const index=this.state.draft.add();this.renderTable();this.revealRow(index);this.editActiveCell();}));add.classList.add('grid-add-row');this.addButton=add;
    const remove=button('Delete','list-x',()=>invoke(()=>{this.controller?.stopRefresh();this.state.draft.deleteSelected();this.renderTable();}));remove.classList.add('grid-delete-row');this.deleteButton=remove;
    split.append(status,primary,menuButton);toolbar.append(split);if(this.draftActions||this.rowActions)toolbar.append(divider(),save,cancel);if(this.rowActions)toolbar.append(divider(),edit,add,remove,divider());this.updateDraftActions();
    primary.disabled=menuButton.disabled=!this.refresh;
    if(this.refresh)this.unsubscribeRefresh=this.refresh.subscribe(schedule=>{primary.disabled=schedule.pending;primary.setAttribute('aria-busy',String(schedule.pending));status.hidden=!schedule.seconds;status.textContent=schedule.seconds?String(schedule.seconds):'';const description=schedule.seconds?'Refresh every '+schedule.seconds+' second'+(schedule.seconds===1?'':'s'):'';status.title=description;status.setAttribute('aria-label',description);});
    const navigation=document.createElement('div');navigation.className='grid-row-navigation';this.navigation={};
    for(const [action,text,title] of [['first','|<','First row'],['previous','<','Previous row'],['next','>','Next row'],['last','>|','Last row']]){const control=document.createElement('button');control.type='button';control.className='grid-icon';control.textContent=text;control.title=title;control.setAttribute('aria-label',title);control.onclick=()=>invoke(()=>this.navigate(action));this.navigation[action]=control;navigation.append(control);}
    const exportButton=button('Export data','download',()=>invoke(()=>this.exportData()));this.exportButton=exportButton;
    const settings=button('Grid settings','settings',()=>this.openGridSettings());
    const limit=document.createElement('input');limit.className='grid-page-size';limit.type='text';limit.inputMode='numeric';limit.pattern='[0-9]*';limit.maxLength=7;limit.spellcheck=false;limit.value=String(this.result.grid?.page?.limit??Math.min(200,this.result.grid?.rowCeiling??1000));limit.setAttribute('aria-label','Rows per page');limit.title='Rows per page (maximum '+(this.result.grid?.rowCeiling??1000)+')';limit.oninput=()=>{limit.value=limit.value.replace(/[^0-9]/g,'').slice(0,7);limit.setCustomValidity('');};limit.onkeydown=event=>{if(event.key!=='Enter')return;event.preventDefault();try{const size=pageSize(limit.value,this.result.grid?.rowCeiling??1000);void invoke(()=>this.page('first',size));}catch(error){limit.setCustomValidity(error.message);limit.reportValidity();}};this.pageInput=limit;
    this.rowStatus=document.createElement('span');this.rowStatus.className='grid-row-status';this.rowStatus.setAttribute('role','status');
    toolbar.append(navigation,divider(),exportButton,divider(),settings,limit,this.rowStatus);return toolbar;
  }
  toggleRefreshMenu(){
    if(this.refreshMenu){this.closeRefreshMenu();return;}if(!this.refresh)return;this.closeColumnMenu();this.closeMenu();this.closeViewer();
    const menu=document.createElement('div');menu.className='grid-refresh-menu';menu.setAttribute('role','menu');menu.setAttribute('aria-label','Result refresh options');
    for(const seconds of [0,1,5,10,15,30,60]){const item=document.createElement('button');item.type='button';item.setAttribute('role','menuitemradio');item.setAttribute('aria-checked',String(seconds===0));item.textContent=seconds===0?'Refresh':seconds===1?'Refresh every 1 second':'... '+seconds+' seconds';item.onclick=()=>{this.closeRefreshMenu(true);void this.refresh.choose(seconds);};menu.append(item);}
    menu.onkeydown=event=>{const items=[...menu.children],index=items.indexOf(document.activeElement);if(['ArrowUp','ArrowDown','Home','End'].includes(event.key)){event.preventDefault();const next=event.key==='Home'?0:event.key==='End'?items.length-1:(index+(event.key==='ArrowDown'?1:items.length-1))%items.length;items[next].focus();}else if(event.key==='Tab')this.closeRefreshMenu();};
    document.body.append(menu);this.refreshMenu=menu;this.refreshMenuButton.setAttribute('aria-expanded','true');const anchor=this.refreshMenuButton.getBoundingClientRect(),box=menu.getBoundingClientRect();menu.style.left=Math.max(8,Math.min(anchor.left,innerWidth-box.width-8))+'px';menu.style.top=Math.max(8,anchor.top-box.height-5)+'px';menu.children[0].focus();
  }
  closeRefreshMenu(focus=false){this.refreshMenu?.remove();this.refreshMenu=null;this.refreshMenuButton?.setAttribute('aria-expanded','false');if(focus)this.refreshMenuButton?.focus();}
  constructor(host,result,{sourceSql=null,notice=()=>{},transform=null,refresh=null,controller=null,structured=null,rowActions=true,draftActions=null,displayOnly=false}={}){this.displayOnly=displayOnly;this.rowActions=rowActions;this.draftActions=draftActions;this.structured=structured;this.host=host;this.result=result;this.sourceSql=sourceSql;this.notice=notice;this.transform=transform;this.refresh=refresh;this.controller=controller;this.state=stateFor(result);this.outside=e=>this.handleOutside(e);this.key=e=>this.handleKey(e);this.viewportChanged=()=>{this.closeColumnMenu();this.closeRefreshMenu();};this.render();document.addEventListener('pointerdown',this.outside,true);document.addEventListener('keydown',this.key,true);window.addEventListener('resize',this.viewportChanged);if(controller)this.unsubscribeQuery=controller.subscribe(()=>this.renderQueryState());}
  destroy(){this.state.scroll={left:this.scroll.scrollLeft,top:this.scroll.scrollTop};this.unsubscribeRefresh?.();this.unsubscribeQuery?.();this.closeRefreshMenu();this.disposed=true;this.host.removeAttribute('aria-busy');document.removeEventListener('pointerdown',this.outside,true);document.removeEventListener('keydown',this.key,true);window.removeEventListener('resize',this.viewportChanged);this.closeColumnMenu();this.closeViewer();this.closeMenu();if(this.dialog?.open)this.dialog.close();this.dialog?.remove();this.valueDialog?.remove();this.host.replaceChildren();}
  render(){this.host.replaceChildren();this.host.className='data-grid-host';const view=document.createElement('section');view.className='data-grid-view';this.command=document.createElement('div');this.command.className='data-grid-command';this.command.append(this.sourceSection(),this.filterSection(),this.actionSection());this.scroll=document.createElement('div');this.scroll.className='data-grid-scroll';this.scroll.tabIndex=0;this.scroll.setAttribute('role','grid');this.scroll.setAttribute('aria-label','Query results');this.live=document.createElement('span');this.live.className='sr-only';this.live.setAttribute('aria-live','polite');this.interactive=document.createElement('div');this.interactive.className='grid-interactive';const footer=this.refreshToolbar();if(this.displayOnly){this.command.hidden=true;footer.hidden=true;}this.interactive.append(this.command,this.scroll,footer);this.queryError=document.createElement('div');this.queryError.className='grid-query-error';this.queryError.setAttribute('role','alert');this.queryError.hidden=true;view.append(this.queryError,this.interactive,this.live);this.view=view;this.host.append(view);this.renderTable();if(this.state.scroll){this.scroll.scrollLeft=this.state.scroll.left;this.scroll.scrollTop=this.state.scroll.top;}}
  sourceSection(){const section=document.createElement('div');section.className='grid-source';const available=!!this.sourceSql;this.sourceButton=iconButton(available?'Show complete result SQL':'No SQL source is available','M5 4h14v16H5zM8 8h8M8 12h8M8 16h5',()=>this.toggleViewer());this.sourceButton.disabled=!available;const input=document.createElement('input');input.className='grid-source-preview';input.readOnly=true;input.setAttribute('aria-label',this.structured?'Current query SQL':'SQL that generated this result');input.value=available?this.sourceSql.replace(/[\r\n]/g,' ').trim():'SQL unavailable — JDBC metadata result';input.title=input.value;section.append(this.sourceButton,input);this.sourceAnchor=section;return section;}
  filterSection(){if(this.structured){const host=document.createElement('div');host.className='grid-filter';this.structured.host=host;this.structured.render(host);return host;}const section=document.createElement('div');section.className='grid-filter';this.expandButton=iconButton('Expand SQL filter expression','M9 9 4 4m0 0h4M4 4v4m11 1 5-5m0 0h-4m4 0v4M9 15l-5 5m0 0v-4m0 4h4m7-5 5 5m0 0h-4m4 0v-4',()=>{this.state.expanded=!this.state.expanded;this.updateFilterSize();});this.filter=document.createElement('textarea');this.filter.className='grid-filter-input';this.filter.wrap='off';this.filter.spellcheck=false;this.filter.rows=this.state.expanded?5:1;this.filter.value=this.state.filter;this.filter.placeholder='SQL filter expression';this.filter.setAttribute('aria-label','SQL filter expression');this.filter.addEventListener('input',()=>{if(this.state.filter!==this.filter.value)this.controller?.stopRefresh();this.state.filter=this.filter.value;});const wrapper=document.createElement('div');wrapper.className='grid-filter-editor';const play=iconButton('Apply SQL filter expression','M8 5l11 7-11 7z',()=>this.applyQueryAction('expression',null,{expression:this.state.filter}));play.classList.add('grid-filter-apply');play.disabled=!this.transform;wrapper.append(this.filter,play);section.append(this.expandButton,wrapper);return section;}
  updateFilterSize(){this.filter.rows=this.state.expanded?5:1;this.expandButton.title=this.state.expanded?'Collapse SQL filter expression':'Expand SQL filter expression';this.expandButton.setAttribute('aria-label',this.expandButton.title);this.expandButton.replaceChildren(svg(this.state.expanded?'M4 9h5V4m0 5L4 4m16 5h-5V4m0 5 5-5M4 15h5v5m0-5-5 5m16-5h-5v5m0-5 5 5':'M9 9 4 4m0 0h4M4 4v4m11 1 5-5m0 0h-4m4 0v4M9 15l-5 5m0 0v-4m0 4h4m7-5 5 5m0 0h-4m4 0v-4'));}
actionSection(){const actions=document.createElement('div');actions.className='grid-actions';const split=document.createElement('div');split.className='grid-split';const clear=iconButton('Remove all filtering/orderings','M4 15 14-11 4 5-10 11H7zM13 19h8',()=>this.applyQueryAction('clear_all'));const caret=iconButton('Choose filtering or ordering to remove','M7 9l5 5 5-5',e=>{e.stopPropagation();this.toggleMenu();});caret.classList.add('grid-caret');split.append(clear,caret);this.menu=document.createElement('div');this.menu.className='grid-action-menu';this.menu.hidden=true;for(const label of ['Remove all filtering','Remove all orderings']){const item=document.createElement('button');item.type='button';item.textContent=label;item.addEventListener('click',()=>{this.closeMenu();this.applyQueryAction(label==='Remove all filtering'?'clear_filters':'clear_order');});this.menu.append(item);}split.append(this.menu);const funnel=iconButton('Result Set Order/Filter Settings','M3 5h18l-7 8v5l-4 2v-7z',()=>this.openSettings());const find=iconButton('Find and Replace','M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0M15 15l6 6',()=>this.notice('Find and Replace is reserved for a future update.'));actions.append(split,funnel,find);return actions;}
  toggleMenu(){if(this.menu.hidden){this.closeColumnMenu();this.closeViewer();this.menu.hidden=false;}else this.closeMenu();}
  closeMenu(){if(this.menu)this.menu.hidden=true;}
  toggleViewer(){if(this.viewer){this.closeViewer();return;}this.closeColumnMenu();this.closeMenu();const box=document.createElement('div');box.className='sql-viewer';box.style.width=this.state.popup.width+'px';box.style.height=this.state.popup.height+'px';const area=document.createElement('textarea');area.readOnly=true;area.wrap='off';area.value=this.sourceSql;area.setAttribute('aria-label',this.structured?'Complete current query SQL':'Complete SQL that generated this result');box.append(area);for(const direction of ['n','ne','e','se','s','sw','w','nw']){const handle=document.createElement('span');handle.className='sql-resize '+direction;handle.dataset.direction=direction;handle.setAttribute('aria-hidden','true');handle.addEventListener('pointerdown',e=>this.beginViewerResize(e,handle));box.append(handle);}document.body.append(box);const anchor=this.sourceButton.getBoundingClientRect(),width=Math.min(this.state.popup.width,window.innerWidth-16),height=Math.min(this.state.popup.height,window.innerHeight-16);box.style.width=width+'px';box.style.height=height+'px';box.style.left=Math.max(8,Math.min(window.innerWidth-width-8,anchor.left))+'px';box.style.top=Math.max(8,Math.min(window.innerHeight-height-8,anchor.bottom+5))+'px';this.viewer=box;area.focus();}
  closeViewer(){if(!this.viewer)return;const rect=this.viewer.getBoundingClientRect();this.state.popup={width:rect.width,height:rect.height};this.viewer.remove();this.viewer=null;}
  beginViewerResize(event,handle){event.preventDefault();const box=this.viewer,dir=handle.dataset.direction,start=box.getBoundingClientRect(),x=event.clientX,y=event.clientY;handle.setPointerCapture(event.pointerId);const move=e=>{let left=start.left,top=start.top,width=start.width,height=start.height;if(dir.includes('e'))width=e.clientX-x+start.width;if(dir.includes('s'))height=e.clientY-y+start.height;if(dir.includes('w')){width=x-e.clientX+start.width;left=e.clientX;}if(dir.includes('n')){height=y-e.clientY+start.height;top=e.clientY;}width=Math.max(240,Math.min(width,window.innerWidth-left-8));height=Math.max(90,Math.min(height,window.innerHeight-top-8));if(dir.includes('w')&&width===240)left=start.right-240;if(dir.includes('n')&&height===90)top=start.bottom-90;left=Math.max(8,left);top=Math.max(8,top);box.style.left=left+'px';box.style.top=top+'px';box.style.width=width+'px';box.style.height=height+'px';};const up=e=>{handle.releasePointerCapture(e.pointerId);handle.removeEventListener('pointermove',move);handle.removeEventListener('pointerup',up);const rect=box.getBoundingClientRect();this.state.popup={width:rect.width,height:rect.height};};handle.addEventListener('pointermove',move);handle.addEventListener('pointerup',up);}
  handleOutside(event){if(this.refreshMenu&&!this.refreshMenu.contains(event.target)&&!this.refreshMenuButton.contains(event.target))this.closeRefreshMenu();if(this.columnMenu&&!this.columnMenu.contains(event.target)&&!this.columnMenuButton.contains(event.target))this.closeColumnMenu();if(this.viewer&&!this.viewer.contains(event.target)&&!this.sourceButton.contains(event.target))this.closeViewer();if(this.menu&&!this.menu.hidden&&!this.menu.contains(event.target))this.closeMenu();}
  handleKey(event){if(event.key==='Escape'){if(this.refreshMenu){event.preventDefault();this.closeRefreshMenu(true);}if(this.columnMenu){event.preventDefault();this.closeColumnMenu(true);}this.closeViewer();this.closeMenu();}}
  closeColumnMenu(focus=false){const button=this.columnMenuButton;this.columnMenu?.remove();this.columnMenu=null;this.columnMenuButton=null;button?.setAttribute('aria-expanded','false');if(focus&&button?.isConnected)button.focus();}
  selectedValue(column){const row=this.result.rows?.[this.state.selectedRow];return row?row[column.source]:undefined;}
  valueLabel(value){
    if(value===null)return 'NULL';
    if(value===undefined)return '(select a row)';
    // This is a bounded display label, never executable SQL or an edited cell value.
    const text=String(value),preview=text.length>100?text.slice(0,100)+'…':text;
    return typeof value==='string'?"'"+preview.replace(/'/g,"''")+"'":preview;
  }
  async applyQueryAction(action,column=null,extra={}){
    if(this.busy)return;
    if(!this.transform||!this.sourceSql){this.notice('This result has no executable SELECT context. Run the SELECT again in a Script tab.');return;}
    this.busy=true;this.host.setAttribute('aria-busy','true');this.host.querySelectorAll('.grid-column-toggle').forEach(b=>b.disabled=true);
    try{await this.transform({action,...(column?{columnIndex:column.source,columnLabel:column.rawLabel,jdbcType:column.jdbcType,columns:this.result.columns}:{}),...extra});}
    catch(error){if(!this.controller)this.notice(error.message);}
    finally{this.busy=false;if(!this.disposed){if(!this.controller)this.host.removeAttribute('aria-busy');this.host.querySelectorAll('.grid-column-toggle').forEach(b=>b.disabled=false);}}
  }
  enterFilterValue(column,operator='='){if(this.structured?.customFilter){this.structured.customFilter(column,operator);return;}
    if(!this.transform||!this.sourceSql){this.notice('This result has no executable SELECT context. Run the SELECT again in a Script tab.');return;}
    this.valueDialog?.remove();const dialog=document.createElement('dialog');dialog.className='grid-value-dialog';
    dialog.setAttribute('aria-label','Custom column filter');dialog.addEventListener('cancel',event=>event.preventDefault());
    const heading=document.createElement('div');heading.className='grid-value-heading';const title=document.createElement('h2');title.textContent='Custom column filter';
    const close=iconButton('Close custom column filter','M6 6l12 12M18 6 6 18',()=>dialog.close());heading.append(title,close);
    const label=document.createElement('label');const predicate=document.createElement('span');predicate.className='grid-value-predicate';predicate.textContent=column.label+' '+operator;
    const input=document.createElement('textarea');input.rows=4;input.wrap='off';input.maxLength=8192;input.autocomplete='off';input.spellcheck=false;input.setAttribute('aria-label','Filter value');const draftKey=column.id+':'+operator;input.value=this.state.valueDrafts?.get(draftKey)??this.selectedValue(column)??'';label.append(predicate,input);
    const help=document.createElement('p');help.className='muted';help.textContent='Expected type: '+(column.type||'text')+'. Enter a value, not SQL. Ok validates the value and reruns this result’s SELECT. Cancel closes without applying further edits.';
    const status=document.createElement('p');status.className='grid-value-status';status.setAttribute('role','status');status.setAttribute('aria-live','polite');
    const actions=document.createElement('div');actions.className='actions';const cancel=document.createElement('button');cancel.type='button';cancel.textContent='Cancel';cancel.onclick=()=>dialog.close();
    const apply=document.createElement('button');apply.type='button';apply.textContent='Ok';
    apply.onclick=async()=>{
      if(apply.disabled)return;apply.disabled=true;const value=input.value;(this.state.valueDrafts??=new Map()).set(draftKey,value);dialog.close();this.valueDialog=null;
      await this.applyQueryAction('filter',column,{operator,value});
    };
    dialog.addEventListener('close',()=>{if(this.valueDialog===dialog)this.valueDialog=null;dialog.remove();});
    actions.append(apply,cancel);dialog.append(heading,label,help,status,actions);document.body.append(dialog);this.valueDialog=dialog;dialog.showModal();input.focus();
  }
  toggleColumnMenu(column,button){
    if(this.columnMenuButton===button){this.closeColumnMenu(true);return;}
    this.closeColumnMenu();this.closeMenu();this.closeViewer();
    const menu=document.createElement('div');menu.className='grid-column-menu';menu.setAttribute('role','menu');menu.setAttribute('aria-label','Sort and filter '+column.label);
    this.columnMenu=menu;this.columnMenuButton=button;button.setAttribute('aria-expanded','true');
    const filterIcon='M3 5h18l-7 8v5l-4 2v-7z';
    const add=(parent,label,path=filterIcon,disabled=false,action=null)=>{
      const item=document.createElement('button');item.type='button';item.setAttribute('role','menuitem');item.tabIndex=-1;item.disabled=disabled;
      item.title=disabled?'Select a row with a non-NULL value for this comparison':label;
      const text=document.createElement('span');text.textContent=label;item.append(svg(path),text);
      item.addEventListener('click',()=>{this.closeColumnMenu(true);if(action)action();else this.notice(label+' is reserved for a future update.');});parent.append(item);return item;
    };
    const divider=()=>{const line=document.createElement('div');line.className='grid-menu-divider';line.setAttribute('role','separator');menu.append(line);};
    add(menu,`Order by ${column.label} ASC`,'M8 20V4m-4 4 4-4 4 4M15 6h5m-5 6h4m-4 6h3',false,()=>this.applyQueryAction('order',column,{direction:'ASC'}));
    add(menu,`Order by ${column.label} DESC`,'M8 4v16m-4-4 4 4 4-4M15 6h5m-5 6h4m-4 6h3',false,()=>this.applyQueryAction('order',column,{direction:'DESC'}));
    add(menu,'Find and Replace','M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0M15 15l6 6');
    add(menu,'Filter by value',filterIcon,false,()=>this.enterFilterValue(column));divider();
    const group=document.createElement('div');group.setAttribute('role','group');group.setAttribute('aria-label','Cell Value');
    const heading=document.createElement('div');heading.className='grid-menu-heading';heading.textContent='Cell Value';heading.setAttribute('aria-hidden','true');group.append(heading);menu.append(group);
    const value=this.selectedValue(column),disabled=value===undefined||value===null||this.result.cellsTruncated;
    for(const operator of ['=','<>','>','<']){const item=add(group,`${column.label} ${operator} ${this.valueLabel(value)}`,filterIcon,disabled,()=>this.applyQueryAction('filter',column,{operator,value}));if(this.result.cellsTruncated)item.title='Cell previews were truncated; enter a complete value through Custom instead';}
    divider();const custom=document.createElement('div');custom.setAttribute('role','group');custom.setAttribute('aria-label','Custom');
    const customHeading=document.createElement('div');customHeading.className='grid-menu-heading';customHeading.textContent='Custom';customHeading.setAttribute('aria-hidden','true');custom.append(customHeading);menu.append(custom);
    for(const operator of ['=','<>','>','<'])add(custom,`${column.label} ${operator} ...`,filterIcon,false,()=>this.enterFilterValue(column,operator));
    divider();add(menu,`${column.label} IS NULL`,filterIcon,false,()=>this.applyQueryAction('filter',column,{operator:'IS NULL'}));add(menu,`${column.label} IS NOT NULL`,filterIcon,false,()=>this.applyQueryAction('filter',column,{operator:'IS NOT NULL'}));
    divider();add(menu,'Remove all filters/orderings','M4 15 14-11 4 5-10 11H7zM13 19h8',false,()=>this.applyQueryAction('clear_all'));
    add(menu,'Customize filters ...',filterIcon,false,()=>this.openSettings());
    menu.addEventListener('keydown',event=>{
      const items=[...menu.querySelectorAll('[role=menuitem]:not(:disabled)')],index=items.indexOf(document.activeElement);let next;
      if(event.key==='ArrowDown')next=(index+1)%items.length;else if(event.key==='ArrowUp')next=(index-1+items.length)%items.length;
      else if(event.key==='Home')next=0;else if(event.key==='End')next=items.length-1;else if(event.key==='Tab'){this.closeColumnMenu(true);return;}else return;
      event.preventDefault();items[next]?.focus();
    });
    document.body.append(menu);const anchor=button.getBoundingClientRect(),box=menu.getBoundingClientRect();
    menu.style.left=Math.max(8,Math.min(anchor.right-box.width,innerWidth-box.width-8))+'px';
    menu.style.top=Math.max(8,Math.min(anchor.bottom+3,innerHeight-box.height-8))+'px';
    menu.querySelector('[role=menuitem]:not(:disabled)')?.focus({preventScroll:true});
  }
  selectRow(index,event={}){this.state.draft.select(index,event);this.state.selectedRow=index;this.scroll.querySelectorAll('.grid-body .grid-row').forEach(row=>row.setAttribute('aria-selected',String(this.state.draft.selection.has(Number(row.dataset.rowIndex)))));this.live.textContent='Selected row '+(index+1);this.rowControls();}
  columns(){const byId=new Map(this.state.columns.map(column=>[column.id,column]));return this.state.order.filter(id=>!this.state.hidden.has(id)).map(id=>byId.get(id)).filter(Boolean);}
  template(){return`52px ${this.columns().map(column=>(this.state.widths.get(column.id)??180)+'px').join(' ')}`;}
  moveColumn(id,target,after=false){if(id===target)return;const order=this.state.order.filter(value=>value!==id),at=order.indexOf(target);if(at<0)return;order.splice(at+(after?1:0),0,id);this.state.order=order;this.renderTable(id);this.live.textContent=`Moved ${this.state.columns.find(c=>c.id===id)?.label??'column'} to position ${order.indexOf(id)+1}`;}
  moveBy(id,delta){const index=this.state.order.indexOf(id),next=index+delta;if(index<0||next<0||next>=this.state.order.length)return;const target=this.state.order[next];this.state.order[index]=target;this.state.order[next]=id;this.renderTable(id);this.live.textContent=`Moved ${this.state.columns.find(c=>c.id===id)?.label??'column'} to position ${next+1}`;}
  renderTable(focusId=null){
    if(!this.state.initialWidths){const canvas=document.createElement('canvas'),measure=canvas.getContext('2d');measure.font=getComputedStyle(this.scroll).font;const maximum=Math.ceil(measure.measureText('0'.repeat(36)).width)+20;for(const column of this.state.columns){let width=measure.measureText(column.label.slice(0,36)).width+48;for(const row of (this.result.rows??[]).slice(0,200)){const value=row[column.source];width=Math.max(width,measure.measureText(String(value===null?'NULL':value??'').slice(0,36)).width+20);}this.state.widths.set(column.id,Math.max(96,Math.min(maximum,Math.ceil(width))));}this.state.initialWidths=true;}
    this.closeColumnMenu();const left=this.scroll.scrollLeft,top=this.scroll.scrollTop;this.scroll.replaceChildren();
    const columns=this.columns(),template=this.template(),header=document.createElement('div');header.className='grid-row header';header.style.gridTemplateColumns=template;header.setAttribute('role','row');
    const corner=document.createElement('span');corner.className='row-number row-number-corner';corner.setAttribute('role','columnheader');corner.setAttribute('aria-label','Row numbers');corner.title='Row numbers';header.append(corner);
    for(const column of columns)header.append(this.columnHeader(column));
    const body=document.createElement('div');body.className='grid-body';this.scroll.append(header,body);
    const paint=()=>{if(this.finishCell)return;const count=this.state.draft.length,start=Math.min(count,Math.max(0,Math.floor((this.scroll.scrollTop-body.offsetTop)/28)-3)),end=Math.min(count,start+Math.ceil(this.scroll.clientHeight/28)+6),topSpace=document.createElement('div'),bottomSpace=document.createElement('div');topSpace.style.height=start*28+'px';bottomSpace.style.height=(count-end)*28+'px';const rows=[];for(let i=start;i<end;i++)rows.push(this.makeRow(this.result.rows?.[i]??[],i+1,columns,template));body.replaceChildren(topSpace,...rows,bottomSpace);this.rowControls();};this.paintRows=paint;
    this.scroll.onscroll=()=>{this.closeColumnMenu();const editor=this.scroll.querySelector('.grid-cell-editor');if(editor){const bounds=editor.getBoundingClientRect(),viewport=this.scroll.getBoundingClientRect();if(bounds.bottom>viewport.top&&bounds.top<viewport.bottom&&bounds.right>viewport.left&&bounds.left<viewport.right){this.rowControls();return;}this.finishCell?.();}paint();};this.scroll.scrollLeft=left;this.scroll.scrollTop=top;paint();
    if(focusId)requestAnimationFrame(()=>{if(!this.disposed&&(document.activeElement===document.body||document.activeElement?.classList.contains('data-column-header')))[...this.scroll.querySelectorAll('.data-column-header')].find(cell=>cell.dataset.columnId===focusId)?.focus();});
  }
  columnHeader(column){
    const cell=document.createElement('span');cell.className='data-column-header';cell.title=column.label+(column.type?' · '+column.type:'');cell.tabIndex=0;cell.draggable=true;cell.dataset.columnId=column.id;cell.setAttribute('role','columnheader');cell.setAttribute('aria-keyshortcuts','Alt+ArrowLeft Alt+ArrowRight');
    const label=document.createElement('span');label.className='grid-column-label';label.textContent=column.label;cell.append(label);
    const button=iconButton('Sort and filter '+column.label,'M6 7h12l-6 10.3923048454z',event=>{event.stopPropagation();this.toggleColumnMenu(column,button);});
    button.classList.add('grid-column-toggle');button.draggable=false;button.setAttribute('aria-haspopup','menu');button.setAttribute('aria-expanded','false');
    button.addEventListener('pointerdown',event=>event.stopPropagation());button.addEventListener('dblclick',event=>event.stopPropagation());
    button.addEventListener('keydown',event=>{event.stopPropagation();if(event.key==='ArrowDown'){event.preventDefault();if(this.columnMenuButton!==button)this.toggleColumnMenu(column,button);}});
    if(!this.displayOnly)cell.append(button);
    let dragBlocked=false;cell.addEventListener('pointerdown',event=>{dragBlocked=!!event.target.closest('.column-resize,.grid-column-toggle');},true);
    cell.addEventListener('keydown',event=>{if(event.target===cell&&event.altKey&&(event.key==='ArrowLeft'||event.key==='ArrowRight')){event.preventDefault();this.moveBy(column.id,event.key==='ArrowLeft'?-1:1);}});
    cell.addEventListener('dragstart',event=>{if(dragBlocked||event.target.closest('.column-resize,.grid-column-toggle')){event.preventDefault();return;}this.closeColumnMenu();event.dataTransfer.setData('text/plain',column.id);cell.classList.add('dragging');});
    cell.addEventListener('dragend',()=>{cell.classList.remove('dragging');this.clearDrop();});
    cell.addEventListener('dragover',event=>{event.preventDefault();this.clearDrop();cell.classList.add(event.clientX<cell.getBoundingClientRect().left+cell.offsetWidth/2?'drop-before':'drop-after');});
    cell.addEventListener('drop',event=>{event.preventDefault();const before=cell.classList.contains('drop-before'),source=event.dataTransfer.getData('text/plain');this.clearDrop();this.moveColumn(source,column.id,!before);});
    const handle=document.createElement('span');handle.className='column-resize';handle.title='Resize '+column.label+' column';handle.tabIndex=0;handle.draggable=false;handle.setAttribute('role','separator');handle.setAttribute('aria-label',handle.title);handle.setAttribute('aria-orientation','vertical');handle.setAttribute('aria-valuemin','60');handle.setAttribute('aria-valuemax','800');handle.setAttribute('aria-valuenow',this.state.widths.get(column.id));
    handle.addEventListener('pointerdown',event=>this.beginColumnResize(event,handle,column.id));
    handle.addEventListener('keydown',event=>{if(event.key==='ArrowLeft'||event.key==='ArrowRight'){event.preventDefault();this.resizeColumn(column.id,(this.state.widths.get(column.id)??180)+(event.key==='ArrowLeft'?-10:10),handle);}});
    handle.addEventListener('dblclick',()=>this.resizeColumn(column.id,180,handle));cell.append(handle);return cell;
  }
  makeRow(values,rowNumber,columns,template){const index=rowNumber-1,draft=this.state.draft,row=document.createElement('div');row.className='grid-row';row.classList.toggle('grid-row-deleted',draft.deleted(index));row.classList.toggle('grid-row-added',index>=(this.result.rows?.length??0));row.style.gridTemplateColumns=template;row.setAttribute('role','row');row.dataset.rowIndex=String(index);row.tabIndex=0;row.setAttribute('aria-selected',String(draft.selection.has(index)));if(draft.deleted(index))row.setAttribute('aria-description','Pending deletion, not yet saved');else if(index>=(this.result.rows?.length??0))row.setAttribute('aria-description','New unsaved row');else if(draft.changes.has(draft.id(index)))row.setAttribute('aria-description','Contains unsaved cell changes');row.addEventListener('click',event=>{if(!event.target.closest('textarea,select'))this.selectRow(index,event);});row.addEventListener('keydown',event=>{if(event.target.matches('textarea,select'))return;if(['Enter',' '].includes(event.key)){event.preventDefault();this.selectRow(index,event);}else if(event.key==='F2'){event.preventDefault();this.selectRow(index);this.editActiveCell();}else if(['ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();const next=index+(event.key==='ArrowUp'?-1:1);if(next>=0&&next<draft.length){this.selectRow(next,event);this.revealRow(next);this.scroll.querySelector('[data-row-index="'+next+'"]')?.focus({preventScroll:true});}}});const number=document.createElement('span');number.className='row-number';number.textContent=index>=(this.result.rows?.length??0)?'+':String((this.result.grid?.page?.offset??0)+rowNumber);number.title='Select row '+number.textContent;number.setAttribute('role','rowheader');row.append(number);for(const column of columns){const cell=document.createElement('span'),value=draft.cell(index,column);cell.textContent=value.kind==='null'?this.state.nullText:value.kind==='default'?'DEFAULT':String(value.value??'');if(this.state.dates==='iso'&&[91,92,93,2013,2014].includes(column.jdbcType))cell.textContent=cell.textContent.replace(/^(\d{4}-\d{2}-\d{2}) /,'$1T');cell.title=cell.textContent;cell.setAttribute('role','gridcell');cell.dataset.columnId=column.id;cell.setAttribute('aria-readonly',String(!draft.canEdit(index,column.id)));cell.classList.toggle('grid-cell-changed',!!draft.changes.get(draft.id(index))?.values?.[column.id]);cell.onclick=()=>{draft.activeColumn=column.id;};cell.ondblclick=()=>this.editCell(cell,index,column);row.append(cell);}return row;}
  clearDrop(){this.scroll.querySelectorAll('.drop-before,.drop-after').forEach(cell=>cell.classList.remove('drop-before','drop-after'));}
  beginColumnResize(event,handle,id){event.preventDefault();event.stopPropagation();const start=event.clientX,width=this.state.widths.get(id)??180;handle.setPointerCapture(event.pointerId);const move=e=>this.resizeColumn(id,width+e.clientX-start,handle);const up=e=>{handle.releasePointerCapture(e.pointerId);handle.removeEventListener('pointermove',move);handle.removeEventListener('pointerup',up);};handle.addEventListener('pointermove',move);handle.addEventListener('pointerup',up);}
  resizeColumn(id,width,handle){width=Math.max(60,Math.min(800,Math.round(width)));this.state.widths.set(id,width);const template=this.template();this.scroll.querySelectorAll('.grid-row').forEach(row=>row.style.gridTemplateColumns=template);if(handle)handle.setAttribute('aria-valuenow',width);}
  openSettings(){if(this.structured?.settings){this.structured.settings();return;}this.closeColumnMenu();this.closeMenu();this.closeViewer();if(!this.dialog)this.buildDialog();this.draft=[...this.state.order];this.renderColumnDraft();this.showSettingsTab('columns');this.dialog.showModal();}
  buildDialog(){const dialog=document.createElement('dialog');dialog.className='result-settings';dialog.innerHTML='<div class="result-settings-heading"><h2>Result Set Order/Filter Settings</h2><button type="button" class="dialog-x" aria-label="Close Result Set Order/Filter Settings" title="Close">×</button></div><div class="result-settings-tabs" role="tablist"><button type="button" role="tab" data-panel="columns">Columns</button><button type="button" role="tab" data-panel="custom">Custom</button></div><section class="result-settings-panel columns-panel" role="tabpanel"><div class="column-order-list"></div></section><section class="result-settings-panel custom-panel" role="tabpanel" hidden><p>Custom filtering and ordering expressions are reserved for a future update.</p></section><div class="actions"><button type="button" class="settings-cancel">Cancel</button><button type="button" class="settings-ok">Ok</button></div>';dialog.addEventListener('cancel',e=>e.preventDefault());dialog.querySelector('.dialog-x').onclick=()=>dialog.close();dialog.querySelector('.settings-cancel').onclick=()=>dialog.close();dialog.querySelector('.settings-ok').onclick=()=>{this.state.order=[...this.draft];dialog.close();this.renderTable();};dialog.querySelectorAll('[role=tab]').forEach(tab=>tab.onclick=()=>this.showSettingsTab(tab.dataset.panel));document.body.append(dialog);this.dialog=dialog;}
  showSettingsTab(name){this.dialog.querySelectorAll('[role=tab]').forEach(tab=>{const selected=tab.dataset.panel===name;tab.setAttribute('aria-selected',String(selected));tab.tabIndex=selected?0:-1;});this.dialog.querySelector('.columns-panel').hidden=name!=='columns';this.dialog.querySelector('.custom-panel').hidden=name!=='custom';}
  renderColumnDraft(focusId=null){const list=this.dialog.querySelector('.column-order-list'),byId=new Map(this.state.columns.map(column=>[column.id,column]));list.replaceChildren();for(const [index,id] of this.draft.entries()){const column=byId.get(id),row=document.createElement('div');row.className='column-order-item';row.draggable=true;row.dataset.columnId=id;row.innerHTML='<span class="column-drag" aria-hidden="true">⋮⋮</span><span class="column-order-name"></span><span class="column-order-type"></span>';row.querySelector('.column-order-name').textContent=column.label;row.querySelector('.column-order-type').textContent=column.type;const up=iconButton('Move '+column.label+' up','M12 19V5m-6 6 6-6 6 6',()=>this.moveDraft(id,-1)),down=iconButton('Move '+column.label+' down','M12 5v14m-6-6 6 6 6-6',()=>this.moveDraft(id,1));up.disabled=index===0;down.disabled=index===this.draft.length-1;row.append(up,down);row.addEventListener('dragstart',e=>e.dataTransfer.setData('text/plain',id));row.addEventListener('dragover',e=>{e.preventDefault();row.classList.add('draft-over');});row.addEventListener('dragleave',()=>row.classList.remove('draft-over'));row.addEventListener('drop',e=>{e.preventDefault();row.classList.remove('draft-over');const source=e.dataTransfer.getData('text/plain'),from=this.draft.indexOf(source),to=this.draft.indexOf(id);if(from<0||from===to)return;this.draft.splice(from,1);this.draft.splice(to,0,source);this.renderColumnDraft(source);});list.append(row);}if(focusId)requestAnimationFrame(()=>list.querySelector(`[data-column-id="${CSS.escape(focusId)}"] .column-order-name`)?.focus());}
  moveDraft(id,delta){const index=this.draft.indexOf(id),next=index+delta;if(next<0||next>=this.draft.length)return;this.draft[index]=this.draft[next];this.draft[next]=id;this.renderColumnDraft();}
}
