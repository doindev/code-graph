/*
 * code-graph visualization frontend (3D + 2D).
 * Plain ES2020, no build step; the only libraries are the vendored 3d-force-graph
 * (global ForceGraph3D) and force-graph (global ForceGraph). All data comes from
 * the same-origin /api endpoints.
 */
'use strict';

// ---------------------------------------------------------------- constants

const KIND_COLORS = {
  CONTAINS: '#3b4252',
  CALLS: '#7aa2f7',
  REFERENCES: '#9ece6a',
  EXTENDS: '#bb9af7',
  IMPLEMENTS: '#b48ead',
  IMPORTS: '#565f89',
  INVOKES_REMOTE: '#ff9e64',
  DEPENDS: '#7dcfff'
};

const BAND_COLORS = {
  low: '#4ade80',
  moderate: '#facc15',
  high: '#fb923c',
  critical: '#f87171'
};

const DBLCLICK_MS = 300;
const BG_COLOR = '#0b0e14';
const NODE_REL_SIZE = 4;  // set explicitly on the 2D instance so custom paint matches hit-detection

// Per-renderer galaxy performance budgets. 3D is the expensive path (WebGL + force sim + per-link
// particles), so it caps hard; the cap we SEND is min(slider, budget) so a big codebase can never
// flood the browser with nodes.
const GALAXY_BUDGET = { '3d': 2500, '2d': 4000 };
// Above this many rendered elements (nodes+links) in 3D we warn the user to switch to 2D / filter.
// Kept above the normal capped max (2500 nodes + 10000-link server cap ≈ 12500) so it stays a genuine
// safety net rather than a false alarm on every large project.
const GALAXY_3D_ELEMENT_CEILING = 14000;

// ---------------------------------------------------------------- dom + state

const $ = (id) => document.getElementById(id);

const state = {
  project: null,
  view: null,        // current view descriptor: {type:'overview'|'module'|'file'|'ego'|'galaxy', ...}
  crumbs: [],        // drill-down trail of view descriptors
  renderMode: '3d',  // '3d' (ForceGraph3D) or '2d' (ForceGraph)
  lastPayload: null, // pristine payload of the current view, re-fed on renderer swap
  egoDepth: 2,
  galaxyCap: 1500,
  galaxyConf: 0,
  galaxyLang: '',
  mcpEndpoint: null, // from /api/server
  mutable: false,    // from /api/server — gates the add/reindex/remove UI entirely
  reindexing: false, // a reindex-and-poll loop is in flight
  browsePath: null,  // directory currently shown in the add-project browse modal
  symbolsByProject: {}, // name -> symbol count (from /api/projects) for auto-tuning the galaxy cap
  galaxyTuned: {},   // name -> true once we've auto-lowered the galaxy slider for a big project
  maxVal: 1,         // 2D: largest node.val in the current payload, for bounded radius scaling
  jobId: null,       // in-flight async task job id (add or reindex), for the shared status poll + cancel
  jobKind: null,     // 'add' | 'reindex' — which task the current job is
  jobName: null,     // display name of the current job's project
  hoverNode: null,   // node currently under the cursor (drives the custom tooltip)
  mouseX: 0,         // latest cursor position (client coords) for tooltip placement
  mouseY: 0
};

// small DOM helper: element with class + text (textContent only — never innerHTML for data)
function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = String(text);
  return node;
}

function toast(message) {
  const box = $('toast');
  box.textContent = message;
  box.classList.remove('hidden');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => box.classList.add('hidden'), 4000);
}

function setStatus(text) {
  $('status').textContent = text;
}

// ---------------------------------------------------------------- api

async function api(path, method) {
  let response;
  try {
    response = await fetch(path, method ? { method } : undefined);
  } catch (e) {
    throw new Error('network error: ' + e.message);
  }
  let body = null;
  try {
    body = await response.json();
  } catch (e) { /* non-JSON error body */ }
  if (!response.ok) {
    throw new Error((body && body.error) || ('HTTP ' + response.status + ' for ' + path));
  }
  return body;
}

function projectBase() {
  return '/api/p/' + encodeURIComponent(state.project);
}

function urlFor(view) {
  switch (view.type) {
    case 'overview':
      return projectBase() + '/overview';
    case 'module':
      return projectBase() + '/module?name=' + encodeURIComponent(view.name);
    case 'file':
      return projectBase() + '/file?path=' + encodeURIComponent(view.path);
    case 'ego':
      return projectBase() + '/ego?id=' + encodeURIComponent(view.id) + '&depth=' + state.egoDepth;
    case 'galaxy': {
      let url = projectBase() + '/galaxy?cap=' + effectiveGalaxyCap() + '&minConfidence=' + state.galaxyConf;
      if (state.galaxyLang) url += '&lang=' + encodeURIComponent(state.galaxyLang);
      return url;
    }
    default:
      throw new Error('unknown view: ' + view.type);
  }
}

function viewLabel(view) {
  switch (view.type) {
    case 'overview': return state.project;
    case 'module': return 'module ' + view.name;
    case 'file': return baseName(view.path);
    case 'ego': return 'ego: ' + view.label;
    case 'galaxy': return 'galaxy';
    default: return view.type;
  }
}

function baseName(path) {
  return path.substring(path.lastIndexOf('/') + 1);
}

function isGalaxyView() {
  return !!(state.view && state.view.type === 'galaxy');
}

function galaxyBudget() {
  return GALAXY_BUDGET[state.renderMode] || state.galaxyCap;
}

// The cap actually requested: never above the active renderer's budget, so 3D stays interactive.
function effectiveGalaxyCap() {
  return Math.min(state.galaxyCap, galaxyBudget());
}

// ---------------------------------------------------------------- renderers (3d + 2d)

let graph = null;  // active renderer instance — rebuilt by makeRenderer() on toggle

