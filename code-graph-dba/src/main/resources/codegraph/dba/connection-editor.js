import {approvalHeaders} from './approval-client.js';
import {renderNativeConnection,nativeConnectionDraft} from './native-connection-editor.js';
// Decode only connection forms the field editor can round-trip. Descriptors, failover
// lists and other advanced URLs remain editable verbatim rather than showing defaults.
function connectionUrlFields(template,url){
  const id=template.id,hostPattern='(\\[[^\\]]+\\]|[^:/?#;,\\s@]+)',decode=value=>decodeURIComponent(value);
  let match,prefix,host,port,tail,database='',rawDatabase='',before='',after='',account='';
  try{
    if(id==='oracle'){
      match=url.match(new RegExp('^(jdbc:oracle:thin:@(?://)?)'+hostPattern+'(?::(\\d+))?([/:])([^?;:]+)(.*)$','i'));
      if(!match)return null;
      [,prefix,host,port,before,rawDatabase,after]=match;database=decode(rawDatabase);
    }else{
      match=url.match(new RegExp('^(jdbc:'+id+'://)'+hostPattern+'(?::(\\d+))?([/;?].*|)$','i'));
      if(!match)return null;
      [,prefix,host,port,tail]=match;
      if(id==='sqlserver'){
        // Braced SQL Server values can contain semicolons and escaped closing braces.
        const properties=/;([^=;{}]+)=(\{(?:[^}]|}})*\}|[^;{}]*)(?=;|$)/gy;
        let property,offset=0,found=false;
        while(offset<tail.length){
          properties.lastIndex=offset;property=properties.exec(tail);if(!property)return null;offset=properties.lastIndex;
          if(property[1].toLowerCase()==='databasename'){
            if(found)return null;found=true;rawDatabase=property[2];database=rawDatabase.startsWith('{')?rawDatabase.slice(1,-1).replaceAll('}}','}'):rawDatabase;
            before=tail.slice(0,property.index+property[1].length+2);after=tail.slice(offset);
          }
        }
        if(!found){before=tail+';databaseName=';after='';}
      }else{
        const path=tail.match(/^(\/([^?;:]*))?([?;:].*)?$/);if(!path)return null;
        before=path[1]===undefined?'':'/';rawDatabase=path[2]??'';database=decode(rawDatabase);after=path[3]??'';
        if(id==='snowflake'){if(database)return null;account=host+(port?':'+port:'');}
      }
    }
    const effectivePort=port??template.port??'';
    return {host,port:effectivePort,database,account,build(values){
      if(id==='snowflake'){
        const value=values.account.trim(),authority=value.includes('.')?value:value+'.snowflakecomputing.com';
        return prefix+authority+before+rawDatabase+after;
      }
      const nextPort=values.port===effectivePort?(port?':'+port:''):(values.port?':'+values.port:''),nextHost=values.host.includes(':')&&!values.host.startsWith('[')?'['+values.host+']':values.host;
      let nextDatabase=rawDatabase,nextBefore=before;
      if(values.database!==database){
        nextDatabase=id==='sqlserver'?(/[;{}]/.test(values.database)?'{'+values.database.replaceAll('}','}}')+'}':values.database):encodeURIComponent(values.database);
        if(!nextBefore)nextBefore='/';
      }
      // An omitted database property remains omitted when only the URL mode changes.
      if(id==='sqlserver'&&!rawDatabase&&!database&&values.database===database&&before===tail+';databaseName=')return prefix+nextHost+nextPort+tail;
      return prefix+nextHost+nextPort+nextBefore+nextDatabase+after;
    }};
  }catch{return null;}
}
function connectionUrlProperties(url){
  const values={};
  if(/^jdbc:sqlserver:/i.test(url)){
    const start=url.indexOf(';');if(start<0)return values;
    const tail=url.slice(start),property=/;([^=;{}]+)=(\{(?:[^}]|}})*\}|[^;{}]*)(?=;|$)/gy;let offset=0,match;
    while(offset<tail.length){property.lastIndex=offset;match=property.exec(tail);if(!match)return {};offset=property.lastIndex;values[match[1]]=match[2].startsWith('{')?match[2].slice(1,-1).replaceAll('}}','}'):match[2];}
  }else if(/^jdbc:(h2|hsqldb):/i.test(url)){
    const start=url.indexOf(';');if(start<0)return values;
    const tail=url.slice(start),property=/;([^=;]+)=((?:\\.|[^;])*)(?=;|$)/gy;let offset=0,match;
    while(offset<tail.length){property.lastIndex=offset;match=property.exec(tail);if(!match)return {};offset=property.lastIndex;values[match[1]]=match[2].replace(/\\;/g,';');}
  }else if(url.includes('?')){
    for(const [key,value] of new URLSearchParams(url.slice(url.indexOf('?')+1)))values[key]=value;
  }else if(/^jdbc:db2:/i.test(url)){
    const tail=url.match(/^jdbc:db2:\/\/[^/]+\/[^:]+:(.*)$/i)?.[1];
    for(const item of (tail??'').split(';')){const at=item.indexOf('=');if(at>0)values[item.slice(0,at)]=item.slice(at+1);}
  }
  return values;
}
// Drafts and secrets live only in this dialog's memory; never local/session storage.
export function connectionEditor({api,toast,saved,csrf}) {
  const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text)n.textContent=text;if(cls)n.className=cls;return n;};
  const dialog=el('dialog',null,'connection-editor');dialog.id='connection-editor';
  dialog.innerHTML=`<div class="editor-heading"><div><div class="eyebrow">CONNECTION SETUP</div><h2 id="ce-title">Choose your database</h2></div><button id="ce-close" aria-label="Close connection setup">×</button></div>
    <section id="ce-picker"><label>Find a database<input id="ce-search" type="search" placeholder="Search databases…" autocomplete="off"></label><div id="ce-databases" class="database-grid" role="group" aria-label="Database types"></div><p class="muted">JDBC connectivity is separate from advanced analysis. PostgreSQL is the verified advanced MCP engine. Drivers run trusted local code.</p></section>
    <section id="ce-editor" hidden><div class="editor-summary"><button id="ce-change">Change database</button><span id="ce-capability"></span></div><nav id="ce-tabs" role="tablist" aria-label="Connection settings"></nav><div id="ce-panels"></div></section>
    <footer class="editor-footer"><div id="ce-status" role="status" aria-live="polite">Choose a database to begin. Nothing is downloaded automatically.</div><div class="actions"><button id="ce-cancel-job" hidden>Cancel operation</button><button id="ce-test">Test</button><button id="ce-test-save">Test & Save</button><button id="ce-save">Save</button><button id="ce-save-untested" class="secondary">Save untested</button></div></footer>`;
  document.body.append(dialog);const $=id=>dialog.querySelector('#'+id);
  const resultDialog=el('dialog',null,'test-result');resultDialog.id='connection-test-result';resultDialog.innerHTML='<h2 id="test-result-title"></h2><div id="test-result-body"></div><button id="test-result-close">Close</button>';document.body.append(resultDialog);resultDialog.querySelector('button').onclick=()=>resultDialog.close();
  const driverDialog=el('dialog',null,'driver-choice');driverDialog.id='driver-choice';driverDialog.innerHTML='<h2>Choose a JDBC driver</h2><p id="driver-choice-message"></p><p class="muted">Downloading loads trusted vendor code. Review applicable vendor licences; this action does not accept a licence agreement.</p><select id="driver-cached" aria-label="Cached driver bundle"></select><div class="actions"><button id="driver-download">Download latest via Maven</button><button id="driver-browse">Browse</button><button id="driver-use-cached">Use cached</button><button id="driver-cancel">Cancel</button></div>';document.body.append(driverDialog);
  const driverDetails=el('section',null,'driver-diagnostic');driverDetails.id='driver-choice-details';driverDialog.querySelector('.actions').before(driverDetails);
  const retryCheck=el('button','Retry version check');retryCheck.id='driver-retry';driverDialog.querySelector('.actions').prepend(retryCheck);
  const driverFailure=el('dialog',null,'driver-failure');driverFailure.id='driver-failure';driverFailure.setAttribute('aria-labelledby','driver-failure-title');driverFailure.innerHTML='<h2 id="driver-failure-title">Driver download failed</h2><section class="driver-diagnostic"></section><div class="actions"><button id="driver-retry-install">Retry download</button><button id="driver-failure-close">Close</button></div>';document.body.append(driverFailure);
  driverFailure.querySelector('#driver-failure-close').onclick=()=>driverFailure.close();
  function diagnostic(container,value){container.replaceChildren();container.hidden=!value;if(!value)return;container.append(el('p',value.summary),el('p',value.guidance));if(value.exitCode!=null)container.append(el('p','Maven exit code: '+value.exitCode));container.append(el('pre',value.details||'No additional details available.'));container.append(el('p','Details are sanitized. Settings → Driver downloads configures Maven, mirrors and certificate trust.','muted'));}
  let templates=[],template=null,profile=null,proposal=null,revision=0,receipt='',busy=null,fields={},props={},secretEdits={},descriptors=[],bundle=null,tab='General',dirty=false,driverStatus=null,shade='transparent',settingsChanged=false,urlFields=null;
  const categories=['General','Driver','Authentication & TLS','Network','Driver properties','Pool & lifecycle'];
  const action=fn=>(...args)=>Promise.resolve().then(()=>fn(...args)).catch(e=>{$('ce-status').textContent=e.message;toast(e.message);});
  function changed(settings=true){if(settings){revision++;receipt='';settingsChanged=true;}dirty=true;updateButtons();}
  function appearanceOnly(){return !proposal&&!!profile&&dirty&&!settingsChanged;}
  function updateButtons(){for(const id of ['ce-test','ce-test-save','ce-save','ce-save-untested'])$(id).disabled=!template||!!busy||(id==='ce-save'&&!receipt&&!appearanceOnly());$('ce-cancel-job').hidden=!busy;}
  async function job(operation,input){if(busy)throw new Error('Wait for the current operation or cancel it.');busy='starting';updateButtons();let id;try{const j=await api(proposal&&operation==='draft-test'?'/approvals/'+proposal.id+'/test-draft':'/setup/'+operation,'POST',input);id=j.id;busy=id;for(;;){await new Promise(r=>setTimeout(r,200));const state=await api('/jobs/'+id);$('ce-status').textContent=state.progress||state.state;if(state.state==='complete')return state.result;if(['failed','cancelled'].includes(state.state)){const e=new Error(state.error||state.state);e.exception=state.exception;e.stage=state.progress;throw e;}}}finally{if(id)await api('/jobs/'+id,'DELETE').catch(()=>{});busy=null;updateButtons();}}
  $('ce-cancel-job').onclick=action(async()=>{if(busy&&busy!=='starting')await api('/jobs/'+busy+'/cancel','POST',{});});
  function close(outcome='cancelled'){revision++;receipt='';if(busy&&busy!=='starting')api('/jobs/'+busy+'/cancel','POST',{}).catch(()=>{});const done=proposal?.done;proposal=null;profile=null;template=null;props={};secretEdits={};fields={};$('ce-panels').replaceChildren();driverDialog.close();driverFailure.close();resultDialog.close();dialog.close();done?.(outcome);}
  $('ce-close').onclick=()=>{if(!dirty||confirm('Discard this unsaved connection draft?'))close();};dialog.addEventListener('cancel',e=>{e.preventDefault();$('ce-close').click();});
  function field(parent,key,label,value='',options={}){const wrapper=el('label',label);const n=el(options.multiline?'textarea':options.choices?'select':'input');n.id='ce-'+key;n.name=key;
    if(options.choices)for(const choice of [...new Set([...options.choices,...(value!=null&&value!==''?[String(value)]:[])])]){const o=el('option',choice);o.value=choice;n.append(o);}else if(!options.multiline)n.type=options.type||'text';n.value=value??'';n.autocomplete='off';if(options.placeholder)n.placeholder=options.placeholder;if(options.required)n.required=true;if(options.readOnly)n.readOnly=true;
    n.addEventListener('input',()=>{changed();options.oninput?.(n.value);});wrapper.append(n);if(options.help)wrapper.append(el('small',options.help,'muted'));parent.append(wrapper);fields[key]=n;return n;}
  function btn(parent,label,fn,title=label){const b=el('button',label);b.type='button';b.title=title;b.onclick=action(fn);parent.append(b);return b;}
  function get(key){return fields[key]?.value??'';}
  function renderPicker(){const q=$('ce-search').value.toLowerCase(),grid=$('ce-databases');grid.replaceChildren();for(const t of templates.filter(t=>(t.name+' '+(t.aliases??'')).toLowerCase().includes(q))){const b=el('button',null,'database-tile');b.dataset.database=t.id;b.setAttribute('aria-label',t.name);const icon=el('img');icon.src=t.icon;icon.alt='';b.append(icon,el('strong',t.name));b.onclick=action(()=>choose(t));grid.append(b);}const buttons=[...grid.children];buttons.forEach((b,i)=>b.onkeydown=e=>{let to=i;const columns=Math.max(1,Math.round(grid.clientWidth/(b.getBoundingClientRect().width+12)));if(e.key==='ArrowRight')to++;else if(e.key==='ArrowLeft')to--;else if(e.key==='ArrowDown')to+=columns;else if(e.key==='ArrowUp')to-=columns;else if(e.key==='Home')to=0;else if(e.key==='End')to=buttons.length-1;else return;e.preventDefault();buttons[Math.max(0,Math.min(buttons.length-1,to))]?.focus();});}
  $('ce-search').oninput=renderPicker;
  $('ce-change').onclick=()=>{if(busy){toast('Cancel the current operation first.');return;}if(dirty&&!confirm('Changing database type discards this draft’s incompatible settings. Continue?'))return;$('ce-picker').hidden=false;$('ce-editor').hidden=true;$('ce-title').textContent='Choose your database';$('ce-search').focus();};
  async function choose(t){template=t;profile=null;props={};secretEdits={};bundle=null;descriptors=t.properties;receipt='';revision++;dirty=false;settingsChanged=false;renderEditor();if(t.id!=='custom'&&(!t.transport||t.transport==='jdbc'))await checkDriver();}
  function renderEditor(){fields={};$('ce-picker').hidden=true;$('ce-editor').hidden=false;$('ce-title').textContent=(profile?'Edit ':'New ')+template.name+' connection';$('ce-capability').textContent=template.advancedMcp?'JDBC + PostgreSQL analysis':'JDBC · advanced MCP analysis not certified';
    if(proposal)$('ce-title').textContent='Review agent '+template.name+' connection proposal';
    const panels=$('ce-panels'),nav=$('ce-tabs');panels.replaceChildren();nav.replaceChildren();for(const category of categories){const b=el('button',category);b.setAttribute('role','tab');b.id='ce-tab-'+categories.indexOf(category);b.onclick=()=>selectTab(category);nav.append(b);const panel=el('section',null,'settings-panel');panel.dataset.category=category;panel.id='ce-panel-'+categories.indexOf(category);panel.setAttribute('role','tabpanel');panel.setAttribute('aria-labelledby',b.id);b.setAttribute('aria-controls',panel.id);panels.append(panel);}
    const panel=name=>panels.querySelector(`[data-category="${name}"]`),general=panel('General');
    general.append(el('p',template.notes??'','muted'));field(general,'name','Connection name',profile?.name??'',{required:true,placeholder:template.name+' connection'});
    shade=profile?.color??'transparent';renderAppearance(general);
    if(template.transport&&template.transport!=='jdbc'){
      $('ce-capability').textContent='Native '+template.name+' · bounded reads · advanced workflows in development';
      renderNativeConnection({template,profile,panel,field,el});
      selectTab('General');updateButtons();return;
    }
    urlFields=connectionUrlFields(template,profile?.url??template.url);
    if(template.id==='snowflake'){
      field(general,'account','Account identifier or hostname',profile?(urlFields?.account??''):'',{placeholder:'organization-account.snowflakecomputing.com',oninput:generateUrl});
      for(const k of ['warehouse','db','schema','role'])field(general,'sf-'+k,k==='db'?'Database':k[0].toUpperCase()+k.slice(1),props[k]??'',{oninput:v=>{if(v)props[k]=v;else delete props[k];}});
    }else if(template.port){const row=el('div',null,'field-columns');general.append(row);field(row,'host','Host',urlFields?.host??'',{oninput:generateUrl});field(row,'port','Port',urlFields?.port??'',{oninput:generateUrl});field(general,'database','Database / service',urlFields?.database??'',{oninput:generateUrl});}
    field(general,'username','Username',profile?.username??'');
    field(general,'password','Password · write-only OS vault','',{type:'password',placeholder:profile?.hasCredential?'Unchanged unless replaced':'Not saved until Save'});
    field(general,'password-action','Saved password',profile?.hasCredential?'Keep':'Replace',{choices:['Keep','Replace','Remove']});
    field(general,'url-mode','URL mode',profile||!template.port?'Edit URL':'Generate from fields',{choices:['Generate from fields','Edit URL'],oninput:()=>{syncUrlFields();if(fields.url.readOnly)generateUrl();}});
    field(general,'url','JDBC URL · credentials must not appear here',profile?.url??template.url,{required:true,readOnly:!profile&&!!template.port,oninput:syncUrlFields,help:'In Edit URL mode, address fields reflect this URL. Choose Generate from fields to edit a supported address while retaining URL options. Advanced descriptors and multiple hosts use Edit URL. URL properties and explicit driver properties cannot define the same setting.'});
    syncUrlFields();
    const driver=panel('Driver');const coordinates=el('div',null,'field-columns');driver.append(coordinates);field(coordinates,'groupId','Maven group',bundle?.groupId??template.groupId);field(coordinates,'artifactId','Maven artifact',bundle?.artifactId??template.artifactId);field(coordinates,'version','Pinned version',bundle?.version??'');
    field(driver,'driverClass','Driver class · discovered or explicit override',profile?.driverClass??template.driverClass,{required:true});
    field(driver,'jars','Complete driver classpath · one JAR path per line',(profile?.jars??(profile?.jar?[profile.jar]:[])).join('\n'),{multiline:true,required:true});
    const driverActions=el('div',null,'actions');driver.append(driverActions);btn(driverActions,'Check latest / Update',checkDriver);btn(driverActions,'Download pinned version',installDriver);btn(driverActions,'Browse JARs',()=>browse('jar'));btn(driverActions,'Inspect / discover classes',inspectDriver);
    const upload=el('input');upload.type='file';upload.accept='.jar';upload.multiple=true;upload.id='ce-upload';const uploadLabel=el('label','JAR upload fallback (when a native picker is unavailable)');uploadLabel.append(upload);driver.append(uploadLabel);upload.onchange=action(async()=>{for(const file of upload.files){if(!file.name.toLowerCase().endsWith('.jar'))throw new Error('Only JAR files may be uploaded.');if(file.size>128*1024*1024)throw new Error('JAR upload limit is 128 MiB per file.');const response=await fetch('/api/dba/drivers/import',{method:'POST',headers:{'Content-Type':'application/java-archive','X-Dba-CSRF':csrf(),...approvalHeaders('/drivers/import')},body:file});const result=await response.json();if(!response.ok)throw new Error(result.error);fields.jars.value+=(fields.jars.value?'\n':'')+result.path;}changed();await inspectDriver();upload.value='';});
    driver.append(el('p','Saved connections stay pinned. Driver changes close the old pool only after you save. A JAR is executable code; install only trusted artifacts.','muted'));
    const auth=panel('Authentication & TLS');
    if(template.id==='snowflake'){
      field(auth,'authenticator','Authentication',props.authenticator??'snowflake_jwt',{choices:['snowflake_jwt','snowflake','externalbrowser','oauth'],oninput:v=>{props.authenticator=v;}});props.authenticator=get('authenticator');
      field(auth,'key-file','Existing PKCS#8 RSA private key file',props.private_key_file??'',{oninput:v=>{props.private_key_file=v;$('ce-key-status').textContent='Not validated';}});
      btn(auth,'Browse private key file',()=>browse('key'));field(auth,'key-passphrase','Key passphrase · write-only, saved only with connection','',{type:'password',placeholder:'Keep existing passphrase unless replaced',oninput:v=>{secretEdits.private_key_pwd=v;}});
      btn(auth,'Remove saved passphrase',()=>{secretEdits.private_key_pwd=null;fields['key-passphrase'].value='';changed();});
      const keyStatus=el('pre','Not validated');keyStatus.id='ce-key-status';auth.append(keyStatus);btn(auth,'Validate key and fingerprint',async()=>{const r=await job('key-validate',{path:get('key-file'),passphrase:get('key-passphrase')});keyStatus.textContent=JSON.stringify(r,null,2);});
      auth.append(el('p','RSA key-pair authentication is not SSH. Use an existing encrypted or unencrypted PKCS#8 PEM RSA key (2048+ bits). The matching public key must already be registered for this Snowflake user. Keys are never uploaded, copied or converted. Protect local file permissions.','muted'));
    }
    for(const category of ['Authentication & TLS','Network','Driver properties']){const p=panel(category);const search=el('input');search.type='search';search.placeholder='Filter properties…';search.setAttribute('aria-label','Search '+category+' properties');const list=el('div',null,'property-list');list.dataset.properties=category;search.oninput=()=>renderProperties(category,search.value);p.append(search,list);}
    const properties=panel('Driver properties');btn(properties,'Discover installed driver properties',async()=>{const r=await job('properties',{...draft(),password:undefined,secretProperties:undefined});descriptors=savedDescriptors(r.properties);renderAllProperties();$('ce-status').textContent=r.catalog;});
    const custom=el('div',null,'field-columns');properties.append(custom);field(custom,'custom-name','Additional property name','');field(custom,'custom-value','Value · unknown properties are secret','',{type:'password'});btn(properties,'Add property override',()=>{const key=get('custom-name').trim();if(!key)throw new Error('Enter a property name.');secretEdits[key]=get('custom-value');if(!descriptors.some(d=>d.name===key))descriptors.push({name:key,secret:true,category:'Driver properties',description:'Unverified custom property; write-only and stored in the OS vault.'});fields['custom-name'].value='';fields['custom-value'].value='';changed();renderAllProperties();});
    const pool=panel('Pool & lifecycle');for(const [key,label,hint] of [['maximumPoolSize','Maximum connections','2'],['minimumIdle','Minimum idle connections','0'],['connectionTimeout','Acquisition timeout (ms)','10000'],['validationTimeout','Validation timeout (ms)','3000'],['idleTimeout','Idle timeout (ms)','60000'],['maxLifetime','Maximum lifetime (ms)','300000']])field(pool,'pool-'+key,label,profile?.pool?.[key]??'',{type:'number',placeholder:'Runtime default: '+hint});pool.append(el('p','Blank values use runtime defaults; they are not explicit overrides. Global admission limits still apply. Idle connections consume resources.','muted'));
    selectTab('General');syncUrlFields();renderAllProperties();updateButtons();
  }
  function selectTab(category){tab=category;[...$('ce-tabs').children].forEach((b,i)=>{b.setAttribute('aria-selected',categories[i]===category);b.tabIndex=categories[i]===category?0:-1;b.onkeydown=e=>{if(['ArrowLeft','ArrowRight'].includes(e.key)){e.preventDefault();selectTab(categories[(i+(e.key==='ArrowRight'?1:5))%6]);$('ce-tabs').children[categories.indexOf(tab)].focus();}};});$('ce-panels').querySelectorAll('.settings-panel').forEach(p=>p.hidden=p.dataset.category!==category);}
  function syncUrlFields(){
    urlFields=connectionUrlFields(template,get('url'));
    const supported=!!urlFields&&!!template.port,mode=fields['url-mode'];
    mode.options[0].disabled=!supported;if(!supported)mode.value='Edit URL';
    fields.url.readOnly=get('url-mode')!=='Edit URL';
    for(const key of ['host','port','database','account'])if(fields[key]){
      fields[key].value=urlFields?.[key]??'';fields[key].readOnly=!fields.url.readOnly;
      fields[key].placeholder=urlFields?'':'Use the JDBC URL below';
    }
    const urlValues=connectionUrlProperties(get('url'));
    if(template.id==='snowflake')for(const key of ['warehouse','db','schema','role'])if(fields['sf-'+key]){
      const input=fields['sf-'+key],fromUrl=props[key]===undefined&&urlValues[key]!==undefined;
      input.value=props[key]??urlValues[key]??'';input.readOnly=fromUrl;input.title=fromUrl?'Set in the JDBC URL; edit on General.':'';
    }
    if($('ce-panels').querySelector('[data-properties]'))renderAllProperties();
  }
  function generateUrl(){
    if(!fields.url||get('url-mode')==='Edit URL'||!urlFields)return;
    fields.url.value=urlFields.build({host:get('host'),port:get('port'),database:get('database'),account:get('account')});
  }
  function renderProperties(category,filter=''){const urlValues=connectionUrlProperties(get('url')),list=$('ce-panels').querySelector(`[data-properties="${category}"]`);list.replaceChildren();const dedicated=template.id==='snowflake'?['authenticator','private_key_file','private_key_pwd','warehouse','db','schema','role']:[];
    const displayed=[...descriptors,...Object.keys(urlValues).filter(name=>!descriptors.some(d=>d.name.toLowerCase()===name.toLowerCase())).map(name=>({name,category:'Driver properties',description:'Configured in the JDBC URL'}))];
    for(const d of displayed.filter(d=>(d.category||'Driver properties')===category&&!dedicated.includes(d.name)&&d.name.toLowerCase().includes(filter.toLowerCase()))){const urlKey=Object.keys(urlValues).find(key=>key.toLowerCase()===d.name.toLowerCase()),fromUrl=!d.secret&&props[d.name]===undefined&&urlKey!==undefined,value=d.secret?(secretEdits[d.name]??''):(props[d.name]??(fromUrl?urlValues[urlKey]:''));const row=el('div',null,'property-row');const label=el('label');label.append(el('strong',d.name),el('small',d.description||'JDBC property'));const input=el(d.choices?'select':'input');if(d.choices){const empty=el('option','Driver default (not overridden)');empty.value='';input.append(empty);for(const choice of [...new Set([...d.choices,...(value!==''?[String(value)]:[])])]){const o=el('option',choice);o.value=choice;input.append(o);}}else input.type=d.secret?'password':'text';input.setAttribute('aria-label',d.name);input.autocomplete='off';input.placeholder=d.secret?(profile?.secretPropertyNames?.includes(d.name)?'Saved · keep unless replaced':'Write-only OS vault'):'Driver default: '+(d.default??'unspecified');input.value=value;if(fromUrl){input.disabled=!!d.choices;input.readOnly=true;input.title='Set in the JDBC URL; edit on General.';}input.oninput=()=>{if(d.secret)secretEdits[d.name]=input.value;else props[d.name]=input.value;changed();};label.append(input);row.append(label);btn(row,'Reset',()=>{delete props[d.name];secretEdits[d.name]=null;changed();renderProperties(category,filter);},'Remove this override; use driver default').disabled=fromUrl;row.append(el('span',fromUrl?'JDBC URL':d.secret?'SECRET':d.source||'catalog','badge'));list.append(row);}}
  function savedDescriptors(entries){
    const result=entries.map(entry=>({...entry})),secrets=new Set(profile?.secretPropertyNames??[]);
    for(const name of new Set([...Object.keys(props),...secrets])){
      let entry=result.find(value=>value.name===name);
      if(!entry){entry={name,category:'Driver properties',secret:secrets.has(name),description:'Saved property override'};result.push(entry);}
      if(secrets.has(name))entry.secret=true;
    }
    return result;
  }
  function renderAllProperties(){for(const c of ['Authentication & TLS','Network','Driver properties'])renderProperties(c);}
  function renderAppearance(parent){const box=el('fieldset',null,'connection-appearance');box.append(el('legend','Connection color'));const choices=el('div',null,'connection-color-choices'),preview=el('div','Connection row preview','connection-color-preview');preview.id='ce-color-preview';const picker=el('input');picker.type='color';picker.id='ce-color';picker.setAttribute('aria-label','Custom connection color');picker.value=shade==='transparent'?'#5796da':shade;const controls=[];
    const apply=value=>{shade=value;changed(false);render();};
    for(const [name,color] of [['Transparent','transparent'],['Red','#ed6363'],['Amber','#edb54b'],['Green','#58bd84'],['Cyan','#4ac3cf'],['Blue','#5796da'],['Purple','#ac7bdb'],['Pink','#db80ae'],['Gray','#9ba5b1']]){const b=btn(choices,name==='Transparent'?'Transparent':'',()=>apply(color),name+' connection shade');b.className='connection-color-swatch';b.dataset.color=color;b.style.setProperty('--swatch-color',color);b.setAttribute('aria-label',name+' connection shade');controls.push(b);}
    const custom=el('label','Custom shade');custom.append(picker);picker.oninput=()=>apply(picker.value);choices.append(custom);
    function render(){for(const b of controls)b.setAttribute('aria-pressed',String(b.dataset.color===shade));preview.dataset.color=shade;preview.style.backgroundColor=shade==='transparent'?'transparent':`color-mix(in srgb, ${shade} 24%, transparent)`;preview.textContent=shade==='transparent'?'Transparent · no color shade':shade.toUpperCase()+' · connection row preview';if(shade!=='transparent')picker.value=shade;}
    box.append(choices,preview,el('small','An identification aid, not an execution safeguard. Always verify the Script connection before running SQL.','muted'));parent.append(box);render();}
  function draft(){if(template.transport&&template.transport!=='jdbc')return nativeConnectionDraft({template,profile,get,shade});const result={templateId:template.id,name:get('name'),color:shade,url:get('url'),driverClass:get('driverClass'),jars:get('jars').split('\n').map(s=>s.trim()).filter(Boolean),username:get('username'),readOnly:profile?.readOnly??true,properties:{...props},secretProperties:{...secretEdits},pool:{}};if(profile)result.connectionId=profile.id;if(bundle)result.driverBundle=bundle;
    if(get('password-action')==='Remove')result.removePassword=true;else if(get('password')||get('password-action')==='Replace'&&profile)result.password=get('password');
    for(const key of ['maximumPoolSize','minimumIdle','connectionTimeout','validationTimeout','idleTimeout','maxLifetime'])if(get('pool-'+key)!=='')result.pool[key]=Number(get('pool-'+key));return result;}
  async function browse(kind){const r=await job('file-select',{kind});if(!r.available){toast(r.message);return;}if(!r.paths.length)return;if(kind==='key'){fields['key-file'].value=r.paths[0];props.private_key_file=r.paths[0];$('ce-key-status').textContent='Not validated';changed();}else{fields.jars.value=r.paths.join('\n');changed();await inspectDriver();}}
  async function inspectDriver(){const result=await job('driver-inspect',{jars:get('jars').split('\n').map(s=>s.trim()).filter(Boolean)});applyBundle(result);}
  function applyBundle(value){bundle={...value};delete bundle.classes;fields.jars.value=value.jars.join('\n');if(value.classes?.length)fields.driverClass.value=value.classes.includes(template.driverClass)?template.driverClass:value.classes[0];for(const key of ['version','groupId','artifactId'])if(value[key])fields[key].value=value[key];changed();$('ce-status').textContent=(value.source||'Driver')+' · '+(value.version||'manual version')+' · '+value.jars.length+' JAR(s). Test before saving.';}
  async function installDriver(){const before=revision;try{const r=await job('driver-install',{templateId:template.id,groupId:get('groupId'),artifactId:get('artifactId'),version:get('version')});if(before===revision&&dialog.open)applyBundle(r);}catch(e){if(!dialog.open||before!==revision)return;diagnostic(driverFailure.querySelector('section'),e.exception??{summary:e.message,details:e.message});$('ce-status').textContent=e.exception?.summary||e.message;driverFailure.showModal();}}
  retryCheck.onclick=action(checkDriver);driverFailure.querySelector('#driver-retry-install').onclick=action(()=>{driverFailure.close();return installDriver();});
  async function checkDriver(){const before=revision;driverStatus=await job('driver-status',{templateId:template.id,groupId:get('groupId'),artifactId:get('artifactId')});if(before!==revision||!dialog.open)return;const current=driverStatus.installed.find(i=>i.version===driverStatus.latestVersion);if(current){driverDialog.close();applyBundle(current);await inspectDriver();return;}
    driverDialog.querySelector('#driver-choice-message').textContent=(driverStatus.latestAvailable?'Latest stable release: '+driverStatus.latestVersion+'. A current driver bundle is not installed.':driverStatus.message)+(driverStatus.insecureTls?' WARNING: TLS verification is disabled for driver downloads.':'');diagnostic(driverDetails,driverStatus.diagnostic);
    const cached=driverDialog.querySelector('#driver-cached');cached.replaceChildren(...driverStatus.installed.map((b,i)=>{const o=el('option',b.version+' · verified cached bundle');o.value=i;return o;}));cached.hidden=!driverStatus.installed.length;
    driverDialog.querySelector('#driver-download').disabled=!driverStatus.latestAvailable;driverDialog.querySelector('#driver-use-cached').disabled=!driverStatus.installed.length;
    driverDialog.querySelector('#driver-download').onclick=action(async()=>{driverDialog.close();fields.version.value=driverStatus.latestVersion;await installDriver();});driverDialog.querySelector('#driver-use-cached').onclick=action(async()=>{driverDialog.close();applyBundle(driverStatus.installed[Number(cached.value)]);await inspectDriver();});driverDialog.querySelector('#driver-browse').onclick=action(async()=>{driverDialog.close();selectTab('Driver');await browse('jar');});driverDialog.querySelector('#driver-cancel').onclick=()=>driverDialog.close();if(!driverDialog.open)driverDialog.showModal();}
  function showTest(result,error){const title=resultDialog.querySelector('h2'),body=resultDialog.querySelector('#test-result-body');body.replaceChildren();title.textContent=error?'Connection test failed':'Connection successful';if(error){body.append(el('p',error.message));if(error.stage)body.append(el('p','Stage: '+error.stage));if(error.exception)body.append(el('pre',JSON.stringify(error.exception,null,2)));}else{body.append(el('p',result.database+' '+result.version+' · Driver '+result.driverVersion+' · '+result.elapsedMillis+' ms'));if(result.versionQuery)body.append(el('pre',result.versionQuery));if(result.versionResult){const table=el('table'),head=el('tr');for(const c of result.versionResult.columns)head.append(el('th',c.label));table.append(head);for(const row of result.versionResult.rows){const tr=el('tr');for(const value of row)tr.append(el('td',String(value)));table.append(tr);}body.append(table);}if(result.versionWarning)body.append(el('p',result.versionWarning));if(result.versionException)body.append(el('pre',JSON.stringify(result.versionException,null,2)));body.append(el('p','This result is temporary and is not saved. A successful test does not guarantee future availability.','muted'));}resultDialog.showModal();}
  async function test(andSave=false){const before=revision;const input=draft();if(/jdbc:(h2|hsqldb|sqlite|duckdb|calcite):/i.test(input.url)||/init|runscript/i.test(JSON.stringify(input.properties))){if(!confirm('The JDBC driver may initialize a database or create embedded files. Allow this connection test?'))return;input.confirmDriverEffects=true;}
    try{const r=await job('draft-test',input);if(before!==revision||!dialog.open){$('ce-status').textContent='Settings changed during the test. Test the new draft again.';return;}receipt=r.receipt;updateButtons();if(andSave){await save(false);showTest(r);}else showTest(r);}catch(e){receipt='';updateButtons();showTest(null,e);}}
  async function save(untested){if(appearanceOnly()){await api('/connections/'+profile.id+'/appearance','PUT',{color:shade});dirty=false;close('saved');await saved();toast('Connection color saved.');return;}if(untested&&!confirm('Save without a successful connectivity test? Required fields, driver and vault checks still apply.'))return;if(profile&&profile.name!==get('name')&&!confirm('Renaming this connection invalidates its name-bound MCP grants. Review and reissue those grants afterward. Continue?'))return;const input=draft();input.receipt=receipt;input.saveUntested=untested;if(proposal){await api('/approvals/'+proposal.id+'/draft','PUT',input);dirty=false;close('revised');toast('Proposal revised. Review the updated diff before approval.');return;}const result=await api('/connections'+(profile?'/'+profile.id:''),profile?'PUT':'POST',input);dirty=false;close('saved');await saved();document.dispatchEvent(new CustomEvent('dba-connection-saved',{detail:{id:result.id}}));toast('Connection saved.');}
  $('ce-test').onclick=action(()=>test());$('ce-test-save').onclick=action(()=>test(true));$('ce-save').onclick=action(()=>save(false));$('ce-save-untested').onclick=action(()=>save(true));
  const controller={close,async testSaved(p){await controller.open(p);await test();},async open(p=null){if(busy)throw new Error('A connection operation is still stopping.');proposal=null;templates=templates.length?templates:await api('/templates');profile=p;receipt='';revision++;dirty=false;settingsChanged=false;props={...(p?.properties??{})};secretEdits={};bundle=p?.driverBundle??null;template=p?templates.find(t=>t.id===(p.templateId||'custom')):null;if(p){descriptors=savedDescriptors(template.properties);renderEditor();}else{$('ce-picker').hidden=false;$('ce-editor').hidden=true;$('ce-search').value='';$('ce-title').textContent='Choose your database';renderPicker();updateButtons();}dialog.showModal();if(!p)$('ce-search').focus();},async openProposal(request,done){if(busy)throw new Error('A connection operation is still stopping.');templates=templates.length?templates:await api('/templates');const p=await api('/approvals/'+request.id+'/draft');proposal={id:request.id,done};profile=p;receipt='';revision++;dirty=false;settingsChanged=false;props={...(p.properties??{})};secretEdits={};bundle=p.driverBundle??null;template=templates.find(t=>t.id===(p.templateId||'custom'));descriptors=savedDescriptors(template.properties);renderEditor();dialog.showModal();$('ce-status').textContent='Agent-supplied secrets remain write-only. Test this exact draft or explicitly retain it as untested, then review the updated diff before approval.';}};return controller;
}
