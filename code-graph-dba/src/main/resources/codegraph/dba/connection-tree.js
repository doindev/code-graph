import {lucide} from './tree-icons.js';
import {copyObjectName,bindTreeContextMenu} from './tree-actions.js';
const iconNames={sql:'file-code',open:'folder-open',last:'clock',new:'file-plus',edit:'pencil',connect:'plug',reconnect:'refresh-cw',disconnect:'unplug',refresh:'refresh-cw',remove:'trash',copy:'copy',rename:'pencil',down:'chevron-down',up:'chevron-up',right:'chevron-right'};
function icon(name){return lucide(iconNames[name]);}
function control(label,name,action){const b=document.createElement('button');b.type='button';b.title=label;b.setAttribute('aria-label',label);if(name)b.append(icon(name));b.addEventListener('click',action);return b;}

// Display only the address, never the rest of a JDBC URL or its query properties.
export function connectionAddress(profile){
  const url=profile.url??'',defaults={postgresql:5432,mysql:3306,mariadb:3306,sqlserver:1433,oracle:1521,db2:50000,snowflake:443};
  const type=url.match(/^jdbc:([^:]+):/)?.[1];
  const address=url.match(/(?:\/\/|@)(\[[^\]]+\]|[a-zA-Z0-9_.${}-]+)(?::(\d+))?(?=[/:;?]|$)/);
  if(address)return address[1]+(address[2]||defaults[type]?':'+(address[2]||defaults[type]):'');
  if(/^jdbc:(h2|hsqldb):mem:/.test(url)||/^jdbc:(sqlite|duckdb):.*:memory:/.test(url))return 'In-memory';
  if(/^jdbc:(h2|hsqldb|sqlite|duckdb):/.test(url))return 'Local database';
  return 'Address unavailable';
}