// single vs double click: neither lib emits dblclick, so track timestamps ourselves.
let clickTimer = null;
let lastClickNode = null;
let lastClickAt = 0;
let lastBackgroundClickAt = 0;

function handleNodeClick(node) {
  const now = Date.now();
  if (node === lastClickNode && (now - lastClickAt) < DBLCLICK_MS) {
    clearTimeout(clickTimer);
    clickTimer = null;
    lastClickNode = null;
    onNodeDoubleClick(node);
  } else {
    lastClickNode = node;
    lastClickAt = now;
    clearTimeout(clickTimer);
    clickTimer = setTimeout(() => onNodeSingleClick(node), DBLCLICK_MS);
  }
}

function handleBackgroundClick() {  // single click hides details; double click drills up a level
  const now = Date.now();
  if (now - lastBackgroundClickAt < DBLCLICK_MS) {
    lastBackgroundClickAt = 0;
    goUp();
  } else {
    lastBackgroundClickAt = now;
    hideDetails();
  }
}

function makeRenderer(mode) {
  if (graph && typeof graph._destructor === 'function') {
    graph._destructor();  // present in both vendored builds — frees WebGL/canvas resources
  }
  const container = $('graph');
  container.replaceChildren();
  const inner = document.createElement('div');
  container.appendChild(inner);

  graph = (mode === '2d' ? ForceGraph() : ForceGraph3D())(inner)
    .backgroundColor(BG_COLOR)
    .nodeLabel(() => '')  // disable the built-in tooltip (renders below-right, covering the node)
    .nodeVal((n) => n.val || 1)
    .nodeAutoColorBy('group')
    .linkColor((l) => KIND_COLORS[l.kind] || '#556')
    // DEPENDS = aggregated module→module edges; keep them a uniform width so 3D overview / module
    // boundary lines don't fatten with the underlying edge count. Other kinds stay count-based.
    .linkWidth((l) => l.kind === 'DEPENDS' ? 1.4
        : l.kind === 'CONTAINS' ? 0.3 : Math.min(4, 0.5 + Math.log1p(l.count || 1)))
    // Arrows + particles animate every frame per link — the biggest per-frame cost with many
    // links, so switch them OFF in galaxy mode (both renderers). Accessors re-evaluate on each
    // graphData() call, so this tracks the current view automatically.
    .linkDirectionalArrowLength(() => isGalaxyView() ? 0 : 3)
    .linkDirectionalArrowRelPos(1)
    .linkDirectionalParticles((l) => isGalaxyView() ? 0 : (l.kind === 'CALLS' ? 1 : 0))
    .linkDirectionalParticleWidth(1.2)
    .enableNodeDrag(true)
    .onNodeClick(handleNodeClick)
    .onNodeHover((node) => {  // custom tooltip positioned ABOVE the cursor (see renderTooltip)
      state.hoverNode = node || null;
      renderTooltip();
    })
    .onNodeDragEnd((node) => {  // pin dragged nodes so hand-made arrangements stick
      node.fx = node.x;
      node.fy = node.y;
      if (node.z !== undefined) node.fz = node.z;  // fz only exists in 3D
    })
    .onBackgroundClick(handleBackgroundClick)
    .width(window.innerWidth)
    .height(window.innerHeight);

  if (mode === '2d') {
    configure2d(graph);
  } else {
    graph.nodeOpacity(0.9).linkOpacity(0.45);  // 3D-only accessors
  }
}

// 2D node radius bounds: log-compressed into a fixed pixel range so one high-degree hub can't
// grow into a giant disk that hides everything else. Shared by the painter AND the hit-area.
const MIN_R = 3;
const MAX_R = 16;

function nodeRadius(node) {
  const maxVal = state.maxVal || 1;
  const t = Math.log1p(node.val || 1) / Math.log1p(maxVal);  // 0..1 on a log curve
  const r = MIN_R + (MAX_R - MIN_R) * Math.min(1, Math.max(0, t));
  return node.group === 'center' ? r * 1.4 : r;  // ego center may exceed MAX_R a bit, but stays sane
}

// 2D on-screen link width in px: denser edges slightly thicker, capped ~2.2px; CONTAINS stays thin.
function link2dWidth(link) {
  if (link.kind === 'CONTAINS') return 0.6;
  return Math.min(2.2, 0.5 + 0.35 * Math.log1p(link.count || 1));
}

