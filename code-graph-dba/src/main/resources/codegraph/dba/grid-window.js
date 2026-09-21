// A rolling, overlapping page, not an ever-growing browser-side result inventory.
const ROW_HEIGHT=28;
export function replaceGridPage(result,next,state,window=false){
  if(result===next)return;
  const previous=result.grid?.page?.offset??0,offset=next.grid?.page?.offset??0;
  const scroll=state.view?.scroll;
  const position=scroll?{left:scroll.scrollLeft,top:scroll.scrollTop}:state.scroll;
  const keys=(result.grid?.columns??[]).filter(column=>column.key).map(column=>result.columns.findIndex(c=>c.id===column.id));
  const identity=(rows,index)=>index===null||index===undefined||!rows?.[index]||!keys.length?null:JSON.stringify(keys.map(key=>rows[index][key]));
  const selectedKeys=window?new Set([...state.draft.selection].map(index=>identity(result.rows,index)).filter(key=>key!==null)):null;
  const activeKey=identity(result.rows,state.draft.active),anchorKey=identity(result.rows,state.draft.anchor),selectedKey=identity(result.rows,state.selectedRow);
  Object.assign(result,next);
  if(!window){state.selectedRow=null;state.draft.clearSelection();return;}
  const positions=new Map((result.rows??[]).map((_,index)=>[identity(result.rows,index),index]));
  const locate=key=>key===null?null:positions.get(key)??null;
  state.draft.selection=new Set([...selectedKeys].map(locate).filter(index=>index!==null));
  state.draft.active=locate(activeKey);state.draft.anchor=locate(anchorKey);state.selectedRow=locate(selectedKey);
  if(position)state.scroll={left:position.left,top:Math.max(0,position.top+(previous-offset)*ROW_HEIGHT)};
  state.windowScroll=true;
}

export class GridScrollWindow {
  constructor(view){
    this.view=view;this.top=view.scroll.scrollTop;this.pending=false;
    // Tiny pages may not have a scrollbar; a wheel or arrow still advances them.
    this.wheel=event=>{if(Math.abs(event.deltaY)>Math.abs(event.deltaX)&&view.scroll.scrollHeight<=view.scroll.clientHeight+1)this.request(Math.sign(event.deltaY));};
    this.key=event=>{if(event.target===view.scroll&&['ArrowDown','ArrowUp','PageDown','PageUp'].includes(event.key)&&view.scroll.scrollHeight<=view.scroll.clientHeight+1)this.request(event.key.endsWith('Down')?1:-1);};
    view.scroll.addEventListener('wheel',this.wheel,{passive:true});view.scroll.addEventListener('keydown',this.key);
  }
  reset(){this.top=this.view.scroll.scrollTop;}
  scrolled(){
    const top=this.view.scroll.scrollTop,direction=Math.sign(top-this.top);this.top=top;
    if(direction)this.request(direction);
  }
  request(direction){
    const v=this.view,page=v.result.grid?.page,n=v.result.rows?.length??0;
    if(!direction||this.pending||v.disposed||!v.host.isConnected||!v.host.getClientRects().length||!v.transform||!v.result.grid?.capabilities.page||v.controller?.busy||v.result.grid.uncertain||v.state.draft.dirty||v.finishCell||v.find?.state.open||document.querySelector('dialog[open]')||!n)return;
    const threshold=Math.max(3*ROW_HEIGHT,Math.min(v.scroll.clientHeight,10*ROW_HEIGHT));
    const near=direction>0?v.scroll.scrollHeight-v.scroll.clientHeight-v.scroll.scrollTop<=threshold:v.scroll.scrollTop<=threshold;
    if(!near||(direction>0?!page.hasMore:!page.offset))return;
    const visible=Math.ceil(v.scroll.clientHeight/ROW_HEIGHT),step=Math.max(1,Math.min(Math.floor(n/2),n-visible-2));
    const offset=Math.max(0,page.offset+direction*step);if(offset===page.offset)return;
    this.pending=true;
    void v.transform({action:'page',direction:'window',offset,limit:page.limit,automatic:true})
      .catch(error=>{if(!v.disposed){v.queryError.hidden=false;v.queryError.textContent=error.message;}})
      .finally(()=>{this.pending=false;this.reset();});
  }
  dispose(){this.view.scroll.removeEventListener('wheel',this.wheel);this.view.scroll.removeEventListener('keydown',this.key);}
}
