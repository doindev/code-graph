# Human approval channels

DBA agent requests use one server-side approval queue across browser and desktop presentation.
The broker never executes SQL or changes a profile itself. Exact target revisions, request
hashes, acknowledgement, single-use decisions, and audit-before-execution remain enforced by
the existing request handlers. Persistent approval is available only for eligible verified reads.

## Configuration

Add `--dba-approval-mode MODE` alongside `--dba`. It is a startup-only setting.

| Mode | Behavior |
|---|---|
| `auto` (default) | Use DBA browser presence when the UI is enabled; otherwise use an available desktop. Without either, expose only safe restricted read tools. |
| `browser` | Require `--viz`. Route to a visible tab or launch the exact approval in the default browser. |
| `desktop` | Require a working interactive AWT desktop at startup. Use the native consent curtain, with browser handoff for complex reviews. |
| `none` | Disable approval-dependent MCP tools, including management and live SQL. Existing authenticated restricted reads/catalog/plans remain capped at 100 rows. |

Examples (append to the normal HTTP or stdio launch):

```text
--dba --viz 8137 --viz-admin --dba-approval-mode auto
--dba --dba-approval-mode desktop
--dba --dba-approval-mode none
```

For a service, container or SSH session, explicitly select `none` or launch Java with
`-Djava.awt.headless=true` and omit `--viz`. A local browser on another machine is not a
supported remote approval channel. Agent authentication and least-privilege database accounts
are still required. A disappeared desktop produces structured `approval_unavailable` errors
for new approval-dependent calls; notification failure never authorizes a request.

## Browser routing

Each DBA document registers a fresh tab UUID, one authenticated SSE stream, focus/visibility
events, and ten-second heartbeats while visible. Presence expires after 25 seconds. The most
recently focused eligible visible tab is offered the request; other tabs receive only a passive
count. SSE contains IDs/counts, not SQL, configuration, credentials, or capability tokens.
Bounded queues coalesce notifications, and reconnect/polling resynchronizes current state.

A reviewer claims a renewable 30-second lease. Renewals occur every ten seconds. Another
tab cannot decide with that lease, and changing the proposal invalidates its revision.
Requests are presented FIFO; a browser dialog already open for another task is not displaced.
The browser Close button defers review without approval. Native Escape/window close rejects.

When no tab is active, the default browser opens `/dba#approval=<request UUID>`.
Automatic launches are rate-limited and attempted once per request. In auto mode a failed
launch falls back to the native prompt if available. Browser policies can report a launch
successful even when no page opens; the request remains unapproved and expires normally.

## Native consent curtain

The JDK-only Swing renderer selects the monitor under the pointer, falling back to the primary
monitor. A charcoal backdrop covers that monitor's full bounds, with an opaque centered,
resizable card. It uses per-pixel translucency, uniform-window translucency, or an opaque
fallback according to platform capabilities. It does not use exclusive full-screen mode.
See [Java translucency capabilities](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/GraphicsDevice.WindowTranslucency.html).

The request viewer has dark horizontal/vertical scrollbars, including hover and drag states.
Use **Ctrl+Plus** / **Ctrl+Minus** to enlarge/shrink only the request text, and **Ctrl+0** to
reset it. Ctrl+Equals and numeric-keypad Plus/Minus/0 also work. Text starts at 14 pt and is
bounded to 10–28 pt; each new prompt starts at the default. The request and approval buttons
are unchanged by zoom. Drag the window edges/corners to resize, or use **Maximize / Restore**
to fill the current monitor's usable area without covering its taskbar/dock or other monitors.
The approval window uses a dark, application-drawn title bar (JDK-only; no OS theme changes).
Its compact icon-only control shows a square for Maximize and overlapping squares for Restore,
with tooltips and accessible names. Drag the title bar to move the restored window, or double-click
it to maximize/restore. The close icon, Alt+F4, and Escape still deny the request. All eight
edges/corners support resizing while restored; resizing is disabled while maximized.

The card contains escaped read-only HTML, native keyboard-accessible decisions, an expiry
countdown, and risk warnings. Clicking an explicit approval button confirms the displayed
request and risks; neither browser nor desktop prompts require a separate acknowledgement
checkbox. Opening or dismissing a prompt never approves it. Mutations cannot receive persistent permission.
Connection creation/update, credentials, drivers, and draft test/save workflows must use
**Open detailed review in browser**; native approval is disabled for those requests.

The window raises once (a maximum 600 ms topmost pulse where supported), is never permanently
always-on-top, and immediately releases topmost status and hides its backdrop when focus
leaves the card. The approval dialog is independent of the dimmer, so hiding the backdrop
does not hide the prompt; it remains available to switch back to. Returning to the prompt
restores the dimmer. Other monitors and deliberately selected applications remain usable.
Backdrop clicks do nothing; Escape, Alt+F4, and window close reject. All windows/timers are
disposed on completion, rejection, expiry, or shutdown.

