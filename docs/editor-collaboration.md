# Agent-requested DBA browser collaboration

An agent can request a workspace using `dba_request_editor_access` with a unique
`requestId` and a short `purpose`. The user no longer needs to generate a pairing
code first. This requires the DBA UI to be enabled; it does not launch a browser
or request consent merely because an MCP client connects.

## Selecting a workspace

The Java consent card identifies the agent and purpose, states the limited scope,
and offers **Deny**, **Use existing DBA tab**, and **Open new DBA workspace**.
These choices apply independently of the SQL approval mode. In particular,
`--yolo` does not approve editor access or choose a browser tab.

- One responsive, unpaired tab: choosing existing authorizes that tab, which
  synchronizes its unsaved workspace before completing the connection.
- Multiple eligible tabs: they show **Use this /dba instance?**. The first
  acceptance wins atomically; other prompts disappear without changing their work.
- No eligible tab: open or return to `/dba` and accept its prompt, or choose new
  workspace in the native card. Existing tabs are never closed.
- A new workspace opens in the default browser with a single-use fragment handoff.
  The page removes the fragment immediately and exchanges it only through an
  authenticated, CSRF-protected request. The token is not exposed to the agent,
  audit logs, or browser storage.
- If browser launch fails, the request tells the agent to ask the user to open
  `/dba` manually. If no interactive Java desktop is available, responsive DBA tabs
  ask for explicit consent directly. With neither UI nor desktop, no editor access
  is granted.

Browsers may refuse programmatic focus. The page title and on-page prompt identify
the workspace; the user may need to bring its browser window forward manually.
This is application consent, not a protected OS security prompt.

## Agent lifecycle and scope

Poll `dba_request_status` with returned `approvalId` in the **same logical MCP
session**. `awaiting_approval` and `awaiting_browser` do not permit editing.
Only `paired` permits the existing document tools. Denial, cancellation and expiry
are terminal; do not repeatedly prompt. `dba_cancel_request` cancels pending consent.
Requests expire after five minutes, share the existing 32-pending-request bound,
and retain bounded terminal receipts for up to an hour after request expiration.

One MCP session pairs with one tab; an occupied tab cannot be silently replaced.
The agent may inspect Script documents and create/edit unsaved drafts with exact
revision checks. Pairing does not execute SQL, save files, read credentials, grant
database access, or implicitly change an existing document's connection.
The toolbar's **Agent connected · Disconnect** control revokes access immediately.
Settings → Pair editor remains available as a short-lived-code compatibility path.

Each tab registers a workspace UUID and a per-document UUID; sessionStorage keeps
only the workspace identifier, never editor content or a consent token. Tabs
sharing cookies have isolated Script workspaces. A duplicate of a live tab receives
a new workspace instead of taking over the original. Refresh restores the original
workspace when the prior page's departure is observed. A lost departure message
can conservatively result in a fresh workspace rather than takeover.

Ten-second heartbeats and bounded editor event reconnects detect responsive tabs
within 25 seconds. Edits fail while a tab is disconnected; an existing pairing may
resume within the two-minute reconnect grace. Longer disconnection, MCP expiry,
termination, server restart, browser session expiry or explicit disconnect revokes
access. Browser sessions remain limited to one hour. Workspace state is bounded to
eight workspaces per browser session, 24 globally, and 16 MiB aggregate per browser
session; inactive workspace snapshots remain until session expiry. New admissions
fail explicitly at the limit rather than evicting unsaved text.

## Browser API

All operations retain loopback/Host/Origin validation, session cookies, and CSRF
checks for writes. Standard SQL approvals remain separate from editor consent.

| Operation | Purpose |
|---|---|
| `POST /api/dba/editor/register` | Register `{workspaceId, documentId}` and obtain the authoritative workspace UUID |
| `POST /api/dba/editor/presence` | Heartbeat and bounded consent offers/status |
| `POST /api/dba/editor/claim` | Accept an offered `approvalId`, or exchange a new-workspace `ticket` once |
| `POST /api/dba/editor/reject` | Deny an offer visible to this workspace |
| `POST /api/dba/editor/leave` | Mark document disconnected without discarding its saved workspace |
| `GET /api/dba/editor/events` | Bounded editor events plus consent offers, reconnecting every three seconds |
| `DELETE /api/dba/editor/pair` | Revoke this workspace's pairing |

Workspace and editor operations use `X-Dba-Workspace` and `X-Dba-Document` headers.
EventSource sends these non-secret UUIDs as `workspaceId`/`documentId` query fields;
they are not sufficient without the session cookie. Legacy header-free callers
remain compatible with one workspace, but fail closed when multiple tabs exist.
Consent is audited before pairing; audit failure prevents access.

## Reproducible validation

```powershell
mvn -pl code-graph-dba -am test "-Dtest=EditorRequestsTest,EditorAccessHttpTest,EditorPairingsTest,ApprovalBrokerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Djava.awt.headless=true"
mvn -pl code-graph-dba dependency:copy-dependencies "-DincludeScope=test" "-DoutputDirectory=target/test-lib"
$env:DBA_BROWSER_SUITE='editor-pairing'
./code-graph-dba/test-browser.ps1 -NodeModules <directory-containing-playwright>
```

Native tests are opt-in (`DBA_DESKTOP_TEST=true`, `DesktopApprovalsTest`) and use
synthetic requests, not live database writes. Real macOS/Linux desktop behavior,
screen readers and foreground focus restrictions require platform testing;
automated routing and browser tests do not certify those environments.

### Validation on 2026-09-21

- Full Java 25 Maven reactor: 947 tests, zero failures/errors; 69 conditional skips.
- Windows interactive Swing suite: all 10 tests passed separately, including both
  editor workspace choices, dismissal, dark controls, zoom and cleanup.
- All 23 isolated DBA browser suites passed. New coverage includes simultaneous
  tabs, first-acceptance ownership, isolated drafts, refresh recovery, duplicate-tab
  protection, denial, disconnect, SSE delivery and the legacy code flow.
- HTTP tests verified CSRF/session/document ownership, replay rejection, atomic
  unload recovery and explicit consent with normal and YOLO authorization modes.
- JavaScript syntax, skill validation, and all 16 skill-installer tests passed.
- Testcontainers removed their own fixtures and reused existing images; no newly
  pulled images were identified. Existing databases and images were preserved.

The broader browser run exposed and fixed an unload race: final workspace state
and document departure now share one keepalive request instead of waiting for
JavaScript to continue after the page has gone away.
