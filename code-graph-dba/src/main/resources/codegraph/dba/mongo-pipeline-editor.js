import {lucide} from './tree-icons.js';
import {MongoPipelineState,PIPELINE_STAGES,PIPELINE_LIMITS} from './mongo-pipeline-state.js';
const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text)node.textContent=text;if(cls)node.className=cls;return node;};

/** Pure draft dialog: execution, authorization and connection ownership stay in its host. */
export class MongoPipelineEditor{
  constructor({collection,text,target,apply,close}){
    Object.assign(this,{apply,close});this.model=new MongoPipelineState(collection,text);
    this.root=el('dialog',null,'mongo-pipeline');this.root.setAttribute('aria-label','MongoDB aggregation pipeline');
    const header=el('div',null,'mongo-pipeline-heading');header.append(el('h2','Aggregation pipeline'));
    this.button(header,'Close pipeline builder','x',()=>this.close());this.root.append(header,el('p',target,'mongo-pipeline-target'));
    this.root.append(el('p',this.model.imported?'Imported pipeline draft. Stage order matters.':'New pipeline draft. The original command stays unchanged until Use pipeline.','mongo-pipeline-notice'));
    const controls=el('div',null,'mongo-pipeline-controls');this.palette=el('select');this.palette.setAttribute('aria-label','Stage to add');
    for(const [name,[description]] of Object.entries(PIPELINE_STAGES)){const option=el('option',name+' — '+description);option.value=name;this.palette.append(option);}
    controls.append(this.palette);this.add=this.button(controls,'Add stage','plus',()=>this.attempt(()=>{this.selected=this.model.add(this.palette.value);this.render();this.value.focus();}));
    const diskLabel=el('label','Allow server disk use');this.disk=el('input');this.disk.type='checkbox';this.disk.checked=this.model.allowDiskUse;this.disk.onchange=()=>{this.model.allowDiskUse=this.disk.checked;this.preview();};diskLabel.prepend(this.disk);controls.append(diskLabel);
    this.root.append(controls);
    const body=el('div',null,'mongo-pipeline-body');this.list=el('ol',null,'mongo-pipeline-stages');this.list.setAttribute('aria-label','Pipeline stages');
    const editor=el('div',null,'mongo-pipeline-value');this.label=el('label','Select or add a stage');this.value=el('textarea');this.value.wrap='off';this.value.spellcheck=false;this.value.maxLength=PIPELINE_LIMITS.bytes;this.value.setAttribute('aria-label','Stage value as Extended JSON');this.label.append(this.value);
    this.value.oninput=()=>this.attempt(()=>{this.model.edit(this.selected,this.value.value);this.preview();},()=>{this.value.value=this.model.stage(this.selected).text;});
    this.hint=el('p','Use canonical $numberLong/$numberDecimal wrappers to preserve BSON values. Stage values and expressions are checked again by the server before execution.');
    editor.append(this.label,this.hint);body.append(this.list,editor);this.root.append(body);
    const details=el('details',null,'mongo-pipeline-preview');details.append(el('summary','Generated command (read-only)'));this.output=el('textarea');this.output.readOnly=true;this.output.wrap='off';this.output.spellcheck=false;this.output.setAttribute('aria-label','Generated aggregation command');details.append(this.output);this.root.append(details);
    this.status=el('div',null,'mongo-pipeline-status');this.status.setAttribute('role','status');this.root.append(this.status);
    const footer=el('div',null,'mongo-pipeline-footer');footer.append(el('span','64 stages · 128 KiB draft · no automatic execution'));
    this.button(footer,'Cancel','x',()=>this.close());
    this.use=this.button(footer,'Use pipeline','check',()=>this.attempt(()=>{this.apply(this.model.text());this.close();}));this.root.append(footer);
    this.root.addEventListener('cancel',event=>{event.preventDefault();this.close();});
    this.selected=this.model.stages[0]?.id;this.render();
  }
  button(parent,label,icon,handler){const button=el('button');button.type='button';button.title=label;button.setAttribute('aria-label',label);button.append(lucide(icon));if(['Cancel','Use pipeline','Add stage'].includes(label))button.append(document.createTextNode(label));button.onclick=handler;parent.append(button);return button;}
  attempt(action,recover){try{action();}catch(error){recover?.();this.status.textContent=error.message;}}
  render(){
    this.list.replaceChildren();this.add.disabled=this.model.stages.length>=PIPELINE_LIMITS.stages;
    if(!this.model.stages.some(s=>s.id===this.selected))this.selected=this.model.stages[0]?.id;
    for(const [index,stage] of this.model.stages.entries()){
      const row=el('li');row.dataset.stageId=stage.id;row.classList.toggle('selected',stage.id===this.selected);
      const enabled=el('input');enabled.type='checkbox';enabled.checked=stage.enabled;enabled.setAttribute('aria-label','Enable stage '+(index+1));enabled.onchange=()=>{stage.enabled=enabled.checked;this.preview();};row.append(enabled);
      const select=el('button',(index+1)+'. '+stage.name,'mongo-stage-select');select.type='button';select.title=PIPELINE_STAGES[stage.name][0];select.setAttribute('aria-pressed',String(stage.id===this.selected));select.onclick=()=>{this.selected=stage.id;this.render();this.value.focus();};select.draggable=true;
      select.ondragstart=event=>{this.dragId=stage.id;event.dataTransfer.effectAllowed='move';event.dataTransfer.setData('text/plain',stage.id);};
      select.ondragend=()=>{this.dragId=null;for(const item of this.list.children)item.classList.remove('drop-target');};
      row.ondragover=event=>{if(this.dragId&&this.dragId!==stage.id){event.preventDefault();event.dataTransfer.dropEffect='move';row.classList.add('drop-target');}};
      row.ondragleave=()=>row.classList.remove('drop-target');
      row.ondrop=event=>{event.preventDefault();if(this.dragId){const id=this.dragId;this.dragId=null;this.model.move(id,index);this.selected=id;this.render();this.focusStage();this.status.textContent='Stage moved to position '+(index+1);}};
      row.append(select);
      const up=this.button(row,'Move stage '+(index+1)+' up','chevron-up',()=>this.move(stage.id,index-1));up.disabled=index===0;
      const down=this.button(row,'Move stage '+(index+1)+' down','chevron-down',()=>this.move(stage.id,index+1));down.disabled=index===this.model.stages.length-1;
      this.button(row,'Delete stage '+(index+1),'trash-2',()=>{this.model.remove(stage.id);this.render();(this.selected?this.list.querySelector('.mongo-stage-select'):this.palette).focus();});
      this.list.append(row);
    }
    const stage=this.model.stages.find(s=>s.id===this.selected);this.label.firstChild.textContent=stage?stage.name+' — '+PIPELINE_STAGES[stage.name][0]:'Select or add a stage';
    this.value.disabled=!stage;this.value.value=stage?.text??'';this.preview();
  }
  focusStage(){this.list.querySelector('[data-stage-id="'+this.selected+'"] .mongo-stage-select')?.focus();}
  move(id,index){if(this.model.move(id,index)){this.selected=id;this.render();this.focusStage();this.status.textContent='Stage moved to position '+(index+1);}}
  preview(){
    if(this.unavailable){this.use.disabled=true;this.status.textContent=this.unavailable;return;}
    try{this.output.value=this.model.text();this.use.disabled=false;const disabled=this.model.stages.filter(s=>!s.enabled).length;this.status.textContent=disabled?disabled+' disabled stage(s) will be omitted from the command. Disabled drafts are discarded when this builder closes.':'Ready to copy into the command editor. Use Run separately; queries can scan extensively.';}
    catch(error){this.output.value='';this.use.disabled=true;this.status.textContent=error.message;}
  }
  open(){document.body.append(this.root);this.root.showModal();this.palette.focus();}
  invalidate(message){this.unavailable=message;this.preview();}
  dispose(){this.root.close();this.root.remove();this.model.stages=[];this.value.value=this.output.value='';}
}