export class ConnectionTree {
  constructor(host,callbacks){this.host=host;this.cb=callbacks;this.rows=new Map();this.expanded=new Set();this.host.setAttribute('role','tree');this.host.setAttribute('aria-label','Database connections');
    this.live=document.createElement('span');this.live.className='sr-only';this.live.setAttribute('role','status');this.host.after(this.live);
    document.addEventListener('pointerdown',e=>{if(e.button===2&&this.anchor?.parentElement?.contains(e.target))return;if(this.anchor&&!this.menu?.contains(e.target)&&!this.submenu?.contains(e.target)&&!this.anchor.contains(e.target))this.closeMenu();},true);
    document.addEventListener('keydown',e=>{if(e.key==='Escape'&&this.menu){e.preventDefault();this.closeMenu(true);}});
    window.addEventListener('resize',()=>this.closeMenu());this.host.addEventListener('scroll',()=>this.closeMenu(),{passive:true});
  }
  render(profiles,selected){this.closeMenu();this.rows.clear();this.host.replaceChildren();for(const id of this.expanded)if(!profiles.some(p=>p.id===id))this.expanded.delete(id);
    for(const p of profiles){const row=document.createElement('section');row.className='connection-row';row.dataset.connection=p.id;row.setAttribute('role','treeitem');row.setAttribute('aria-label',p.name);row.setAttribute('aria-expanded','false');
      if(/^#[0-9a-f]{6}$/i.test(p.color??'')){row.classList.add('has-color');row.style.setProperty('--connection-tint',`color-mix(in srgb, ${p.color} 24%, transparent)`);}row.dataset.color=p.color??'transparent';
      const head=document.createElement('div');head.className='connection-title';const children=document.createElement('div');children.className='children';children.setAttribute('role','group');children.hidden=true;
      this.dragEvents(head,p.id,row);
      const toggle=control('Expand '+p.name,'down',()=>this.toggle(p.id));toggle.className='connection-toggle';toggle.setAttribute('aria-expanded','false');
      const image=document.createElement('img');image.className='connection-icon';image.src='/dba/database.svg';image.alt='';image.draggable=false;
      const name=document.createElement('button');name.type='button';name.className='connection-select';name.textContent=p.name;name.title=p.name;name.addEventListener('click',()=>this.cb.select(p.id));
      name.title=p.name+' · Drag to reorder; Alt+Up/Down also moves this connection';name.addEventListener('keydown',e=>{if(e.altKey&&(e.key==='ArrowUp'||e.key==='ArrowDown')){e.preventDefault();this.move(p.id,e.key==='ArrowUp'?-1:1);}else if(e.key==='ArrowRight'||e.key==='ArrowLeft'){e.preventDefault();this.toggle(p.id,e.key==='ArrowRight');}else if(e.key==='ArrowDown'||e.key==='ArrowUp'){e.preventDefault();const names=[...this.host.querySelectorAll('.connection-select')],i=names.indexOf(name);names[Math.max(0,Math.min(names.length-1,i+(e.key==='ArrowDown'?1:-1)))].focus();}});
      const address=document.createElement('em');address.className='connection-address';address.textContent=connectionAddress(p);address.title=address.textContent;
      const more=control('Actions for '+p.name,null,()=>this.openMenu(p.id,more));more.className='connection-more';more.setAttribute('aria-haspopup','menu');more.setAttribute('aria-expanded','false');
      bindTreeContextMenu(head,more);
      head.append(toggle,image,name,address,more);row.append(head,children);this.host.append(row);this.rows.set(p.id,{p,row,children,toggle,more,loaded:false,loading:false});this.updateState(p.id,p.connectionState??{});
      if(this.expanded.has(p.id))this.toggle(p.id,true);
    }this.select(selected);
  }
  select(id){for(const [key,{row}] of this.rows){const selected=key===id;row.classList.toggle('selected',selected);row.setAttribute('aria-selected',String(selected));row.querySelector('.connection-select').setAttribute('aria-pressed',String(selected));}}
  clearDrag(){for(const {row} of this.rows.values())row.classList.remove('connection-dragging','connection-drop-before','connection-drop-after');this.dragged=null;}
  dragEvents(head,id,row){head.draggable=true;head.addEventListener('pointerdown',e=>{this.dragBlocked=!!e.target.closest('.connection-toggle,.connection-more');});head.addEventListener('dragstart',e=>{if(this.orderBusy||this.dragBlocked){e.preventDefault();return;}this.closeMenu();this.dragged=id;e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('application/x-dba-connection',id);row.classList.add('connection-dragging');});head.addEventListener('dragend',()=>this.clearDrag());head.addEventListener('dragover',e=>{if(!this.dragged||this.dragged===id||this.orderBusy)return;e.preventDefault();e.dataTransfer.dropEffect='move';for(const {row:r} of this.rows.values())r.classList.remove('connection-drop-before','connection-drop-after');row.classList.add(e.clientY<head.getBoundingClientRect().top+head.clientHeight/2?'connection-drop-before':'connection-drop-after');const bounds=this.host.getBoundingClientRect();if(e.clientY<bounds.top+30)this.host.scrollTop-=16;else if(e.clientY>bounds.bottom-30)this.host.scrollTop+=16;});head.addEventListener('drop',e=>{if(!this.dragged||this.orderBusy)return;e.preventDefault();const from=this.dragged,before=e.clientY<head.getBoundingClientRect().top+head.clientHeight/2;this.clearDrag();this.reorder(from,id,before);});}
  move(id,delta){const ids=[...this.rows.keys()],index=ids.indexOf(id),target=ids[index+delta];if(target)this.reorder(id,target,delta<0);}
  async reorder(id,target,before){if(this.orderBusy||id===target||!this.rows.has(id)||!this.rows.has(target))return;const prior=[...this.rows.keys()],ids=prior.filter(key=>key!==id);ids.splice(ids.indexOf(target)+(before?0:1),0,id);if(ids.every((key,index)=>key===prior[index]))return;this.orderBusy=true;try{await this.cb.order(ids);if(ids.some(key=>!this.rows.has(key)))return;this.rows=new Map(ids.map(key=>[key,this.rows.get(key)]));for(const {row} of this.rows.values())this.host.append(row);this.rows.get(id).row.querySelector('.connection-select').focus();this.live.textContent=this.rows.get(id).p.name+' moved to position '+(ids.indexOf(id)+1)+' of '+ids.length;}catch(error){this.cb.notice(error.message+' Refresh the connection list before trying again.');}finally{this.orderBusy=false;}}
  updateState(id,state){const entry=this.rows.get(id);if(!entry)return;entry.state=state;entry.row.dataset.connected=String(!!state.connected);entry.row.querySelector('.connection-icon').title=state.connected?'Connected':'Disconnected';}
  async sync(id){try{const state=await this.cb.api('/connections/'+id+'/state');this.updateState(id,state);return state;}catch(e){this.cb.notice(e.message);return null;}}
  async toggle(id,force){const e=this.rows.get(id);if(!e)return;const open=force??e.children.hidden;e.children.hidden=!open;e.row.setAttribute('aria-expanded',String(open));e.toggle.setAttribute('aria-expanded',String(open));e.toggle.title=(open?'Collapse ':'Expand ')+e.p.name;e.toggle.setAttribute('aria-label',e.toggle.title);e.toggle.replaceChildren(icon(open?'up':'down'));if(!open){this.expanded.delete(id);return;}this.expanded.add(id);this.cb.select(id);if(!e.loaded&&!e.loading){e.loading=true;e.row.setAttribute('aria-busy','true');try{await this.cb.load(e.p,e.children);e.loaded=true;}catch(error){this.cb.notice(error.message);}finally{e.loading=false;e.row.removeAttribute('aria-busy');await this.sync(id);}}}
  closeMenu(focus=false){const anchor=this.anchor;this.menu?.remove();this.submenu?.remove();this.menu=null;this.submenu=null;this.anchor=null;anchor?.setAttribute('aria-expanded','false');if(focus)anchor?.focus();}
  position(menu,anchor,side=false){document.body.append(menu);const rect=anchor.getBoundingClientRect(),box=menu.getBoundingClientRect();let x=side?rect.right-2:rect.right-box.width;if(side&&x+box.width>innerWidth-8)x=rect.left-box.width+2;menu.style.left=Math.max(8,Math.min(x,innerWidth-box.width-8))+'px';menu.style.top=Math.max(8,Math.min(side?rect.top:rect.bottom+3,innerHeight-box.height-8))+'px';}
  menuKeys(menu){menu.addEventListener('keydown',e=>{const items=[...menu.querySelectorAll(':scope > button:not(:disabled)')],i=items.indexOf(document.activeElement);let next;if(e.key==='ArrowDown')next=(i+1)%items.length;else if(e.key==='ArrowUp')next=(i-1+items.length)%items.length;else if(e.key==='Home')next=0;else if(e.key==='End')next=items.length-1;else if(e.key==='Tab'){this.closeMenu();return;}else return;e.preventDefault();items[next]?.focus();});}
  item(menu,label,name,action,disabled=false){const b=control(label,name,()=>{this.closeMenu();this.cb.select(this.target);Promise.resolve().then(action).catch(e=>this.cb.notice(e.message));});b.removeAttribute('aria-label');b.setAttribute('role','menuitem');b.disabled=disabled;const text=document.createElement('span');text.textContent=label;b.append(text);menu.append(b);return b;}
  divider(menu){const line=document.createElement('div');line.className='connection-menu-divider';line.setAttribute('role','separator');menu.append(line);}
  async openMenu(id,anchor){if(this.anchor===anchor){this.closeMenu();return;}this.closeMenu();this.anchor=anchor;this.target=id;anchor.setAttribute('aria-expanded','true');const state=await this.sync(id);if(this.anchor!==anchor)return;if(!state){this.closeMenu();return;}const e=this.rows.get(id),menu=document.createElement('div');menu.className='connection-menu';menu.setAttribute('role','menu');menu.setAttribute('aria-label','Actions for '+e.p.name);this.menu=menu;this.menuKeys(menu);
    const sql=control('SQL Editor','sql',()=>this.openSqlMenu(sql,id));sql.setAttribute('role','menuitem');sql.setAttribute('aria-haspopup','menu');sql.setAttribute('aria-expanded','false');const text=document.createElement('span');text.textContent='SQL Editor';sql.append(text,icon('right'));sql.addEventListener('pointerenter',()=>this.openSqlMenu(sql,id));sql.addEventListener('keydown',event=>{if(event.key==='ArrowRight'){event.preventDefault();this.openSqlMenu(sql,id,true);}});menu.append(sql);this.divider(menu);
    this.item(menu,'Edit Connection','edit',()=>this.cb.edit(e.p),state.busy);this.divider(menu);
    this.item(menu,'Connect','connect',()=>this.lifecycle(id,'connect'),state.busy||state.connected);
    this.item(menu,'Invalidate/Reconnect','reconnect',()=>this.lifecycle(id,'reconnect'),state.busy||!state.connected);
    this.item(menu,'Disconnect','disconnect',()=>this.lifecycle(id,'disconnect'),state.busy||!state.connected);this.divider(menu);
    this.item(menu,'Copy','copy',()=>copyObjectName(e.p.name));
    this.item(menu,'Delete','remove',()=>this.cb.remove(e.p),state.busy);
    this.item(menu,'Rename','rename',()=>this.cb.rename(e.p),state.busy);this.divider(menu);
    this.item(menu,'Refresh','refresh',async()=>{e.loaded=false;if(!e.children.hidden)await this.toggle(id,true);else await this.sync(id);},state.busy);
    this.divider(menu);const order=[...this.rows.keys()],position=order.indexOf(id);this.item(menu,'Move up','up',()=>this.move(id,-1),this.orderBusy||position===0);this.item(menu,'Move down','down',()=>this.move(id,1),this.orderBusy||position===order.length-1);
    menu.addEventListener('pointerover',event=>{if(event.target.closest('button')!==sql){this.submenu?.remove();this.submenu=null;sql.setAttribute('aria-expanded','false');}});
    this.position(menu,anchor);sql.focus();
  }
  async openMetadataMenu(id,anchor,refresh,object,group){
    if(this.anchor===anchor){this.closeMenu();return;}this.closeMenu();this.anchor=anchor;this.target=id;anchor.setAttribute('aria-expanded','true');
    const menu=document.createElement('div');menu.className='connection-menu';menu.setAttribute('role','menu');menu.setAttribute('aria-label',anchor.title);this.menu=menu;this.menuKeys(menu);
    let copy,remove,rename,truncate,materializedRefresh,plan;
    if(group?.canCreate)this.item(menu,'New','new',()=>this.cb.create(this.rows.get(id).p,group,object?.refresh??refresh));
    if(object){
      remove=this.item(menu,'Delete','remove',()=>this.cb.objectAction('delete',this.rows.get(id).p,object.selection,plan,object.refresh),true);
      if(object.selection.parent.kind==='tables')truncate=this.item(menu,'Truncate','remove',()=>this.cb.objectAction('truncate',this.rows.get(id).p,object.selection,plan,object.refresh),true);
      if(object.selection.parent.kind==='materialized_views')materializedRefresh=this.item(menu,'Refresh','refresh',()=>this.cb.objectAction('refresh',this.rows.get(id).p,object.selection,plan,object.refresh),true);
    }
    if(group?.canCreate||object)this.divider(menu);
    if(object?.canOpen)this.item(menu,'Open object tab','sql',()=>object.open());
    if(object&&['tables','views','materialized_views'].includes(object.selection.parent.kind)){
      this.item(menu,'Open Visual Query Builder','sql',()=>this.cb.builder(id,object));
      this.item(menu,'Add to active query builder','new',()=>this.cb.addToBuilder({connectionId:id,...object.selection}));
    }
    if(object){
      copy=this.item(menu,'Copy','copy',()=>copyObjectName(plan.name),true);
      rename=this.item(menu,'Rename','rename',()=>this.cb.objectAction('rename',this.rows.get(id).p,object.selection,plan,object.refresh),true);
      for(const b of [copy,remove,rename,truncate,materializedRefresh].filter(Boolean))b.title='Checking this object…';
      this.divider(menu);
    }
    this.item(menu,object?'Refresh metadata':'Refresh','refresh',refresh);this.position(menu,anchor);menu.querySelector('button:not(:disabled)')?.focus();
    if(object)try{
      plan=await this.cb.wait(await this.cb.api('/metadata/object','POST',{connectionId:id,...object.selection}));
      if(this.menu!==menu)return;copy.disabled=false;copy.title='Copy object name';
      for(const[b,cap,label]of [[remove,'canDelete','Delete'],[rename,'canRename','Rename'],[truncate,'canTruncate','Truncate'],[materializedRefresh,'canRefresh','Refresh materialized view']]){
        if(!b)continue;b.disabled=!plan[cap];b.title=plan[cap]?label+' '+plan.name:plan.reason;
      }
    }catch(error){if(this.menu===menu){for(const b of [copy,remove,rename,truncate,materializedRefresh].filter(Boolean))b.title=error.message;this.cb.notice(error.message);}}
  }
  openSqlMenu(parent,id,focus=false){if(!this.menu)return;if(this.submenu){if(focus)this.submenu.querySelector('button:not(:disabled)')?.focus();return;}const submenu=document.createElement('div');submenu.className='connection-menu connection-submenu';submenu.setAttribute('role','menu');submenu.setAttribute('aria-label','SQL Editor');this.submenu=submenu;parent.setAttribute('aria-expanded','true');this.menuKeys(submenu);
    this.item(submenu,'Open SQL script','open',()=>this.cb.open(id));this.item(submenu,'Last edited SQL script','last',()=>this.cb.last(id),!this.cb.hasLast(id));this.item(submenu,'New SQL script','new',()=>this.cb.new(id));
    submenu.addEventListener('keydown',e=>{if(e.key==='ArrowLeft'){e.preventDefault();submenu.remove();this.submenu=null;parent.setAttribute('aria-expanded','false');parent.focus();}});this.position(submenu,parent,true);if(focus)submenu.querySelector('button:not(:disabled)')?.focus();
  }
  async lifecycle(id,action){const entry=this.rows.get(id);try{const result=await this.cb.api('/connections/'+id+'/'+action,'POST',{});if(action!=='disconnect')await this.cb.wait(result);entry.loaded=false;entry.children.replaceChildren();if(action==='disconnect')await this.toggle(id,false);else if(!entry.children.hidden)await this.toggle(id,true);this.cb.notice(action==='disconnect'?'Disconnected '+entry.p.name:'Connected '+entry.p.name);}finally{await this.sync(id);}}
}
