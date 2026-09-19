# Native MongoDB, Redis and SQL Server delivery plan

Status: implementation in progress. Date: 2026-09-18.

This is a delivery checklist, not a declaration of implemented or tested support.
The target is first-class UI and MCP workflows, including infrastructure
administration. Every advertised operation must have a version/edition-specific
capability and evidence. Unknown features fail explicitly; JDBC connectivity is
not evidence of native feature support. Existing JDBC profiles keep their IDs,
credentials, bindings and behavior. No automatic conversion to native profiles.

## Decisions and invariants

- Native MongoDB Java driver, native Redis client, Microsoft JDBC for SQL Server.
  Native connections are separate transports, not simulated JDBC drivers.
- MongoDB SQL Interface and Redis Calcite remain explicit compatibility templates.
- Reuse profile/vault, exact-target authorization, sessions, jobs, auditing,
  environment bindings, UI workspace ownership and resource accounting.
- MongoDB scopes use database/collection; Redis uses topology, logical database
  and permitted keys/patterns; SQL Server uses database/schema/object. No invented
  SQL schema requirement for document/key-value targets. Redis Cluster targets
  database 0 only. A discovered node or referenced object grants no permission.
- Normal approvals, reusable read policies, headless reduction and startup-only
  YOLO remain consistent. Native writes cannot masquerade as SQL reads.
- Unsafe/unknown commands never gain reusable read permission. MongoDB $out,
  $merge, executable expressions, Redis scripts/functions, module/admin commands
  and SQL Server execution-based plans receive explicit operation classification.
- Do not execute local shell commands, accept licences, create paid resources,
  or expose Docker through application/MCP features.
- Never automatically replay uncertain writes or infrastructure operations.
  Cancellation is best-effort and must report effects/unknown outcomes honestly.
- Keep 100-row/1-MiB agent limits, bounded UI results and global DBA admission.
  Bound BSON/RESP decoding, nested depth, oversized cells and event queues as
  well as retained results. SCAN COUNT and Mongo batchSize are not byte limits.
- Native pools, cursor leases, change streams, subscriptions and buffers count
  toward telemetry; driver/native/OS overhead remains explicitly separate.
  The graph allowance does not automatically fund DBA work or cap server RAM.
- Do not pin complete databases/keyspaces or create a second graph index.
- Secrets remain write-only and in the vault, including native URI credentials,
  certificates, tokens, ACL passwords and cloud authentication material.

## Phase 0: baseline and self-hosted navigation

- [x] Inventory existing support: Mongo SQL Interface, Redis Calcite, partial
  SQL Server JDBC/catalog/estimated-plan contracts; advanced workflows unverified.
- [x] Start isolated copy of existing build: MCP 3000, UI 8137, hybrid 1 GiB,
  normal desktop approvals; no production profile directory or YOLO.
- [x] Onboard this repository through MCP; record generation, counts and coverage.
- [x] Use MCP symbol/impact/navigation tools during implementation and exercise
  watcher refresh after changes; verify ordinary graph regressions.
- [x] SQL Server Developer EULA authorized by user for disposable local tests only.
- [ ] Record baseline resource/performance evidence and all pre-existing Docker
  IDs before downloading new images; never delete pre-existing resources.

## Phase 1: common transport and native safety foundation

Implemented for the currently advertised standalone adapters; advanced topology,
authentication and operation coverage remains tracked in later phases.

- [x] Introduce typed transport/operation/capability registry shared by UI/MCP.
- [x] Add native profile validation, endpoint/TLS/auth options, vault integration,
  test-before-save and explicit save-untested with compatible JDBC persistence.
- [x] Lazily initialize bounded clients; idle cleanup; close on remove/change.
- [x] Generalize asynchronous job cancellation/resources without introducing
  a second unbounded executor or approval queue.
- [x] Implement structured native requests (not JavaScript evaluation, shell
  execution or redis-cli invocation); exact nested command classification.
- [x] Bind reviewed operations to target/profile revisions, requesting session,
  typed payload hash and expiry; audit before execution; stale-plan protection.
