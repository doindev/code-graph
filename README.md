# code-graph

Enterprise blast-radius and code-intelligence MCP server for the JVM (Java 25). code-graph turns
large text codebases into a queryable **code property graph** so AI agents can answer
*"what breaks if I change this?"* in milliseconds instead of burning tokens on file-reading loops.

## What it does

- **DBA preview**: opt-in `/dba` connection management, capped SELECTs, PostgreSQL estimated
  plans/definition inspection, and authenticated read-only MCP access with or without the UI.
  Direct local browser access, an alphabetical database picker, embedded Maven driver bundles,
  advanced JDBC settings, statement-aware human scripts, reusable/reorderable result grids,
  savepoint-backed browser error decisions, unsaved connection tests with version-query results, and Snowflake
  existing-file RSA key-pair authentication are described in [Connection setup](docs/dba-connections.md).
  [Grid query editing and the expanded JDBC catalog](docs/dba-grid-and-drivers.md) covers
  alias-aware sorting/filtering, editable Boolean filters, driver recipes and validation limits.
  [Editable, pageable grids and exports](docs/dba-editable-grids.md) add staged
  transactional row changes, 200-row server pages, selection/navigation, grid
  settings, loaded-page Find/Replace with staged edits and ten-entry histories,
  and bounded CSV/XLSX/TXT/SQL downloads with explicit capability gates.
  Double-click a table in the database tree to open a fixed-connection **Table** tab with a reusable
  Data grid and session recovery. Tables expand into lazy, vendor-aware categories such as
  Columns, Constraints and Foreign Keys; see [catalog navigation](docs/dba-catalog-tree.md#table-children).
  A staged [Table Properties designer](docs/dba-table-designer.md) supports reviewed PostgreSQL/H2
  and verified SQL Server 2022 ordinary-table schema edits; the capability matrix documents remaining vendor and advanced-editor gaps.
  Supported PostgreSQL/H2 category menus offer [New with SQL review](docs/dba-catalog-tree.md#creating-objects-from-category-menus)
  and explicit Apply; Tables → New reuses the Properties tab, with Data/Diagram disabled until creation succeeds.
  The reusable [Visual Query Builder](docs/dba-query-builder.md) opens standalone, from selected
  Script SQL, or in the Diagram view of tables/views/materialized views. It supports source and
  column drag/drop, joins, query expressions, SQL files, undo/redo and bounded result grids.
  Unsupported SQL is preserved in SQL mode; saving a query never changes a database view.
  Database relationships use canonical local/dev/test/stage/prod environments, unique logical roles,
  human-readable purposes, direct UI management, and exact human-reviewed MCP connection/binding proposals.
  [Scoped reusable approvals](docs/reusable-approvals.md) cover verified reads and narrowly validated
  new-object creation, either exactly or by category and MCP-session/persistent lifetime. Destructive
  operations remain one-time; legacy read grants retain their existing scope. See [DBA status and remaining gates](docs/dba.md).
  [Project database context](docs/project-database-context.md) links applications to environment-aware database/schema snapshots,
  pauses catalog scans when MCP activity stops, and provides human approval for live agent SQL across all connection templates.
  The [approval broker](docs/approval-broker.md) routes requests to an active DBA browser or a
  JDK-only desktop consent prompt, with a restricted temporary browser editor for complex reviews.
  [Native MongoDB and Redis](docs/native-databases.md) add separate non-JDBC profiles,
  typed command workspaces, bounded reads, reviewed CRUD and native catalog observations/contracts.
  Mongo replica/sharded and Redis Sentinel/Cluster connections have disposable topology tests.
  Redis Sentinel supports separate password-only or ACL discovery credentials,
  stored write-only in the OS vault; data-node credentials remain independent.
  See [Sentinel authentication and validation](docs/redis-sentinel-auth.md).
  The [incremental Redis key browser](docs/redis-key-browser.md) provides MATCH
  filtering, bounded binary-key deduplication, explicit empty-page continuation,
  Cluster-wide cursors and live non-snapshot coverage.
  [Bounded Redis transactions](docs/redis-transactions.md) add reviewed batches and
  optimistic WATCH expectations, including single-slot Cluster execution; Redis
  execution errors do not roll back successful commands.
  A [bounded Redis string editor](docs/redis-string-editor.md) adds binary-safe
  drafts, conflict checks and reviewed TTL-preserving saves for existing small values,
  plus explicit absent-key creation, staged whole-string deletion, and reviewed
  expiry choices (preserve, no expiry, or 1–2147483647 seconds), and staged
  non-overwriting string-key rename with source/destination guards.
  The [hash-field editor](docs/redis-hash-editor.md) adds the same bounded workflow
  for existing fields, plus explicit new-field drafts and staged deletion. Every
  Save is reviewed; absent/original-value checks reject concurrent changes.
  Expiring fields remain unsupported and last-field deletion removes its hash key.
  The [list-item editor](docs/redis-list-editor.md) adds bounded text/base64 editing
  at an existing zero-based position, with reviewed exact length/value checks
  and TTL preservation. It also stages prepend/append and guarded first/last-item
  deletion without loading the list. A position is not a stable record identity;
  deleting the last item removes its key and TTL.
  The [stream-entry composer](docs/redis-stream-editor.md) stages bounded ordered
  text/base64 field pairs and appends only to an existing stream after review;
  explicit receipts distinguish a new entry from a missing-key no-op.
  A [set-member editor](docs/redis-set-editor.md) checks one exact member and
  stages reviewed insertion/deletion with membership conflict checks.
  The [sorted-set member editor](docs/redis-sorted-set-editor.md) stages finite
  score updates, member insertion or deletion within an existing sorted set.
  Current-score/absence guards reject concurrent changes; deleting the last
  member removes its key and TTL. No whole-set loading or implicit key creation.
  [Bounded Redis pipelines](docs/redis-pipelines.md) combine up to 32 supported
  scalar/range commands into one dispatch with per-command receipts. Pipelines
  are not atomic; partial failures and cancellation never imply rollback.
  [Bounded bitmap, HyperLogLog and geo operations](docs/redis-values.md) add
  typed bitfield results, cardinality workflows and limited geo searches.
  PFCOUNT requires write review because Redis can update its cached count.
  [Bounded Redis streams](docs/redis-streams.md) provide finite consumer-group
  reads, pending inspection, reviewed claims/ACKs and group lifecycle commands.
  Message delivery is a write; no automatic acknowledgement or subscription.
  [Bounded MongoDB transactions](docs/mongodb-transactions.md) provide reviewed
  atomic CRUD on one existing ordinary collection in replica-set/sharded mode,
  with exact match checks, cancellation and explicit uncertain-commit outcomes.
  The [MongoDB document editor](docs/mongodb-document-editor.md) adds staged,
  canonical Extended JSON creation, replacement and staged deletion with replica-set-only
  absence/original-BSON and collection UUID guards; standalone/sharded editing remains unavailable.
  The [MongoDB pipeline builder](docs/mongodb-pipeline-builder.md) adds bounded
  stage editing, disabling and reordering with a typed command preview. Use pipeline
  hands the draft to the native editor; execution remains a separate action.
  [MongoDB change-stream batches](docs/mongodb-change-streams.md) add finite,
  resumable exact-collection reads with explicit history gaps and a next-batch UI.
  MongoDB supports [reviewed same-database collection renames](docs/mongodb-collection-rename.md)
  without replacing destinations or silently retargeting open workspaces.
  [Reviewed collection settings](docs/mongodb-collection-settings.md) support existing TTL
  indexes, time-series retention/granularity and capped limits. Retention or size
  reductions can delete data and always carry a destructive-operation warning.
  SQL Server gains native definitions, reviewed ordinary-table design and migration checks.
  Infrastructure administration and other advanced workflows remain explicitly unsupported;
  see the [current acceptance report](docs/mcp-efficiency-coverage-delivery.md).
- **Tree-sitter AST parsing** for the top-10 languages: Java, JavaScript, TypeScript (+TSX),
  Python, C#, Go, Rust, C, C++, PHP — plus Ruby and Kotlin. New languages plug in behind a
  `LanguageAnalyzer` SPI.
- **Unified code property graph**: files, types, functions and variables connected by
  CONTAINS / IMPORTS / CALLS / REFERENCES / EXTENDS / IMPLEMENTS / OVERRIDES / READS / WRITES edges, every
  cross-file edge carrying a resolution **confidence** score.
  [Java dependency precision](docs/dependency-precision.md) uses receiver/owner types, imports,
  lexical variables, inheritance and argument evidence instead of same-name guessing.
  Reference results disclose uncertainty; runtime dispatch and unresolved code still require care.
  [Language-isolated resolution and MCP-first validation](docs/language-resolution.md)
  prevent unrelated language symbols from consuming candidate/lookup budgets, while
  retaining JavaScript/TypeScript interoperability and explicit coverage limitations.
- **Fast in-memory queries by default**: lock-free reads via generation-swapped immutable state.
  Opt-in [hybrid graph storage](docs/hybrid-graph-storage.md) pages graph records from session-only
  disk storage using a shared bounded cache. H2 SQL, Neo4j and ArangoDB remain separate mirrors.
- **Incremental background indexing**: a file watcher re-parses only changed files and patches
  the graph atomically. Hybrid mode uses bounded copy-on-write updates; directory/ignore-rule
  changes and watcher overflow fall back to staged rebuilds.
- **Blast score + risk gating**: an auditable weighted formula (transitive reach, fan-in, module
  spread, cross-language reach, test coverage proxy); high-score targets get a mandatory risk
  report attached, and CI can fail the build on it.
- **Architectural drift**: a golden blueprint (layers + allowed dependencies) checked with cycle
  detection and baseline diffing — only NEW violations are reported.
- **Code smells**: god class, long method, hubs, cycles, feature envy, data clumps, refused
  bequest, temporal coupling (git co-change mining) and duplicate logic — every finding carries
  its metric evidence.
- **MCP tools** (stdio or loopback-only streamable HTTP for local agents): `search_symbols`,
  `get_symbol`, `get_symbol_context`, `find_implementations`, `get_impact_radius`, `get_call_graph`, `get_blast_score`, `find_dead_code`,
  `find_code_smells`, `compare_architectural_drift`, `index_status`, `reindex`,
  `list_projects`, `get_workspace_context`, `add_project`, `remove_project`.
  Tools publish backward-compatible text plus structured results. Symbol/catalog
  pagination uses expiring generation-bound cursors. DBA adds template discovery,
  exact-target capability observations and authorized catalog refresh/waits, including standalone
  connections. Opt-in symbol bundles share one bounded generation; `index_status` can wait
  for exact file hashes/deletions and jobs support revision-aware bounded waiting. See
  [method coverage](docs/method-implementation-coverage.md) and
  [measured acquisition results](docs/mcp-efficiency-coverage-delivery.md).
  See the [MCP tool reference](docs/guides/mcp-server.md),
  [generated schemas and canonical descriptions](docs/mcp-tools-generated.md), the
  [workflow acceptance report](docs/mcp-workflow-acceptance.md), and the
  [42-template capability manifest](docs/workflow-capabilities.json).
- **Headless CI mode**: scan a PR, compute the blast radius of the diff, post a markdown risk
  report to GitHub/GitLab, and gate merges via exit codes.
- **Local-first**: all parsing and graph traversal stays inside your perimeter; tools return
  minimized metadata (signatures, IDs, counts) — never file contents.

## Modules

The maintained [agent skill](skills/code-graph/SKILL.md) supports Codex, GitHub
Copilot, Claude Code and Windsurf. Explicit installation instructions are in the
[MCP guide](docs/guides/mcp-server.md#agent-skill-for-copilot-claude-code-codex-and-windsurf);
the standalone skill helper never configures MCP access or overwrites customized skills.
The application installers offer MCP registration as a separate, optional step before
offering skills for detected connections; neither step grants tool permissions.

| Module | Purpose |
|---|---|
| `code-graph-bom` | Bill of Materials for version-managing all modules |
| `code-graph-core` | Zero-dependency graph model, stable symbol IDs, `GraphQuery` API, `GraphStore` SPI, in-memory engine, binary snapshot + journal |
| `code-graph-config` | `code-graph.json` (canonical, comments allowed) / `code-graph.yaml` loader with env overrides |
| `code-graph-parsers` | `LanguageAnalyzer` SPI + tree-sitter walk infrastructure (grammar ABI gate lives here) |
| `code-graph-lang-*` | One analyzer per language: java, javascript (JS/TS/TSX), python, go, rust, c (C/C++), csharp, php, ruby, kotlin |
| `code-graph-index` | Two-pass pipeline, confidence-laddered resolution, incremental indexer + file watcher |
| `code-graph-storage` | Opt-in MVStore graph paging, shared record cache, session cleanup and memory telemetry |
| `code-graph-analysis` | Blast-score formula, dead code, Tarjan SCC |
| `code-graph-rules` | Golden-blueprint architecture rules, module graph, drift fingerprints |
| `code-graph-smells` | Smell detectors (god class, long method, hubs, cycles, unstable dependencies) |
| `code-graph-tools` | Transport-agnostic tool layer: schemas, response caps, RiskGate, multi-project routing |
| `code-graph-mcp` | stdio MCP server (bundles all languages; the only module importing the MCP SDK) |
| `code-graph-mcp-http` | Streamable-HTTP MCP server on embedded Jetty 12 (Docker-ready) |
| `code-graph-viz` | Embedded 3D visualization: drill-down, ego networks, galaxy view (vendored WebGL, no CDN) |
| `code-graph-dba` | Opt-in local JDBC administration preview; native vault adapters, isolated pools and bounded reads |
| `code-graph-store-h2` / `-neo4j` | Durable graph-mirror adapters for custom integrations |
| `code-graph-store-arangodb` | ArangoDB dependency scaffolding; no standard-server configuration |
| `code-graph-linker` | Cross-language HTTP-route linking (`INVOKES_REMOTE` edges) |
| `code-graph-cli` | Headless CI runner: diff-scoped risk reports, GitHub/GitLab posting, gating exit codes |

## Guides

| Guide | Topic |
|---|---|
| [docs/installation.md](docs/installation.md) | Windows/macOS/Linux installation, prerequisites/proxies, optional MCP and skills, launcher defaults, updates and safe uninstall |
| [docs/dba.md](docs/dba.md) | DBA preview startup/configuration, credential handling, limits, APIs and remaining implementation gates |
| [docs/dba-catalog-tree.md](docs/dba-catalog-tree.md) | Vendor-specific database/schema object trees, refresh behavior, metadata bounds and validation commands |
| [docs/hybrid-graph-storage.md](docs/hybrid-graph-storage.md) | Disk paging, shared cache budget, admin settings and memory limits |
| [docs/guides/mcp-server.md](docs/guides/mcp-server.md) | Registering with Claude Code, tool reference, worked session |
| [docs/guides/configuration.md](docs/guides/configuration.md) | Full `code-graph.json` / `.yaml` reference |
| [docs/guides/blast-score.md](docs/guides/blast-score.md) | The risk formula, worked example, threshold gating |
| [docs/guides/indexing.md](docs/guides/indexing.md) | Two-pass extraction, confidence ladder, incremental watching |
| [docs/guides/smells.md](docs/guides/smells.md) | Smell detector descriptions and limitations; current threshold catalog below |
| [docs/guides/architecture-drift.md](docs/guides/architecture-drift.md) | Blueprint rules, cycle detection, baseline diffs |
| [docs/guides/ci.md](docs/guides/ci.md) | The `ci` pipeline, exit codes, GitHub Actions / GitLab examples |
| [docs/guides/visualization.md](docs/guides/visualization.md) | 3D graph UI and multi-project workspaces |

## Install the `cgraph` command

The source installers check Git, a full **JDK 25**, and **Maven 3.9+**, offer supported
prerequisite installations with confirmation, clone a selected repository/ref, test/build in an
isolated checkout, and create a native launcher with its own Java runtime. Node.js/npm are not required.

From a checkout containing the installer:

```powershell
# Windows; use the current checkout, including local changes
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -SourceDir .
```

```bash
# macOS / Linux
bash ./install.sh --source-dir "$PWD"
```

Omit `-SourceDir .` / `--source-dir "$PWD"` to clone the published `main` branch instead.
The selected remote ref must include these installer files. Open a new terminal after installation:

```text
cgraph
```

This starts local MCP on **3000**, Graph/admin UI and DBA on **8137**, desktop approvals,
and hybrid storage with a **1.5 GiB shared graph/cache budget** (`1536m`). No project is automatically
onboarded unless explicitly configured. This is not a hard total-RAM or JVM heap limit.
Use `cgraph --help` for overrides; Ctrl+C stops the foreground server. Installation itself
does not start, stop, or restart a server.

Existing installations receive the **1.5 GiB** default after rerunning the installer
from this updated source/ref. An explicit argument still takes precedence, for example
`cgraph --graph-memory 512m`. Use `1536m`, not `1.5g`: the size parser accepts whole
MiB/GiB values. `cgraph --print-config` shows the effective arguments without starting
the server. Raw Java HTTP/stdio entry points retain their **1 GiB** default.

Install builds **skip Maven tests by default**; add `-RunTests` (Windows) or `--run-tests`
(macOS/Linux) to run them. Windows automatically uses `%USERPROFILE%\.m2\settings.xml`
when present, unless `-MavenSettings` specifies another file; the file remains unchanged.
By default, installer Git/Maven downloads disable TLS certificate verification **only during
installation**, so corporate self-signed certificates do not require manual certificate setup.
Use a trusted network: intercepted downloads can execute untrusted code. If you have a corporate
PEM certificate bundle, add `-CertPem 'C:\Company\cert.pem'` (Windows) or
`--cert-pem /path/to/cert.pem` (macOS/Linux) to enable verification for **both Git and Maven**.
The PEM is not modified; Maven's temporary trust store is not installed. Runtime TLS, OS trust
and prerequisite package-manager/signature checks remain unchanged.

**Behind a proxy?** Use `-Proxy` / `--proxy`, standard proxy environment variables, and
`-MavenSettings` / `--maven-settings` for corporate mirrors and authentication. See the
[installation guide](docs/installation.md) for authenticated proxies, certificates,
prerequisite checks, updates, native distribution and validation limits.

### Optional MCP connections and skills

Optional MCP setup is offered **first**: choose one or more of Codex, Claude Code,
Copilot CLI, Copilot in VS Code, and Windsurf, or none. Use `-McpClients all`
(Windows) or `--mcp-clients all` (macOS/Linux), or a subset such as `codex,claude`.
Matching connections in supported client configuration files are detected even under
another name and are not added twice. Conflicting entries are reported, not overwritten.
The default client endpoint is `http://localhost:3000/mcp`.
See [MCP setup, detection and recovery](docs/installation.md#optional-mcp-connections-then-skills).

Then optional global skills are offered only for clients with matching configured
code-graph connections (including pre-existing connections). Use `-Skills all`
(Windows) or `--skills all` (macOS/Linux), a subset such as `codex,claude`, or
`none`. Unattended installs default to none. The skill recommends code-graph only
when its MCP tools are available and useful; otherwise agents use their normal
tools. Differing installed skills prompt before replacement (default **No**); an
approved update keeps a backup of the entire previous skill folder. Declining or
running non-interactively keeps the existing skill without failing installation.
Connection settings and tool permissions remain unchanged. `all` skills means all detected matching clients.
See [global skill paths and standalone installation](docs/installation.md#optional-global-agent-skills).

For example, configure Codex and Claude Code, then install skills for all detected
matching clients (including previously configured clients):

```powershell
.\install.ps1 -SourceDir . -McpClients 'codex,claude' -Skills all
```

```bash
bash ./install.sh --source-dir "$PWD" --mcp-clients codex,claude --skills all
```

Use `-McpClients none` / `--mcp-clients none` to skip connection registration; existing
matching connections can still qualify for the skill offer. A custom `-McpUrl` /
`--mcp-url` changes only the client endpoint: start `cgraph --port PORT` separately
with the matching port. Registration does not install the client applications or
make this machine's loopback server reachable from cloud-hosted agents.

### Uninstall

Stop the installed application first. Run the script from the checkout or its copied
version in the installation directory; it verifies ownership and prompts before removal:

```powershell
# Windows: check without changes, then uninstall with confirmation
.\uninstall.ps1 -Check
.\uninstall.ps1
```

```bash
# macOS / Linux
bash ./uninstall.sh --check
bash ./uninstall.sh
```

For custom locations, supply `-InstallDir PATH` / `--install-dir PATH`.
Unattended removal requires explicit `-Yes` / `--yes`. The scripts remove the verified
application, binary releases and managed command/PATH entry, but preserve database
profiles, vault credentials, projects, MCP connections and skills **outside the installation**.
Do not store personal data in the application directory. No running process is killed.
See [uninstall safety, backups and PATH options](docs/installation.md#uninstall-on-windows-macos-and-linux).

## Build

Requires JDK 25 and Maven 3.9+.

```
mvn verify
```

The `code-graph-parsers` tests are the **grammar ABI gate**: every bundled tree-sitter grammar
must load and parse against the core binding (`io.github.bonede:tree-sitter`). If a grammar
fails there, pin the core binding back (0.25.3 is the documented fallback) before building
anything on top.

Known limitation: the Kotlin grammar (0.3.8.1) reports errors on expression-body functions
(`fun m() = ...`); block bodies parse fine.

## Run

Quick navigation: [server arguments](#server-startup-argument-reference),
[workspace files](#workspace-file-and-project-onboarding),
[configuration precedence](#configuration-scopes-and-precedence),
[environment variables](#environment-variables), [TTL](#project-idle-timeout),
[graph memory](#graph-memory-and-disk-storage), [disk location](#disk-location-and-cleanup),
[admin settings](#admin-ui-and-settings-api), [project JSON/YAML](#per-project-jsonyaml-reference),
[smell thresholds](#smell-detector-settings), [CLI](#headless-cli-configuration),
[containers](#container-and-embedded-deployments).

Build the HTTP runtime (including its `target/lib` dependencies) from the repository root:

~~~powershell
mvn -pl code-graph-mcp-http -am package
~~~

Stop a running server before rebuilding on Windows: Java can lock the runtime JARs.
These examples use PowerShell backticks for line continuation and `;` as the Java classpath
separator. On Linux/macOS use your shell's continuation syntax and `:` instead.

### HTTP server with admin UI

~~~powershell
java -Xmx1g --enable-native-access=ALL-UNNAMED `
  -cp "code-graph-mcp-http\target\classes;code-graph-mcp-http\target\lib\*" `
  io.doindev.codegraph.mcp.http.HttpMain `
  --port 3000 --viz 8137 --viz-admin --project-ttl 1h
~~~

UI: [http://localhost:8137/](http://localhost:8137/). MCP:
[http://localhost:3000/mcp](http://localhost:3000/mcp).

This starts with **no onboarded projects**, provided `CODE_GRAPH_ROOT` is unset or blank.
The current working directory is not automatically onboarded. Add projects through the admin
UI or MCP `add_project`, or supply explicit startup roots.

For disk paging, append `--graph-storage hybrid --graph-memory 1g`. The
[hybrid configuration](#graph-memory-and-disk-storage) below explains why this is distinct
from `-Xmx1g`.

### Local stdio server

After the same HTTP-runtime build, its dependency directory also contains the stdio server:

~~~powershell
java -Xmx1g --enable-native-access=ALL-UNNAMED `
  -cp "code-graph-mcp-http\target\lib\*" `
  io.doindev.codegraph.mcp.Main --viz 8137 --viz-readonly
~~~

Configure your MCP client to launch that command for stdio; stdout is reserved for the
protocol and logs go to stderr. Omit `--viz-readonly` to enable stdio UI administration.
Omit `--viz` entirely if a UI is not needed. Client-side request timeouts must allow enough
time for initial onboarding and large queries.

### Server startup argument reference

The defaults below describe the raw HTTP/stdio entry points, not the installed
[`cgraph` launcher](#install-the-cgraph-command), which supplies its own defaults.

These are application arguments, placed **after the main class**. Use `--flag value`,
not `--flag=value`, for these server entry points. Only `--root` is intended to repeat;
scalar arguments use the first matching value. Unknown server arguments are not uniformly
rejected, so verify startup logs and `/api/server` rather than relying on typo detection.

| Argument | Applies to | Default | Meaning |
|---|---|---|---|
| `--root DIR` | HTTP, stdio | None | Onboard and watch a directory at startup. Repeat for multiple projects. Relative paths resolve from the process working directory. |
| `--workspace FILE` | HTTP, stdio | None | Load named startup projects from a workspace JSON file. Takes precedence over all `--root` values and `CODE_GRAPH_ROOT`. |
| `--port N` | HTTP only | `CODE_GRAPH_PORT`, otherwise `3000` | MCP HTTP listener port; endpoint is `/mcp`. |
| `--viz N` | HTTP, stdio | Disabled | Enable the separate UI/API listener on this port. |
| `--viz-admin` | HTTP only | Off | Enable UI onboarding, removal, reindexing, browsing, TTL and RAM settings. |
| `--viz-readonly` | stdio only | Off | Disable UI admin actions; stdio UI administration is otherwise on. |
| `--project-ttl DURATION` | HTTP, stdio | `1h` | Shared idle-expiry policy for every onboarded project; live admin changes are possible. |
| `--graph-storage MODE` | HTTP, stdio | `memory` | `memory` keeps the graph in Java heap; `hybrid` stores records on disk with a shared cache. Selected for the whole server session. |
| `--graph-memory SIZE` | HTTP, stdio | `1g` | Shared graph/cache allowance in hybrid mode, **not per project**. Accepted but does not bound the pure in-memory backend. |
| `--dba-decision-timeout N` | HTTP, stdio with `--dba` | `60` | Seconds to wait for a human script-error decision; valid range 10–600. Other DBA flags are documented in [the DBA guide](docs/dba.md#start-explicit). |
| `--dba-approval-mode MODE` | HTTP, stdio with `--dba` | `auto` | Human approval channel: `auto`, `browser`, `desktop`, or `none`. Browser mode requires the UI; desktop mode requires an interactive desktop. See [routing, security and validation](docs/approval-broker.md). |
| `--yolo` | HTTP, stdio with `--dba`; installed `cgraph` | Off | DANGER: automatically authorize validated local MCP database operations, including writes and administration. Overrides approval routing; preserves validation, auditing, limits and browser confirmations. Startup-only, no value. See [exact targeting, setup and recovery](docs/yolo.md). |

For listener ports, use `1–65535`; `0` requests an OS-assigned port, reported in startup logs.
MCP and UI need separate available ports. HTTP MCP and its UI bind strictly to `127.0.0.1`;
the stdio UI also binds to loopback. There is no remote/wildcard bind option or TLS listener.
The HTTP UI uses JDK `HttpServer`; the MCP HTTP transport uses embedded Jetty 12.

**Local trust:** no token or Agent access setup is required for local MCP clients. With DBA
enabled, token-free HTTP and stdio clients use a shared **Trusted local agents** identity.
They can discover connection IDs/names and application/environment bindings and submit
approval requests. Connection details/tests and SQL still require human approval or an
existing grant. Verified creation can receive narrowly scoped reusable approval; destructive
operations and administration remain one-time. Persistent policies, ownership and created-connection
history apply to all token-free local clients; temporary policies stay isolated by MCP session.
Optional named bearer tokens remain available for separate identities. Invalid explicit
tokens fail closed rather than falling back to local trust. See [DBA access](docs/dba.md).

All MCP HTTP requests enforce loopback peer, Host and Origin checks, including graph-only
mode. Forwarding headers do not establish local trust. Local processes are trusted; do not
expose this listener with a reverse proxy or tunnel. Docker port publishing does not expose
a loopback-bound listener; clients must share its network namespace (or use local stdio).
MCP `add_project` can access directories readable by the server account. Read-only UI mode
does **not** disable MCP project management. Neither `--viz-admin` nor its absence identifies
an agent. Unknown/expired MCP sessions return 404 so clients can reinitialize after restart.

### Workspace file and project onboarding

A workspace file uses ordinary JSON (unlike `code-graph.json`, comments/trailing commas are
not enabled):

~~~json
{
  "projects": [
    { "name": "api", "root": "C:/repos/api" },
    { "name": "web", "root": "C:/repos/web" }
  ]
}
~~~

Run with `--workspace C:\config\workspace.json`. `projects` must be an array; each entry
requires `root`. `name` is optional and defaults to the root's final path component.
Workspace names must be unique. Relative roots resolve from the **process working
directory**, not the workspace file's directory. `{"projects":[]}` explicitly starts empty,
even if `CODE_GRAPH_ROOT` is set.

With repeated `--root` or runtime onboarding, names default to directory names and receive
numeric suffixes when needed. The first registered project is the default for MCP requests
that omit `project`. Projects are isolated for queries and share server-wide TTL/storage policy.

Roots must exist and be directories. Real-path validation rejects duplicate roots and child
paths of existing or in-flight projects, including symlink aliases. Siblings are allowed.
The rule is directional: adding a parent of an existing project is currently allowed.
Hybrid mode additionally rejects project roots overlapping its session-storage directory.

MCP `add_project` takes a **server-side** directory path and returns its assigned name after
indexing completes. While it runs, `list_projects.onboarding` exposes initial-scan
phase, elapsed time and file counts separately from queryable `projects`, without
renewing TTLs. At most four runtime onboarding scans run concurrently; excess
requests receive `onboarding_busy`. The UI scan dialog shows parsing, relationship
resolution and publication stages. Counts describe completed work, not an estimated
percentage; hybrid discovery and parsing overlap. Check pending/ready entries before
retrying an uncertain add. The UI provides a server-side directory browser and asynchronous progress.
Removal stops monitoring and releases the graph, without deleting source files. There is no
automatic restoration or persistence of the runtime project roster.

For known symbol IDs, `get_symbol_context` can combine selected evidence sections.
Its optional `detail: "locations"` omits display name/kind/signature while preserving
IDs, paths/spans, relationships, confidence, occurrence evidence and risk/coverage.
Full detail remains the default; keep detail unchanged when following cursors.

## Configuration

### Configuration scopes and precedence

| Scope | Where to configure | When it takes effect |
|---|---|---|
| Server listeners, startup roots, backend | Server arguments; root/HTTP-port environment fallbacks below | Startup; restart to change listeners or backend. |
| Project idle expiry and hybrid allowance | Startup arguments; root **Settings → Server** (Project idle timeout / Graph memory) | Admin changes apply immediately to existing and future projects, session-only. |
| Parsing filters, scoring, analysis rules, response limits | Each project's `code-graph.json` or `code-graph.yaml`; three explicit environment overrides | Loaded when a project is opened/onboarded. Remove/re-add the project or restart to reload. Reindexing alone does not reload configuration. |
| JVM heap and temporary directory | JVM arguments **before** `-cp` / `-jar` | JVM startup; independent of project configuration. |
| CI report/gating/publication | Headless CLI arguments and CI environment variables | That CLI invocation only. |
| Visualization filters/layout | UI controls | Browser-page state, not saved server configuration. |

There is **not** a generic mapping from every JSON property to an environment variable or
CLI flag. Precedence is specific:

- Startup roots: `--workspace` > repeated `--root` > nonblank `CODE_GRAPH_ROOT` > empty workspace.
- HTTP port: `--port` > `CODE_GRAPH_PORT` > `3000`.
- Project settings: the three supported environment overrides > project file > defaults.
  CLI `ci --threshold` and `ci --fail-on` override their respective loaded CI settings.
- TTL/cache budget: latest successful admin update > startup argument > default, for this
  session only. No corresponding project-file fields or dedicated environment variables exist.

### Environment variables

| Variable | Default / requirement | Effect |
|---|---|---|
| `CODE_GRAPH_ROOT` | Unset/blank means no startup root | One server-side root; used only without `--workspace` or `--root`. Not a list. The headless CLI instead defaults `--root` to `.`. |
| `CODE_GRAPH_PORT` | `3000` | HTTP MCP port fallback. Not used by stdio or the UI port. |
| `CODE_GRAPH_GATING_THRESHOLD` | Loaded `gating.threshold` | Integer `0–100`; overrides each project's risk threshold. |
| `CODE_GRAPH_LIMITS_MAX_RESULTS` | Loaded `limits.maxResults` | Integer `>=1`; overrides the configured result cap. |
| `CODE_GRAPH_LIMITS_MAX_RESPONSE_BYTES` | Loaded `limits.maxResponseBytes` | Integer `>=1024`; overrides the configured response-byte cap. |

The three project-setting overrides ignore blank values and reject non-integers. They apply
process-wide whenever project configuration is loaded. There is no built-in `.env` loader.

### Project idle timeout

`--project-ttl` accepts positive whole-number durations with lowercase `s`, `m`, `h` or `d`:
`90s`, `10m`, `1h`, `2d`. Default: `1h`. Zero, fractional durations, unitless values,
`never` and overflowing durations are not supported.

Project-specific evidence queries and explicit `reindex`, UI queries, relevant job
activity and active graph interaction renew the relevant project's timer. `list_projects`
and `index_status` (including bounded freshness waits),
UI roster/countdown refreshes, browsing, settings, an idle browser tab and automatic
file-watcher indexing do **not** renew it. The timer starts when onboarding is ready; active
requests and explicit reindex jobs are protected until completion.

Expiry checks run every five seconds. Expiry stops monitoring, removes routing and releases
the graph; hybrid mode also closes/deletes that project's owned disk store. Source files,
CLI snapshots and independent database mirrors are not deleted. Onboard the project again
to use it after expiry.

Admin **Settings → Project idle timeout (TTL)** changes recalculate deadlines from last activity for existing and future projects.
Shortening the timeout may expire idle projects on the next sweep. It is available even with
zero projects; changes are not written to disk and reset to the startup value on restart.

### Graph memory and disk storage

| Setting | Default | Valid values / behavior |
|---|---|---|
| `--graph-storage` | `memory` | `memory` or `hybrid`; server-wide, restart required to switch. |
| `--graph-memory` / **Settings → Graph memory (RAM)** | Raw HTTP/stdio: `1g`; installed `cgraph`: `1536m` = 1.5 GiB | Positive whole MiB/GiB sizes such as `32m`, `256MiB`, `1g`, `2GiB`; suffixes are case-insensitive. Minimum 32 MiB. No bare bytes, fractional sizes, `MB` or `GB` suffixes. |
| JVM `-Xmx` | JVM/environment-selected | Maximum Java heap, e.g. `-Xmx1g`. Not a graph-cache setting or a total-process RAM limit. |
| JVM `-Djava.io.tmpdir=PATH` | JVM temporary directory | Writable existing parent directory for temporary session storage; affects the whole JVM, not just graphs. |

Hybrid mode uses **MVStore directly**, without H2 SQL/JDBC, and stores graph records on disk
from the outset. A single shared cache retains hot node, adjacency and repeated search results;
it evicts older cached entries as needed. Streaming scans bypass that cache. Pure memory mode
does not evict graphs to disk when heap fills.

Effective cache capacity is:

~~~text
max(0, min(configured allowance, JVM maximum heap / 2)
       - 16 MiB temporary reserve
       - 1 MiB per active or staged store)
~~~

For example, `--graph-memory 1g -Xmx1g` with one active store gives a **495 MiB effective
cache**, not 1 GiB of cache. Each staged rebuild temporarily reserves another store.
New stores or budget reductions that cannot reserve metadata are rejected. **Settings → Graph memory (RAM)** shows
requested allowance, effective capacity, estimated use, disk bytes and cache hits/misses.
Shrinking the budget evicts cache entries immediately without removing projects or changing
their published generation. Increasing it allows on-demand cache growth.

This is **not a hard limit on total application RAM**. Cache weights, dirty-memory accounting
and reserves are estimates; parsing, decoded query results, engine metadata, JVM/native
allocations and OS file cache need additional memory. Use an OS/container memory limit if a
process-wide ceiling is required, with headroom for those allocations.

Hybrid indexing is serialized across projects and stages file fragments/name indexes on disk
rather than retaining a second complete Java graph. Ordinary saves update copy-on-write pages
in the existing file, then atomically publish a generation; readers continue using the prior
snapshot. Unchanged content is not reparsed. Declaration/module/configuration changes re-resolve
stored fragments without reparsing unrelated files. Directory/ignore changes, watcher overflow,
and batches exceeding 1,024 paths or 1 MiB of path text fall back to a full staged rebuild.
Rebuild fallbacks temporarily require space for two stores. See the
[incremental validation and measurements](docs/hybrid-incremental-delivery.md).

Safety bounds are currently fixed in code, not configuration options: source files up to
2 MiB in full/hybrid scans; serialized records up to 8 MiB; 32K-character storage keys;
20,000 materialized results with additional byte bounds; resolver candidate sets up to
10,000 / 8 MiB; up to four concurrent compound graph reads. Some whole-graph analyses still
materialize nodes and can fail these bounds. Exceeding a bound reports an error rather than
silently returning a complete-looking partial answer. `limits.maxResults` cannot raise these
storage safety bounds. See [hybrid storage details and measurements](docs/hybrid-graph-storage.md).

### Disk location and cleanup

By default, hybrid storage creates:

~~~text
<JVM temporary directory>/code-graph-session-<unique-id>/<unique-id>.mv
~~~

On this Windows setup the parent is normally `%TEMP%` (for example,
`C:\Users\<user>\AppData\Local\Temp`). To choose a disk, create a writable directory **outside
the projects you will onboard**, then set `java.io.tmpdir`:

~~~powershell
New-Item -ItemType Directory -Force -Path "D:\CodeGraphTemp"
java "-Djava.io.tmpdir=D:\CodeGraphTemp" -Xmx1g --enable-native-access=ALL-UNNAMED `
  -cp "code-graph-mcp-http\target\classes;code-graph-mcp-http\target\lib\*" `
  io.doindev.codegraph.mcp.http.HttpMain `
  --port 3000 --viz 8137 --viz-admin `
  --graph-storage hybrid --graph-memory 1g --project-ttl 1h
~~~

There is **no dedicated graph-storage-directory flag or UI setting**, no disk quota argument,
and no automatic project restoration. Use JVM temporary-directory configuration at startup.
Normal removal/expiry/workspace shutdown/JVM shutdown closes stores and removes owned files.
Forced termination or power loss can leave orphan session directories; they are neither
restored nor automatically swept. Cleanup does not recursively delete unknown files, sources,
or existing caches. Do not remove a session directory while its server is running.

### Admin UI and settings API

Admin controls are enabled by `--viz-admin` for HTTP, or by default for stdio unless
`--viz-readonly` is supplied. All controls have descriptive tooltips.

| Control | Purpose / availability |
|---|---|
| `+` | Onboard a project through the server-side directory browser; works with an empty workspace. |
| `⟳` | Reindex the selected project; disabled when no project is selected or a reindex is active. |
| `×` | Remove the selected project from this server; does not delete source files. |
| **Settings** gear | Opens the searchable server-settings dialog; available with zero projects and in read-only mode. |
| **Settings → Project idle timeout (TTL)** | Draft the shared idle timeout. Apply commits it; Cancel discards it. |
| **Settings → Graph memory (RAM)** | View telemetry; draft the shared allowance in hybrid mode. Editing is disabled in memory/read-only mode. |
| **Settings → MCP connection** | View/copy the local MCP endpoint, or see stdio transport information. Listener changes require restart. |

`GET /api/server` returns listener/admin information, `projectTtlSeconds` and `graphStorage`
telemetry. `PUT /api/settings` is admin-only and accepts either or both of these string fields
per request (maximum request body 1,024 bytes):

~~~json
{ "projectTtl": "10m" }
~~~

~~~json
{ "graphMemory": "256m", "projectTtl": "10m" }
~~~

The root page and DBA share compact dark toolbars, green accents, typography and controls.
The root Settings dialog has a searchable tree and a draggable vertical pane divider, also
operable with Left/Right arrow keys. Search matches node names and setting descriptions.
Drafts survive navigation and filtering; nothing is saved until **Apply**. Only changed fields
are sent, and both are validated before publishing TTL. A rejected RAM budget cannot partially
change the timeout. **Cancel** discards the draft; successful Apply closes the dialog.
With no projects, the welcome view offers Add project, MCP setup and DBA navigation when enabled.
See the [UI guide](docs/guides/visualization.md#shared-workspace-styling-and-settings).

Successful updates return refreshed server information. Invalid settings return HTTP 400;
read-only UI mutation attempts return HTTP 403. These settings affect the running session,
not `code-graph.json`, the workspace file or future restarts.

Visualization controls are separate from server policy: 3D/2D renderer (default 3D);
ego depth 1–4 (default 2); galaxy node-cap slider 100–5,000 (initially 1,500, may auto-tune
downward); minimum edge confidence 0–1 (default 0, step 0.05); optional language filter.
Effective galaxy requests are additionally capped at 2,500 nodes in 3D and 4,000 in 2D.
Node dragging/pins, release pins, reset view and camera controls affect rendering only.
These page-local choices do not alter the index or persist as server settings.

### Per-project JSON/YAML reference

Place exactly one `code-graph.json` or `code-graph.yaml` in each onboarded project's root.
No file means built-in defaults. JSON accepts Java-style comments and trailing commas; YAML
uses the same structure. Both files together are an error. Unknown record properties are
rejected. Free-form map keys (such as smell IDs and threshold names) are not validated against
the detector catalog: misspelled keys can have no effect.

**Important defaulting rule:** omitted *sections* receive defaults, but supplied sections are
not deep-merged with those defaults. In a supplied section, omitted lists/maps generally become
empty and omitted primitive numbers/booleans become `0`/`false`. For example,
`"limits":{"maxResults":50}` fails validation because `maxResponseBytes` is missing;
`"tests":{}` disables the default test globs; `"smells":{"god-class":{}}` disables that detector.
Supply complete scalar settings for each section you customize.

| Property | Default when its section is absent | Meaning / constraints |
|---|---|---|
| `paths.include` | `["**"]` | Repo-relative globs to index. An empty include list also means all supported files. |
| `paths.exclude` | `[]` | Exclude matching paths; exclusions and ignored directories override includes. |
| `tests.globs` | See exact list below | Identify tests for blast scoring and dead-code exclusion. |
| `limits.maxResults` | `50` | Configured result cap where a tool consults it; integer `>=1`. Tool-specific defaults/caps still apply. |
| `limits.maxResponseBytes` | `32768` | Configured UTF-8 payload cap in capped analysis responses; integer `>=1024`. Oversized payloads return an error requesting a narrower query. Not an HTTP-body or heap limit. |
| `scoring.weights` | `reach:0.40, fanin:0.25, spread:0.20, lang:0.05, untested:0.10` | Blast factors. Missing individual weight keys use the corresponding built-in factor weight. Use nonnegative weights totaling 1; they are not automatically normalized. |
| `scoring.reachRef` | `1000` | Positive normalization reference for transitive dependents. Supply explicitly when customizing `scoring`. |
| `scoring.faninRef` | `100` | Positive normalization reference for direct callers/references. Supply explicitly when customizing `scoring`. |
| `gating.threshold` | `70` | Integer `0–100`; scores at/above this value trigger configured risk gating. |
| `gating.attachRiskReport` | `true` | Attach risk reports to supported target-oriented MCP responses at/above threshold. |
| `gating.failCiOn` | `["blast","drift"]` | CI gate categories. Use `[]` to disable both CI gates; smell findings do not form a CI failure category. |
| `deadCode.entryPoints` | `[]` | Path globs for entry points that must not be reported as dead code. |
| `deadCode.exclude` | `[]` | Additional path globs to exclude from dead-code analysis. |
| `smells` | `{}` | Per-detector overrides; absent detector entries use enabled/default severity/default thresholds. |
| `smells.<id>.enabled` | Enabled when the detector entry is absent | Explicit boolean required when creating an enabled override entry. |
| `smells.<id>.severity` | Detector default; `warning` if an override entry omits severity | Reported severity; use `info`, `warning` or `error`. Embedded SQL can promote `info` for higher-risk findings. |
| `smells.<id>.thresholds` | Detector defaults per missing key | Numeric map using the exact names in the detector table below. |
| `architecture.modules` | No blueprint | Array of `{"name":"...","paths":["..."]}`. Names must be nonblank; path matching is first-match-wins. |
| `architecture.allowedDependencies` | No layer restrictions | Map from module name to allowed target names. An empty map skips layer enforcement. With a nonempty map, a declared source module absent from it may depend on no other module. Undeclared fallback modules are not layer-governed. |
| `architecture.forbidCycles` | Cycle checking on when the whole section is absent | Set `true` explicitly in a supplied architecture section to check module cycles; omitted in that section means `false`. |
| `architecture.unassigned` | `ignore` without a blueprint; `warn` in a supplied section | Policy label (`warn`, `ignore`, `violation`). Currently controls/report-labels unassigned-file metadata; `violation` does not itself create a drift violation or fail CI. |

Default `tests.globs`:

~~~json
[
  "**/src/test/**", "**/*.test.ts", "**/*.test.js", "**/*.spec.ts",
  "**/test_*.py", "**/*_test.py", "**/*_test.go", "**/*Tests.cs", "**/*Test.java"
]
~~~

Globs match full repo-relative paths using `/` separators: `**` crosses directories, `*`
and `?` do not, and `**/` can match zero directories. Layered `.gitignore` rules also apply.
Full/hybrid scans skip unsupported extensions and files larger than 2 MiB. These directories
are always ignored regardless of includes:

~~~text
.git .hg .svn node_modules target build dist out __pycache__ .venv venv
.idea .vscode .code-graph vendor bin obj
~~~

### Smell detector settings

Set overrides under `smells.<id>`. Threshold values below are built-in fallbacks, not mandatory
configuration. Detectors require the relevant language/graph evidence; enabling one does not
guarantee findings for every language.

| Detector ID | Default severity | Threshold names and defaults |
|---|---|---|
| `god-class` | `warning` | `methodCount:25`, `fieldCount:15`, `loc:500` |
| `long-method` | `warning` | `loc:75`, `cyclomatic:15`, `nesting:5` |
| `long-parameter-list` | `info` | `paramCount:6` |
| `large-file` | `info` | `loc:1000` |
| `high-fan-out` | `info` | `fanOut:25` |
| `hub` | `warning` | `minFanEach:10`, `fanProduct:400` |
| `cyclic-files` | `warning` | No configurable numeric threshold |
| `unstable-dependency` | `info` | `instabilityGap:0.5` |
| `feature-envy` | `info` | `minForeignCalls:3` |
| `data-clumps` | `info` | `minGroupSize:3`, `minOccurrences:3` |
| `refused-bequest` | `info` | `minParentMethods:5`, `maxUsageRatio:0.2` |
| `temporal-coupling` | `info` | `minCoChanges:5`, `minConfidence:0.6` |
| `duplicated-logic` | `warning` | `minSharedShingles:8` |
| `embedded-sql` | `info` | No configurable numeric threshold |

The last three require a repo root. Normal server wiring supplies it; temporal coupling also
requires Git history. Headless `ci` currently constructs the graph-only smell engine, so these
three detectors are not included in its report. Unknown IDs/threshold keys do not activate
new detectors. [Detector behavior and limitations](docs/guides/smells.md).

### Complete project configuration example

This example explicitly supplies all section fields; its filters, threshold and blueprint
are illustrative overrides, not the defaults:

~~~json
{
  "paths": {
    "include": ["**"],
    "exclude": ["**/generated/**"]
  },
  "tests": {
    "globs": ["**/src/test/**", "**/*.test.ts", "**/*.test.js", "**/*.spec.ts",
              "**/test_*.py", "**/*_test.py", "**/*_test.go", "**/*Tests.cs", "**/*Test.java"]
  },
  "limits": { "maxResults": 50, "maxResponseBytes": 32768 },
  "scoring": {
    "weights": { "reach": 0.40, "fanin": 0.25, "spread": 0.20, "lang": 0.05, "untested": 0.10 },
    "reachRef": 1000,
    "faninRef": 100
  },
  "gating": { "threshold": 80, "attachRiskReport": true, "failCiOn": ["blast", "drift"] },
  "deadCode": { "entryPoints": ["**/Main.java", "**/*Application.java"], "exclude": ["**/api/**"] },
  "smells": {
    "god-class": {
      "enabled": true,
      "severity": "warning",
      "thresholds": { "methodCount": 25, "fieldCount": 15, "loc": 500 }
    }
  },
  "architecture": {
    "modules": [
      { "name": "api", "paths": ["src/main/java/com/acme/api/**"] },
      { "name": "domain", "paths": ["src/main/java/com/acme/domain/**"] }
    ],
    "allowedDependencies": { "api": ["domain"], "domain": [] },
    "forbidCycles": true,
    "unassigned": "warn"
  }
}
~~~

## Headless CLI configuration

The separate `code-graph-cli` module produces
`code-graph-cli/target/code-graph.jar`. Build it with
`mvn -pl code-graph-cli -am package`, then use:

~~~powershell
java --enable-native-access=ALL-UNNAMED -jar "code-graph-cli\target\code-graph.jar" index --root C:\repos\api
~~~

The headless CLI supports `--flag value` and `--flag=value`; unknown flags are rejected.
It uses the in-memory indexing path, not server hybrid/TTL/listener arguments.

| Command | Arguments and defaults |
|---|---|
| `index` | `--root DIR` (default `.`); `--snapshot-out FILE` (optional binary graph snapshot; no snapshot written if omitted). |
| `check` | `--target SYMBOL_ID_OR_PATH` (required); `--root DIR` (default `.`). Prints target blast score and relevant drift findings. |
| `ci` | `--base REF` (required); `--head REF` (default `HEAD`); `--root DIR` (default `.`); `--report FILE` (otherwise stdout); `--format md|json` (default `md`); `--threshold N` (`0–100`, otherwise loaded gating threshold); `--fail-on blast,drift` (otherwise loaded `gating.failCiOn`); `--github-comment` / `--gitlab-comment` (both off); `--pr N` (otherwise platform environment). |

An empty `--fail-on` value falls back to configured categories; use `gating.failCiOn: []` in
the project file to disable gating. `ci` resolves refs/diffs against Git but indexes the
current working tree as its head graph: check out the intended head before invoking it.
Baseline snapshots are cached at `<root>/.code-graph/snapshots/<merge-base-sha>.snap` and are
independent of hybrid temporary files and project TTL. There is no snapshot-directory flag.
Report and snapshot output paths resolve from the process working directory.

CI exit codes: `0` pass; `1` execution error; `2` blast gate failure; `3` drift gate failure;
`4` both; `64` usage error. Reports use Markdown for PR/MR comments even with `--format json`.

### CI publication environment

These variables are read only when the corresponding publication flag is enabled. Keep
tokens in environment/secret storage, not project configuration.

| Platform | Variable | Meaning / default |
|---|---|---|
| GitHub | `GITHUB_TOKEN` | Required token with permission to write the target PR's issue comments. |
| GitHub | `GITHUB_REPOSITORY` | Required `owner/repository`. |
| GitHub | `GITHUB_REF` | PR number fallback from `refs/pull/<n>/merge` (overridden by `--pr`). |
| GitHub | `GITHUB_API_URL` | API base; default `https://api.github.com`. |
| GitLab | `GITLAB_TOKEN` | Preferred token, sent as `PRIVATE-TOKEN`. |
| GitLab | `CI_JOB_TOKEN` | Fallback when `GITLAB_TOKEN` is blank/unset; sent as `JOB-TOKEN`. |
| GitLab | `CI_PROJECT_ID` | Required project identifier. |
| GitLab | `CI_MERGE_REQUEST_IID` | Required MR IID unless `--pr` is supplied. |
| GitLab | `CI_API_V4_URL` | API base; default `https://gitlab.com/api/v4`. |

See [CI workflow examples](docs/guides/ci.md) for report/comment integration.

## Container and embedded deployments

The checked-in [Dockerfile](code-graph-mcp-http/Dockerfile) **explicitly supplies**
`--root /workspace --port 3000`. Unlike a bare JVM launch, that image therefore onboards
`/workspace` by default. Its baked-in `--port 3000` also takes precedence over
`CODE_GRAPH_PORT` or an additional appended `--port`. Override the entrypoint when choosing
a different root/port or starting empty.

After building the HTTP module, build the image from the repository root:

~~~text
docker build -t code-graph-mcp-http -f code-graph-mcp-http/Dockerfile code-graph-mcp-http
~~~

Example empty hybrid server inside the container's network namespace, using an explicit
entrypoint (PowerShell). This is not reachable through published host ports; run Java locally
for desktop MCP clients, or run the client in this same network namespace:

~~~powershell
docker run --rm --name code-graph-local `
  --entrypoint java code-graph-mcp-http `
  -Xmx1g --enable-native-access=ALL-UNNAMED -cp "/app/classes:/app/lib/*" `
  io.doindev.codegraph.mcp.http.HttpMain `
  --port 3000 --viz 8137 --viz-admin --graph-storage hybrid --graph-memory 1g
~~~

Mount source directories before onboarding them and use their **container-side** paths.
The image runs as `codegraph`, so mounted sources must be readable and any chosen temporary
storage mount writable by that account. JVM options still precede `-cp`; choose a disk-backed
temporary mount rather than a memory-backed tmpfs if the goal is offloading RAM. Container
limits and swap policies are deployment settings, not `--graph-memory`.

Custom applications can supply MCP transports through `CodeGraphMcpServer.serve(...)` and
provide workspace/control objects programmatically. H2 SQL and Neo4j `GraphStore` mirror
adapters are separate library integrations (database path/JDBC URL or driver/URI/auth);
they are not selectable through the server's `--graph-storage` flag. The ArangoDB module
currently provides dependency scaffolding, not a configurable server backend. No database
mirror URL/password settings exist in the standard server's JSON, environment or UI.

## Development-only benchmark configuration

The [MCP precision/efficiency guide](docs/mcp-precision-efficiency.md) documents
build/catalog diagnostics, byte-aware navigation pages, distinct call graphs,
static JS/TS module resolution, adaptive workflow benchmarks, and optional agent
skill guidance. Current acceptance evidence is in the
[delivery checklist](docs/mcp-efficiency-delivery.md).

The `storage-benchmark` Maven profile opts into the isolated
`code-graph-storage-benchmark` module; its experimental dependencies are not included in the
normal runtime. See its [README](code-graph-storage-benchmark/README.md) for workload/backend,
budget, run-count and output-directory switches and reproducible commands. The production
storage stress test is opt-in with `-Dhybrid.stress=true`; its test JVM defaults to
`-Xmx256m` through `hybrid.test.heap` (the stress test asserts a maximum of 256 MiB).
`-Dhybrid.repository=<absolute-path>` enables the real-repository indexing test. These are
test/build properties, not production startup settings.
