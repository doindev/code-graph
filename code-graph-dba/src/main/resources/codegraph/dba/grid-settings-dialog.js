import {fields,defaults,preferences,preferenceState,preferenceCatalog,reloadPreferences,savePreferences,validatePreferences} from './grid-preferences.js';
export function openGridSettings(view,{gridDialog,action}){
  if(view.settingsDialog?.open){view.settingsDialog.focus();return;}
  const anchor=view.settingsButton??document.activeElement,original=preferenceState(view.result),order=[...view.state.order],hidden=new Set(view.state.hidden);
  let catalog=preferenceCatalog(),scope='result',tab='Data loading',resetWidths=false,saving=false,closed=false;
  const drafts={result:{...original.local},connection:{...original.connection},global:{...original.global}};
  const dialog=gridDialog('Grid settings',({dialog,body,footer,status})=>{
    view.settingsDialog=dialog;dialog.classList.add('grid-preferences-dialog');
    const scopeLabel=document.createElement('label');scopeLabel.textContent='Settings scope';const scopes=document.createElement('select');scopes.setAttribute('aria-label','Settings scope');
    for(const [value,label] of [['result','Current result'],['connection','Connection'],['global','Global']]){const option=document.createElement('option');option.value=value;option.textContent=label;option.disabled=value==='connection'&&!original.connectionId;scopes.append(option);}scopeLabel.append(scopes);body.append(scopeLabel);
    const tabs=document.createElement('div');tabs.className='grid-preferences-tabs';tabs.setAttribute('role','tablist');tabs.setAttribute('aria-label','Grid settings categories');
    const panel=document.createElement('section');panel.setAttribute('role','tabpanel');panel.className='grid-preferences-panel';
    for(const name of ['Data loading','Editing','Appearance','Columns']){const button=action(tabs,name,()=>{tab=name;paint();});button.setAttribute('role','tab');button.onkeydown=e=>{const all=[...tabs.children],at=all.indexOf(button);if(['ArrowLeft','ArrowRight','Home','End'].includes(e.key)){e.preventDefault();const next=e.key==='Home'?0:e.key==='End'?all.length-1:(at+(e.key==='ArrowRight'?1:all.length-1))%all.length;all[next].click();all[next].focus();}};}
    body.append(tabs,panel);
    const inherited=()=>scope==='global'?{...defaults}:scope==='connection'?{...defaults,...catalog.global}:{...defaults,...original.global,...original.connection};
    const paint=()=>{
      for(const button of tabs.children){const active=button.textContent===tab;button.setAttribute('aria-selected',String(active));button.tabIndex=active?0:-1;}
      panel.setAttribute('aria-label',tab);panel.replaceChildren();
      if(tab==='Columns'){
        const note=document.createElement('p');note.textContent='Column layout applies only to this result, regardless of settings scope.';panel.append(note);
        const list=document.createElement('div');list.className='grid-settings-columns';
        order.forEach((id,index)=>{const col=view.state.columns.find(c=>c.id===id);if(!col)return;const row=document.createElement('div');row.className='grid-settings-column';const label=document.createElement('label'),input=document.createElement('input');input.type='checkbox';input.checked=!hidden.has(id);input.onchange=()=>input.checked?hidden.delete(id):hidden.add(id);label.append(input,document.createTextNode(col.label+' ('+id+')'));row.append(label);for(const [delta,text] of [[-1,'↑'],[1,'↓']]){const button=action(row,text,()=>{const to=index+delta;[order[index],order[to]]=[order[to],order[index]];paint();});button.disabled=index+delta<0||index+delta>=order.length;button.setAttribute('aria-label','Move '+col.label+(delta<0?' up':' down'));}list.append(row);});panel.append(list);
        action(panel,resetWidths?'Column widths will reset on Apply':'Reset column widths on Apply',()=>{resetWidths=true;paint();});return;
      }
      const base=inherited();
      for(const field of fields.filter(f=>f.group===tab)){
        const fieldScope=scope,row=document.createElement('div');row.className='grid-preference';const label=document.createElement('label');label.textContent=field.label;
        const input=document.createElement(field.type==='enum'?'select':'input');input.name=field.key;input.setAttribute('aria-label',field.label);
        if(field.type==='enum')for(const [value,text]of Object.entries(field.options)){const option=document.createElement('option');option.value=value;option.textContent=text;input.append(option);}
        else if(field.type==='boolean')input.type='checkbox';else if(field.type==='integer'){input.type='number';input.min=field.min;input.max=field.key==='pageSize'?Math.min(field.max,catalog.rowCeiling):field.max;input.step=1;}else{input.type='text';input.maxLength=field.maxLength;}
        const override=Object.hasOwn(drafts[scope],field.key),rawValue=override?drafts[scope][field.key]:base[field.key],value=field.key==='pageSize'?Math.min(rawValue,catalog.rowCeiling):rawValue;if(field.type==='boolean')input.checked=value;else input.value=value;
        let reason='';const cap=view.result.grid?.capabilities??{};
        if(scope==='result'&&field.capability&&!cap[field.capability])reason=cap[field.capability+'Reason']||cap.reason||'Unavailable for this result.';
        if(scope==='result'&&field.key==='valuesServer'&&!view.result.grid)reason='This result has no retained server source.';
        input.disabled=!!reason;const info=document.createElement('small');info.textContent=(override?'Override in '+(scope==='result'?'this result':scope):'Inherited from '+(scope==='global'?'built-in defaults':scope==='connection'?(Object.hasOwn(catalog.global,field.key)?'global settings':'built-in defaults'):(Object.hasOwn(original.connection,field.key)?'connection settings':Object.hasOwn(original.global,field.key)?'global settings':'built-in defaults')))+(reason?' · '+reason:field.help?' · '+field.help:'');
        input.oninput=input.onchange=()=>{drafts[fieldScope][field.key]=field.type==='boolean'?input.checked:field.type==='integer'?Number(input.value):input.value;info.textContent='Override in '+fieldScope+(field.help?' · '+field.help:'');reset.disabled=false;};
        label.append(input);row.append(label,info);const reset=action(row,'Use inherited value',()=>{delete drafts[scope][field.key];paint();});reset.disabled=!override;reset.setAttribute('aria-label','Reset '+field.label);panel.append(row);
      }
    };
    scopes.onchange=()=>{scope=scopes.value;status.textContent='';paint();};paint();
    action(footer,'Restore Defaults',()=>{drafts[scope]={};if(tab==='Columns'){order.splice(0,order.length,...view.state.columns.map(c=>c.id));hidden.clear();resetWidths=true;}paint();});
    action(footer,'Cancel',()=>{if(!saving)dialog.close();});
    const apply=async close=>{
      if(saving)return;
      try{
        if(hidden.size===order.length)throw Error('Keep at least one column visible.');
        validatePreferences(drafts[scope],catalog.rowCeiling);
        const next=scope==='result'?{...inherited(),...drafts.result}:scope==='connection'?{...defaults,...catalog.global,...drafts.connection,...original.local}:{...defaults,...drafts.global,...original.connection,...original.local};
        if(next.readOnly&&!preferences(view.result).readOnly&&view.state.draft.dirty&&!await view.constructor.guard(view.result,()=>view.saveRows()))return;
        saving=true;for(const button of footer.querySelectorAll('button'))button.disabled=true;
        catalog=await savePreferences(view.result,scope,drafts[scope],catalog.revision);if(closed||view.disposed)return;
        view.state.order=[...order];view.state.hidden=new Set(hidden);
        if(resetWidths){view.state.initialWidths=false;view.state.rowNumberResized=false;view.state.rowNumberDigits=null;resetWidths=false;}
        view.applyPreferences();status.textContent='Settings applied. Page size takes effect on the next fetch.';paint();if(close)dialog.close();
      }catch(error){status.textContent=error.message;if(scope!=='result')reload.hidden=false;}finally{saving=false;for(const button of footer.querySelectorAll('button'))button.disabled=false;}
    };
    action(footer,'Apply',()=>void apply(false));action(footer,'Apply and Close',()=>void apply(true));
    const reload=action(footer,'Reload saved settings',async()=>{try{catalog=await reloadPreferences();drafts.global={...catalog.global};drafts.connection={...catalog.connections[original.connectionId]};status.textContent=catalog.warning||'Saved defaults reloaded; current-result draft retained.';paint();}catch(error){status.textContent=error.message;}});
    reload.hidden=true;footer.insertBefore(reload,footer.children[1]);
    if(view.state.draft.dirty)action(body,'Inspect pending SQL and parameters',async()=>{dialog.close();try{await view.transform({action:'review_rows'});}catch(error){view.notice(error.message);}});
    status.textContent=catalog.warning??'';
    dialog.addEventListener('cancel',event=>{if(saving)event.preventDefault();});
  });
  dialog.addEventListener('close',()=>{closed=true;if(view.settingsDialog===dialog)view.settingsDialog=null;if(anchor?.isConnected)anchor.focus();view.scheduleCount();});
}