- [x] Native result envelope preserves BSON types and binary Redis values;
  complete/truncated/partial/cancelled/uncertain outcomes are distinct.
- [x] Native MCP tools advertise typed inputs/outputs, actual capabilities,
  read/write/destructive annotations and restrictions. Existing tools unchanged.
- [x] Focused profile, classifier, resource, redaction and ownership tests pass.

## Phase 2: MongoDB developer and database workflows

- [ ] Standalone, replica-set, SRV and sharded-router connections; SCRAM/TLS,
  optional client certificates and supported externally provisioned auth modes.
- [ ] Lazy databases/collections/views/indexes tree and bounded metadata.
- [ ] BSON/Extended JSON document and grid views preserving ObjectId, dates,
  Decimal128, int64, binary, missing/null and nested arrays/objects.
- [ ] Find/filter/projection/sort, aggregation pipeline editor and builder,
  queryPlanner Explain; executionStats requires explicit execution intent.
- [ ] Insert/update/replace/delete/bulk, optimistic conflicts and exact review.
- [ ] Collection create/rename/drop, validators, indexes/TTL indexes, view
  definitions, supported time-series/capped settings with restrictions visible.
- [ ] Sessions and transactions on verified topologies; bounded change streams
  with resume tokens, backpressure, gaps and cleanup. No implied stream snapshot.
- [ ] GridFS bounded upload/download and metadata workflows with file authority
  explicit; do not turn a database tool into unrestricted filesystem access.
- [ ] Schema sampling labels inferred/mixed/missing types and sample coverage;
  definition capture/compare, migration plans and synthetic-data rehearsals.
- [ ] Code mappings for statically recoverable Mongo queries and common Java/JS
  mappings, confidence and incomplete dynamic-query coverage.
- [ ] UI/MCP integration, errors, permissions, concurrent updates and live tests.

## Phase 3: Redis developer and database workflows

- [ ] Standalone, Sentinel and Cluster connectivity; ACL auth/TLS; isolated
  transactional/blocking/subscription connections and topology refresh.
- [ ] Incremental keyspace browsing, cluster-wide cursors, bounded duplicate
  handling, empty pages and clear non-snapshot semantics; never automatic KEYS *.
- [ ] Binary-safe key/value previews and editors for strings, hashes, lists,
  sets, sorted sets, streams; TTL, rename, deletion and conditional writes.
- [ ] Bitmaps, bitfields, HyperLogLog, geospatial operations; detected JSON,
  search/vector/time-series/probabilistic capabilities where available. Do not
  pretend storage representation proves the semantic key type.
- [ ] Native command editor, bounded pipelines and WATCH/MULTI/EXEC with no
  rollback promise; scripts/functions reviewed as executable code.
- [ ] Pub/Sub and stream consumer groups with bounded buffers, explicit ACK and
  destructive-consumption behavior, cancellation/disconnect cleanup.
- [ ] INFO, latency, memory/slowlog and index/query diagnostics where supported;
  no invented relational Explain or unbounded MONITOR subscription.
- [ ] Capture/compare keyspace conventions, TTL policies and supported index
  definitions; bounded synthetic-fixture rehearsals, not copied user records.
- [ ] Namespace-aware code mappings and contract checks with explicit uncertainty.
- [ ] UI/MCP integration, cluster slots, failover, permissions and live tests.

## Phase 4: SQL Server developer and database workflows

- [ ] SQL auth/TLS; verified Windows integrated/Kerberos/Entra modes with
  actionable prerequisites and separate unavailable-environment test reporting.
- [ ] Databases, schemas, tables, views, indexes, constraints, computed/identity
  columns, sequences, synonyms, types, routines, triggers, permissions/dependencies.
- [ ] T-SQL batches, GO boundaries, variables, stored routines, output parameters,
  multiple results/notices, cancellation and transaction/error semantics.
- [ ] Full-fidelity supported DDL extraction; encrypted/inaccessible modules
  reported incomplete rather than empty/removed.
