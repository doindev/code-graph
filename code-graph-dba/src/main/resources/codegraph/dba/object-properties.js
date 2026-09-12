import {TableProperties} from './table-properties.js';
import {lucide} from './tree-icons.js';
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
const copy=value=>JSON.parse(JSON.stringify(value));
const button=(text,icon,action)=>{const n=el('button',undefined,'designer-button');n.type='button';n.append(lucide(icon),document.createTextNode(text));n.onclick=action;return n;};

/** Same draft, job ownership and disposal lifecycle as the Table Properties editor. */
export class ObjectProperties extends TableProperties {
  constructor(options){super(options);this.queryBuilder=options.queryBuilder??(()=>null);this.category='General';this.filter='';}
  get dirty(){return super.dirty||!!this.queryBuilder()?.dirty;}
  syncButtons(){super.syncButtons();this.queryBuilder()?.syncViewActions();}
  async revert(){if(this.loading||this.unreconciled||this.committed)return;this.loading=true;this.update();try{this.draft=this.baseline();await this.queryBuilder()?.restoreView();this.message='Pending changes reverted.';}catch(error){this.message=error.message;}finally{this.loading=false;this.changed();this.render();this.queryBuilder()?.notice(this.message);}}
  load(message='',queryAction=null){if(this.loadPromise)return this.loadPromise;this.loadPromise=this.loadNow(message,queryAction).finally(()=>this.loadPromise=null);return this.loadPromise;}
  baseline(){return {fields:copy(this.snapshot.fields),sqlMode:!this.snapshot.formSupported,sql:this.snapshot.template??'',splitSql:false};}
  async loadNow(message='',queryAction=null){
    if(this.disposed)return;this.loading=true;this.render();
    try{const {value}=await this.job('/object-properties/load',this.target());if(this.disposed)return;this.snapshot=value;this.draft=this.baseline();this.unreconciled=false;this.committed=false;this.message=message;this.cache.clear();if(queryAction==='saved')this.queryBuilder()?.markViewSaved();else if(queryAction==='reload'&&this.queryBuilder())await this.queryBuilder().reloadView(value.fields.query);}
    catch(e){this.message=e.message;}finally{this.loading=false;this.changed();this.render();}
  }
  async refresh(){
    if(this.committed){await this.recover();return;}
    if((this.dirty||this.unreconciled)&&!await this.confirmDiscard('Reload the database properties and discard this object draft?'))return;
    await this.load('', 'reload');
  }
  async canClose(){
    if(this.loading)return false;if(!this.dirty&&!this.unreconciled&&!this.committed)return true;
    return new Promise(resolve=>{
      const dialog=el('dialog',undefined,'designer-dialog');dialog.append(el('header','Unsaved object changes'));
      const footer=el('footer');let saving=false,result=false;
      const save=button('Save','save',async()=>{saving=true;dialog.close();resolve(await this.save());});save.disabled=this.unreconciled||this.committed;
      footer.append(save,button('Discard','trash-2',()=>{result=true;dialog.close();}),button('Cancel','x',()=>dialog.close()));dialog.append(footer);
      dialog.onclose=()=>{dialog.remove();if(!saving)resolve(result);};document.body.append(dialog);dialog.showModal();
    });
  }
  render(){
    if(!this.host?.isConnected||this.disposed)return;const host=this.host;host.className='table-properties object-properties';host.replaceChildren();
    const context=this.target();host.append(el('div',[context.connectionName,this.snapshot?.database??context.target?.database,this.snapshot?.label??'Object'].filter(Boolean).join(' · '),'object-editor-context'));
    if(this.message){const status=el('p',this.message,'designer-message');status.setAttribute('role','status');host.append(status);}
    if(!this.snapshot){if(!this.loading)host.append(button('Retry metadata','refresh-cw',()=>this.load()));return;}
    if(this.committed||this.unreconciled){
      host.append(el('p',this.committed?'Changes committed. Resolve the object before saving again.':'The previous operation may have partially committed. Reload and compare the actual object before another save.','designer-reconcile'));
      if(this.committed)host.append(button('Resolve saved object','refresh-cw',()=>this.recover()));
    }
    const work=el('div',undefined,'designer-work'),nav=el('nav',undefined,'designer-nav'),content=el('section',undefined,'designer-content object-property-content');
    nav.setAttribute('role','tablist');nav.setAttribute('aria-label',(this.snapshot.label??'Object')+' property categories');nav.setAttribute('aria-orientation','vertical');content.setAttribute('role','tabpanel');
    const categories=this.snapshot.categories;if(!categories.includes(this.category))this.category=categories[0];
    for(const [index,name]of categories.entries()){
      const tab=el('button',name);tab.type='button';tab.setAttribute('role','tab');tab.setAttribute('aria-selected',String(this.category===name));tab.tabIndex=this.category===name?0:-1;
      tab.id='object-category-'+index;tab.setAttribute('aria-controls','object-property-panel');if(name===this.category)content.setAttribute('aria-labelledby',tab.id);
      const select=()=>{this.category=name;this.filter='';this.render();};tab.onclick=select;
      tab.onkeydown=e=>{if(!['ArrowUp','ArrowDown','Home','End'].includes(e.key))return;e.preventDefault();this.category=categories[e.key==='Home'?0:e.key==='End'?categories.length-1:(index+(e.key==='ArrowUp'?-1:1)+categories.length)%categories.length];this.render();host.querySelector('.designer-nav [aria-selected=true]')?.focus();};
      nav.append(tab);
    }
    content.id='object-property-panel';work.append(nav,content);host.append(work);
    const heading=el('div',undefined,'object-property-heading');heading.append(el('h3',this.category));
    if(this.category!=='DDL'){const search=el('input');search.type='search';search.placeholder='Filter properties';search.setAttribute('aria-label','Filter properties');search.value=this.filter;search.oninput=()=>{this.filter=search.value.toLowerCase();for(const row of content.querySelectorAll('[data-property]'))row.hidden=!row.dataset.property.includes(this.filter);};heading.append(search);}
    content.append(heading);
    if(this.category==='DDL')this.ddl(content);else {
      let count=0;
      for(const spec of this.snapshot.controls.filter(c=>c.category===this.category)){this.field(content,spec);count++;}
      if(this.snapshot.details[this.category]!==undefined){this.metadata(content,this.snapshot.details[this.category]);count++;}
      if(!count)content.append(el('p',this.creation?'Additional properties can be specified in the native creation statement on DDL.':'The database did not expose entries for this category. Native properties can also be edited through DDL.'));
    }
    if(this.snapshot.warnings?.length){const warnings=el('details',undefined,'object-editor-notes');warnings.append(el('summary','Database capabilities and metadata notes'));for(const warning of this.snapshot.warnings)warnings.append(el('p',warning));host.append(warnings);}
    const footer=el('footer',undefined,'designer-footer');
    const refresh=button('Refresh metadata','refresh-cw',()=>this.refresh());refresh.disabled=this.loading;
    this.saveButton=button('Save','save',()=>this.save());
    this.revertButton=button('Revert','undo-2',()=>this.revert());
    footer.append(refresh,this.saveButton,this.revertButton);host.append(footer);
    if(this.loading||this.unreconciled||this.committed)for(const input of content.querySelectorAll('input,textarea,select,button'))input.disabled=true;
    this.syncButtons();
  }
  field(host,spec){
    const row=el('label',undefined,'object-property-row');row.dataset.property=(spec.label+' '+spec.id).toLowerCase();row.append(el('span',spec.label));
    let input;
    if(spec.type==='boolean'){input=el('input');input.type='checkbox';input.checked=!!this.draft.fields[spec.id];}
    else if(spec.type==='select'){input=el('select');for(const value of spec.options??[]){const option=el('option',value);option.value=value;input.append(option);}input.value=this.draft.fields[spec.id]??'';}
    else{input=el(['textarea','sql'].includes(spec.type)?'textarea':'input');if(input.tagName==='INPUT')input.type='text';input.value=this.draft.fields[spec.id]??'';input.spellcheck=false;input.maxLength=65536;if(spec.type==='sql')input.classList.add('object-sql-field');if(input.tagName==='TEXTAREA')input.rows=spec.type==='sql'?8:3;}
    input.setAttribute('aria-label',spec.label);input.disabled=(!spec.editable&&!(spec.id==='name'&&this.creation))||(this.draft.sqlMode&&spec.id!=='name');
    if(!spec.editable)input.title='Inspect here; use DDL for native changes where supported.';
    if(spec.choices&&input.tagName==='INPUT'){const list=el('datalist');list.id='object-choices-'+spec.id;for(const value of spec.choices){const option=el('option');option.value=value.name;list.append(option);}input.setAttribute('list',list.id);row.append(list);}
    input.oninput=()=>{this.draft.fields[spec.id]=spec.type==='boolean'?input.checked:input.value;this.update();};row.append(input);host.append(row);
  }
  metadata(host,data){
    if(data===null){host.append(el('p','No explicit values returned by the database.'));return;}
    const entries=Array.isArray(data)?data:[data];
    if(!entries.length){host.append(el('p','No entries returned.'));return;}
    for(const [index,item]of entries.entries()){
      const section=el('div',undefined,'object-catalog-entry');if(entries.length>1)section.append(el('h4',item.name??item.column_name??item.parameter_name??'Entry '+(index+1)));
      if(item&&typeof item==='object')for(const[key,value]of Object.entries(item)){
        const row=el('div',undefined,'object-catalog-property');row.dataset.property=key.toLowerCase();
        row.append(el('span',key),el('pre',typeof value==='object'?JSON.stringify(value,null,2):String(value??'NULL')));section.append(row);
      }else section.append(el('pre',String(item)));host.append(section);
    }
  }
  ddl(host){
    host.append(el('p',this.creation?'Creation SQL is generated when you Save. Native SQL mode supports additional database-specific properties.':this.snapshot.ddlComplete?'Database definition (grants and related objects are shown in their property categories).':'Definition available from metadata; this is not a complete restoration script.'));
    const definition=el('textarea',undefined,'designer-ddl');definition.readOnly=true;definition.value=this.snapshot.ddl||'-- No native definition was returned.';definition.wrap='off';definition.setAttribute('aria-label','Existing object DDL');
    if(!this.creation){host.append(definition,button('Copy definition','copy',()=>navigator.clipboard.writeText(this.snapshot.ddl??'').catch(e=>{this.message=e.message;this.render();})));}
    const label=el('label',undefined,'object-mode-control'),mode=el('input');mode.type='checkbox';mode.checked=this.draft.sqlMode;mode.onchange=()=>{this.draft.sqlMode=mode.checked;this.update();this.render();};label.append(mode,document.createTextNode('Use native SQL for this save'));host.append(label);
    const sql=el('textarea',undefined,'designer-ddl');sql.value=this.draft.sql;sql.wrap='off';sql.rows=10;sql.maxLength=65536;sql.disabled=!this.draft.sqlMode;sql.setAttribute('aria-label','Object change SQL');sql.placeholder='Enter the complete CREATE or ALTER statement. Save opens review before execution.';sql.oninput=()=>{this.draft.sql=sql.value;this.update();};host.append(sql);
    const use=button('Use definition as draft','file-code',()=>{this.draft.sql=this.snapshot.ddl??'';this.draft.sqlMode=true;this.update();this.render();});use.disabled=!this.snapshot.ddl;host.append(use);
    const splitLabel=el('label',undefined,'object-mode-control'),split=el('input');split.type='checkbox';split.checked=this.draft.splitSql;split.disabled=!this.draft.sqlMode;split.onchange=()=>{this.draft.splitSql=split.checked;this.update();};splitLabel.append(split,document.createTextNode('Split multiple statements (leave off for a routine body)'));host.append(splitLabel);
    if(this.previewSql){const preview=el('textarea',undefined,'designer-ddl');preview.readOnly=true;preview.value=this.previewSql;preview.setAttribute('aria-label','Last reviewed object SQL');host.append(el('h4','Last reviewed changes'),preview);}
  }
  async reviewPlan(plan){
    this.previewSql=plan.sql;
    return new Promise(resolve=>{
      const dialog=el('dialog',undefined,'designer-dialog');this.review=dialog;
      dialog.append(el('header','Review '+this.snapshot.label+' changes'));
      const body=el('div',undefined,'designer-dialog-body');body.append(el('p',[this.target().connectionName,plan.snapshot.database,plan.snapshot.fields.schema,this.draft.fields.name].filter(Boolean).join(' · ')),el('p',plan.warning),el('p',plan.atomic?'Changes use one database transaction.':'Statements may commit immediately. A failure can leave earlier changes committed.'));
      const sql=el('textarea',undefined,'designer-review-sql');sql.readOnly=true;sql.value=plan.sql;sql.setAttribute('aria-label','Reviewed object SQL');body.append(sql);
      let accepted=false;const apply=button('Apply','check',()=>{accepted=true;dialog.close();});apply.disabled=true;
      const label=el('label'),ack=el('input');ack.type='checkbox';ack.onchange=()=>apply.disabled=!ack.checked;label.append(ack,document.createTextNode('I understand and approve the displayed SQL and its effects.'));body.append(label);
      const footer=el('footer');footer.append(apply,button('Cancel','x',()=>dialog.close()));dialog.append(body,footer);document.body.append(dialog);
      dialog.onclose=()=>{this.review=null;dialog.remove();resolve(accepted);};dialog.showModal();
    });
  }
  async save(){
    if(this.loading||this.unreconciled||this.committed||(!this.creation&&!this.dirty))return false;
    this.loading=true;this.message='Preparing object changes…';this.update();this.render();let preview,queryAction='reload';
    try{
      const builder=this.queryBuilder();if(builder?.dirty){
        if(builder.mode!=='visual')throw Error('This SQL cannot be represented by the canvas. Use the definition editor in Properties to save it.');
        const query=await builder.currentQuery();if(query.bindings?.length)throw Error('A saved view cannot contain runtime parameters. Replace them with typed values before saving.');
        if(this.draft.sqlMode)throw Error('Turn off native SQL mode in Properties before saving the Diagram query.');
        if(!this.snapshot.controls.some(c=>c.id==='query'&&c.editable))throw Error('Updating this view definition is not available through the property editor for this database.');
        this.draft.fields.query=query.sql;queryAction='saved';
      }
      preview=await this.job('/object-properties/prepare',{...this.target(),fingerprint:this.snapshot.fingerprint,draft:this.draft},true);
      if(this.disposed)return false;if(!await this.reviewPlan(preview.value)){this.message='Save canceled. Pending changes retained.';return false;}
      await this.beforeApply();this.message='Applying reviewed object changes…';this.render();
      const {value}=await this.job('/object-properties/apply',{planId:preview.id,confirmed:true});
      if(value.status!=='success'){this.unreconciled=['unknown','partial','partial_or_unknown'].includes(value.outcome);this.message=value.message+' Outcome: '+value.outcome+'.';return false;}
      this.committed=true;this.message='Saved. Resolving catalog identity…';await this.saved(value.fields,value);
      this.creation=false;this.snapshot=null;this.committed=false;this.message='Object changes saved.';return true;
    }catch(e){this.message=e.message;if(e.result)this.unreconciled=true;return false;}
    finally{if(preview)await this.release(preview.id);this.loading=false;if(!this.snapshot&&!this.disposed)await this.load(this.message,queryAction);this.changed();this.render();this.queryBuilder()?.notice(this.message);}
  }
  async recover(){
    if(this.loading)return;this.loading=true;this.render();
    try{await this.saved(this.draft.fields,{sqlMode:this.draft.sqlMode,recover:true});this.creation=false;this.committed=false;this.unreconciled=false;await this.load('Reloaded the saved object.','reload');}
    catch(e){this.message=e.message;}finally{this.loading=false;this.changed();this.render();}
  }
}
