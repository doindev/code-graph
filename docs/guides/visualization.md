# 3D Visualization & Multi-Project Workspaces

## Workspaces: many projects, one server

Pass multiple roots (or a workspace file) to either server and each indexes as an independent
project — blast scores, drift and dead code never bleed across repos:

```
# repeated roots (names = directory names)
java ... io.doindev.codegraph.mcp.Main --root C:/repos/api --root C:/repos/web --viz 8137

# or a workspace file with explicit names
java ... io.doindev.codegraph.mcp.Main --workspace team.code-graph.json --viz 8137
```

```jsonc
// team.code-graph.json
{ "projects": [
    { "name": "api", "root": "C:/repos/acme-api" },
    { "name": "web", "root": "C:/repos/acme-web" }
] }
```

Every MCP tool gains an optional `project` parameter (the schema lists the known names; the
first project is the default), and `list_projects` returns the roster. `reindex` is
per-project. The same flags work on the team server (`HttpMain`).

## The 3D view

`--viz PORT` serves a self-contained browser UI (WebGL, vendored — **no CDN, nothing leaves
the machine**; the stdio server binds it to localhost). Open `http://localhost:PORT/`.

Three ways to look at a codebase:

- **Overview (drill-down)** — start at the module level (node size = file count, edge width =
  dependency count). Double-click a module to expand its files, a file to expand its symbols.
  Breadcrumbs navigate back up.
- **Ego networks** — search any symbol (or double-click one) to render its N-hop neighborhood:
  callers, callees, and everything the blast radius touches, depth-adjustable. This is
  `get_impact_radius`, but visible.
- **Galaxy** — the capped whole-graph view: top-degree symbols first, with cap, language and
  minimum-confidence filters.

Nodes drag in 3D and stay where you pin them (release-pins button resets). Clicking a node
opens the details panel: signature, location, caller/callee counts, and the itemized blast
score with factor bars. A project switcher swaps repos instantly.

## API (same origin, read-only)

The UI consumes a small JSON API you can also script against:
`/api/projects`, `/api/p/{name}/overview`, `/module?name=`, `/file?path=`,
`/ego?id=&depth=`, `/galaxy?cap=&lang=&minConfidence=`, `/node?id=`, `/search?q=`.
All payloads are capped and marked `truncated` when cut — the browser never receives an
unbounded graph.

## Shared workspace styling and Settings

Graph and DBA share a bundled dark theme, green accents, compact 36-pixel toolbar,
brand/navigation links, buttons, and keyboard focus styling. Their content remains independent:
the graph page does not load the database editor, and styling works with DBA disabled.

The graph toolbar's rightmost **Settings** gear opens a searchable, resizable two-pane dialog.
Its icon-free tree groups **Graph memory (RAM)**, **MCP connection**, and
**Project idle timeout (TTL)** under **Server**. Search matches category names and field
names/descriptions; the inline clear button restores the unfiltered tree. Drag the divider or
focus it and use Left/Right (Home/End for bounds). Its position lasts for this page.

Edits remain drafts while navigating/searching. **Apply** is enabled only for meaningful
editable changes, validates them, submits only changed fields, and closes after success.
**Cancel** or Escape discards edits. An interrupted save reloads actual values for review,
without automatically retrying the write. Read-only servers still show settings and MCP details.
The MCP endpoint can be copied; changing listeners or backend mode requires a restart.

With no projects, the welcome view offers **Add project**, **Connect an agent**, and
**Open DBA** (when enabled). Graph-specific controls remain disabled. A read-only server
explains how to onboard through MCP or enable UI administration; visiting the page never
onboards a directory automatically.

## Idle timeout administration

With UI administration enabled, **Settings → Server → Project idle timeout (TTL)** edits the server-wide inactivity policy (one
hour by default, also configurable via `--project-ttl`). The setting is available when the
workspace is empty. Saving changes the policy for current and future projects for this server
session only. Shorter values may cause immediate expiry on the next five-second check.

The selected project's countdown shows remaining idle time; hover for its last activity.
Queries and active interaction, including rotate/zoom/drag, renew its timer. Read-only UI
users also count as active users. The UI sends throttled interaction signals, not an automatic
keep-alive heartbeat. Passive roster refreshes and idle tabs cannot prevent expiry.

After expiry the graph is cleared, the roster is refreshed, and project controls are disabled
until a project is selected or onboarded. An expired project is never automatically reloaded.

`GET /api/server` includes `projectTtlSeconds`. Admin-only `PUT /api/settings` accepts
`{"projectTtl":"30m"}`, `{"graphMemory":"1536m"}`, or both fields in one request.
Both fields are validated before publishing the timeout; a rejected budget leaves TTL unchanged.
Project listings include passive lifetime metadata. A browser can send
`POST /api/p/{name}/activity` for actual interaction; `X-Project-Instance` binds a request to the
instance ID from the roster so stale requests cannot renew a re-added project with the same name.

## Security

Graph queries are read-only; no source text is served (same SnippetPolicy invariant as the
tools). HTTP and stdio UI listeners are loopback-only. Do not expose them through a reverse
proxy or tunnel. Administration requires the existing UI-admin setting; opening Settings
does not grant additional authority.

## UI regression checks

With Node.js, Playwright and Edge available:

~~~
node --test code-graph-viz/settings-test.cjs
node code-graph-viz/browser-settings.cjs
mvn -pl code-graph-viz -am test
~~~

The browser test serves real assets from a disposable loopback fixture, checks read-only,
empty and active workspaces, draft/search/resize behavior and failed-save recovery, and
writes screenshots to `code-graph-viz/target/ui-screenshots`. It never calls the running
user server. Shared DBA toolbar regressions use `code-graph-dba/test-browser.ps1`
with `DBA_BROWSER_SUITE=workspace-toolbar`. Set `NODE_PATH` if Playwright is installed outside
the repository.