// 2D-only: own the node painting so overlapping same-color clusters read clearly.
function configure2d(g) {
  g.nodeRelSize(NODE_REL_SIZE)
    .nodeCanvasObject((node, ctx, globalScale) => {
      const isCenter = node.group === 'center';
      const r = nodeRadius(node);  // already bounded + center-scaled
      const ring = 1.5 / globalScale;  // ~1.5 screen px at any zoom -> constant thickness

      // 1) background-colored halo first, so touching same-color nodes cut into each other
      ctx.beginPath();
      ctx.arc(node.x, node.y, r + ring, 0, 2 * Math.PI);
      ctx.fillStyle = BG_COLOR;
      ctx.fill();

      // 2) the node fill (color assigned by nodeAutoColorBy). Big nodes get a touch of
      //    transparency so anything overlapping underneath still shows through.
      ctx.beginPath();
      ctx.arc(node.x, node.y, r, 0, 2 * Math.PI);
      ctx.fillStyle = node.color || '#7aa2f7';
      ctx.globalAlpha = r >= MAX_R - 1 && !isCenter ? 0.9 : 1;
      ctx.fill();
      ctx.globalAlpha = 1;  // reset

      // 3) outline on top: white for the ego center, faint dark otherwise (kept subtle)
      ctx.lineWidth = (isCenter ? 2 : 1) / globalScale;
      ctx.strokeStyle = isCenter ? '#ffffff' : 'rgba(0,0,0,0.45)';
      ctx.stroke();

      // 4) readable label below the node once zoomed in enough
      if (globalScale > 1.5) {
        ctx.font = Math.max(2.5, 10 / globalScale) + 'px "Segoe UI", sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';
        ctx.fillStyle = '#9aa5ce';
        ctx.fillText(node.label, node.x, node.y + r + ring + 1);
      }
    })
    .nodePointerAreaPaint((node, color, ctx) => {  // keep click/hover hit-detection on the real circle
      ctx.fillStyle = color;
      const r = nodeRadius(node);  // same bounded radius as the painter
      ctx.beginPath();
      ctx.arc(node.x, node.y, r, 0, 2 * Math.PI);
      ctx.fill();
    })
    // Custom link painter (2D only): force-graph draws links in GRAPH units, so default link width
    // fattens as you zoom out. Dividing the desired px width by the live globalScale keeps it a
    // CONSTANT on-screen thickness at any zoom. (Trade-off: replace mode drops 2D arrows/particles —
    // an accepted nicety loss; colour-by-kind and count-based thickness are preserved.)
    .linkCanvasObject((link, ctx, globalScale) => {
      const s = link.source;
      const t = link.target;
      if (!s || !t || s.x === undefined || t.x === undefined) return;
      ctx.beginPath();
      ctx.moveTo(s.x, s.y);
      ctx.lineTo(t.x, t.y);
      ctx.strokeStyle = KIND_COLORS[link.kind] || '#556';
      ctx.lineWidth = link2dWidth(link) / globalScale;  // constant screen px
      ctx.stroke();
    });

  // spread the layout so same-color clusters don't pile (2D only — 3D depth already separates).
  // Reachable in the vendored force-graph 1.45 build: d3Force('charge'|'link'), d3VelocityDecay.
  // No forceCollide factory is bundled, so we widen via charge/link/decay instead.
  const charge = g.d3Force('charge');
  if (charge) {
    charge.strength(-120);
    if (charge.distanceMax) charge.distanceMax(400);
  }
  const link = g.d3Force('link');
  if (link && link.distance) link.distance(34);
  g.d3VelocityDecay(0.28);
  g.d3ReheatSimulation();
}

function setGraphData(payload) {
  state.lastPayload = payload;
  renderPayload();
}

function renderPayload() {
  const payload = state.lastPayload || { nodes: [], links: [], truncated: false };
  // deep-clone: graphData() mutates nodes/links in place, and a renderer toggle
  // must be able to re-feed the pristine payload to the other library.
  const data = JSON.parse(JSON.stringify({ nodes: payload.nodes || [], links: payload.links || [] }));
  for (const node of data.nodes) {  // ego center stands out: white + bigger
    if (node.group === 'center') {
      node.color = '#ffffff';
      node.val = Math.max((node.val || 1) * 3, 10);
    }
  }
  if (state.renderMode === '2d') {
    // per-payload max val drives the bounded log radius (read in nodeCanvasObject + hit-area)
    state.maxVal = data.nodes.reduce((m, n) => Math.max(m, n.val || 1), 1);
    // paint largest first (underneath) so small nodes land on top and never get fully buried.
    // Safe: links reference node ids, not array positions.
    data.nodes.sort((a, b) => (b.val || 1) - (a.val || 1));
  } else {
    // 3D force sim can peg the main thread forever. In galaxy, bound it so the layout FREEZES
    // quickly instead of grinding; restore gentle defaults for the small overview/module/file/ego
    // views so they still settle nicely. (cooldownTicks/Time confirmed in 3d-force-graph 1.77.)
    if (isGalaxyView()) {
      graph.warmupTicks(0).cooldownTicks(120).cooldownTime(4000);
    } else {
      graph.warmupTicks(0).cooldownTicks(Infinity).cooldownTime(15000);  // library defaults
    }
  }
  graph.graphData(data);
  setStatus(data.nodes.length + ' nodes · ' + data.links.length + ' links');
  $('truncated').classList.toggle('hidden', !payload.truncated);
  setTimeout(zoomFit, 600);  // frame the new layout once the simulation settles a bit
}

function releasePins() {
  const data = graph.graphData();
  for (const node of data.nodes) {
    node.fx = node.fy = node.fz = undefined;
  }
  graph.d3ReheatSimulation();
}

function flyTo(node) {
  if (node.x === undefined) return;
  if (state.renderMode === '2d') {
    graph.centerAt(node.x, node.y, 800);
    graph.zoom(4, 800);
    return;
  }
  const distance = 120;
  const r = Math.hypot(node.x, node.y, node.z) || 1;
  const k = 1 + distance / r;
  graph.cameraPosition({ x: node.x * k, y: node.y * k, z: node.z * k }, node, 800);
}

function zoomFit() {
  try {
    graph.zoomToFit(600, 40);  // present in both vendored builds
  } catch (e) { /* empty graph */ }
}

function zoomBy(factor) {  // 3D: move camera toward/away from its current look-at target
  const pos = graph.cameraPosition();
  const target = graph.controls().target;
  graph.cameraPosition({
    x: target.x + (pos.x - target.x) * factor,
    y: target.y + (pos.y - target.y) * factor,
    z: target.z + (pos.z - target.z) * factor
  }, target, 300);
}

function zoomIn() {
  if (state.renderMode === '2d') graph.zoom(graph.zoom() * 1.4, 300);
  else zoomBy(0.7);
}

function zoomOut() {
  if (state.renderMode === '2d') graph.zoom(graph.zoom() / 1.4, 300);
  else zoomBy(1 / 0.7);
}

function resetView() {
  releasePins();
  zoomFit();
}

// ---------------------------------------------------------------- renderer toggle

const HINTS = {
  '3d': 'drag rotate · scroll zoom · right-drag pan · node drag to pin',
  '2d': 'drag pan · scroll zoom · node drag to pin'
};

