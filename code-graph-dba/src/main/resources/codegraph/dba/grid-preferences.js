import schema from './grid-settings-schema.json' with {type:'json'};
export const fields=schema.fields;
export const defaults=Object.freeze(Object.fromEntries(fields.map(f=>[f.key,f.default])));
const results=new WeakMap();
let catalog={revision:0,global:{},connections:{},rowCeiling:1000},transport;
export async function initializeGridPreferences(api){transport=api;return reloadPreferences();}
export async function reloadPreferences(){if(!transport)return catalog;catalog=await transport('/grid-settings');return catalog;}
export function preferenceCatalog(){return catalog;}
export function preferenceState(result,connectionId=result?.grid?.connectionId){let state=results.get(result);if(!state){state={connectionId,global:{...catalog.global},connection:{...catalog.connections[connectionId]},local:{}};results.set(result,state);}else if(!state.connectionId&&connectionId){state.connectionId=connectionId;state.connection={...catalog.connections[connectionId]};}return state;}
export function preferences(result,connectionId){const state=preferenceState(result,connectionId);return {...defaults,...state.global,...state.connection,...state.local};}
export function pagePreference(result,connectionId){return Math.min(preferences(result,connectionId).pageSize,result?.grid?.rowCeiling??catalog.rowCeiling);}
export function defaultPageSize(connectionId){return Math.min(catalog.connections[connectionId]?.pageSize??catalog.global.pageSize??defaults.pageSize,catalog.rowCeiling);}
export function validatePreferences(values,ceiling=10000){
  for(const [key,value] of Object.entries(values)){
    const f=fields.find(f=>f.key===key);if(!f)throw Error('Unknown grid setting: '+key);
    const valid=f.type==='boolean'?typeof value==='boolean':f.type==='integer'?Number.isInteger(value)&&value>=f.min&&value<=Math.min(f.max,key==='pageSize'?ceiling:f.max):f.type==='enum'?typeof value==='string'&&Object.hasOwn(f.options,value):typeof value==='string'&&[...value].length<=f.maxLength&&!/[\r\n\0]/.test(value);
    if(!valid)throw Error('Invalid '+f.label);
  }
  return {...values};
}
export async function savePreferences(result,scope,values,revision){
  const state=preferenceState(result);validatePreferences(values,catalog.rowCeiling);
  if(scope==='result')state.local={...values};
  else{
    if(!transport)throw Error('Persistent settings are unavailable in this grid.');
    const next=await transport('/grid-settings','PUT',{scope,settings:values,expectedRevision:revision,...(scope==='connection'?{connectionId:state.connectionId}:{})});
    catalog=next;state.global={...next.global};state.connection={...next.connections[state.connectionId]};
  }
  return catalog;
}
export function rowHeight(result){const s=preferences(result);return Math.max(s.rowHeight,s.fontSize+10);}
export function formatNumber(value,locale){
  const text=String(value),match=text.match(/^([+-]?)(\d+)(\.\d*)?$/);
  if(!match)return text;
  try{
    const formatter=new Intl.NumberFormat(locale,{useGrouping:true,maximumFractionDigits:0});
    const decimal=new Intl.NumberFormat(locale).formatToParts(1.1).find(p=>p.type==='decimal')?.value??'.';
    const integer=formatter.format(BigInt(match[2]));
    return match[1]+integer+(match[3]?decimal+match[3].slice(1):'');
  }catch{return text;}
}
export function formatCell(value,column,settings){
  if(value.kind==='null')return settings.nullText;
  if(value.kind==='default')return 'DEFAULT';
  let text=String(value.value??'');if(text==='')return settings.emptyText;
  if(settings.numbers==='grouped'&&[-6,5,4,-5,2,3,6,7,8].includes(column.jdbcType))text=formatNumber(text);
  if(settings.dates==='iso'&&[91,92,93,2013,2014].includes(column.jdbcType))text=text.replace(/^(\d{4}-\d{2}-\d{2}) /,'$1T');
  return text;
}
// Native controls cannot preserve nanoseconds or zoned timestamps; retain text for those values.
export function dateInputType(column,value){
  const text=String(value??'');
  if(column.jdbcType===91&&(!text||/^\d{4}-\d{2}-\d{2}$/.test(text)))return 'date';
  if(column.jdbcType===92&&(!text||/^\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?$/.test(text)))return 'time';
  if(column.jdbcType===93&&(!text||/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?$/.test(text)))return 'datetime-local';
  return null;
}
