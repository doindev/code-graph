import {lucide} from './tree-icons.js';
import {bindTreeContextMenu} from './tree-actions.js';
const MAX_NODES=2000;
const MAX_DESCRIPTOR_BYTES=2*1024*1024;
const descriptorBytes=node=>new TextEncoder().encode(JSON.stringify(node)).length;
const isKeyScan=entry=>entry.descriptor.kind==='native_keys';
const objectParents=new Set(['schemas','databases','tables','foreign_tables','views','materialized_views','external_tables','indexes','functions','procedures','sequences','types','aggregates','event_triggers','extensions','roles','tablespaces','foreign_servers','relation','domains','triggers','events','queues','packages','synonyms','schema_triggers','table_triggers','database_links','java','scheduled_jobs','jobs','scheduler_jobs','scheduler_programs','scheduler_schedules','scheduler_chains','aliases','stages','file_formats','pipes','tasks','streams','table_columns','table_constraints','table_foreign_keys','table_indexes','table_triggers','table_policies','table_rules','table_partitions']);
function svg(path){const icon=document.createElementNS('http://www.w3.org/2000/svg','svg');icon.setAttribute('viewBox','0 0 24 24');icon.setAttribute('aria-hidden','true');const p=document.createElementNS(icon.namespaceURI,'path');p.setAttribute('d',path);icon.append(p);return icon;}

