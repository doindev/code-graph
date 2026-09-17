import {approvalHeaders} from './approval-client.js';
import {installApprovalUI} from './approval-ui.js';
import {connectionEditor} from './connection-editor.js';
let csrf='',ready=false;
const error=document.getElementById('review-error');
async function api(path,method='GET',body){
  const response=await fetch('/api/dba'+path,{method,credentials:'same-origin',headers:{'Content-Type':'application/json','X-Dba-CSRF':csrf,...approvalHeaders(path)},body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json();if(!response.ok)throw new Error(result.error||'Review unavailable');return result;
}
async function start(input){
  if(ready)return;
  try{
    const session=input?await api('/review-bootstrap','POST',input):await api('/session');csrf=session.csrf;ready=true;
    document.getElementById('pair-code').value='';document.getElementById('pair').hidden=true;
    const button=document.getElementById('review-open');button.hidden=false;
    const editor=connectionEditor({api,toast:message=>error.textContent=message,saved:()=>{},csrf:()=>csrf});
    installApprovalUI({api,button,only:session.requestId,reviewConnection:(request,done)=>editor.openProposal(request,done)});
  }catch(e){if(input)error.textContent=e.message;}
}
const token=location.hash.slice(1);history.replaceState(null,'',location.pathname);
if(token)void start({token});
else void start();
document.getElementById('pair').onsubmit=event=>{event.preventDefault();void start({code:document.getElementById('pair-code').value.trim()});};
