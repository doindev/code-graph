// Regex work never runs on the UI thread. The owner terminates overdue workers.
const MAX_MATCHES=20000,MAX_TEXT=8*1024*1024,MAX_CELLS=256000;
const word=/[\p{L}\p{N}\p{M}_]/u;
function substitution(template,match,text){
  return template.replace(/\$([$&`']|[0-9]{1,2}|<[^>]+>)/g,(token,key)=>{
    if(key==='$')return '$';if(key==='&')return match[0];
    if(key==='`')return text.slice(0,match.index);if(key==="'")return text.slice(match.index+match[0].length);
    if(key.startsWith('<'))return match.groups?match.groups[key.slice(1,-1)]??'':token;
    const n=Number(key);if(n>0&&n<match.length)return match[n]??'';
    if(key.length===2&&Number(key[0])>0&&Number(key[0])<match.length)return (match[Number(key[0])]??'')+key[1];
    return token;
  });
}
export function searchCells({query,regex=false,caseSensitive=false,wholeWord=false,cells=[],replacement='',replace=false,current=null}){
  if(typeof query!=='string'||query.length>512)throw Error('Search text is limited to 512 characters.');
  if(typeof replacement!=='string'||replacement.length>8192)throw Error('Replacement text is limited to 8192 characters.');
  if(cells.length>MAX_CELLS)throw Error('Too many loaded cells to search. Reduce the row limit.');
  if(!query)return{matches:[],changes:[],skipped:0};
  let expression;try{expression=new RegExp(regex?query:query.replace(/[.*+?^${}()|[\]\\]/g,'\\$&'),'gu'+(caseSensitive?'':'i'));}catch(error){throw Error('Invalid regular expression: '+error.message);}
  const matches=[],changes=[];let size=0,output=0,skipped=0;
  for(const cell of cells){
    const text=cell.text;size+=text.length*2;if(size>MAX_TEXT)throw Error('Search allowance exceeded. Reduce the row limit.');
    expression.lastIndex=0;let match,last=0,next='',changed=false;
    while((match=expression.exec(text))!==null){
      const start=match.index,end=start+match[0].length;
      const before=[...text.slice(Math.max(0,start-2),start)].at(-1),after=String.fromCodePoint(text.codePointAt(end)??0);
      if(!wholeWord||!(before&&word.test(before))&&!word.test(after)){
        if(matches.length===MAX_MATCHES)throw Error('More than 20,000 matches. Narrow the search before replacing.');
        matches.push({row:cell.row,column:cell.column,start,end});
        if(replace&&(!current||current.row===cell.row&&current.column===cell.column&&current.start===start&&current.end===end)){
          if(cell.editable){
            next+=text.slice(last,start)+(regex?substitution(replacement,match,text):replacement);last=end;changed=true;
            if(next.length>8192)throw Error('Replacement exceeds the 8192-character cell limit.');
          }else skipped++;
        }
      }
      if(!match[0].length){if(end>=text.length)break;expression.lastIndex=end+(text.codePointAt(end)>0xffff?2:1);}
    }
    if(changed){next+=text.slice(last);output+=next.length*2;if(next.length>8192||output>MAX_TEXT)throw Error('Replacement allowance exceeded. Narrow the search.');if(next!==text)changes.push({row:cell.row,column:cell.column,text:next});}
  }
  return{matches,changes,skipped};
}
if(typeof WorkerGlobalScope!=='undefined'&&globalThis instanceof WorkerGlobalScope){
  globalThis.onmessage=({data})=>{try{globalThis.postMessage({result:searchCells(data)});}catch(error){globalThis.postMessage({error:error.message});}};
}
