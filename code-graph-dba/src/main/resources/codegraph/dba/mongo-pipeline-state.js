/** Bounded, memory-only pipeline draft. This is not a server authorization boundary. */
export const PIPELINE_LIMITS=Object.freeze({stages:64,bytes:128*1024,depth:32,nodes:16384});
export const PIPELINE_STAGES=Object.freeze({
  $match:['Keep matching documents',{}],
  $project:['Choose fields or expressions',{_id:1}],
  $sort:['Order documents',{_id:1}],
  $limit:['Keep at most this many documents',100],
  $skip:['Skip this many documents',0],
  $group:['Group and aggregate',{_id:null,count:{$sum:1}}],
  $unwind:['Expand an array','$field'],
  $addFields:['Add computed fields',{newField:{$literal:null}}],
  $set:['Set computed fields',{newField:{$literal:null}}],
  $unset:['Remove fields from the result','field'],
  $replaceRoot:['Replace the result document',{newRoot:'$$ROOT'}],
  $replaceWith:['Replace the result document','$$ROOT'],
  $count:['Count documents','count'],
  $sortByCount:['Group and count a value','$field'],
  $sample:['Random sample',{size:100}],
  $bucket:['Group into explicit ranges',{groupBy:'$field',boundaries:[0,10],default:'other'}],
  $bucketAuto:['Group into estimated ranges',{groupBy:'$field',buckets:5}]
});
export const pipelineBytes=text=>new TextEncoder().encode(text).length;

// Check source tokens before JSON.parse can discard duplicate keys or round an
// integer. Canonical BSON wrappers keep int64/Decimal128 values as strings.
export function parsePipelineJson(text){
  if(pipelineBytes(text)>PIPELINE_LIMITS.bytes)throw Error('Pipeline draft exceeds 128 KiB.');
  let parsed;try{parsed=JSON.parse(text);}catch{throw Error('Enter valid Extended JSON; JavaScript expressions are not supported.');}
  let pos=0,nodes=0;
  const space=()=>{while(pos<text.length&&/\s/.test(text[pos]))pos++;};
  const string=()=>{const start=pos++;while(pos<text.length){const c=text[pos++];if(c==='\\')pos++;else if(c==='"')return JSON.parse(text.slice(start,pos));}};
  const value=depth=>{
    if(depth>PIPELINE_LIMITS.depth||++nodes>PIPELINE_LIMITS.nodes)throw Error('Pipeline exceeds 32 nesting levels or 16,384 values.');
    space();const c=text[pos];
    if(c==='{'){
      pos++;space();const names=new Set();if(text[pos]==='}'){pos++;return;}
      for(;;){space();const name=string();if(names.has(name))throw Error('Duplicate JSON fields are not supported; combine expressions explicitly.');
        names.add(name);space();pos++;value(depth+1);space();if(text[pos++]==='}'){
          // JavaScript reorders array-index-shaped object keys. BSON ordering
          // can matter (compound sorts, embedded-document equality); fail closed.
          const keys=[...names],indexKey=key=>/^(0|[1-9][0-9]*)$/.test(key)&&Number(key)<4294967295;
          const roundTrip=[...keys.filter(indexKey).sort((a,b)=>Number(a)-Number(b)),...keys.filter(key=>!indexKey(key))];
          if(keys.some((key,i)=>key!==roundTrip[i]))throw Error('Numeric field names would be reordered by JSON serialization. This builder cannot preserve that object; use explicit nonnumeric field aliases where appropriate.');
          return;
        }}
    }else if(c==='['){
      pos++;space();if(text[pos]===']'){pos++;return;}for(;;){value(depth+1);space();if(text[pos++]===']')return;}
    }else if(c==='"')string();
    else{
      const start=pos;while(pos<text.length&&!/[\s,\]}]/.test(text[pos]))pos++;
      const token=text.slice(start,pos);
      if(/^[-0-9]/.test(token)){const number=Number(token);if(!Number.isFinite(number)||Number.isInteger(number)&&!Number.isSafeInteger(number)||Object.is(number,-0))throw Error('Use canonical $numberLong, $numberDecimal or $numberDouble wrappers for unsafe integers, negative zero or non-finite values.');}
    }
  };value(0);return parsed;
}
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
export class MongoPipelineState{
  constructor(collection,text=''){
    if(typeof collection!=='string'||!collection.trim()||collection.includes('\0'))throw Error('Choose an exact collection before building a pipeline.');
    this.collection=collection;this.stages=[];this.sequence=0;this.allowDiskUse=false;this.imported=false;
    if(text.trim()){
      const command=parsePipelineJson(text);
      if(object(command)&&Object.hasOwn(command,'aggregate')){
        if(command.aggregate!==collection)throw Error('The command collection differs from the selected collection. Correct the target first.');
        for(const key of Object.keys(command))if(!['aggregate','pipeline','allowDiskUse'].includes(key))throw Error('The builder cannot preserve option '+key+'. Edit this command directly.');
        if(!Array.isArray(command.pipeline))throw Error('An aggregate command requires a pipeline array.');
        if(Object.hasOwn(command,'allowDiskUse')&&typeof command.allowDiskUse!=='boolean')throw Error('allowDiskUse must be true or false.');
        this.allowDiskUse=command.allowDiskUse===true;
        for(const stage of command.pipeline){
          if(!object(stage)||Object.keys(stage).length!==1)throw Error('Each pipeline stage must contain exactly one operator.');
          const name=Object.keys(stage)[0];this.add(name,JSON.stringify(stage[name]));
        }
        this.imported=true;this.command();
      }
    }
  }
  stage(id){const stage=this.stages.find(s=>s.id===id);if(!stage)throw Error('Stage is no longer in this draft.');return stage;}
  assertBound(extra='',except=null){
    if(this.stages.reduce((n,s)=>n+(s.id===except?0:pipelineBytes(s.text))+pipelineBytes(s.name)+32,0)+pipelineBytes(extra)>PIPELINE_LIMITS.bytes)throw Error('Combined stage drafts exceed 128 KiB; disabled stages count toward this limit.');
  }
  add(name,text){
    if(!Object.hasOwn(PIPELINE_STAGES,name))throw Error('Unsupported builder stage '+name+'. Keep this command in the native editor; no stages were discarded.');
    if(this.stages.length>=PIPELINE_LIMITS.stages)throw Error('At most 64 stages are supported, including disabled stages.');
    const value=text??JSON.stringify(PIPELINE_STAGES[name][1],null,2);this.assertBound(value+name);
    const stage={id:'stage-'+(++this.sequence),name,text:value,enabled:true};this.stages.push(stage);return stage.id;
  }
  edit(id,text){this.assertBound(text,id);this.stage(id).text=text;}
  move(id,index){const from=this.stages.findIndex(s=>s.id===id);if(from<0||!Number.isInteger(index)||index<0||index>=this.stages.length)return false;const [stage]=this.stages.splice(from,1);this.stages.splice(index,0,stage);return true;}
  remove(id){this.stage(id);this.stages=this.stages.filter(s=>s.id!==id);}
  command(){
    const pipeline=this.stages.filter(s=>s.enabled).map(stage=>({[stage.name]:parsePipelineJson(stage.text)}));
    const command={aggregate:this.collection,pipeline,allowDiskUse:this.allowDiskUse},text=JSON.stringify(command);
    parsePipelineJson(text);return command;
  }
  text(){const command=this.command(),pretty=JSON.stringify(command,null,2);return pipelineBytes(pretty)<=PIPELINE_LIMITS.bytes?pretty:JSON.stringify(command);}
}
