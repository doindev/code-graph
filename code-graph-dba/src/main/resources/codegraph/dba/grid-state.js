// Result-lifetime state, independent of DOM, Script tabs, transports and timers.
const draftReservations=new Set(),SHARED_DRAFT_BYTES=4*1024*1024;
export class GridDraft {
  constructor(result,{maxRows=10000,maxBytes=4*1024*1024}={}) {
    this.result=result;this.maxRows=maxRows;this.maxBytes=maxBytes;
    this.selection=new Set();this.active=null;this.anchor=null;this.activeColumn=null;
    this.changes=new Map();this.added=[];this.sequence=0;
  }
  get length(){return (this.result.rows?.length??0)+this.added.length;}
  get dirty(){return this.changes.size>0||this.added.length>0;}
  get capabilities(){return this.result.grid?.capabilities??{};}
  id(index){return index<(this.result.rows?.length??0)?this.result.grid?.rowIds?.[index]??'read:'+index:this.added[index-this.result.rows.length]?.id;}
  select(index,{shiftKey=false,ctrlKey=false,metaKey=false}={}) {
    if(index<0||index>=this.length)return;
    if(shiftKey&&this.anchor!==null){this.selection.clear();for(let i=Math.min(index,this.anchor);i<=Math.max(index,this.anchor);i++)this.selection.add(i);}
    else if(ctrlKey||metaKey){this.selection.has(index)?this.selection.delete(index):this.selection.add(index);this.anchor=index;}
    else {this.selection.clear();this.selection.add(index);this.anchor=index;}
    this.active=index;
  }
  clearSelection(){this.selection.clear();this.active=this.anchor=null;}
  column(id){return this.result.grid?.columns?.find(c=>c.id===id);}
  cell(index,column){
    const added=this.added[index-(this.result.rows?.length??0)];
    const changed=(added?.values??this.changes.get(this.id(index))?.values)?.[column.id];
    return changed??{kind:this.result.rows?.[index]?.[column.source]===null?'null':'value',value:this.result.rows?.[index]?.[column.source]};
  }
  deleted(index){return this.changes.get(this.id(index))?.operation==='delete';}
  canEdit(index,id){return !!this.capabilities.edit&&!this.deleted(index)&&!!this.column(id)?.editable;}
  checkSize(changes=this.changes,added=this.added){
    if(changes.size+added.length>this.maxRows)throw Error('Too many pending rows; save or cancel changes first.');
    const bytes=changes.size||added.length?new TextEncoder().encode(JSON.stringify({changes:[...changes.values()],added})).length*3:0;
    let used=0;for(const ref of draftReservations){const draft=ref.deref();if(!draft)draftReservations.delete(ref);else if(draft!==this)used+=draft.reservedBytes??0;}
    if(bytes>this.maxBytes||used+bytes>SHARED_DRAFT_BYTES)throw Error('Shared grid draft allowance full; save or cancel pending changes first.');
    this.reservedBytes=bytes;if(!this.budgetRef)this.budgetRef=new WeakRef(this);if(bytes)draftReservations.add(this.budgetRef);else draftReservations.delete(this.budgetRef);
  }
  set(index,column,value){
    if(!this.canEdit(index,column.id))throw Error(this.capabilities.reason||'This cell is read-only.');
    if(!['value','null','default'].includes(value.kind))throw Error('Choose a value, NULL, or DEFAULT.');
    const metadata=this.column(column.id);
    if(value.kind==='null'&&!metadata.nullable)throw Error('This column does not allow NULL.');
    if(value.kind==='default'&&!metadata.hasDefault)throw Error('This column has no usable default.');
    if(value.kind==='value'&&typeof value.value!=='string')throw Error('Cell values must be text; the server validates their database type.');
    const current=this.cell(index,column);
    if(current.kind===value.kind&&(value.kind!=='value'||String(current.value)===value.value))return;
    const insert=index-(this.result.rows?.length??0);
    if(insert>=0){const added=this.added.map((row,i)=>i===insert?{...row,values:{...row.values,[column.id]:value}}:row);this.checkSize(this.changes,added);this.added=added;return;}
    const id=this.id(index),old=this.result.rows[index][column.source];
    const values={...(this.changes.get(id)?.values??{})};
    if(value.kind==='null'&&old===null||value.kind==='value'&&old!==null&&String(old)===value.value)delete values[column.id];else values[column.id]=value;
    const changes=new Map(this.changes);if(Object.keys(values).length)changes.set(id,{rowId:id,operation:'update',values});else changes.delete(id);
    this.checkSize(changes);this.changes=changes;
  }
  setMany(edits){
    // Validate and reserve the entire replacement before exposing any draft change.
    const candidate=Object.assign(Object.create(GridDraft.prototype),this,{checkSize(){}});
    for(const {index,column,value} of edits){
      validateCell(this.column(column.id),value);
      candidate.set(index,column,value);
    }
    this.checkSize(candidate.changes,candidate.added);
    this.changes=candidate.changes;this.added=candidate.added;
  }
  add(){
    if(!this.capabilities.insert)throw Error(this.capabilities.reason||'Adding rows is unavailable for this result.');
    const values={};for(const c of this.result.grid.columns)if(c.editable)values[c.id]=c.hasDefault?{kind:'default'}:c.nullable?{kind:'null'}:{kind:'value',value:''};
    const row={id:'new:'+ ++this.sequence,operation:'insert',values},added=[...this.added,row];this.checkSize(this.changes,added);this.added=added;this.select(this.length-1);return this.length-1;
  }
  deleteSelected(){
    if(!this.capabilities.delete)throw Error(this.capabilities.reason||'Deleting rows is unavailable for this result.');
    const changes=new Map(this.changes),removed=new Set();
    for(const index of this.selection){const id=this.id(index);if(index>=(this.result.rows?.length??0))removed.add(id);else changes.set(id,{rowId:id,operation:'delete',values:{}});}
    const added=this.added.filter(row=>!removed.has(row.id));this.checkSize(changes,added);this.changes=changes;this.added=added;this.clearSelection();
  }
  payload(){const changes=[...this.changes.values(),...this.added.map(({id,...row})=>({...row,rowId:id}))];for(const row of changes)for(const [id,value]of Object.entries(row.values))validateCell(this.column(id),value);return{revision:this.result.grid?.revision,changes};}
  cancel(){this.changes.clear();this.added=[];this.clearSelection();this.reservedBytes=0;if(this.budgetRef)draftReservations.delete(this.budgetRef);}
}