This is application consent, **not Windows UAC**, OS elevation, identity verification, or a
protected secure desktop. Other local programs can imitate or interact with it. Actual UAC
uses different OS protections; see [Microsoft's UAC architecture](https://learn.microsoft.com/en-us/windows/security/application-security/application-control/user-account-control/how-it-works).

## Detailed review without the DBA UI

A complex native request creates an ephemeral listener on `127.0.0.1` and a random port.
It serves only the review assets, the selected request, and allowlisted editor/test operations.
It does not expose the graph, workspace, arbitrary SQL, connection listing, agent management,
or policy administration. Driver/editor mutations require the active request's review lease.
Jobs remain session-owned and use the existing shared DBA resource limits.

A single-use random fragment token is exchanged for an HttpOnly/SameSite=Strict scoped cookie;
the fragment is immediately removed from history. Reload reuses the cookie. If browser launch
fails, a native message shows a landing URL and ten-digit short-lived pairing code. Pairing
accepts at most five failed attempts per minute and never places the code in a URL.
Host/Origin checks, CSRF, token expiry, request ownership and lease validation remain mandatory.

After completion a short grace period lets the page display its outcome. Scoped sessions are
then revoked and the listener closes after ten idle seconds (normally within about 22 seconds
of completion). Closing the application immediately disposes it. Pending reviews expire with
the existing five-minute request deadline.

There are at most 32 retained approval requests across SQL and administration queues, 24
browser presence entries, and 16 coalesced events per stream. Completed request status is
retained for one hour. Pending credentials remain write-only and are cleared on rejection,
cancellation, expiry or terminal completion. HTML, SSE and audit records do not contain vault
references, secret-property values, private-key paths, resolved secrets or pairing capabilities.

## Browser API additions

All paths below start with `/api/dba` and require the appropriate authenticated browser session.
Mutations require `X-Dba-CSRF`; lease operations also identify the tab using `X-Dba-Tab`.

| Endpoint | Contract |
|---|---|
| `POST /approvals/presence` | `tabId`, `visible`, `focused`, `polling`; returns offered IDs and pending count |
| `GET /approvals/events?tabId=UUID` | One bounded SSE stream per registered tab |
| `POST /approvals/{id}/claim` | Returns opaque `lease`, `reviewRevision`, `leaseExpiresAt` |
| `POST /approvals/{id}/renew` | Renew lease using `X-Dba-Review` |
| `POST /approvals/{id}/release` | Release the matching lease without approving |
| `POST /approvals/{id}` | Existing decision action and acknowledgement, now bound to the lease |
| `GET/PUT /approvals/{id}/draft` | Review/edit a proposal under its lease; an edit requires a fresh claim before deciding |
| `POST /approvals/{id}/test-draft` | Test an unsaved edited proposal with retained write-only secrets; returns a session-owned job |

The temporary site alone exposes `POST /review-bootstrap` for a one-use token or pairing code.
MCP request-status objects add `approvalChannel`, `reviewAvailable`, and `deliveryStatus`.
No agent tool can claim a review lease or submit an approval decision.

## Reproducible validation and remaining release gates

```powershell
mvn "-Djava.awt.headless=true" test
$env:NODE_PATH = "<directory containing Playwright>"
$env:DBA_BROWSER_SUITE = "approvals"
./code-graph-dba/test-browser.ps1
$env:DBA_BROWSER_SUITE = "approval-review"
./code-graph-dba/test-browser.ps1
Remove-Item Env:DBA_BROWSER_SUITE
./code-graph-dba/test-browser.ps1

# Intentionally shows synthetic native prompts. No user databases are used.
$env:DBA_DESKTOP_TEST = "true"
mvn -pl code-graph-dba -am "-Djava.awt.headless=false" "-Dtest=DesktopApprovalsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
Remove-Item Env:DBA_DESKTOP_TEST
```

Automated coverage includes routing/leases/revisions, expiry, headless modes, browser-launch
failure, native denial and complex-review restrictions, HTML escaping, isolated review APIs,
single-use pairing, CSRF/origin/ownership failures, secret removal, audit failure, and listener
cleanup. Browser coverage includes competing tabs, SSE routing, persistent reads, proposal
editing/testing/review/apply, rejection and review-page reload. Native tests explicitly check
geometry, checkbox-free explicit confirmation, focus-loss/return without hiding the prompt,
bounded initial foreground raising, Escape, complex-review restrictions and repeated window cleanup.

Real Windows Swing prompts have been exercised on this workstation. Latest measurements:
first prompt approximately 412 ms; subsequent prompts 105–144 ms. Six repeated prompts
allocated roughly 7.3 MB in that run; this is **not retained heap or process memory** and not
a cross-platform performance guarantee. No displayable prompt windows remained afterward.

**Still requires external interactive validation:** real macOS and Linux desktops; screen-reader
usability; mixed-DPI/multi-monitor hotplug/taskbar variations; services, SSH and container display
failure combinations; sustained retained-heap/native-memory profiling. Simulated routing and
geometry tests do not certify those environments. Do not mark the cross-platform release gate
complete until those checks have been performed.