- [ ] Native create/alter designer and reviewed migration adapters; no silent
  drop/recreate/cascade; temporal, partitioned, indexed-view and special table
  features individually capability-gated, preserving populated fixtures.
- [ ] Schema capture/compare, code contracts, synthetic rehearsal, fingerprints,
  target isolation and partial/uncertain outcome reconciliation.
- [ ] SHOWPLAN_XML estimated plans, normalized comparison and evidence-based
  observations; Query Store/DMV diagnostics where authorized; actual execution
  plans only with explicit execution review.
- [ ] SQL Server and Azure SQL capabilities remain separate, not assumed equal.
- [ ] Disposable live integration and full UI/MCP workflows.

## Phase 5: infrastructure administration (included, not silently deferred)

All operations need structured vendor-specific plans with exact servers/nodes,
affected namespaces, before/after settings, prerequisites, privileges, downtime,
data-loss/retention risks and recovery steps. Server-wide effects use server-wide
authorization scopes, never an ordinary application read grant. Inspection and
mutation are separate capabilities. YOLO supplies consent, not missing setup
intent, licence acceptance, plan validity or filesystem authority.

- [ ] Mongo: users/roles, replica membership/stepdown, sharding inspection and
  reviewed supported changes, server parameters, backup/restore workflows.
- [ ] Redis: ACL users, persistence configuration/snapshots, replication,
  Sentinel failover and Cluster membership/slots; backup/restore and explicitly
  reviewed flush operations. No automatic force failover or unsafe migration.
- [ ] SQL Server: users/logins/roles, SQL Agent jobs/schedules/history, backup
  and restore (files are on the server), recovery/configuration, replication and
  availability/failover operations supported by the detected edition/platform.
- [ ] Backup export/import location and overwrite authority must be explicit;
  validate server paths and off-host implications. No automatic workstation
  shell/process tools. Features needing vendor utilities use documented explicit
  external handoff until an authorized bounded integration is implemented.
- [ ] Managed-service control-plane APIs and enterprise-only capabilities get
  separate adapters and credentials; no implicit cloud provisioning or payments.
- [ ] Long-running admin actions have durable server operation references where
  available; disconnect/timeout is not proof of cancellation or rollback.
- [ ] Test destructive/restore/failover paths only on owned disposable topologies.
  Features without required editions/topologies/accounts remain blocked, not pass.

## Phase 6: UI, MCP, performance and completion

- [ ] Native connection templates, comprehensive configuration, test modal,
  capability-based trees, context menus/icons, confirmation and workspace recovery.
- [ ] Native object tabs, resizable editors/results and reusable grid controllers;
  no SQL transformations on native queries. Preserve layout/schedules per result.
- [ ] Agent tools for discovery, metadata, read/write requests, plans, admin
  review/status/cancel/release, exact-target scopes and environment policies.
- [ ] Keep editor pairing explicit; native agent edits do not execute commands.
- [ ] Unit tests for malicious/ambiguous native payloads, nested effects, target
  escapes, stale policies/plans, redaction, expiry, cancellation and audit failure.
- [ ] Sequential disposable integration fixtures: Mongo standalone/replica/shards,
  Redis standalone/Sentinel/Cluster and supported module variants, SQL Server
  Developer; pin versions, image digests and client libraries in evidence.
- [ ] Browser accessibility, keyboard, state recovery, dialogs, native results,
  approvals, reduced-headless tooling and YOLO regressions.
- [ ] Three independent performance runs: p50/p95/p99, throughput, queue wait,
  heap/GC/process/native/retained memory, result bytes, cursor/client cleanup and
  concurrent graph indexing under the 1 GiB graph + separate DBA allowance.
- [ ] JavaScript checks, DBA/HTTP/stdio/installer/browser suites, Maven reactor.
- [ ] Update README, tool/API/security/recovery docs, versioned capability
  matrix and shared agent skill; no claim of literal all-version 100% support.
- [ ] Final acceptance report distinguishes implemented+live-tested,
  implemented+contract-tested, unsupported and blocked features per engine.