const HELP_CAMERA = {
  '3d': [
    ['Left-drag background', 'rotate camera'],
    ['Scroll wheel', 'zoom in / out (or the + / − buttons)'],
    ['Right-drag background', 'pan camera']
  ],
  '2d': [
    ['Drag background', 'pan'],
    ['Scroll wheel', 'zoom in / out (or the + / − buttons)']
  ]
};

const HELP_COMMON = [
  ['3D | 2D', 'switch renderer, keeping the current view'],
  ['Drag a node', 'move it; it stays pinned where you drop it'],
  ['Release pins', 'unpin all dragged nodes'],
  ['Click a node', 'fly to it and show details'],
  ['Double-click a module', 'drill down to its files'],
  ['Double-click a file', 'drill down to its symbols'],
  ['Double-click a symbol', 'open its ego (neighborhood) view'],
  ['↑ Up / Esc / double-click background', 'drill up one level'],
  ['Breadcrumbs', 'jump back to any earlier level'],
  ['Fit', 'frame the whole graph'],
  ['Reset view', 'camera home + release all pins']
];

function renderHelp() {
  const list = $('help-list');
  list.replaceChildren();
  for (const [term, meaning] of [...HELP_CAMERA[state.renderMode], ...HELP_COMMON]) {
    list.appendChild(el('dt', null, term));
    list.appendChild(el('dd', null, meaning));
  }
}

function setRenderMode(mode) {
  if (mode === state.renderMode) return;
  state.renderMode = mode;
  $('dim-3d').classList.toggle('active', mode === '3d');
  $('dim-2d').classList.toggle('active', mode === '2d');
  $('hint').textContent = HINTS[mode];
  renderHelp();
  makeRenderer(mode);
  // Galaxy's effective cap is renderer-dependent, so a toggle must re-request it (3D asks for
  // fewer nodes, 2D may allow more). Every other view just re-feeds the cached payload.
  if (state.view && state.view.type === 'galaxy') {
    loadView(state.view, 'keep');
  } else {
    renderPayload();  // same payload, mode, breadcrumbs and selection — just the other renderer
  }
}

// ---------------------------------------------------------------- navigation

async function loadView(view, crumbAction) {
  hideTooltip();  // data is about to swap; drop any stale hover
  setStatus('loading…');
  try {
    const data = await api(urlFor(view));
    state.view = view;
    if (crumbAction === 'reset') {
      state.crumbs = [view];
    } else if (crumbAction === 'push') {
      state.crumbs.push(view);
    } else if (crumbAction === 'replace') {
      state.crumbs[state.crumbs.length - 1] = view;
    } // 'keep': crumbs already correct (breadcrumb navigation)
    renderCrumbs();
    updateModeUi();
    hideDetails();
    setGraphData(data);
    if (view.type === 'galaxy') {
      const eff = effectiveGalaxyCap();
      if (eff < state.galaxyCap) {  // surface the clamp on the status line, alongside node/link counts
        setStatus($('status').textContent + ' · limited to ' + eff
            + ' for smooth ' + state.renderMode.toUpperCase() + ' (slider ' + state.galaxyCap + ')');
      }
      // Belt-and-suspenders: even after the node cap, thousands of links can be heavy in 3D.
      // If the payload is still large, tell the user how to see more without freezing the tab.
      const elements = (data.nodes || []).length + (data.links || []).length;
      if (state.renderMode === '3d' && elements > GALAXY_3D_ELEMENT_CEILING) {
        toast('Large graph — showing top ' + eff
            + ' nodes; use 2D or narrow with lang/module filters for more.');
      }
    }
  } catch (e) {
    toast(e.message);
    setStatus('load failed');
  }
}

function loadOverview() {
  loadView({ type: 'overview' }, 'reset');
}

function loadGalaxy() {
  maybeAutoTuneGalaxyCap();
  loadView({ type: 'galaxy' }, 'reset');
}

// First time galaxy opens for a project whose symbol count dwarfs the budget, drop the slider to
// the budget so the user starts smooth without having to discover the cap themselves.
function maybeAutoTuneGalaxyCap() {
  const name = state.project;
  if (!name || state.galaxyTuned[name]) return;
  state.galaxyTuned[name] = true;
  const symbols = state.symbolsByProject[name] || 0;
  const budget = galaxyBudget();
  if (symbols > budget * 2 && state.galaxyCap > budget) {
    state.galaxyCap = budget;
    $('galaxy-cap').value = String(budget);
    $('galaxy-cap-val').textContent = budget;
  }
}

function openModule(name) {
  loadView({ type: 'module', name }, 'push');
}

function openFile(path) {
  loadView({ type: 'file', path }, 'push');
}

function openEgo(id, label) {
  const top = state.crumbs[state.crumbs.length - 1];
  const action = (top && top.type === 'ego') ? 'replace' : 'push';
  loadView({ type: 'ego', id, label }, action);
}

function navigateToCrumb(index) {
  state.crumbs = state.crumbs.slice(0, index + 1);
  loadView(state.crumbs[index], 'keep');
}

function goUp() {  // one breadcrumb level up; no-op at root
  if (state.crumbs.length > 1) {
    navigateToCrumb(state.crumbs.length - 2);
  }
}

function renderCrumbs() {
  const bar = $('crumbs');
  bar.replaceChildren();
  state.crumbs.forEach((view, i) => {
    if (i > 0) bar.appendChild(el('span', 'crumb-sep', '›'));
    const crumb = el('button', 'crumb' + (i === state.crumbs.length - 1 ? ' current' : ''), viewLabel(view));
    crumb.addEventListener('click', () => navigateToCrumb(i));
    bar.appendChild(crumb);
  });
  $('nav-up').disabled = state.crumbs.length <= 1;
}