/* Lucide table/sheet icons: https://github.com/lucide-icons/lucide (ISC License).
 * Copyright (c) 2026 Lucide Icons and Contributors
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
function nodeIcon(parent,descriptor){
  if(parent.descriptor.kind==='databases')return lucide('database');
  const kind=parent.descriptor.kind==='schemas'?'table':parent.descriptor.kind==='tables'||parent.parent?.descriptor.kind==='schema'?'sheet':null;
  if(!kind)return svg(descriptor.branch?'M3 6h7l2 3h9v11H3z':'M5 3h10l4 4v14H5zM8 11h8M8 15h8');
  const icon=svg(kind==='table'?'M12 3v18M3 9h18M3 15h18':'M3 9h18M3 15h18M9 9v12M15 9v12');
  icon.dataset.lucide=kind;const rect=document.createElementNS(icon.namespaceURI,'rect');
  for(const [key,value] of Object.entries({x:3,y:3,width:18,height:18,rx:2,ry:2}))rect.setAttribute(key,String(value));
  icon.append(rect);return icon;
}

/** Lazy metadata tree. Stable catalog IDs preserve expanded branches across refreshes. */
export class MetadataTreeView {
  constructor({api,wait,menu,notice,select,openTable}){Object.assign(this,{api,wait,menu,notice,select,openTable});this.roots=new Map();this.expanded=new Set();this.pages=new Map();}
  retain(profiles){for(const [id,root] of this.roots)if(!profiles.some(p=>p.id===id)){this.invalidate(root);this.roots.delete(id);for(const key of this.expanded)if(key.startsWith(id+'/'))this.expanded.delete(key);for(const key of this.pages.keys())if(key.startsWith(id+'/'))this.pages.delete(key);}}
  count(entry){return(entry.children??[]).reduce((n,c)=>n+1+this.count(c),0);}
  remember(collection,key,value){if(collection instanceof Map)collection.set(key,value);else collection.add(key);while(collection.size>MAX_NODES)collection.delete(collection.keys().next().value);}
  total(){return[...this.roots.values()].reduce((n,root)=>n+this.count(root),0);}
  cancelLoad(operation){operation.cancelled=true;if(operation.id&&!operation.cancelSent){operation.cancelSent=true;void this.api('/jobs/'+encodeURIComponent(operation.id)+'/cancel','POST',{}).catch(()=>{});}}
  invalidate(entry){entry.revision=(entry.revision??0)+1;for(const operation of entry.operations??[])this.cancelLoad(operation);for(const child of entry.children??[])this.invalidate(child);}
  async load(profile,container){let root=this.roots.get(profile.id);if(root)this.invalidate(root);root={profile,key:profile.id,descriptor:{kind:'root'},container,children:[],revision:0,root:true};this.roots.set(profile.id,root);await this.reload(root);}
  async fetch(entry,offset=0){
    const operation={};(entry.operations??=new Set()).add(operation);
    try{
      const job=await this.api(entry.profile.transport&&entry.profile.transport!=='jdbc'?'/native/tree':'/metadata/tree','POST',{connectionId:entry.profile.id,...entry.descriptor,offset});
      operation.id=job.id;if(operation.cancelled)this.cancelLoad(operation);
      // Even invalidated loads must settle through wait(), which releases their job result.
      return await this.wait(job);
    }finally{entry.operations.delete(operation);}
  }
  retainedBytes(entry){return(entry.children??[]).reduce((n,c)=>n+descriptorBytes(c.descriptor)+this.retainedBytes(c),0);}
  checkCapacity(entry,nodes,replacing=false){
    const count=this.total()-(replacing?this.count(entry):0)+nodes.length;
    const bytes=[...this.roots.values()].reduce((n,root)=>n+this.retainedBytes(root),0)-(replacing?this.retainedBytes(entry):0)+nodes.reduce((n,node)=>n+descriptorBytes(node),0);
    if(count>MAX_NODES||bytes>MAX_DESCRIPTOR_BYTES)throw new Error('Metadata tree limit reached (2,000 items / 2 MiB descriptor data). Refine the Redis pattern and refresh, or refresh a parent/remove a connection to release entries. This page was not consumed.');
  }
  scanNodes(entry,nodes,replacing=false){
    if(!isKeyScan(entry))return nodes;
    const seen=new Set(replacing?[]:entry.children.map(child=>child.descriptor.key));
    return nodes.filter(node=>{if(seen.has(node.key))return false;seen.add(node.key);return true;});
  }
  sortKeys(entry){if(!isKeyScan(entry))return;entry.children.sort((a,b)=>a.descriptor.name.localeCompare(b.descriptor.name,undefined,{sensitivity:'base'})||a.descriptor.key.localeCompare(b.descriptor.key));for(const child of entry.children)entry.container.append(child.wrapper);}
  scanControls(entry){
    if(!isKeyScan(entry))return;
    const form=document.createElement('form');form.className='metadata-scan-controls';
    const label=document.createElement('label');label.textContent='Key pattern';
    const input=document.createElement('input');input.type='text';input.maxLength=1024;input.value=entry.descriptor.pattern??'*';input.setAttribute('aria-label','Redis key pattern');input.spellcheck=false;
    const submit=document.createElement('button');submit.type='submit';submit.textContent='Scan';submit.title='Start a new bounded scan using this Redis glob pattern';
    label.append(input);form.append(label,submit);form.onsubmit=async event=>{event.preventDefault();if(entry.loading)return;const previous=entry.descriptor.pattern;entry.descriptor.pattern=input.value;submit.disabled=true;try{if(!await this.reload(entry)){if(previous===undefined)delete entry.descriptor.pattern;else entry.descriptor.pattern=previous;}}finally{submit.disabled=false;}};
    entry.container.prepend(form);
  }
  message(container,text){const message=document.createElement('p');message.className='metadata-message';message.textContent=text;container.append(message);return message;}
  async reload(entry){
    if(entry.loading)return;entry.loading=true;const revision=++entry.revision;entry.wrapper?.setAttribute('aria-busy','true');
    // Read all previously loaded pages before swapping, so a failed refresh keeps old children.
    // Refresh a live Redis scan from zero; do not replay unbounded empty batches.
    let result,nodes=[],offset=0;const pageCount=isKeyScan(entry)?1:this.pages.get(entry.key)??1;
    try{
      for(let page=0;page<pageCount;page++){result=await this.fetch(entry,offset);if(entry.revision!==revision||!entry.container.isConnected)return;nodes.push(...result.nodes);offset=result.nextOffset;if(offset===undefined)break;}
      nodes=this.scanNodes(entry,nodes,true);this.checkCapacity(entry,nodes,true);
      const keep=new Set(nodes.map(n=>entry.key+'/'+encodeURIComponent(n.key)));for(const state of [this.expanded,this.pages])for(const key of state.keys()){if(key.startsWith(entry.key+'/')&&![...keep].some(prefix=>key===prefix||key.startsWith(prefix+'/')))state.delete(key);}
      for(const child of entry.children)this.invalidate(child);entry.children=[];entry.container.replaceChildren();entry.loaded=true;
      for(const descriptor of nodes)this.append(entry,descriptor);
      this.scanControls(entry);this.sortKeys(entry);this.footer(entry,result);if(isKeyScan(entry))this.pages.delete(entry.key);
      await this.restore(entry);return true;
    }catch(error){if(entry.revision!==revision||!entry.container.isConnected)return;entry.container.querySelector(':scope > .metadata-error')?.remove();const message=this.message(entry.container,'Metadata unavailable: '+error.message);message.classList.add('metadata-error');this.notice(error.message);return false;}
    finally{entry.loading=false;entry.wrapper?.removeAttribute('aria-busy');}
  }
  async restore(entry){for(const child of entry.children){if(!entry.container.isConnected)return;if(child.descriptor.branch&&this.expanded.has(child.key))await this.toggle(child,true);}}
  footer(entry,result={}){
    for(const element of entry.container.querySelectorAll(':scope > .metadata-more,:scope > .metadata-page-status'))element.remove();
    const nextOffset=result.nextOffset;
    const status=text=>{const message=this.message(entry.container,text);message.classList.add('metadata-page-status');message.setAttribute('role','status');};
    if(isKeyScan(entry))status(`${entry.children.length} distinct keys loaded. ${nextOffset!==undefined?'Scan unfinished; empty and duplicate-only batches can occur.':result.scanComplete?'Scan finished.':'Scan incomplete; refine the pattern and refresh.'} Live scan, not a snapshot; keys can change or disappear. Refresh starts a new scan.`);
    else if(!entry.children.length)status('No items');
    if(entry.descriptor.schedulerMessage)status(entry.descriptor.schedulerMessage);
    if(result.warning)status(result.warning);
    if(nextOffset===undefined)return;
    const more=document.createElement('button');more.type='button';more.className='metadata-more';more.textContent=isKeyScan(entry)?'Continue scan…':'Load more…';more.title=isKeyScan(entry)?'Read one bounded SCAN batch; it may contain no new keys':'Load the next 200 items';
    more.onclick=async()=>{
      if(entry.loading)return;entry.loading=true;more.disabled=true;entry.wrapper?.setAttribute('aria-busy','true');const revision=entry.revision;
      try{
        const result=await this.fetch(entry,nextOffset);if(entry.revision!==revision||!entry.container.isConnected)return;
        const nodes=this.scanNodes(entry,result.nodes);this.checkCapacity(entry,nodes);
        for(const descriptor of nodes)this.append(entry,descriptor);
        if(!isKeyScan(entry))this.remember(this.pages,entry.key,(this.pages.get(entry.key)??1)+1);
        this.sortKeys(entry);this.footer(entry,result);await this.restore(entry);
      }catch(error){if(entry.revision===revision&&entry.container.isConnected)this.notice(error.message);}
      finally{entry.loading=false;more.disabled=false;entry.wrapper?.removeAttribute('aria-busy');}
    };entry.container.append(more);
  }
  append(parent,descriptor){
    if(parent.descriptor.kind==='tables')descriptor={...descriptor,relationType:'table'};
    const key=parent.key+'/'+encodeURIComponent(descriptor.key),wrapper=document.createElement('section'),head=document.createElement('div'),children=document.createElement('div');wrapper.className='metadata-node';wrapper.dataset.kind=descriptor.kind;wrapper.dataset.name=descriptor.name;wrapper.setAttribute('role','treeitem');wrapper.setAttribute('aria-label',descriptor.name);head.className='metadata-title';children.className='metadata-children';children.setAttribute('role','group');children.hidden=true;
    const entry={key,descriptor,profile:parent.profile,parent,wrapper,container:children,children:[],revision:0,loaded:false};parent.children.push(entry);
    let toggle;if(descriptor.branch){toggle=document.createElement('button');toggle.type='button';toggle.className='metadata-toggle';toggle.append(svg('m6 9 6 6 6-6'));toggle.title='Expand '+descriptor.name;toggle.setAttribute('aria-label',toggle.title);toggle.setAttribute('aria-expanded','false');wrapper.setAttribute('aria-expanded','false');toggle.onclick=()=>{this.select?.(entry.profile.id);this.toggle(entry);};head.append(toggle);entry.toggle=toggle;}else{const spacer=document.createElement('span');spacer.className='metadata-leaf-spacer';spacer.setAttribute('aria-hidden','true');head.append(spacer);}
    const image=nodeIcon(parent,descriptor);image.classList.add('metadata-icon');head.append(image);
    // Pointer disclosure belongs only to the chevron; names remain available for object actions.
    const name=document.createElement('button');name.type='button';name.className='metadata-name node';name.textContent=descriptor.name;name.title=descriptor.name;const canOpen=descriptor.nativeObject||objectParents.has(parent.descriptor.kind)&&parent.descriptor.kind!=='relation';const activateTable=()=>{this.select?.(entry.profile.id);if(canOpen)this.openTable?.(entry.profile,descriptor,{parent:{...parent.descriptor,offset:Math.floor(parent.children.indexOf(entry)/200)*200},key:descriptor.key});};name.onclick=()=>this.select?.(entry.profile.id);name.ondblclick=activateTable;if(canOpen)name.title=descriptor.name+' · Double-click to open object (Enter)';name.addEventListener('keydown',event=>{if(event.key==='Enter'&&canOpen){event.preventDefault();activateTable();}else if(event.key==='ArrowRight'&&descriptor.branch){event.preventDefault();this.toggle(entry,true);}else if(event.key==='ArrowLeft'){event.preventDefault();if(descriptor.branch&&!children.hidden)this.toggle(entry,false);else parent.wrapper?.querySelector('.metadata-name')?.focus();}else if(event.key==='ArrowUp'||event.key==='ArrowDown'){event.preventDefault();const visible=[...document.querySelectorAll('#tree .metadata-name,#tree .connection-select')].filter(e=>e.getClientRects().length),index=visible.indexOf(name);visible[Math.max(0,Math.min(visible.length-1,index+(event.key==='ArrowDown'?1:-1)))]?.focus();}});head.append(name);
    if(['tables','views','materialized_views'].includes(parent.descriptor.kind)){name.draggable=true;name.addEventListener('dragstart',event=>{event.stopPropagation();event.dataTransfer.effectAllowed='copy';event.dataTransfer.setData('application/x-codegraph-query-source',JSON.stringify({connectionId:entry.profile.id,parent:{...parent.descriptor,offset:Math.floor(parent.children.indexOf(entry)/200)*200},key:descriptor.key}));});}
    const more=document.createElement('button');more.type='button';more.className='connection-more metadata-actions';more.title='Actions for '+descriptor.name;more.setAttribute('aria-label',more.title);more.setAttribute('aria-haspopup','menu');more.setAttribute('aria-expanded','false');
    more.onclick=()=>{const object=(objectParents.has(parent.descriptor.kind)&&parent.descriptor.relationType!=='table'||parent.descriptor.kind==='table_columns')?{selection:{parent:{...parent.descriptor,offset:Math.floor(parent.children.indexOf(entry)/200)*200},key:descriptor.key},refresh:()=>this.reload(parent)}:null;if(object){object.descriptor=descriptor;object.open=()=>this.openTable?.(entry.profile,descriptor,object.selection);object.canOpen=canOpen;}this.menu(entry.profile.id,more,()=>this.reload(descriptor.branch?entry:parent),object,descriptor.canCreate?{...descriptor}:object&&parent.descriptor.canCreate?{...parent.descriptor}:null);};bindTreeContextMenu(head,more);head.append(more);wrapper.append(head,children);parent.container.append(wrapper);
  }
  async toggle(entry,force){const open=force??entry.container.hidden;entry.container.hidden=!open;entry.wrapper.setAttribute('aria-expanded',String(open));entry.toggle.setAttribute('aria-expanded',String(open));entry.toggle.title=(open?'Collapse ':'Expand ')+entry.descriptor.name;entry.toggle.setAttribute('aria-label',entry.toggle.title);entry.toggle.replaceChildren(svg(open?'m6 15 6-6 6 6':'m6 9 6 6 6-6'));if(open){this.remember(this.expanded,entry.key);if(!entry.loaded)await this.reload(entry);}else this.expanded.delete(entry.key);}
}
