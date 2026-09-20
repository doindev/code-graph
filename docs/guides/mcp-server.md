# MCP Server

code-graph serves its code-intelligence tools over the Model Context Protocol so any MCP-capable
agent (Claude Code, IDE assistants) can query blast radii instead of reading files. The stdio
transport and loopback-only streamable HTTP are both local modes; HTTP ships in
`code-graph-mcp-http` and binds only to `127.0.0.1`. Local DBA clients need no token or agent
registration. Token-free clients share the built-in local identity, but database reads and
changes still require reviewed permissions/approvals. Optional named tokens remain available
for separate identities. See [DBA access](../dba.md).

The explicit startup-only [`--yolo` mode](../yolo.md) automatically authorizes local
MCP DBA operations, including writes and administration, without creating policies.
It still requires live sessions and exact targets, preserves restricted read-tool
semantics and limits, and returns automatic-authorization metadata. Connection
setup must explicitly choose `testBeforeSave` or `saveUntested`; driver installation
must be explicitly requested with a pinned version. Do not infer permission for
an agent task merely from the server running in this mode.

## Registering with Claude Code

```
claude mcp add code-graph -- java --enable-native-access=ALL-UNNAMED \
    -cp <code-graph-mcp jar + dependencies> io.doindev.codegraph.mcp.Main --root C:/repos/acme
```

When roots are supplied, startup indexes them and starts their file watchers. Without roots
or `CODE_GRAPH_ROOT`, startup leaves the workspace empty; onboard with `add_project` below.
Progress goes to stderr; stdout belongs to the protocol.

## Tool reference

### Efficient evidence and freshness

Use `search_symbols` once to identify targets, then `get_symbol_context` for
1–20 known IDs when declarations plus optional references/callers/callees/
implementations would answer the question together. Sections are opt-in; the
default is declarations and coverage, not every relationship. One generation,
one byte/result allowance and a 100,000-edge inspection budget cover the entire
request. Follow unchanged signed cursors; work-limit counts are lower bounds.

`find_implementations` accepts methods and types. Its `OVERRIDES` evidence is
declaration-based, not a claim of complete runtime dispatch.
[Supported languages and exclusions](../method-implementation-coverage.md) matter
more than an empty result.

After edits, use `index_status` with up to 20 relative file paths and expected
SHA-256 hashes or deletions, optionally `waitMillis: 5000`. Hash the UTF-8 decoded
text re-encoded as UTF-8. A generation predicate additionally requires the returned
`indexInstanceId`. Timeout is not freshness success. Waits do not renew TTL or
hold the graph read lock; stale instances are rejected. Generation guards can
bind subsequent navigation to the observed publication.

DBA job status supports `afterRevision` and `waitMillis` up to 5,000 ms, bounded
to four waiters and rechecking ownership. Accepted cancellation is not completion.
For approvals, use the returned `approvalId` to poll/cancel; the caller's
idempotency `requestId` is not the execution `jobId`. Compatibility aliases remain.

Tool definitions vary by runtime mode: no DBA means no DBA definitions; reduced
headless mode advertises only its existing safe subset. Enabling a capability or
reading its description never grants permission. See the
[generated canonical contracts](../mcp-tools-generated.md) and
[acceptance results](../mcp-efficiency-coverage-delivery.md).

### Dependency precision and coverage

Java call binding now uses explicit receivers, lexical types, imports, declared inheritance,
and bounded overload analysis. Unresolved external receivers do not fall back to unrelated
same-file methods. Full, incremental and hybrid indexing share this resolver.

Use search_symbols to obtain an ID, then find_references for occurrence spans and
resolutionEvidence (binding basis, resolved/candidate status, dispatch caveat and omitted
candidates). get_call_graph remains a compact navigation summary. Neither tool claims that
unresolved, dynamic, external-library or unindexed references are absent. A resolved virtual
call identifies its declared receiver target, not every overriding runtime implementation.

See [precision scope, validation and benchmarks](../dependency-precision.md). No new startup
flag is required. Reindex existing projects after installing the new resolver.

### Agent skill for Copilot, Claude Code, Codex, and Windsurf