function updateModeUi() {
  const type = state.view ? state.view.type : null;
  const overviewFamily = type === 'overview' || type === 'module' || type === 'file';
  $('tab-overview').classList.toggle('active', overviewFamily);
  $('tab-galaxy').classList.toggle('active', type === 'galaxy');
  $('ego-controls').classList.toggle('hidden', type !== 'ego');
  $('galaxy-controls').classList.toggle('hidden', type !== 'galaxy');
}

// ---------------------------------------------------------------- node interaction

function onNodeSingleClick(node) {
  flyTo(node);
  if (node.kind === 'module') {
    showModuleDetails(node);
  } else if (node.kind === 'file' && String(node.id).startsWith('file:')) {
    showFileDetails(node);   // synthetic viz node — no /node details behind it
  } else {
    showNodeDetails(node.id);
  }
}

function onNodeDoubleClick(node) {
  if (node.kind === 'module') {
    openModule(node.label);
  } else if (node.kind === 'file') {
    const path = node.path || (String(node.id).startsWith('file:') ? String(node.id).slice(5) : null);
    if (path) openFile(path);
  } else {
    openEgo(node.id, node.label);  // symbols and external stubs
  }
}

// ---------------------------------------------------------------- details panel

function hideDetails() {
  $('details').classList.add('hidden');
}

// ---------------------------------------------------------------- custom hover tooltip

const TOOLTIP_GAP = 14;  // px between the cursor and the tooltip edge

function hideTooltip() {
  state.hoverNode = null;
  $('node-tooltip').classList.add('hidden');
}

// Render/position the custom tooltip ABOVE the cursor (flips below / clamps to the viewport edges).
function renderTooltip() {
  const tip = $('node-tooltip');
  const node = state.hoverNode;
  if (!node) {
    tip.classList.add('hidden');
    return;
  }
  // content — textContent only (data is escaped by construction)
  tip.replaceChildren();
  const line1 = el('div', 'tip-label', node.label);
  tip.appendChild(line1);
  const line2 = el('div', 'tip-meta');
  line2.appendChild(el('span', 'tip-kind', node.kind));
  if (node.sig) {
    line2.appendChild(el('span', 'tip-sep', '·'));
    line2.appendChild(el('span', 'tip-sig', node.sig));
  }
  tip.appendChild(line2);

  tip.classList.remove('hidden');
  // measure after content is set, then clamp to the viewport
  const w = tip.offsetWidth;
  const h = tip.offsetHeight;
  const vw = window.innerWidth;
  let left = state.mouseX;
  const half = w / 2;
  left = Math.max(half + 4, Math.min(vw - half - 4, left));  // keep within left/right edges

  const above = state.mouseY - h - TOOLTIP_GAP >= 0;  // enough room above the cursor?
  const top = above ? state.mouseY - TOOLTIP_GAP : state.mouseY + TOOLTIP_GAP;
  tip.style.left = left + 'px';
  tip.style.top = top + 'px';
  tip.style.transform = above ? 'translate(-50%, -100%)' : 'translate(-50%, 0)';
}

function detailsBody() {
  hideTooltip();  // a click opened the details panel — the tooltip has served its purpose
  const body = $('details-body');
  body.replaceChildren();
  $('details').classList.remove('hidden');
  return body;
}

function addHeader(body, label, kind) {
  const header = el('div', 'details-header');
  header.appendChild(el('span', 'details-label', label));
  header.appendChild(el('span', 'kind-badge kind-' + kind, kind));
  body.appendChild(header);
}

function addRow(body, name, value) {
  const row = el('div', 'details-row');
  row.appendChild(el('span', 'row-name', name));
  row.appendChild(el('span', 'row-value', value));
  body.appendChild(row);
}

function showModuleDetails(node) {
  const body = detailsBody();
  addHeader(body, node.label, 'module');
  if (node.files !== undefined) addRow(body, 'files', node.files);
  if (node.symbols !== undefined) addRow(body, 'symbols', node.symbols);
  const hint = el('div', 'details-hint', 'Double-click the node to open this module.');
  body.appendChild(hint);
}

function showFileDetails(node) {
  const body = detailsBody();
  addHeader(body, node.label, 'file');
  const path = node.path || String(node.id).slice(5);
  addRow(body, 'path', path);
  if (node.loc !== undefined) addRow(body, 'loc', node.loc);
  body.appendChild(el('div', 'details-hint', 'Double-click the node to open this file.'));
}

async function showNodeDetails(id) {
  let details;
  try {
    details = await api(projectBase() + '/node?id=' + encodeURIComponent(id));
  } catch (e) {
    toast(e.message);
    return;
  }
  const body = detailsBody();
  addHeader(body, details.label, details.kind);
  if (details.sig) body.appendChild(el('div', 'details-sig', details.sig));
  if (details.path) addRow(body, 'location', details.path + (details.line ? ':' + details.line : ''));
  if (details.lang) addRow(body, 'lang', details.lang);
  if (details.loc) addRow(body, 'loc', details.loc);

  if (details.counts) {
    const counts = el('div', 'counts-row');
    for (const [name, value] of Object.entries(details.counts)) {
      const cell = el('div', 'count-cell');
      cell.appendChild(el('div', 'count-value', value));
      cell.appendChild(el('div', 'count-name', name));
      counts.appendChild(cell);
    }
    body.appendChild(counts);
  }

  if (details.blast) {
    const blast = details.blast;
    const color = BAND_COLORS[blast.band] || '#c8d0e0';
    const section = el('div', 'blast-section');
    const head = el('div', 'blast-head');
    const score = el('span', 'blast-score', blast.score);
    score.style.color = color;
    head.appendChild(score);
    const band = el('span', 'blast-band', blast.band);
    band.style.color = color;
    head.appendChild(band);
    section.appendChild(head);
    for (const factor of blast.factors || []) {
      const row = el('div', 'factor-row');
      row.appendChild(el('span', 'factor-name', factor.name));
      const track = el('span', 'factor-track');
      const bar = el('span', 'factor-bar');
      bar.style.width = Math.min(100, (factor.points / 40) * 100) + '%';
      bar.style.background = color;
      track.appendChild(bar);
      row.appendChild(track);
      row.appendChild(el('span', 'factor-points', factor.points));
      section.appendChild(row);
    }
    if (blast.explanation) section.appendChild(el('div', 'blast-explanation', blast.explanation));
    body.appendChild(section);
  }

  const explore = el('button', 'explore-btn', 'Explore neighborhood');
  explore.addEventListener('click', () => openEgo(details.id, details.label));
  body.appendChild(explore);
}

