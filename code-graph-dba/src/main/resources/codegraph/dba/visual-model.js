// Stable identities are independent of SQL formatting, aliases and canvas layout.
export const id=()=>crypto.randomUUID();
export const copy=value=>structuredClone(value);
export const column=(source,name)=>({kind:'column',source,name});
export const output=(expression,alias='')=>({id:id(),alias,expression});
export const emptyModel=()=>({version:2,mode:'detail',distinct:false,sources:[],roots:[],parameters:[],where:null,detail:{outputs:[],order:[]},summary:{outputs:[output({kind:'function',name:'COUNT',args:[{kind:'star'}]})],groups:[],order:[],having:null}});
export const active=m=>m[m.mode];
export const findOutput=(m,id)=>[...m.detail.outputs,...m.summary.outputs].find(o=>o.id===id);
export const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
export function leaves(node){return node?.kind==='source'?[node.source]:node?.kind==='join'?[...leaves(node.left),...leaves(node.right)]:[];}
export function joins(m){const all=[];const walk=n=>{if(n?.kind==='join'){all.push(n);walk(n.left);walk(n.right);}};m.roots.forEach(walk);return all;}
export function sharedJoin(m,a,b){const descend=n=>{if(n?.kind!=='join')return null;const left=leaves(n.left),right=leaves(n.right);if(left.includes(a)&&right.includes(b)||left.includes(b)&&right.includes(a))return n;return descend(n.left)||descend(n.right);};return m.roots.map(descend).find(Boolean);}
export function connect(m,left,right,type='INNER',op='='){
  if(left.source===right.source)throw Error('Use a second instance of the source for a self-join.');
  let join=sharedJoin(m,left.source,right.source);
  if(!join){const a=m.roots.find(n=>leaves(n).includes(left.source)),b=m.roots.find(n=>leaves(n).includes(right.source));if(!a||!b)throw Error('A connection source is missing.');join={kind:'join',id:id(),type,left:a,right:b,pairs:[]};m.roots=m.roots.filter(n=>n!==a&&n!==b);m.roots.push(join);}
  if(type==='CROSS'&&join.pairs.length)throw Error('Remove column predicates before choosing CROSS JOIN.');
  if(join.type==='CROSS'&&type!=='CROSS')join.type=type;
  if(type!=='CROSS'&&!join.pairs.some(p=>same(p.left,left)&&same(p.right,right)&&p.op===op))join.pairs.push({id:id(),left:copy(left),right:copy(right),op});
  return join;
}
export function disconnect(m,join){const split=n=>{if(n===join)return null;if(n?.kind==='join'){n.left=split(n.left);n.right=split(n.right);if(!n.left)return n.right;if(!n.right)return n.left;}return n;};m.roots=m.roots.map(split).filter(Boolean);m.roots.push(join.left,join.right);}
export function removeSource(m,source){m.sources=m.sources.filter(s=>s.id!==source);const prune=n=>{if(n.kind==='source')return n.source===source?null:n;n.left=prune(n.left);n.right=prune(n.right);if(!n.left)return n.right;if(!n.right)return n.left;n.pairs=n.pairs.filter(p=>p.left.source!==source&&p.right.source!==source);return n;};m.roots=m.roots.map(prune).filter(Boolean);/* Keep dependent outputs/filters visible for repair. */}
export function label(e,m,seen=new Set()){
 if(!e)return 'Choose expression';if(seen.size>64)return 'Circular reference';
 switch(e.kind){case 'column':return (m.sources.find(s=>s.id===e.source)?.alias??'[missing source]')+'.'+e.name;case 'output':{const o=findOutput(m,e.output);if(!o)return '[missing output]';if(seen.has(o.id))return '[circular output]';seen.add(o.id);return o.alias||label(o.expression,m,seen);}case 'literal':return e.value===null?'NULL':e.type==='text'?JSON.stringify(e.value):String(e.value);case 'parameter':return ':'+(m.parameters.find(p=>p.id===e.parameter)?.name??'[missing parameter]');case 'star':return '*';case 'function':return (e.schema?e.schema+'.':'')+e.name+'('+(e.distinct?'DISTINCT ':'')+(e.args??[]).map(a=>label(a,m,new Set(seen))).join(', ')+')';case 'binary':return '('+label(e.left,m,new Set(seen))+' '+e.op+' '+label(e.right,m,new Set(seen))+')';case 'logical':return '('+(e.args??[]).map(a=>label(a,m,new Set(seen))).join(' '+e.op+' ')+')';case 'not':return 'NOT '+label(e.arg,m,seen);case 'null':return label(e.arg,m,seen)+(e.not?' IS NOT NULL':' IS NULL');case 'between':return label(e.arg,m,seen)+' BETWEEN '+label(e.lower,m,new Set(seen))+' AND '+label(e.upper,m,new Set(seen));case 'in':return label(e.arg,m,seen)+' IN ('+(e.args??[]).map(a=>label(a,m,new Set(seen))).join(', ')+')';case 'case':return 'CASE '+(e.branches??[]).map(b=>'WHEN '+label(b.when,m,new Set(seen))+' THEN '+label(b.then,m,new Set(seen))).join(' ')+' ELSE '+label(e.else,m,new Set(seen))+' END';default:return 'Choose expression';}
}
export function dependencies(e,m){const problems=[];const visit=(n,seen=new Set())=>{if(!n||typeof n!=='object')return;if(seen.size>64){problems.push('Circular output reference');return;}if(n.kind==='column'){const s=m.sources.find(s=>s.id===n.source);if(!s||!s.columns.some(c=>c.name===n.name))problems.push('Missing column: '+label(n,m));}if(n.kind==='output'){const o=findOutput(m,n.output);if(!o)problems.push('Missing output');else if(seen.has(o.id))problems.push('Circular output reference');else{const next=new Set(seen);next.add(o.id);visit(o.expression,next);}}if(n.kind==='parameter'&&!m.parameters.some(p=>p.id===n.parameter))problems.push('Missing parameter');for(const [k,v]of Object.entries(n))if(typeof v==='object')visit(v,new Set(seen));};visit(e);return [...new Set(problems)];}
export function descriptor(m,e){if(e.kind==='output')return descriptor(m,findOutput(m,e.output)?.expression??{});if(e.kind==='column')return m.sources.find(s=>s.id===e.source)?.columns.find(c=>c.name===e.name);return {jdbcType:e.jdbcType??12,type:e.type??'text'};}
export const aggregate=e=>!!e&&((e.kind==='function'&&(e.aggregate||!e.schema&&['COUNT','SUM','AVG','MIN','MAX'].includes(e.name)))||Object.values(e).some(v=>typeof v==='object'&&v&&(Array.isArray(v)?v.some(aggregate):aggregate(v))));
