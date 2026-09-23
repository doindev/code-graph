# Startup-only automatic local-agent authorization

`--yolo` is deliberately off by default. It replaces human approval and missing
agent grants for validated local MCP calls with exact, automatic one-time
authorization. It creates no saved policy and grants no additional shell or
Docker tools. Any local process that can reach MCP can exercise this authority.
Use a least-privilege database account and disposable environments where possible.

## Launch

```text
cgraph --yolo
cgraph --no-ui --yolo
```

Installed defaults otherwise remain MCP 3000, admin/DBA UI 8137, hybrid graph
storage and a 1 GiB graph/cache allowance. Raw Java entry points require both
`--dba --yolo`; the flag works for HTTP and stdio. It does not enable DBA alone.
`--no-dba --yolo`, `--yolo=false`, `--yolo=true` and `--yolo false` fail.
The case-sensitive flag takes no value.

YOLO overrides a valid explicit `--dba-approval-mode` with a warning. It neither
needs nor probes an interactive approval desktop, and does not launch approval
windows or browser reviews. No environment variable, REST call, saved profile,
or live setting enables it. Restart without the flag to restore normal approval
behavior; previously saved policies remain unchanged.

## Targets, limits and ownership

Project operations require the exact `bindingId`, without target overrides.
Standalone SQL/schema execution requires `connectionId`, exact `connectionName`,
and an explicit `database`. Supply an explicit `schema` as well except for
MySQL/MariaDB (schema equals database) and SQLite/ClickHouse, which have no separate
schema requirement in this interface. Unsupported targeting still fails rather
than falling back. Connection-profile inspection, tests and administration target
the saved profile by its UUID/name, or an applicable binding.

Session validation, revoked named identities, target revisions, fingerprints,
database-account privileges, unsupported-operation checks, SQL/parameter bounds,
100-row/1-MiB agent results, concurrency and timeout limits remain enforced.
Read-only tools do not become unrestricted SQL tools. Use `dba_request_live_sql`
for writes. Native statements/routines may affect other objects allowed by the
database account; a selected schema is not database-enforced confinement.

Migration requests are fully initialized before authorization. Their retained
plans remain bound to the requesting MCP session, exact target and fingerprint.
YOLO does not make stale, consumed or foreign-session plans reusable.
Browser-editor pairing and browser-user destructive confirmations are unchanged.

## Connection setup

Create/update requests require exactly one of:

- `testBeforeSave: true`: test the unchanged draft and save only on success.
- `saveUntested: true`: skip connectivity testing, not required fields, driver
  validation, or vault access.

If installation is needed, explicitly supply `driverInstall` with groupId,
artifactId and a pinned version. `latest` is not accepted in automatic setup.
Installation, testing and saving use a bounded asynchronous job. No licence or
EULA is accepted automatically. Missing vendor entitlements remain the user's
responsibility; unavailable licensed artifacts cannot be substituted.

`confirmDriverEffects: true` is additionally required for tests whose driver
settings may initialize an embedded database or run initialization commands.
Known credentials remain write-only, but MCP clients may retain their submitted
arguments in task history. Locked/unavailable OS vaults have no plaintext fallback.
Browser test receipts cannot be supplied to authorize an agent proposal.

An optional binding failure after a successful profile save is reported as partial
success with the saved connection identity. Inspect it before proposing a separate
binding; do not replay profile creation.

## Status, audit and recovery

MCP returns ordinary queued/running/terminal operation states. Automatic-authorization
metadata remains in the browser/reviewer records and audit; it is omitted from MCP
operation responses. Submission is not completion. Use returned `operationId`
for status/cancellation, and release completed job results when no longer needed.
Cancellation acceptance does not guarantee rollback.

Automatic authorization is audited before execution without credentials or full
request payloads. Audit failure fails closed. Connection loss, cancellation during
commit, or audit failure after a side effect can leave a partial/uncertain outcome.
Never retry writes automatically; reconcile actual state first.

There are 32 shared active approval/request slots. Each queue retains at most 64
recent status records (also subject to one-hour expiry), independently of active
admission. Completed JDBC jobs retain their existing separate allowance and must
still be released. Each queue keeps up to 2,048 bounded duplicate-protection
records across live MCP sessions. Exhaustion rejects new requests instead of
discarding replay protection. Result expiry never authorizes re-execution.
Duplicate IDs with changed content are rejected; expire/end a session only after
reconciling its outcomes, not as a way to retry uncertain work.

The DBA toolbar and settings show a persistent warning. Settings, workspace
context, capabilities and permission responses expose effective startup authority.
Requests create neither approval popups nor persistent permissions.

## Reproducible validation

```powershell
$env:YOLO_TEST_MAVEN = "true" # opt-in verified H2 Maven download test
mvn -pl code-graph-mcp-http -am test -Dtest=YoloTest,YoloMcpHttpTest,CgraphMainTest,DbaToolSchemaTest -Dsurefire.failIfNoSpecifiedTests=false -Djava.awt.headless=true
powershell -File code-graph-dba/test-reusable-vendors.ps1 -Yolo
powershell -File code-graph-dba/test-browser.ps1 -NodeModules PATH_TO_PLAYWRIGHT_NODE_MODULES
mvn verify
```

The vendor harness uses labelled disposable PostgreSQL/MySQL/MariaDB fixtures,
removes its containers/volumes and newly pulled unused images, and preserves
pre-existing resources. H2 is covered by the unit/integration suite.