// ---------------------------------------------------------------- search

let searchTimer = null;

// The results list overlaps the zoom bar (bottom-left), so hide the zoom bar while real results
// are shown and restore it when the list is cleared, empty, or a result is picked.
function setZoomBarHidden(hidden) {
  $('zoombar').classList.toggle('hidden', hidden);
}

function clearResults() {
  $('results-list').replaceChildren();
  $('results').classList.add('hidden');
  setZoomBarHidden(false);
}

async function runSearch(query) {
  const panel = $('results');
  const list = $('results-list');
  if (!query.trim()) {
    clearResults();
    return;
  }
  let results;
  try {
    results = await api(projectBase() + '/search?q=' + encodeURIComponent(query) + '&limit=20');
  } catch (e) {
    toast(e.message);
    return;
  }
  list.replaceChildren();
  panel.classList.remove('hidden');
  if (!results.length) {
    list.appendChild(el('li', 'result-empty', 'no matches'));
    setZoomBarHidden(false);  // no real results covering the zoom bar
    return;
  }
  setZoomBarHidden(true);  // ≥1 result shown -> get the zoom bar out from under the list
  for (const hit of results) {
    const item = el('li', 'result');
    const top = el('div', 'result-top');
    top.appendChild(el('span', 'result-label', hit.label));
    top.appendChild(el('span', 'kind-badge kind-' + hit.kind, hit.kind));
    item.appendChild(top);
    if (hit.path) item.appendChild(el('div', 'result-path', hit.path + (hit.line ? ':' + hit.line : '')));
    item.addEventListener('click', () => {
      clearResults();  // restore the zoom bar when a result is picked
      openEgo(hit.id, hit.label);
    });
    list.appendChild(item);
  }
}

// ---------------------------------------------------------------- legend

function renderLegend() {
  const legend = $('legend');
  legend.replaceChildren();
  for (const [kind, color] of Object.entries(KIND_COLORS)) {
    const entry = el('span', 'legend-entry');
    const swatch = el('span', 'legend-swatch');
    swatch.style.background = color;
    entry.appendChild(swatch);
    entry.appendChild(el('span', 'legend-name', kind));
    legend.appendChild(entry);
  }
}

// ---------------------------------------------------------------- server info + admin actions

function applyServerInfo(info) {
  state.mcpEndpoint = info.mcpEndpoint;
  state.mutable = info.mutable === true;
  const pill = $('mcp-pill');
  pill.textContent = 'MCP: ' + info.mcpEndpoint;
  pill.classList.remove('hidden');
  if (info.mcpEndpoint !== 'stdio') {
    pill.classList.add('copyable');
    pill.title = 'Click to copy the MCP endpoint';
  } else {
    pill.title = 'MCP served over stdio';
  }
  // read-only server: none of the add/reindex/remove UI renders at all
  $('project-actions').classList.toggle('hidden', !state.mutable);
}

async function copyMcpEndpoint() {
  if (!state.mcpEndpoint || state.mcpEndpoint === 'stdio') return;
  try {
    await navigator.clipboard.writeText(state.mcpEndpoint);
    toast('copied ' + state.mcpEndpoint);
  } catch (e) {
    toast('copy failed: ' + e.message);
  }
}

function populateProjects(projects) {
  const select = $('project');
  select.replaceChildren();
  for (const project of projects) {
    state.symbolsByProject[project.name] = project.symbols || 0;
    const option = el('option', null, project.name + ' (' + project.symbols + ' symbols)');
    option.value = project.name;
    select.appendChild(option);
  }
  if (projects.some((p) => p.name === state.project)) {
    select.value = state.project;
  }
}

async function refreshProjects() {
  try {
    const projects = await api('/api/projects');
    populateProjects(projects);
    return projects;
  } catch (e) {
    toast(e.message);
    return null;
  }
}

function setReindexing(flag) {
  state.reindexing = flag;
  $('reindex-project').disabled = flag;
  $('remove-project').disabled = flag;
  $('add-project').disabled = flag;
  $('reindex-glyph').classList.toggle('spinning', flag);
  $('reindex-label').textContent = flag ? ' Reindexing…' : ' Reindex';
}

async function reindexActive() {
  const name = state.project;
  if (!name || state.jobId) return;
  let job;
  try {
    job = await api('/api/p/' + encodeURIComponent(name) + '/reindex', 'POST');
  } catch (e) {
    toast(e.message);  // surface the server error body (incl. 403)
    return;
  }
  setReindexing(true);  // keep the reindex button disabled + spinning until a terminal state
  showTaskModal('Reindexing ' + name + '…',
      'Re-scanning ' + name + ' — parsing files and rebuilding the graph.');
  startJob('reindex', job, name);
}

async function removeActive() {
  const name = state.project;
  if (!name || state.jobId) return;
  if (!window.confirm('Remove project ' + name + ' from this server? (source on disk is not touched)')) {
    return;
  }
  try {
    await api('/api/p/' + encodeURIComponent(name), 'DELETE');
  } catch (e) {
    toast(e.message);
    return;
  }
  toast('removed ' + name);
  const projects = await refreshProjects();
  if (projects && projects.length) {
    state.project = projects[0].name;
    $('project').value = state.project;
    loadOverview();
  } else {
    state.project = null;
    setStatus('no projects');
  }
}