export function validateCell(column,value){
  if(!column)throw Error('Unknown column');if(value.kind!=='value')return;
  const text=value.value,type=column.jdbcType,integer=[-6,5,4,-5].includes(type),numeric=[2,3,6,7,8].includes(type);
  if(integer&&!/^[+-]?[0-9]+$/.test(text)||numeric&&!/^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$/.test(text))throw Error(column.name+' requires a '+(integer?'whole number':'number')+'.');
  if(integer){const bits=type===-6?8:type===5?16:type===4?32:64,n=BigInt(text);if(n<(column.unsigned?0n:-(1n<<BigInt(bits-1)))||n>=(1n<<BigInt(column.unsigned?bits:bits-1)))throw Error(column.name+' is outside its integer range.');}
  if([16,-7].includes(type)&&!['true','false','1','0'].includes(text.toLowerCase()))throw Error(column.name+' requires true, false, 1, or 0.');
  if(type===91&&!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(text))throw Error(column.name+' requires YYYY-MM-DD.');
  if(type===92&&!/^[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\.[0-9]{1,9})?)?$/.test(text))throw Error(column.name+' requires HH:MM:SS.');
  if(type===93&&!/^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}/.test(text))throw Error(column.name+' requires a date and time.');
  if([1,12,-15,-9].includes(type)&&column.size>0&&[...text].length>column.size)throw Error(column.name+' allows at most '+column.size+' characters.');
}

export function pageSize(value,ceiling=1000){
  if(!/^[0-9]{1,7}$/.test(value)||Number(value)<1||Number(value)>ceiling)throw Error('Rows per page must be between 1 and '+ceiling+'.');
  return Number(value);
}