The portable [code-graph skill](../../skills/code-graph/SKILL.md) teaches agents
to prefer the connected MCP server for symbol navigation, callers/dependencies,
and change impact, while retaining file reads and text search for exact contents
and unindexed files. It uses only standard `name`/`description` frontmatter and
does not configure or start an MCP server.

Copy the `skills/code-graph` folder into the target repository's skill directory:

- GitHub Copilot: `.github/skills/code-graph/SKILL.md`.
- Claude Code: `.claude/skills/code-graph/SKILL.md`.
- Codex: `.agents/skills/code-graph/SKILL.md`.
- Windsurf: `.windsurf/skills/code-graph/SKILL.md`.

Or explicitly install the canonical skill and its references (Node.js required):

```powershell
node skills/install-skill.mjs --client codex --project C:/repos/my-app --dry-run
node skills/install-skill.mjs --client codex --project C:/repos/my-app
```

For optional current-user/global installation, select all four or a subset:

```text
node skills/install-skill.mjs --client all --global --dry-run
node skills/install-skill.mjs --client codex,claude --global
```

The application installers also offer `-Skills` / `--skills` selection without requiring
Node. See [global locations and installer behavior](../installation.md#optional-global-agent-skills).
The skill is conditional guidance, not a dependency: use MCP when available and useful,
otherwise continue with ordinary tools.

Choose `all`, `none`, or selected `codex`, `copilot`, `claude`, `windsurf` clients. The helper does not
configure MCP, start the server, broaden tool permissions, or overwrite an
existing differing skill. Identical installed copies are a no-op; merge customized
copies manually. It rejects linked destination directories. Install only the
locations needed for your clients: some clients also scan another client's
directory, so multiple copies may produce duplicate discovery.

The distributed file is not automatically installed by cloning this repository.
Connect the MCP server separately using the agent's normal configuration. Skill
selection remains host-controlled; the file guides applicable tasks, not every
shell operation. See the official [Copilot skill guide](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/add-skills),
[Claude Code skill guide](https://code.claude.com/docs/en/skills),
[Codex skill guide](https://learn.chatgpt.com/docs/build-skills), and
[Windsurf skill guide](https://docs.windsurf.com/windsurf/cascade/skills).
Discovery depends on client version, surface and workspace trust. Installing a
skill in a cloud checkout does not make your machine's loopback MCP reachable;
this helper never changes listener exposure.

### Available tools

| Tool | Purpose |
|---|---|
| `search_symbols` | Find symbols by name — call this FIRST to obtain valid symbol IDs |
| `get_symbol` | Signature, location, dependency counts for one ID |
| `get_file_outline` | Cursor-paged declarations and spans in an explicit relative file |
| `resolve_symbol_at_position` | Indexed reference-expression candidates or containing declarations; 1-based position and explicit precision/confidence |
| `find_references` | Incoming indexed references, calls, reads, writes and imports; occurrence ranges where available, otherwise enclosing-symbol locations |
| `analyze_change` | Current-index evidence for explicit files/symbols or proposed database names; optional working-tree or exact-revision Git selection records resolved commits while graph evidence remains tied to the current index |
| `find_affected_tests` | Candidate tests with dependency paths/confidence; recommendations only, never executes test commands |
| `find_implementations` | Direct indexed type EXTENDS/IMPLEMENTS relationships; method dispatch is not inferred |
| `get_impact_radius` | Direct + transitive dependents/dependencies, compressed to counts and spread |
| `get_call_graph` | Callers (up) / callees (down) as compact index-pair adjacency |
| `get_blast_score` | Auditable 0–100 change-risk score, factor by factor |
| `find_dead_code` | Unreferenced symbols with confidence tiers and caveats |
| `find_code_smells` | Structural code smells with measured evidence |
| `compare_architectural_drift` | Blueprint violations and module cycles |
| `index_status` | Freshness: generation, counts, pending changes |
| `reindex` | Async re-index; never touches source |
| `list_projects` | Available project names and the default project |
| `get_workspace_context` | Passive, cursor-paged project/freshness summary; with DBA enabled, authenticated connection/environment/role summaries |
| `add_project` | Index and watch a server-side directory; returns when ready |
| `remove_project` | Drop a project's graph and stop its watcher; source is untouched |

### Typed contracts and discovery

Tools advertise input schemas, an output envelope schema, and advisory MCP
annotations. Annotations do **not** authorize a call. Unknown/mutating tools
retain conservative hints; live read jobs are not marked idempotent because
retrying can submit another job or approval request.

The original JSON text payload remains unchanged. Clients supporting structured
results also receive `structuredContent: {data, meta}`. `data` is that same
payload (including arrays from legacy tools); `meta.contractVersion` is `1`.
`invocationState: returned` only means the tool returned: it does not mean a job
completed or permission was granted. Inspect `data.state` and authorization.
Known target, generation, completeness, continuation and authorization fields
are projected into metadata; absent evidence is not invented. The legacy payload
cap remains in force; text plus structured encoding adds protocol bytes and
temporary serialization memory, not additional retained graph generations.

With DBA enabled:

| Tool | Behavior |
|---|---|
| `dba_list_templates` | Public recipes/property descriptors; `templateId`, `limit` (1–50, default 10), `cursor`; never downloads or connects |
| `dba_get_capabilities` | Exact binding or connection ID/name; cached description by default; `live: true` submits bounded JDBC metadata or native server-version observation requiring scoped permission |
| `dba_request_native_command` | Exact native MongoDB/Redis binding or UUID/name/database, optional Mongo collection, structured command, requestId and purpose; one-time approval or startup YOLO, asynchronous bounded results; unavailable in reduced approval-disabled mode |
| `dba_refresh_catalog` | Requests/coalesces an authorized binding scan under existing single-scanner limits; no database writes |
| `dba_scan_status` | Passive status; optional `afterGeneration` and `waitMillis` (0–5000), at most four concurrent waiters |
| `dba_capture_schema` | Asynchronous, capped catalog observation for an exact binding or standalone UUID/name/database/schema; requires existing catalog permission |
| `dba_compare_schemas` | Compare owned retained capture-job IDs (`leftSnapshotId`, `rightSnapshotId`, `limit` 1–100); rechecks authorization and revisions for both sides |
| `dba_prepare_migration` | Create a ten-minute retained SQL/manifest artifact from explicit changes or supplied DDL; no execution or inferred rename/cascade/backfill |
| `dba_validate_migration` | Recapture the exact target fingerprint and reject stale target/plan state without executing migration SQL |
| `dba_prepare_migration_rehearsal` | Bind setup, bounded synthetic fixture INSERTs, migration steps, and read-only checks to a distinct authorized disposable target snapshot; rejects source aliases |
| `dba_request_apply_migration` | Exact one-time human review and asynchronous execution of a retained migration or rehearsal; reusable SQL grants never authorize the plan |
| `validate_database_contracts` | Compare bounded static SQL/JPA/MyBatis/Prisma/TypeORM evidence with one authorized retained schema snapshot |
| `compare_query_plans` | Compare structural evidence from two retained estimated-plan jobs; preserves raw plans and never compares cross-vendor cost units |
| `dba_pair_editor` and editor document tools | User-code pairing, revision-checked Script drafts/edits, bounded events, and explicit revocation; never executes SQL, saves files, or grants database permission |

Capability discovery does not pretend a template proves the actual vendor or
server version. An unobserved standalone target reports `not_observed` until
explicitly requested with `live: true`; poll the returned job ID normally.
Advanced migration availability requires a live observed PostgreSQL, MySQL,
MariaDB, or H2 identity and an interactive approval channel. Other JDBC templates
remain explicit unverified/unsupported entries in the capability matrix rather
than inheriting SQL syntax from connectivity alone. Workspace permission summaries
are not grants; `dba_get_my_permissions` contains reusable exact/category/session
policy details. Combined DBA context is available only through the authenticated
agent handler, not anonymous graph/UI responses.

Schema captures retain no application rows. They share the DBA job accounting and
expire under the normal five-minute result retention; release their jobs when done.
Captures stop at 100 objects, 100 aggregate nested metadata rows, 32 KiB per
definition, or the bounded byte allowance, returning explicit partial coverage.
Comparison leases keep both source reservations accounted until comparison settles;
release requests during that interval fail with an actionable in-use message.
Changed or revoked target permissions are rechecked before publishing results.
Object presence on only one side is **not** asserted to be a creation/removal.
Definitions without a verified semantic normalizer are reported separately as text
differences. No renames or cross-engine type equivalence are inferred. Narrow the
selected schema when a capture is truncated: an incomplete capture cannot establish
that an object was removed.

Migration plans retain the originating capture reservation, target scope, fingerprint,
ordered SQL, risks, postconditions, transaction guarantee, and plan hash. A plan is
single-use, expires after ten minutes, and is revalidated immediately before execution.
PostgreSQL requests an atomic transaction; MySQL, MariaDB, and H2 disclose possible
partial completion. Connection loss during commit is reported as uncertain and is
never retried automatically. Rehearsal setup accepts CREATE/COMMENT only, fixtures
accept INSERT only, and checks use the restricted read-query validator. A rehearsal
does not copy source records, delete its target, or claim production equivalence.

`search_symbols` and `dba_search_objects` accept `cursor` and return `nextCursor`.
Repeat the same query filters and exact project/binding. Cursors expire after
five minutes and are scoped to the tool instance/query (and agent for catalog
pages). Generation changes, restart, or project reload invalidate them. Restart
without a cursor after `stale_cursor`, `expired_cursor`, or `invalid_cursor`;
never merge mixed generations. No graph generation is retained between calls.
Legacy catalog `offset` remains accepted, but must not be combined with `cursor`
and cannot guarantee consistency across intervening scans.

The four navigation tools also support generation-bound cursors. They report
index coverage as incomplete: dynamic references, unindexed files and unsupported
language constructs may be absent. Occurrence ranges use 1-based UTF-16 columns
and inclusive ends. A range is labeled `identifier_token` only when the parser's
call-name node matches the extracted name; other sites retain expression or
declaration precision. Location precision does not imply unique target resolution.
Older indexed edges may provide only a line or
containing declaration; reindex to collect the expanded reference metadata.
Ambiguous same-scope/overloaded references now retain bounded candidates with
reduced confidence instead of choosing the first declaration as a unique match.

`dba_find_code_references` uses incrementally indexed source mappings, not a fresh
filesystem search. Static SQL files/literals, JPA, MyBatis mapper XML, Prisma and
TypeORM decorators contribute bounded name/location evidence. XML external entities
are disabled. Mappings use the existing graph storage/cache and are replaced or
removed with their source file. Up to 512 mapping records / approximately 128 KiB
of attributes per file are retained, plus an explicit limit marker. SQL input is
bounded to 32 KiB and 32 statements; results do not retain SQL literal values.

Mappings are evidence, not proof of use or non-use. Computed names, dynamic SQL,
custom naming strategies, derived/CTE column lineage and unassociated MyBatis
result maps remain uncertain. Default-named ORM fields are candidates. SQL source
locations identify the source expression, not every token inside an escaped
string. Complete contract validation and additional framework forms remain on
the delivery checklist.

Impact tools currently accept explicit `files`, `symbols` and `databaseChanges`
(`table`, optional `schema`/`column`/`operation`/`newName`). They inspect the current
published generation with 200 seeds, 1,000 visited symbols and a five-second work
bound, and never rebuild a complete graph. Missing/deleted targets remain
unresolved—not unaffected. Source-name matches do not authorize database access.
An optional `git` selection can describe the working tree or exact `base`/`head`
revisions. The server resolves commit IDs and changed/renamed paths with a bounded,
shell-free Git process, then analyzes those paths against the current published
graph. It labels that distinction explicitly; it does not construct a historical
graph. Test-path conventions remain heuristic and do not establish complete coverage.

Compatibility aliases remain supported: `dba_live_request_status` →
`dba_request_status`, and `dba_cancel_live_request` → `dba_cancel_request`.
Aliases take `approvalId`; canonical operations take the returned approval ID
in `requestId` (not the caller's idempotency key).

### Onboarding projects through MCP

Both HTTP and stdio support onboarding without the UI or UI admin mode:

```text
→ add_project {"path": "C:/repos/acme"}
← {"project": "acme", "state": "ready"}
```

`path` must identify an existing directory on the **server machine**. Prefer absolute paths;
relative paths resolve against the server's working directory. The call waits for initial
indexing, so increase the client's request timeout for large projects. The returned project
name is deduplicated by name and can be passed to the analysis tools' `project` argument.
Omitting that argument uses the first onboarded project. Runtime additions are session-local.

Duplicate roots and children of already onboarded or in-flight roots are rejected before
scanning. Real-path checks include symlink resolution and Windows case handling; a textual
prefix alone is not a conflict (`acme-other` is a valid sibling of `acme`). The check is
directional: adding a parent after its child is allowed. Removing a project releases its root
restriction. The same safety rule applies to UI onboarding and startup roots.

### Idle project expiry

Both entry points accept `--project-ttl 1h` (default); `90s`, `30m`, and `2d` are also valid.
Each resolved project-specific MCP call resets that project's idle timer, including `index_status`.
When `project` is omitted, only the resolved default project is renewed. `list_projects` is
passive: its `lastActivityAt`, `expiresAt`, `remainingSeconds`, `activeOperations`, and `instanceId`
fields let clients observe lifetimes without keeping projects alive. Tool discovery and MCP
connection traffic do not count as project use.

Initial indexing finishes before the timer starts. Explicit reindex jobs and in-flight queries
hold usage leases until completion. A five-second sweep removes idle projects, stops watchers,
and drops graph references. Expired-project queries return an error; call `add_project` to load
the directory again. UI users can change the global timeout for the current server session;
that affects MCP projects too, without refreshing their last-activity timestamps.

### Symbol IDs

`<lang>:<repo-relative-path>#<qualifiedName>/<arity>`, e.g.
`java:src/main/java/com/acme/auth/AuthService.java#com.acme.auth.AuthService.validateToken/1`.
IDs are stable across re-indexing — agents can hold them between calls.

### Worked session

```json
→ search_symbols {"query": "AuthService.validate", "kind": "function"}
← {"symbols": [{"id": "java:...#AuthService.validateToken/1", "kind": "function",
     "sig": "validateToken(String jwt)", "line": 88}], "total": 1, "truncated": false}

→ get_impact_radius {"target": "java:...#AuthService.validateToken/1", "depth": 3}
← {"dependents": {"direct": ["java:...#LoginController.login/1", "..."],
     "byDepth": [35, 172, 213], "total": 420, "modules": ["api","auth","billing"],
     "langs": ["java","ts"], "topByFanIn": ["java:...#ApiGatewayFilter.doFilter/3"]},
   "risk": {"score": 83, "band": "critical", "gate": "fail",
            "note": "MANDATORY RISK REVIEW: blast score 83 >= gating threshold 70. ..."}}
```

The `risk` block is the **threshold gate**: it is attached automatically to
`get_impact_radius`, `get_symbol` and `get_call_graph` whenever the target's blast score
reaches `gating.threshold` — an agent about to edit high-risk code always sees the risk report.

## Token frugality guarantees

- Hard caps: `limits.maxResults` (default 50) per list, `limits.maxResponseBytes`
  (default 32 768) per tool JSON. Symbol/navigation pages automatically shorten to
  fit and return signed generation-bound cursors. A single oversized record fails
  explicitly; no records are silently skipped. Other oversized responses fail.
- Every truncated list carries `"truncated": true` plus an `omitted` count.
- **No tool ever returns file contents** — signatures, IDs and counts only.
- Call graphs are node-array + `[callerIdx, calleeIdx]` pairs, so each ID string appears once.
  Pairs aggregate distinct relationships with parallel occurrence counts and
  completeness flags. See [precision contracts](../mcp-precision-efficiency.md)
  for work limits, pagination, build/catalog identity and resolver coverage.

## Security posture

Code graph queries stay local. Opt-in DBA operations can connect to configured
database hosts; explicitly approved driver installation can use Maven repositories.
DBA configuration/vaults and hybrid session storage use their configured locations,
which may be outside `.code-graph/`. There is no telemetry.

MCP clients can onboard directories readable by the server's account, independently of
`--viz-admin`. Only expose the HTTP endpoint to trusted clients behind appropriate network
controls. The child-path guard prevents nested onboarding; it is not a filesystem allowlist.