// ---- add-project browse modal ----

function closeBrowse() {
  $('browse-overlay').classList.add('hidden');
}

async function openBrowse(path) {
  let listing;
  try {
    listing = await api('/api/browse' + (path ? '?path=' + encodeURIComponent(path) : ''));
  } catch (e) {
    toast(e.message);  // includes the 403 "actions are disabled" message
    return;
  }
  state.browsePath = listing.path;
  $('browse-path').textContent = listing.path;
  const list = $('browse-list');
  list.replaceChildren();
  if (listing.parent) {
    const up = el('li', 'browse-entry browse-up');
    up.appendChild(el('span', 'folder-icon', '↰'));
    up.appendChild(el('span', 'browse-name', '..'));
    up.addEventListener('click', () => openBrowse(listing.parent));
    list.appendChild(up);
  }
  const entries = listing.entries || [];
  for (const entry of entries) {
    const row = el('li', 'browse-entry');
    row.appendChild(el('span', 'folder-icon', '📁'));
    row.appendChild(el('span', 'browse-name', entry.name));
    if (entry.looksLikeRepo) row.appendChild(el('span', 'repo-badge', 'repo'));
    row.addEventListener('click', () => openBrowse(entry.path));
    list.appendChild(row);
  }
  if (!entries.length) {
    list.appendChild(el('li', 'browse-empty', 'no subdirectories'));
  }
  $('browse-overlay').classList.remove('hidden');
}

const TASK_POLL_MS = 700;  // how often we poll GET /api/project/status while a job runs

let scanStart = 0;
let scanTimer = null;   // elapsed-time display interval
let scanPoll = null;    // status poll interval
let scanElapsed = 0;    // final measured seconds, stashed when the modal hides

// Reusable task modal, shared by add-project and reindex: a title, a message, the running timer
// and the Cancel button. Non-dismissable (backdrop/Escape ignored) — Cancel is the only exit.
function showTaskModal(title, message) {
  $('scan-title').textContent = title;
  $('scan-message').textContent = message;
  scanStart = Date.now();
  scanElapsed = 0;
  const tick = () => {
    $('scan-timer').textContent = ((Date.now() - scanStart) / 1000).toFixed(1) + 's';
  };
  tick();
  clearInterval(scanTimer);
  scanTimer = setInterval(tick, 250);
  $('scan-overlay').classList.remove('hidden');
}

// Single teardown for both intervals — called from every terminal path and from the modal hide,
// so neither the timer nor the poll can keep running once the modal is gone.
function hideTaskModal() {
  clearInterval(scanTimer);
  scanTimer = null;
  clearInterval(scanPoll);
  scanPoll = null;
  scanElapsed = (Date.now() - scanStart) / 1000;
  $('scan-overlay').classList.add('hidden');
}

async function addCurrentFolder() {
  const path = state.browsePath;
  if (!path) return;
  // The add is async on the backend: POST returns a job id immediately, then we poll status.
  // Close the browse modal first and put up the non-dismissable task modal (Cancel is the only exit).
  closeBrowse();
  const dirName = baseName(path) || path;
  showTaskModal('Scanning project…',
      'Indexing ' + dirName + ' — parsing files and building the graph. Large projects may take a moment.');
  let job;
  try {
    job = await api('/api/project?path=' + encodeURIComponent(path), 'POST');
  } catch (e) {
    hideTaskModal();
    toast(e.message);  // surface the server error body (incl. 403); no polling
    return;
  }
  if (job.name && job.name !== dirName) {  // reflect the server's deduped name
    $('scan-message').textContent = 'Indexing ' + job.name
        + ' — parsing files and building the graph. Large projects may take a moment.';
  }
  startJob('add', job, dirName);
}

// Arm the shared poll loop for a freshly-created job (add or reindex).
function startJob(kind, job, fallbackName) {
  state.jobKind = kind;
  state.jobId = job.job;
  state.jobName = job.name || fallbackName;
  pollJob(job.job);
}

function pollJob(jobId) {
  clearInterval(scanPoll);
  scanPoll = setInterval(async () => {
    if (state.jobId !== jobId) {  // superseded/cancelled — stop quietly
      clearInterval(scanPoll);
      scanPoll = null;
      return;
    }
    let status;
    try {
      status = await api('/api/project/status?job=' + encodeURIComponent(jobId));
    } catch (e) {
      return;  // transient — keep polling until a terminal state or the user cancels
    }
    if (state.jobId !== jobId) return;  // cancelled while the request was in flight
    if (status.state === 'indexing') return;  // still working; timer keeps ticking

    const kind = state.jobKind;
    const finalName = status.name || state.jobName;
    const secs = (typeof status.elapsedMs === 'number'
        ? status.elapsedMs / 1000 : (Date.now() - scanStart) / 1000).toFixed(1);
    finishJob();  // clears intervals, hides modal, re-enables the reindex button

    if (status.state === 'ready') {
      await refreshProjects();  // counts / new project name
      if (kind === 'add') {
        state.project = finalName;
        $('project').value = finalName;
        toast('added ' + finalName + ' in ' + secs + 's');
        loadOverview();
      } else {  // reindex: same project, refetch the current view against the rebuilt graph
        toast('reindexed ' + finalName + ' in ' + secs + 's');
        if (state.view) loadView(state.view, 'keep');
      }
    } else if (status.state === 'cancelled') {
      toast(kind === 'add'
          ? 'cancelled — ' + finalName + ' was not added'
          : 'reindex of ' + finalName + ' continues in the background');
    } else {  // 'error' or anything unexpected
      const verb = kind === 'add' ? 'add' : 'reindex';
      toast(status.error ? (verb + ' failed: ' + status.error) : (verb + ' failed for ' + finalName));
    }
  }, TASK_POLL_MS);
}

