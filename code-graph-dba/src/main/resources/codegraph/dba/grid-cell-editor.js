// A view-owned portal: never steals horizontal space from the edited cell.
export class FloatingCellMode {
  constructor(view,cell,select,finish){
    this.view=view;this.cell=cell;this.finish=finish;
    this.host=document.createElement('div');this.host.className='grid-cell-options';
    this.host.append(select);document.body.append(this.host);
    this.schedule=()=>{if(!this.disposed&&!this.frame)this.frame=requestAnimationFrame(()=>{this.frame=0;this.position();});};
    this.observer=new ResizeObserver(this.schedule);
    for(const node of [cell,view.scroll,view.stage])this.observer.observe(node);
    window.addEventListener('resize',this.schedule);
    document.addEventListener('scroll',this.schedule,true);
    this.position();
  }
  position(){
    if(this.disposed)return;
    const cell=this.cell,scroll=this.view.scroll,r=cell.getBoundingClientRect(),s=scroll.getBoundingClientRect();
    const left=Math.max(4,s.left+this.view.rowNumberWidth),right=Math.min(innerWidth-4,s.left+scroll.clientWidth);
    const top=Math.max(4,s.top+28),bottom=Math.min(innerHeight-4,s.top+scroll.clientHeight);
    if(!cell.isConnected||r.right<=left||r.left>=right||r.bottom<=top||r.top>=bottom){this.finish();return;}
    const w=this.host.offsetWidth,h=this.host.offsetHeight,gap=3;
    let x=r.right+gap,y=Math.max(top,Math.min(r.top,bottom-h));
    if(x+w>right){
      if(r.left-gap-w>=left)x=r.left-gap-w;
      else{x=Math.max(left,Math.min(r.left,right-w));y=r.bottom+gap+h<=bottom?r.bottom+gap:r.top-gap-h;}
    }
    this.host.style.left=Math.max(4,Math.min(x,innerWidth-w-4))+'px';
    this.host.style.top=Math.max(4,Math.min(y,innerHeight-h-4))+'px';
  }
  contains(node){return this.host.contains(node);}
  destroy(){
    if(this.disposed)return;this.disposed=true;
    cancelAnimationFrame(this.frame);this.observer.disconnect();
    window.removeEventListener('resize',this.schedule);document.removeEventListener('scroll',this.schedule,true);
    this.host.remove();
  }
}