- [ ] Remove only owned containers/volumes and introduced unused images;
  preserve existing resources. No commit/push or production database mutations.

## Primary references

- Mongo Java: https://www.mongodb.com/docs/drivers/java/sync/current/
- Mongo write pipelines: https://www.mongodb.com/docs/v8.0/reference/operator/aggregation/merge/
- Redis Java: https://redis.io/docs/latest/develop/clients/lettuce/
- Redis cursors: https://redis.io/docs/latest/commands/scan/
- Redis transactions: https://redis.io/docs/latest/develop/using-commands/transactions/
- Redis Cluster: https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/
- SQL Server containers: https://learn.microsoft.com/en-us/sql/linux/sql-server-linux-docker-container-deployment
- SQL Server JDBC: https://learn.microsoft.com/en-us/sql/connect/jdbc/setting-the-connection-properties

## Evidence log

- Existing build: isolated copied runtime under a uniquely named OS temporary
  directory, PID 27904 initially; ports 3000/8137; hybrid 1073741824-byte budget.
- Existing Docker containers preserved: code-graph-mysql-local-20260916,
  code-graph-mysql-agent-test, code-graph-postgres-test. Existing Redis 7.4.1 image
  and other unrelated images are not owned by this work.
- MCP onboarding produced generation 1, 438 files, 5,460 symbols and 62,817 edges.
  Search located DbaRuntime/AgentRequests, dependency traversal located JDBC
  callers, and watcher updates reached generation 17 without manual reindexing.
  New NativeTarget symbols were retrievable through MCP. Broad generic-name
  dependency matches remain candidate evidence, not proof of exact runtime calls.
- Phase 1 partial: transport identity, exact native targets, conservative command
  classifier, native profile drafts, native client leases and initial bounded
  read adapters are implemented. These are not yet advertised as full UI/MCP
  support. Native clients pinned to MongoDB Java 5.12.0 and Lettuce 7.7.0.RELEASE.
- NativeFoundationTest (11) and ConnectionSetupTest (12) passed. NativeVendorTest
  passed separately against owned MongoDB 8.0 and Redis 7.4.1 fixtures: ephemeral
  draft/version checks, no test credential/profile writes, receipt ownership,
  typed BSON int64/ObjectId/date results, limits, estimated Mongo plans, binary
  Redis previews, bounded string reads, SCAN and rejection of writes via reads.
- Mongo image digest used:
  sha256:4968f22d0c6c10ef29952f3e807f62872ba22b3312f25803564fbfc08255efc2.
  Redis image was pre-existing:
  sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275.
  Both owned containers and their anonymous volumes were removed after tests;
  the newly pulled Mongo image was removed, and the existing Redis image retained.
- Added native single-target CRUD, browser review plans, the native MCP request
  contract, application/environment binding resolution, native capability reporting,
  lazy metadata tree and session-recovered native workspaces. Redis range/cursor
  adapters cover hashes, lists, sets, sorted sets and streams without whole-container
  reads. Read-only profiles still block mutations even under YOLO.
- Native normal approvals reject forged persistent choices and stale targets.
  NativeReviewTest checks browser ownership, command integrity, revision invalidation
  and reservation cleanup. AgentRequestsTest checks native binding scope/revisions.
- MongoDB live workflow tests additionally passed creation, insert, update, index
  create/drop, single-document delete, collection drop, metadata and live version
  capability observation. Redis CRUD and range/cursor tests passed after correcting
  reverse-stream range translation. SQL Server module/table DDL inspection passed.
- SQL Server 2022 Developer digest:
  sha256:4402d880dd4c34bfa7d8705e56a86cd6c88da80a1f6bbbe741f999e76264a090.
  Microsoft JDBC 13.4.0.jre11. Owned containers/volumes and the introduced unused
  image were removed. Mongo test images were also removed after each owned run.
- Full Maven reactor passed in an isolated source snapshot after updating removed-
  profile tests to use a pre-existing observer; new connections through a deleted
  profile correctly fail. Native browser setup, tree navigation, typed rendering,
  session recovery and explicit review/cancel/apply passed.