// terminal cleanup: forget the job, re-enable reindex, tear down the modal + both intervals
function finishJob() {
  if (state.jobKind === 'reindex') setReindexing(false);
  state.jobId = null;
  state.jobKind = null;
  hideTaskModal();
}

function cancelJob() {
  const jobId = state.jobId;
  if (!jobId) return;
  const kind = state.jobKind;
  const name = state.jobName || 'the project';
  if (kind === 'reindex') setReindexing(false);
  state.jobId = null;    // stop the poll loop immediately (its guard checks state.jobId)
  state.jobKind = null;
  hideTaskModal();       // close optimistically — don't wait for the cancel response
  toast(kind === 'add'
      ? 'cancelling — ' + name + ' will not be added (a scan in progress may finish in the background)'
      : 'reindex of ' + name + ' continues in the background');
  api('/api/project/cancel?job=' + encodeURIComponent(jobId), 'POST')
      .catch(() => { /* best-effort; UI is already freed */ });
}

// ---------------------------------------------------------------- wiring

function debounce(fn, ms) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}

const refetchGalaxy = debounce(() => {
  if (state.view && state.view.type === 'galaxy') loadView(state.view, 'replace');
}, 300);

function wireControls() {
  $('project').addEventListener('change', (e) => {
    state.project = e.target.value;
    loadOverview();
  });
  $('tab-overview').addEventListener('click', loadOverview);
  $('tab-galaxy').addEventListener('click', loadGalaxy);
  $('dim-3d').addEventListener('click', () => setRenderMode('3d'));
  $('dim-2d').addEventListener('click', () => setRenderMode('2d'));
  $('nav-up').addEventListener('click', goUp);
  $('release-pins').addEventListener('click', releasePins);
  $('reset-view').addEventListener('click', resetView);
  $('details-close').addEventListener('click', hideDetails);

  $('mcp-pill').addEventListener('click', copyMcpEndpoint);
  $('reindex-project').addEventListener('click', reindexActive);
  $('remove-project').addEventListener('click', removeActive);
  $('add-project').addEventListener('click', () => openBrowse(null));
  $('browse-add').addEventListener('click', addCurrentFolder);
  $('browse-cancel').addEventListener('click', closeBrowse);
  $('browse-overlay').addEventListener('click', (e) => {
    if (e.target === $('browse-overlay')) closeBrowse();
  });
  $('scan-cancel').addEventListener('click', cancelJob);

  $('zoom-in').addEventListener('click', zoomIn);
  $('zoom-out').addEventListener('click', zoomOut);
  $('zoom-fit').addEventListener('click', zoomFit);

  const helpOverlay = $('help-overlay');
  $('help-toggle').addEventListener('click', () => helpOverlay.classList.toggle('hidden'));
  $('help-close').addEventListener('click', () => helpOverlay.classList.add('hidden'));
  helpOverlay.addEventListener('click', (e) => {
    if (e.target === helpOverlay) helpOverlay.classList.add('hidden');
  });

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!$('scan-overlay').classList.contains('hidden')) {
      return;  // loading modal is non-dismissable — ignore Escape while indexing runs
    }
    if (!$('browse-overlay').classList.contains('hidden')) {
      closeBrowse();
    } else if (!helpOverlay.classList.contains('hidden')) {
      helpOverlay.classList.add('hidden');
    } else if (document.activeElement === $('search')) {
      $('search').blur();
    } else {
      goUp();
    }
  });

  $('search').addEventListener('input', (e) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => runSearch(e.target.value), 250);
  });

  $('ego-depth').addEventListener('input', (e) => {
    state.egoDepth = Number(e.target.value);
    $('ego-depth-val').textContent = state.egoDepth;
    if (state.view && state.view.type === 'ego') loadView(state.view, 'replace');
  });

  $('galaxy-cap').addEventListener('input', (e) => {
    state.galaxyCap = Number(e.target.value);
    $('galaxy-cap-val').textContent = state.galaxyCap;
    refetchGalaxy();
  });
  $('galaxy-conf').addEventListener('input', (e) => {
    state.galaxyConf = Number(e.target.value);
    $('galaxy-conf-val').textContent = state.galaxyConf.toFixed(2);
    refetchGalaxy();
  });
  $('galaxy-lang').addEventListener('input', (e) => {
    state.galaxyLang = e.target.value.trim();
    refetchGalaxy();
  });

  window.addEventListener('resize', () => {
    graph.width(window.innerWidth).height(window.innerHeight);
  });

  // One persistent listener on the stable #graph container (makeRenderer only swaps an inner div,
  // so this survives 2D/3D toggles). Tracks the cursor and repositions the tooltip while hovering.
  $('graph').addEventListener('mousemove', (e) => {
    state.mouseX = e.clientX;
    state.mouseY = e.clientY;
    if (state.hoverNode) renderTooltip();
  });
}

// ---------------------------------------------------------------- boot

async function boot() {
  renderLegend();
  renderHelp();
  wireControls();
  makeRenderer(state.renderMode);
  $('hint').textContent = HINTS[state.renderMode];

  api('/api/server')
      .then(applyServerInfo)
      .catch((e) => toast('failed to load server info: ' + e.message));

  let projects;
  try {
    projects = await api('/api/projects');
  } catch (e) {
    toast('failed to load projects: ' + e.message);
    setStatus('no projects');
    return;
  }
  if (!projects.length) {
    toast('no projects available');
    setStatus('no projects');
    return;
  }
  state.project = projects[0].name;
  populateProjects(projects);
  loadOverview();
}

boot();
