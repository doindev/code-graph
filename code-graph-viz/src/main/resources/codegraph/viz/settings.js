/* Bundled Lucide icons: https://github.com/lucide-icons/lucide
ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

---

The following Lucide icons are derived from the Feather project:

airplay, alert-circle, alert-octagon, alert-triangle, aperture, arrow-down-circle, arrow-down-left, arrow-down-right, arrow-down, arrow-left-circle, arrow-left, arrow-right-circle, arrow-right, arrow-up-circle, arrow-up-left, arrow-up-right, arrow-up, at-sign, calendar, cast, check, chevron-down, chevron-left, chevron-right, chevron-up, chevrons-down, chevrons-left, chevrons-right, chevrons-up, circle, clipboard, clock, code, columns, command, compass, corner-down-left, corner-down-right, corner-left-down, corner-left-up, corner-right-down, corner-right-up, corner-up-left, corner-up-right, crosshair, database, divide-circle, divide-square, dollar-sign, download, external-link, feather, frown, hash, headphones, help-circle, info, italic, key, layout, life-buoy, link-2, link, loader, lock, log-in, log-out, maximize, meh, minimize, minimize-2, minus-circle, minus-square, minus, monitor, moon, more-horizontal, more-vertical, move, music, navigation-2, navigation, octagon, pause-circle, percent, plus-circle, plus-square, plus, power, radio, rss, search, server, share, shopping-bag, sidebar, smartphone, smile, square, table-2, tablet, target, terminal, trash-2, trash, triangle, tv, type, upload, x-circle, x-octagon, x-square, x, zoom-in, zoom-out

The MIT License (MIT) (for the icons listed above)

Copyright (c) 2013-present Cole Bemis

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/
/* Server settings are a local draft until Apply. No project activity requests are made here. */
'use strict';
(() => {
  const descriptors = [
    { id: 'memory', name: 'Graph memory (RAM)', fields: [
      ['graphMemory', 'Graph/cache budget', 'Shared hybrid memory allowance, capacity, residency and disk paging.'],
      ['storage', 'Storage mode and usage', 'Cache estimates, disk bytes, hits and misses.']
    ] },
    { id: 'mcp', name: 'MCP connection', fields: [
      ['endpoint', 'MCP endpoint', 'Copy the HTTP URL to configure a local agent, or use the stdio transport.']
    ] },
    { id: 'ttl', name: 'Project idle timeout (TTL)', fields: [
      ['projectTtl', 'Project idle timeout', 'Expire inactive projects and release their resources after a duration.']
    ] }
  ];
  const lower = value => String(value ?? '').toLocaleLowerCase();
  function matches(query) {
    const q = lower(query).trim();
    return descriptors.map(d => {
      const all = !q || lower('Server ' + d.name).includes(q);
      return { ...d, fields: d.fields.filter(f => all || lower(f.slice(1).join(' ')).includes(q)) };
    }).filter(d => d.fields.length);
  }
  function normalized(key, value) {
    const v = String(value).trim();
    const match = key === 'projectTtl' ? /^([0-9]+)(s|m|h|d)$/i.exec(v) : /^([0-9]+)(m|g|mib|gib)$/i.exec(v);
    if (!match) return null;
    const unit = match[2].toLowerCase();
    const factors = key === 'projectTtl' ? {s:1n,m:60n,h:3600n,d:86400n} : {m:1048576n,mib:1048576n,g:1073741824n,gib:1073741824n};
    const n = BigInt(match[1]) * factors[unit];
    const max = key === 'projectTtl' ? 9223372036n : 9223372036854775807n;
    return n < (key === 'projectTtl' ? 1n : 33554432n) || n > max ? null : n.toString();
  }
  function changed(base, draft, enabled) {
    return Object.fromEntries(Object.keys(draft).filter(k => enabled[k] &&
      (normalized(k, draft[k]) === null ? draft[k].trim() !== base[k].trim()
        : normalized(k, draft[k]) !== normalized(k, base[k]))).map(k => [k, draft[k].trim()]));
  }
  const paths = {
    x: '<path d="m6 6 12 12M6 18 18 6"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    settings: '<path d="M12.22 2h-.44a2 2 0 0 0-2 1.72l-.12.87a2 2 0 0 1-1.18 1.52l-.36.16a2 2 0 0 1-1.92-.18l-.7-.54a2 2 0 0 0-2.62.62l-.22.38a2 2 0 0 0 .62 2.62l.7.54a2 2 0 0 1 .8 1.73v.4a2 2 0 0 1-.8 1.73l-.7.54a2 2 0 0 0-.62 2.62l.22.38a2 2 0 0 0 2.62.62l.7-.54a2 2 0 0 1 1.92-.18l.36.16a2 2 0 0 1 1.18 1.52l.12.87a2 2 0 0 0 2 1.72h.44a2 2 0 0 0 2-1.72l.12-.87a2 2 0 0 1 1.18-1.52l.36-.16a2 2 0 0 1 1.92.18l.7.54a2 2 0 0 0 2.62-.62l.22-.38a2 2 0 0 0-.62-2.62l-.7-.54a2 2 0 0 1-.8-1.73v-.4a2 2 0 0 1 .8-1.73l.7-.54a2 2 0 0 0 .62-2.62l-.22-.38a2 2 0 0 0-2.62-.62l-.7.54a2 2 0 0 1-1.92.18l-.36-.16a2 2 0 0 1-1.18-1.52l-.12-.87A2 2 0 0 0 12.22 2z"/><circle cx="12" cy="12" r="3"/>',
    'folder-plus': '<path d="M3 7V5h6l2 2h10v13H3ZM12 10v7m-3-3h6"/>',
    plug: '<path d="m9 3 3 3m3-3 3 3M7 8l9 9m-7-7-3 3a4 4 0 0 0 6 6l3-3M5 20l-2 2m8-16-4 4m12 3-4 4"/>',
    database: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14c0 4 18 4 18 0V5M3 12c0 4 18 4 18 0"/>'
  };
  function icons(root = document) {
    for (const host of root.querySelectorAll('[data-root-icon]')) {
      host.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">' + (paths[host.dataset.rootIcon] || '') + '</svg>';
    }
  }
  function element(tag, text, className) {
    const n = document.createElement(tag);
    if (text != null) n.textContent = text;
    if (className) n.className = className;
    return n;
  }
  class RootSettings {
    constructor({load, save, onSaved}) {
      Object.assign(this, {load, save, onSaved, selected:'server', expanded:true, ratio:50, version:0, ready:false, busy:false});
      this.$ = id => document.getElementById(id);
      this.dialog = this.$('root-settings-dialog');
      this.$('root-settings').onclick = () => this.open();
      this.$('settings-cancel').onclick = () => this.close();
      this.$('settings-apply').onclick = () => this.apply();
      this.$('settings-reload').onclick = () => this.reload(true);
      this.$('settings-search').oninput = () => {
        this.query = this.$('settings-search').value;
        if (this.query && !this.previousSelection) this.previousSelection = this.selected;
        if (!this.query && this.previousSelection) { this.selected = this.previousSelection; this.previousSelection = null; }
        this.renderTree(); this.renderContent();
      };
      this.$('settings-search-clear').onclick = () => {
        this.$('settings-search').value = ''; this.$('settings-search').dispatchEvent(new Event('input')); this.$('settings-search').focus();
      };
      this.dialog.addEventListener('cancel', e => { e.preventDefault(); this.close(); });
      this.dialog.addEventListener('keydown', e => {
        // Do not let Escape also navigate the graph.
        if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); this.close(); }
      });
      this.dialog.addEventListener('close', () => this.$('root-settings').setAttribute('aria-expanded','false'));
      const divider = this.$('settings-divider');
      divider.onpointerdown = e => { if(e.button !== 0 || this.busy) return; divider.setPointerCapture(e.pointerId); e.preventDefault(); };
      divider.onpointermove = e => {
        if (!divider.hasPointerCapture(e.pointerId)) return;
        const box = this.$('settings-work').getBoundingClientRect();
        this.resize((e.clientX - box.left) / box.width * 100);
      };
      divider.onpointerup = e => { if(divider.hasPointerCapture(e.pointerId)) divider.releasePointerCapture(e.pointerId); };
      divider.onkeydown = e => {
        const n = {ArrowLeft:this.ratio-2,ArrowRight:this.ratio+2,Home:25,End:70}[e.key];
        if(n !== undefined) { e.preventDefault(); this.resize(n); }
      };
      window.addEventListener('resize', () => { if(this.dialog.open) this.resize(this.ratio); });
    }
    resize(ratio) {
      const width = this.$('settings-work').clientWidth;
      const minimum = width ? Math.min(49,Math.max(25,140 / width * 100)) : 25;
      const maximum = width ? Math.max(51,Math.min(70,100 - 146 / width * 100)) : 70;
      this.ratio = Math.max(minimum,Math.min(maximum,ratio));
      this.$('settings-work').style.setProperty('--settings-left',this.ratio + '%');
      this.$('settings-divider').setAttribute('aria-valuenow',String(Math.round(this.ratio)));
      this.$('settings-divider').setAttribute('aria-valuemin',String(Math.ceil(minimum)));
      this.$('settings-divider').setAttribute('aria-valuemax',String(Math.floor(maximum)));
    }
    async open(section = 'server') {
      if(this.dialog.open) return;
      this.selected = section; this.query = ''; this.previousSelection = null;
      this.$('settings-search').value = ''; this.dialog.showModal();
      this.$('root-settings').setAttribute('aria-expanded','true');
      this.resize(this.ratio); this.$('settings-search').focus();
      await this.reload(false);
    }
    close() {
      if(this.busy) return;
      this.version++; this.dialog.close();
      this.draft = {}; this.ready = false;
    }
    baseline(info) {
      if (!info || typeof info.mutable !== 'boolean' || !info.graphStorage) throw new Error('Server settings response is incomplete.');
      const storage = info.graphStorage;
      this.info = info;
      this.base = {
        projectTtl: info.projectTtlSeconds == null ? '' : info.projectTtlSeconds + 's',
        graphMemory: storage.budgetBytes == null ? '' : (storage.budgetBytes / 1048576) + 'm'
      };
      this.enabled = {projectTtl: info.mutable && info.projectTtlSeconds != null,
        graphMemory: info.mutable && storage.mode === 'hybrid' && storage.budgetBytes != null};
    }
    async reload(preserve) {
      const version = ++this.version;
      const draft = preserve ? {...this.draft} : null;
      this.ready = false; this.reconcile = false; this.feedback('');
      this.renderTree(); this.renderContent(); this.footer();
      try {
        const info = await this.load();
        if(version !== this.version || !this.dialog.open) return;
        this.baseline(info); this.draft = {...this.base, ...draft}; this.ready = true;
        if(preserve) this.feedback('Server values reloaded. Review your draft against the current values before applying again.');
      } catch(e) {
        if(version !== this.version || !this.dialog.open) return;
        this.feedback('Unable to load settings: ' + e.message, true);
      }
      this.renderTree(); this.renderContent(); this.footer();
    }
    feedback(message, retry = false) {
      this.$('settings-feedback').hidden = !message;
      this.$('settings-feedback').querySelector('span').textContent = message;
      this.$('settings-reload').hidden = !retry;
    }
    renderTree() {
      const root = this.$('settings-tree');
      const list = matches(this.query);
      this.$('settings-search-clear').hidden = !this.query;
      root.replaceChildren();
      this.$('settings-search-status').textContent = list.length + ' matching settings categories';
      if(!list.length) { root.append(element('p','No matching settings.','settings-no-match')); return; }
      if(this.query && !list.some(d=>d.id===this.selected)) this.selected=list[0].id;
      const expanded = this.expanded || !!this.query;
      const row = (id, name, parent) => {
        const n = element('div',null,'settings-node');
        n.setAttribute('role','treeitem'); n.dataset.section = id;
        n.setAttribute('aria-label',name);
        n.setAttribute('aria-selected',String(this.selected===id));
        n.tabIndex = this.selected===id ? 0 : -1;
        if(parent) {
          n.setAttribute('aria-expanded',String(expanded));
          const b = element('button',expanded ? '⌄' : '›','settings-chevron');
          b.tabIndex = -1; b.title = expanded ? 'Collapse Server' : 'Expand Server';
          b.setAttribute('aria-label',b.title);
          b.onclick = e => {e.stopPropagation(); if(!this.query) this.expanded=!this.expanded; this.renderTree(); root.querySelector('[data-section=server]')?.focus();};
          n.append(b);
        }
        n.append(element('span',name));
        n.onclick = e => {
          if(e.target.closest('[role=treeitem]')!==n) return;
          this.selected=id; this.renderTree(); this.renderContent();
          root.querySelector('[data-section="'+id+'"]')?.focus();
        };
        n.onkeydown = e => {
          if(e.target!==n)return;
          const items=[...root.querySelectorAll('[role=treeitem]')], index=items.indexOf(n);
          let target;
          if(e.key==='ArrowDown')target=items[Math.min(items.length-1,index+1)];
          if(e.key==='ArrowUp')target=items[Math.max(0,index-1)];
          if(e.key==='Home')target=items[0];
          if(e.key==='End')target=items.at(-1);
          if(e.key==='ArrowRight'&&parent){this.expanded=true;this.renderTree();target=root.querySelectorAll('[role=treeitem]')[1];}
          if(e.key==='ArrowLeft'){this.expanded=parent?false:this.expanded;this.selected='server';this.renderTree();this.renderContent();target=root.firstElementChild;}
          if(e.key==='Enter'||e.key===' '){e.preventDefault();n.click();return;}
          if(target){e.preventDefault();for(const item of root.querySelectorAll('[role=treeitem]'))item.tabIndex=-1;target.tabIndex=0;target.focus();}
        };
        return n;
      };
      const server=row('server','Server',true);
      root.append(server);
      if(expanded){
        const group=element('div',null,'settings-tree-group');group.setAttribute('role','group');
        for(const d of list)group.append(row(d.id,d.name,false));
        server.append(group);
      }
      if(!root.querySelector('[tabindex="0"]')) server.tabIndex=0;
    }
    renderContent() {
      const pane=this.$('settings-content');pane.replaceChildren();
      if(!this.ready){pane.append(element('p','Settings are unavailable until server values are loaded.','settings-note'));return;}
      const list=matches(this.query);
      if(!list.length){pane.append(element('p','Try searching for memory, idle timeout, or MCP.','settings-note'));return;}
      const d=descriptors.find(d=>d.id===this.selected);
      pane.append(element('div','SERVER SETTINGS','settings-eyebrow'),element('h3',d?.name || 'Server'));
      if(!this.info.mutable) pane.append(element('p','Read-only server. Restart with --viz-admin to change these settings.','settings-caution'));
      if(!d){
        pane.append(element('p','Manage this running server without changing your project or database workspace.','settings-note'));
        for(const category of descriptors){
          const b=element('button',category.name,'settings-category-link');
          b.onclick=()=>{this.selected=category.id;this.renderTree();this.renderContent();};
          pane.append(b);
        }
        pane.append(element('p','Changes apply to this server session. Startup arguments still control the next launch.','settings-note'));
        return;
      }
      const visible = new Set(list.find(x=>x.id===d.id)?.fields.map(f=>f[0])||[]);
      const input=(key,label,help)=>{
        if(!visible.has(key))return;
        const wrapper=element('div',null,'settings-field');
        const l=element('label',label);l.htmlFor='setting-'+key;
        const field=element('input');field.id=l.htmlFor;field.value=this.draft[key];field.disabled=!this.enabled[key];
        field.spellcheck=false;field.autocomplete='off';field.setAttribute('aria-describedby',field.id+'-help');
        field.oninput=()=>{this.draft[key]=field.value;field.removeAttribute('aria-invalid');this.footer();};
        const p=element('p',help,'settings-note');p.id=field.id+'-help';
        wrapper.append(l,field,p,element('p','Current server value: '+(this.base[key]||'unavailable'),'settings-current'));pane.append(wrapper);
      };
      if(d.id==='ttl'){
        input('projectTtl','Project idle timeout','A positive whole number followed by s, m, h, or d; for example 10m or 1h.');
        pane.append(element('p','Shortening the timeout can expire already-idle projects at the next cleanup. Active project operations pause expiry. Listing projects and opening Settings do not renew their lifetime.','settings-caution'));
      }
      if(d.id==='memory'){
        const s=this.info.graphStorage, mib=v=>v==null?'unavailable':(v/1048576).toLocaleString(undefined,{maximumFractionDigits:1})+' MiB';
        input('graphMemory','Graph/cache budget','Whole MiB or GiB: 1536m, 2g, 1536MiB. Minimum 32 MiB; the server also enforces its metadata reserve.');
        if(visible.has('storage')){
          const dl=element('dl',null,'settings-metrics');
          for(const [name,value] of [['Mode',s.mode],['Accounted budget',mib(s.budgetBytes)],['Cache residency (estimate)',mib(s.cacheUsedBytesEstimate)],['Cache capacity',mib(s.cacheCapacityBytes)],['Disk usage',mib(s.diskBytes)],['Cache hits / misses',(s.cacheHits??'—')+' / '+(s.cacheMisses??'—')]])dl.append(element('dt',name),element('dd',value));
          pane.append(dl);
        }
        pane.append(element('p',s.mode==='hybrid'?'Reducing the budget may evict cached pages. This is a shared graph/cache allowance—not a hard limit on total JVM, native, or process RAM. DBA has its own separate allowance.':'Pure in-memory mode has no adjustable page cache. Restart with --graph-storage hybrid to enable disk paging. A graph/cache budget is not a hard total-RAM limit.','settings-caution'));
      }
      if(d.id==='mcp'){
        const endpoint=this.info.mcpEndpoint;
        const field=element('input');field.readOnly=true;field.value=endpoint||'Unavailable';field.setAttribute('aria-label','MCP endpoint');
        pane.append(element('label','MCP endpoint'),field);
        if(endpoint && endpoint!=='stdio'){
          const copy=element('button','Copy endpoint','settings-copy');
          copy.onclick=async()=>{try{await navigator.clipboard.writeText(endpoint);this.$('settings-draft-status').textContent='Endpoint copied.';}catch{this.feedback('Clipboard unavailable. Select and copy the endpoint field instead.');field.focus();field.select();}};
          pane.append(copy);
        }
        pane.append(element('p',endpoint==='stdio'?'MCP uses standard input/output on this instance; there is no HTTP endpoint to copy. Configure your client to launch the server through stdio.':'Use this URL in a local MCP client. Cloud-hosted agents cannot reach your machine’s loopback address. Changing the transport or port requires a restart.','settings-note'));
      }
    }
    footer(){
      const count=this.ready?Object.keys(changed(this.base,this.draft,this.enabled)).length:0;
      this.$('settings-apply').disabled=!this.ready||this.busy||this.reconcile||!count;
      this.$('settings-cancel').disabled=this.busy;
      this.$('settings-work').inert=this.busy;
      this.$('settings-reload').disabled=this.busy;
      this.dialog.setAttribute('aria-busy',String(this.busy));
      this.$('settings-draft-status').textContent=this.busy?'Applying…':count?count+' unsaved setting'+(count===1?'':'s'):'No unsaved changes';
    }
    async apply(){
      if(!this.ready||this.busy||this.reconcile)return;
      const payload=changed(this.base,this.draft,this.enabled);
      if(!Object.keys(payload).length)return;
      const invalid=Object.keys(payload).find(k=>normalized(k,payload[k])===null);
      if(invalid){
        this.query='';this.$('settings-search').value='';this.selected=invalid==='projectTtl'?'ttl':'memory';
        this.renderTree();this.renderContent();
        this.feedback(invalid==='projectTtl'?'Enter a positive duration, such as 10m or 1h.':'Enter a whole memory amount of at least 32 MiB, such as 1536m or 2g.');
        const input=this.$('setting-'+invalid);input.setAttribute('aria-invalid','true');input.focus();return;
      }
      this.busy=true;this.feedback('');this.footer();
      try{
        const info=await this.save(payload);
        this.baseline(info);
        this.onSaved(info);
        this.busy=false;this.footer();this.close();
      }catch(e){
        // Never retry an ambiguous write: reload the actual state and require a fresh Apply.
        if(!e.status||e.status>=500){
          this.reconcile=true;
          try {this.baseline(await this.load());this.reconcile=false;this.renderContent();}
          catch { /* Keep Apply blocked until explicit reload succeeds. */ }
          this.feedback('The save outcome could not be confirmed. '+(this.reconcile?'Reload server settings before retrying.':'Current server values were reloaded; review the remaining draft.')+' '+e.message,this.reconcile);
        }else this.feedback(e.message);
      }finally{this.busy=false;this.footer();}
    }
  }
  if(typeof module!=='undefined') module.exports={normalized,changed,matches};
  if(typeof window!=='undefined') Object.assign(window,{RootSettings,rootIcons:icons});
})();