- Graph dogfooding reached generation 50 with 457 files, 5,671 symbols and 64,604
  edges, with no pending changes. New native classes were discoverable through MCP.
- Pending: advanced administration/topology, native reusable policies, full binary
  editing and object editors, transactions/change streams/PubSub, native snapshots,
  mappings/rehearsals, remaining SQL Server operations, and complete performance/
  interactive-platform certification. This delivery is not 100% support.
- Latest isolated Maven package/reactor test reports: 667 tests discovered, 626 passed, zero failures/errors,
  41 skipped by explicit opt-in/environment guards. Live-vendor gates are run
  separately; skips are not claimed as passes.
- Native HTTP/MCP ownership, CSRF and request-state regression passed. Normal
  approval live tests now verify writes remain pending before approval, persistent
  choices are rejected, and independent read requests still require review.
  MongoDB view creation/read/definition edit/drop and Redis SET NX/XX with explicit
  TTL options passed against the pinned owned fixtures.
- Unsaved native tests reserve 64 MiB before client creation, including tests
  nested in connection-creation requests. Added admission/idempotence/release tests.
  Removing a connection disables its open native workspaces while retaining drafts;
  browser recovery and disabled execution were verified.
- Native capability discovery supports both exact bindings and standalone targets,
  checks profile/binding revisions again before live observation, and holds the
  bound project during its active job.
- The full browser pass exposed a missing static-module allowlist entry in the
  restricted approval site. Only the native connection-editor module was added;
  native workspace and execution APIs remain denied, covered by regression tests.
  The subsequent complete browser run passed, including isolated approval review,
  all graph-independent DBA workflows, keyboard operation and session recovery.
- Shared skill frontmatter validation and all three installation-helper tests
  passed. DBA JavaScript syntax checks passed. MongoDB/Redis/SQL Server harnesses
  now select exact recorded digests rather than mutable version tags.
- Windows bootstrap checks and 34 Java installer checks passed. No user PATH,
  installation, MCP configuration or running application was changed by these tests.
- Native results now include read-only JSON/Grid switching using the existing
  DataGridView. Browser checks cover precision, missing/null distinction, HTML
  escaping, 256-field bounds, resizing/reordering and absent SQL actions.
- Redis pre-decode RESP structural limits passed fragmented RESP2/RESP3 tests
  and the live Redis fixture. NativeMemoryTest now has seven passing cases,
  including excessive array length, nesting and aggregate value counts.
- Graph navigation found the new RESP guard at generation 103 without reindexing.
- Packaged-runtime smoke passed on isolated ports 13000/18137: root UI, DBA UI
  and MCP initialization returned 200; both native templates and the native MCP
  tool were discovered. The test process and its profile directory were removed.
- Added explicit canonical-base64 Redis key/value arguments, binary/empty-key
  tree identities, typed MCP descriptions and normal-approval live round trips.
  Control syntax stays text; key/field and value input bounds are enforced.
- Added reproducible three-JVM native measurements and retained all raw samples,
  including rejected uncapped diagnostic runs. Capped Mongo before/after evidence
  supported sixteen-document raw batches: managed p95 improved from 131–178 ms
  to 18–20 ms for this specific workload. The forty-oversized-document live test
  passed with a 256 MiB heap. Redis capped p95 was 15–16 ms. See
  [the performance checkpoint](native-database-performance.md) for exact results,
  accounting, caveats and still-incomplete performance gates.
- Final complete browser regression passed after binary Redis changes, including
  typed command review, exact base64 preservation and session recovery. The final
  packaged-runtime smoke also passed again. NativeFoundationTest has fifteen
  passing cases and DbaToolSchemaTest five; full reactor totals above include them.
- Final cleanup check found no ownership-labelled native test containers and no
  introduced MongoDB/SQL Server images. Existing containers/images were preserved.
  Graph MCP found NativeRedisArguments at generation 122 without manual reindexing.
  The self-hosted baseline was not replaced, and no commit/push was performed.
