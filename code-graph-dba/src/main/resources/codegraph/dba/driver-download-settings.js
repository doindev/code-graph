import {lucide} from './tree-icons.js';

// Application preferences only. The contents of settings.xml / PEM are never sent to the browser.
export function installDriverDownloadSettings({api,toast}) {
  const button=document.createElement('button');button.id='driver-download-settings';button.setAttribute('role','menuitem');
  button.title='Configure Maven, corporate mirrors and certificate trust';button.append(lucide('download'),document.createTextNode('Driver downloads'));
  document.getElementById('workspace-settings-menu').append(button);
  const dialog=document.createElement('dialog');dialog.id='driver-download-settings-dialog';dialog.setAttribute('aria-labelledby','driver-download-title');
  dialog.innerHTML='<form><h2 id="driver-download-title">Driver downloads</h2><p>Use installed Maven when your company routes packages through a private mirror.</p>'
    +'<label>Download method<select name="mode"><option value="embedded">Embedded Maven · public Maven Central</option><option value="maven">Installed Maven · corporate settings</option></select></label>'
    +'<fieldset><legend>Installed Maven</legend><label>Maven executable (optional)<input name="command" placeholder="mvn / mvn.cmd from PATH" autocomplete="off" spellcheck="false"></label>'
    +'<label>Maven settings.xml (optional)<span class="driver-path-field"><input name="settings" autocomplete="off" spellcheck="false"><button type="button" data-browse="settings">Browse…</button></span></label>'
    +'<p class="muted" id="driver-default-settings"></p>'
    +'<label>CA certificate PEM (optional)<span class="driver-path-field"><input name="certPem" placeholder="Full path to public CA certificate.pem" autocomplete="off" spellcheck="false"><button type="button" data-browse="certPem">Browse…</button></span></label>'
    +'<p class="muted">Adds public certificates to a temporary trust store for Maven only. The original files and system trust store are not modified.</p>'
    +'<label class="driver-insecure-choice"><input name="insecureTls" type="checkbox">Disable TLS certificate verification for driver downloads (unsafe)</label>'
    +'<p id="driver-tls-warning" role="status" hidden>Warning: certificate trust, hostname and expiry checks are disabled for this Maven process. A supplied PEM is ignored. Mirror routing and artifact checksum checks remain enabled. Use only on a trusted network.</p></fieldset>'
    +'<p class="muted">Apply saves these preferences in the application settings.json. Existing connections stay pinned. Downloads already running retain their original configuration.</p>'
    +'<p id="driver-settings-status" role="status" aria-live="polite"></p><div class="actions"><button type="button" data-cancel>Cancel</button><button type="submit" disabled>Apply</button></div></form>';
  document.body.append(dialog);
  const form=dialog.querySelector('form'),status=dialog.querySelector('#driver-settings-status'),apply=form.querySelector('[type=submit]');
  let original='',busy=false,picker=null,pickerCancelled=false;
  const data=()=>({mode:form.elements.mode.value,command:form.elements.mode.value==='maven'?form.elements.command.value.trim():'',
    settings:form.elements.mode.value==='maven'?form.elements.settings.value.trim():'',
    certPem:form.elements.mode.value==='maven'?form.elements.certPem.value.trim():'',
    insecureTls:form.elements.mode.value==='maven'&&form.elements.insecureTls.checked});
  function update(){form.elements.mode.disabled=busy;form.querySelector('fieldset').disabled=busy||form.elements.mode.value!=='maven';apply.disabled=busy||original===JSON.stringify(data());dialog.querySelector('#driver-tls-warning').hidden=!data().insecureTls;}
  form.addEventListener('input',update);form.addEventListener('change',update);
  const close=()=>{pickerCancelled=true;if(picker)api('/jobs/'+picker+'/cancel','POST',{}).catch(()=>{});dialog.close();};
  dialog.querySelector('[data-cancel]').onclick=close;dialog.addEventListener('cancel',e=>{e.preventDefault();close();});
  dialog.addEventListener('close',()=>document.getElementById('workspace-settings').focus());
  button.onclick=async()=>{try{
    const config=await api('/settings/driver-downloads');
    for(const key of ['mode','command','settings','certPem'])form.elements[key].value=config[key]??'';
    form.elements.insecureTls.checked=!!config.insecureTls;
    form.elements.settings.placeholder=config.defaultSettings;
    dialog.querySelector('#driver-default-settings').textContent='Blank uses '+config.defaultSettings+' and Maven’s global settings. File paths are local to the server.';
    original=JSON.stringify(data());status.textContent='';update();dialog.showModal();
  }catch(e){toast(e.message);}};
  form.onsubmit=async e=>{e.preventDefault();busy=true;update();status.textContent='Saving…';try{
    await api('/settings/driver-downloads','PUT',data());original=JSON.stringify(data());dialog.close();toast('Driver download settings saved.');
  }catch(e){status.textContent=e.message;}finally{busy=false;update();}};
  for(const browse of dialog.querySelectorAll('[data-browse]'))browse.onclick=async()=>{
    busy=true;pickerCancelled=false;update();status.textContent='Choose an existing local file…';
    try{
      const job=await api('/setup/file-select','POST',{kind:browse.dataset.browse==='settings'?'maven-settings':'maven-cert'});
      picker=job.id;if(pickerCancelled){await api('/jobs/'+picker+'/cancel','POST',{});return;}
      for(;;){
        await new Promise(resolve=>setTimeout(resolve,200));
        const state=await api('/jobs/'+picker);
        if(pickerCancelled)return;
        if(state.state==='complete'){
          if(!state.result.available)status.textContent=state.result.message;
          else {if(state.result.paths.length)form.elements[browse.dataset.browse].value=state.result.paths[0];status.textContent='';}break;
        }
        if(['failed','cancelled'].includes(state.state))throw new Error(state.error||state.state);
      }
    }catch(e){if(dialog.open)status.textContent=e.message;}
    finally{if(picker)await api('/jobs/'+picker,'DELETE').catch(()=>{});picker=null;busy=false;update();}
  };
}
