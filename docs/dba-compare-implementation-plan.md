# DBA database compare: implementation plan and engineering validation

Validated 2026-09-24 against source revision `3dc7a5a66b59694980e4ff9bfbe6eceee12120d9`.

Status: implemented in the working tree, with the capability boundaries and execution evidence below. The original engineering baseline is retained separately from feature validation. Unsupported operations are explicit blockers; this is not a universal database restore engine.

## Agreed product behavior

- Add Database Compare to `/dba`, using a dedicated workspace tab and four steps:
  Databases, Objects and Options, Review Differences, Generated Script.
- Select source and destination connections and databases, then one schema pair
  or All schemas. Test each connection on selection and validate the selected
  database/schema whenever it changes. Next requires success for both current
  selections; stale responses cannot enable it.
- Compare the same observed SQL engine. Give every SQL connection template an
  explicit capability entry; unknown/read-only/unsupported gateways never gain
  script generation merely because JDBC connectivity succeeds. Cross-engine
  translation and native MongoDB/Redis comparison are outside this feature.
- Whole-database comparisons match schema names and can create missing source
  schemas in the existing destination database. A selected schema pair maps the
  source schema to the selected destination schema.
- Choose object types and optional per-table data/key settings, then run the comparison before opening review. The review groups source-only, different, destination-only, identical and unsupported objects. Its resizable split view provides individually selectable column/constraint/index changes, side-by-side source/destination definitions, actual row counts and paged row differences, and a planned-change preview.
- The interaction follows the schema-comparison review pattern illustrated in [Quest's Toad schema comparison walkthrough](https://blog.quest.com/product-post/how-to-compare-two-database-schemas-in-oracle-using-toad/) and [Toad for Oracle 2026 R1 User Guide](https://support-public.cfm.quest.com/82278_ToadForOracle_2026_R1_UserGuide.pdf), using the application's own theme and adapting available controls to each engine.
- Group discovered objects by type, with search, category and individual
  selection, and missing/changed/unchanged/unsupported/blocked status. Default to
  supported source objects. A selected unsupported object must be deselected;
  incomplete scripts cannot be presented as successful results.
- Create missing objects and update selected existing objects. Preserve unrelated
  destination objects. Do not infer renames. Destructive schema changes are off
  by default and require the user to enable the option or deselect the change.
- Only supported table objects offer Include data, initially off. Ordinary and
  materialized views have no data checkbox. Materialized-view population uses
  the destination's tables after relevant data loading, where supported.
- Data modes: insert missing; insert and update (default); replace all rows;
  exact mirror including removal of destination-only rows. A default applies to
  included tables, with per-table overrides. Data replacement/deletion is an
  explicit data-mode choice, separate from destructive schema changes.
- Add Sync sequence values, off by default, with a default and per-sequence
  overrides. When enabled, default to safe advancement; offer matching captured
  source state only when that capability is actually available. Include
  identity-backed generators where the engine permits inspection and adjustment.
- Show a large read-only scrollable textarea. Copy and Save operate on the
  complete generated artifact; oversized results have a clearly labeled preview.
  Cancel closes the comparison and disposes of retained state and artifacts.
  There is no Execute/Apply action and no automatic user-file save.
- Any compare-flow modal fills `calc(100vw - 32px)` by `calc(100dvh - 32px)`, with
  the existing fixed modal maximum widths overridden. At widths below 640px,
  use the full viewport. Header/footer remain visible; the content area and
  textarea use the remaining space and scroll. Controls wrap at narrow widths
  and remain accessible at browser zoom.

## Implementation structure and interfaces

Use a dedicated compare service with session-owned comparison records, a vendor
adapter registry, an operation planner, a streaming data writer, and an artifact
store. Reuse the existing connection, authentication, CSRF, resource-accounting,
job polling/cancellation, metadata, and private-export conventions. No MCP
contract changes are needed.

Browser-only interfaces under `/api/dba`:

| Interface | Contract |
| --- | --- |
| `POST /compare/test` | Exact target: connection ID, database and schema scope. Return an owned job and observed engine/version/access result. |
| `POST /compare/catalog` | Tested source receipt and selected types; return candidate objects, data availability and matching-key choices. |
| `POST /compare/start` | Tested target receipts, types and data choices; return an owned comparison job and retained comparison ID. |
| `GET /compare/{id}/results` | Page retained identities, differences, counts and capability/blocker information. |
| `GET /compare/{id}/objects/{objectId}` | Source/destination definitions and individual selectable changes. |
| `GET /compare/{id}/objects/{objectId}/data` | Actual row differences and counts, filtered and paged; bounded display previews retain fingerprints of truncated values. |
| `POST /compare/{id}/plan` | Validate the current immutable revision and selections; preview ordered destination operations. |
| `POST /compare/{id}/generate` | Selected object IDs, default/per-table data modes and keys, sequence options, destructive-schema option, and comparison revision. Return a generation job. |
| `GET /compare/artifacts/{id}/preview` | Bounded UTF-8 preview, full byte count, completeness indicator and expiry. |
| `GET /compare/artifacts/{id}/download` | Complete SQL stream; attachment for Save and raw text response for Copy. Reading does not consume the artifact. |
| `DELETE /compare/{id}` | Idempotent close: request cancellation, discard comparison state, and release associated artifacts when active users finish. |
| `DELETE /compare/artifacts/{id}` | Idempotent explicit artifact disposal with owner checks. |

Existing job endpoints provide status, cancellation and release. All mutation
requests retain existing session/CSRF rules; downloads also require the owning
live session. Artifact identifiers never expose paths or connection secrets.

Important integration findings:

1. `SchemaSnapshots` is a bounded MCP observation, limited to 100 objects and
   deliberately unable to establish complete absence. Build independent,
   paginated compare inventories; do not relax the MCP contract. Definition
   inspection failures are blockers, not empty definitions.
2. `QueryJobs.activeConnection()` currently checks one connection ID and Job
   holds one active Statement. Add a backward-compatible set of participating
   connection IDs. Use sequential target acquisition and one registered active
   statement at a time, including when comparing two schemas through a pool with
   maximum size one. Both profiles remain busy until the comparison job ends.
3. Test receipts bind to profile revision, target and request revision. Recheck
   both profiles and relevant schema fingerprints when generating. Cancel and
   invalidate dependent jobs/output when a target or selection changes.
4. Use `Connections.target()` and explicit qualification. Some drivers cannot
   switch catalogs; surface that condition instead of querying the default
   database. Roll back read transactions and close/discard their sessions.
5. Existing `SqlScript` accepts at most 16 KiB and 32 execution units and rejects
   MySQL DELIMITER. The generated download needs its own vendor script formatter,
   including native batch/routine delimiters. Do not route generation through the
   human execution API or enlarge that API as part of this feature.
6. `GridExports` has useful private-file, streaming, size and cleanup conventions,
   but its downloads are single-use and its cell encoder rejects binary and
   structured values. Share/extract infrastructure, not those limitations as an
   implicit complete-restore implementation.
7. Register the compare tab as a non-Script type in workspace rendering and close
   handling. Keep compare contents out of Script/editor pairing and automatic
   workspace persistence. A browser reload ends the transient comparison.

## Dependency planning and data correctness

Plan operations, not just object types. One table may need several operations
separated by data loading and constraint validation. Use dependency edges and a
stable topological sort, with deterministic ordering of independent steps.

- Normalize names using the vendor's identifier rules and the selected schema
  mapping. Include routine signatures in identity. Rewrite references using
  structured metadata or a verified vendor parser, never global string
  replacement across literals or procedural bodies. Block unsupported rewrites.
- Inventory prerequisites, incoming dependents, table-owned objects and shared
  sequences. Do not duplicate implicit indexes or identity-owned sequences.
  Missing prerequisites outside the selection must already exist compatibly in
  the destination or be explicitly included by the user.
- Remove affected dependencies in reverse dependency order and recreate them in
  forward order. Use native ALTER where supported; do not rebuild a table from an
  incomplete definition. Preserve destination-only dependent objects or block.
- Create schemas, types, sequences and other prerequisites before objects that
  reference them. Attach sequence ownership after the owning table exists.
  Functions used by defaults and expressions are graph dependencies, not a fixed
  late-stage category.
- Separate base tables from foreign-key creation. Resolve supported FK cycles by
  loading first and adding/validating the constraints afterward, or by a proven
  engine deferral strategy. Unsupported cycles remain blocking errors.
- For existing destination tables, delete dependent rows before referenced rows
  and insert referenced rows before dependents. Account for unique-key swaps,
  not-null backfills, external foreign keys and data affected by type changes.
  Block operations without a verified ordering/transition strategy.
- Do not silently fire or suppress data-changing triggers as an assumed restore
  mechanism. For affected triggers, use a vendor-tested preservation/restoration
  strategy shown in the change summary; otherwise block the data operation.
- Keyed data modes require a primary key or usable non-null unique key; default
  to the primary key, otherwise a deterministic usable unique key. Validate keys
  when catalog declarations are not enforced. With no usable key, require Replace
  all or deselection; never silently change the user's mode.
- Stream full typed source values into vendor-supported staging/conditional DML.
  Use exact integer/decimal representations, proper binary and temporal literals,
  and explicit writable column lists. Preserve required identity values; omit
  computed values. Unsupported codecs block Include data for that object.
- Read all rows for included tables, not the grid page. A zero-row source is a
  no-op for insert modes and empties the selected destination table for replace
  or exact mirror. Snapshot consistency follows the verified vendor capability;
  do not claim an atomic snapshot across separate databases.
- Add dependency-ordered views/materialized-view population and remaining
  constraints/indexes at their required phases. Validate restored constraints.
  Apply selected sequence state after all operations that can consume it.
- Format transaction boundaries per engine; do not promise universal rollback.
  Include target, engine/version, generation time, selection summary and execution
  assumptions in the script header. Saved scripts describe the observed targets;
  later concurrent changes cannot be prevented by this generation-only workflow.

## Sequence-state rules

Keep definition comparison and runtime state separate. With Sync values off,
changing sequence definitions must not implicitly restart an existing sequence.
Represent numeric state as decimal strings over JSON and exact integers on the
server, avoiding JavaScript's loss of precision above 2^53.

- Inspection never calls NEXTVAL/NEXT VALUE FOR or changes either database.
- Track observed state, increment direction, bounds, cycle/exhaustion status,
  caching semantics, and known consuming columns. Source state is captured at
  generation time, not described as the live value at later execution time.
- Safe advancement respects both source/destination allocated state and retained
  or copied destination data, aligned to the sequence increment. Descending
  sequences use the lower bound in the direction of advancement. Unknown
  allocation semantics, exhaustion, and unsafe cycle wrapping block that mode.
- Exact matching is separate from safe advancement. Disable exact mode if
  read-only metadata cannot establish the required state. A cached high-water
  mark is not an exact next value; do not subtract or add a cache size by guess.
- Unused sequences require distinct handling to avoid advancing their first
  returned value. Restore the intended next-value behavior with the vendor's
  restart operation where supported.
- If copying explicit IDs would leave the destination generator behind those IDs
  and the engine does not advance it automatically, require the user to enable
  safe synchronization for that generator or deselect the data operation. Do not
  silently enable value synchronization.
- A sequence definition can remain selected while an unsupported value-sync
  option is turned off. Definition and state capabilities must be independent.

Primary-source checks support these distinctions: PostgreSQL documents
[cached allocated values](https://www.postgresql.org/docs/16/sql-createsequence.html),
[is_called and nontransactional setval](https://www.postgresql.org/docs/16/functions-sequence.html),
and [transactional RESTART](https://www.postgresql.org/docs/16/sql-altersequence.html).
SQL Server distinguishes allocated and used state in
[sys.sequences](https://learn.microsoft.com/en-us/sql/relational-databases/system-catalog-views/sys-sequences-transact-sql?view=sql-server-ver17).
Oracle's [LAST_NUMBER](https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/ALL_SEQUENCES.html)
can represent cached allocation rather than the last returned number. Snowflake's
[ALTER SEQUENCE](https://docs.snowflake.com/en/sql-reference/sql/alter-sequence)
does not provide an ordinary restart/start-value alteration. These require
capability checks rather than a universal sequence SQL template.

Other operation-specific references:
[PostgreSQL dependency catalogs](https://www.postgresql.org/docs/16/catalog-pg-depend.html),
[SQLite's table-rebuild procedure](https://www.sqlite.org/lang_altertable.html),
and [MySQL implicit DDL commits](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html).

## Artifact lifecycle and limits

Use a separate private `compare-exports` directory under the owned DBA data
directory. Inherit the existing export defaults: 64 MiB per script, 128 MiB total
reserved artifact disk allowance, ten-minute completed-artifact expiry. Include
these reservations in telemetry; partial files count against the allowance.
Serve at most 1 MiB of preview text, ending on a valid UTF-8 boundary. The complete
artifact remains authoritative and contains the full selected output.

Generate into an owned partial file and publish only after every selected step
and full-data read succeeds. Limit/timeout/encoding failures and cancellation
remove partial files. A concurrent Cancel marks the comparison disposed before
cancelling work; a late job cannot publish an orphaned artifact. Artifact readers
hold short leases so deletion occurs safely after readers close. Repeated Copy
and Save do not consume the artifact. Session expiry, explicit disposal and
runtime shutdown release it; startup cleans only recognizable expired files.

Copy retrieves and byte-checks the full artifact rather than copying the preview. The file size is bounded at 64 MiB; Save streams it directly. If browser or
clipboard limits prevent complete copying, report the failure and keep complete
Save available; never silently copy a truncated script. Cancellation cannot undo
a copy/download the user already completed.

## Retained comparison data

Source and destination rows are captured using private external-sort files. Each comparison has a 128 MiB disk ceiling; a shared 256 MiB budget also includes generation-time revalidation files. Metadata is bounded at 10,000 objects and 16 MiB per inventory. At most two comparisons are retained, with an 80 MiB memory reservation each and a ten-minute idle expiry. Row capture is limited to one million rows per table and 2 MiB per encoded row. Row-detail responses are bounded at 1 MiB; the complete captured values are used for generation.

Generation rechecks profile revisions, metadata fingerprints and selected row fingerprints. Definitions or rows changed since review require a fresh comparison. Sequence observations are refreshed separately, without evaluating NEXTVAL. Closing the tab, Cancel, session expiry and runtime shutdown dispose of owned state; publication and disposal are synchronized.

## Current capability boundary

| Engine | Enabled and tested operations | Explicit limitations |
| --- | --- | --- |
| PostgreSQL (live tests: 16.14) | New ordinary tables, native constraints/indexes, enum types, views, routines with unchanged signatures, sequences; selected column/constraint/index changes; four data modes; cached/unused/descending/identity sequence advancement | Existing type/domain changes, materialized-view rebuilds, dependent-view column changes, unsafe conversions/backfills, dynamic SQL remapping, special storage, explicit column collations, RLS, partitioning/inheritance, generated columns and trigger-bearing data operations are blocked. |
| H2 (embedded tests) | New ordinary tables/views/sequences; individual column/constraint/index changes; unique/check constraints and column comments; keyed data changes and replace mode; sequence and identity state | Special/generated definitions, triggers, unsafe conversions/backfills, dependency-preserving view rebuilds and unsupported codecs are blocked. |
| MySQL (live tests: 8.4.11) | Native new table definitions, foreign keys, views, and all four data modes on ordinary tables | Existing table definition changes other than supported FK staging are blocked; routines require an additional script formatter. No standalone sequence-state adapter. |
| MariaDB (live tests: 11.4.13) | Native new table definitions, foreign keys, views, and all four data modes on ordinary tables | Same existing-table/routine limits as MySQL; standalone sequence-state synchronization is unavailable. |
| Oracle (live tests: Free 23.26.3) | Native definitions, dependencies, grants, supported scheduler objects, four data modes on eligible tables, ordinary/identity sequence advancement | See [Oracle support](dba-oracle.md) for explicit transformation, data, scheduler and privilege boundaries. Standard 19c administration certification is pending. |

Safe data generation requires parent-table data to be included for referenced rows, and dependent-table data when deleting referenced rows. Conflicting alternate unique-key transitions are blocked. No-key tables require explicit Replace all. Unsupported data codecs, such as arrays and vendor object types, fail the comparison without publishing partial SQL. Fixed-width and unverified case/padding-sensitive matching keys are unavailable. Catalog and data changes after download remain outside a generation-only tool's control; the script documents maintenance/exclusive-access assumptions.

Oracle owner-issued object/column grants are included within the documented Oracle boundaries. Account ownership and server-level configuration remain outside comparison synchronization. Destination-only objects are preserved. The scanner-status follow-up is implemented as a separate catalog monitor; see the follow-up section below.

The PostgreSQL identity sequence-name syntax is based on the official [CREATE TABLE documentation](https://www.postgresql.org/docs/16/sql-createtable.html); cached-state handling follows the sequence references above.

## Original engineering baseline (before implementation)

Validation ran in `target/dba-compare-validation-20260924-01`, an isolated copy of
1,476 tracked source files excluding tracked build outputs. During that baseline, production source, saved database profiles and running user databases were not changed. Implementation now changes source files; builds still use the isolated copy because this repository tracks existing build outputs.

| Check | Evidence/result |
| --- | --- |
| Offline compilation and focused backend tests | 42 tests passed with fresh JVMs; covers targets, connections, jobs, metadata, snapshots, migration plans, object/table designers, SQL boundaries and exports. See `baseline-isolated-forks.log`. |
| Initial shared-JVM baseline | 41 passed, one existing ObjectCreationTest H2 driver-registration error. Fresh JVMs pass; preserve this initial result in `baseline-maven.log` and `baseline-reports`. |
| Shared-JVM fixture correction | Explicit `org.h2.Driver.load()` before the direct DriverManager call, applied only in the isolated copy, makes all 42 tests pass in the original shared-JVM configuration. See `baseline-fixture-adjusted.log`. |
| Disposable PostgreSQL 16.14 | Three existing integration tests passed: schema/migration rehearsal, reusable permissions and catalog/read permissions. See `live-vendors.log` and `workflow-postgresql.txt`. |
| Disposable MySQL 8.4.11 | The same three integration tests passed using Connector/J 9.7.0. See `live-vendors.log` and `workflow-mysql.txt`. |
| Browser catalog | Passed the existing catalog suite without modifications. |
| Browser object designer | Passed without modifications, including large exact sequence values and workspace lifecycle. |
| Browser toolbar | Existing test omitted Database permissions from expected menu labels. Correcting that expectation only in the isolated copy passes, including keyboard/focus and narrow viewport checks. |
| PostgreSQL SQL feasibility probe | Passed dependencies/default ordering, FK cycle staging, upsert preservation, non-consuming sequence reads, unused/descending/cached state handling, safe advancement, transactional restart rollback and child-first deletes. See `sequence-order-probe.sql` and `.log`. This is a manually authored feasibility probe, not output from the future generator. |

The implementation includes the two baseline fixture repairs: explicitly register H2 before the direct DriverManager call, and include Database permissions in the toolbar menu expectation. Production driver cleanup and permissions behavior remain intact.

The following remains the acceptance checklist for each enabled adapter; some complex operations are deliberately capability-blocked as listed above:

1. All wizard states, out-of-order responses, connection edits/removal, pool-size
   one, schema remapping, full-database traversal, more than 100 objects,
   incomplete metadata, selection invalidation and ownership/CSRF isolation.
2. Stable operation ordering with defaults/functions/types/sequences, incoming
   dependencies, FK cycles, table rebuilds, backfills, unique-key conflicts,
   triggers, quoted identifiers and routine overloads; no partial output on a
   blocked operation.
3. All four data modes, empty sources, composite/missing/unenforced keys, precise
   large numeric values, temporal/binary/structured codecs, identities, generated
   columns, and exact/cached/unused/descending/exhausted sequence states.
4. Execute complete generated files using the supported vendor/client format in
   disposable fixtures; verify resulting definitions, preserved destination
   objects/rows and selected source data. Test a no-difference comparison and a
   fresh comparison after applying the script. Scripts themselves need not be
   replay-safe unless that adapter explicitly guarantees it.
5. Full Copy/Save versus preview, repeat downloads, size and disk limits, Cancel
   during generation/download, disposal races, expiry, startup cleanup, and
   viewport/zoom geometry with no inaccessible controls.
6. Every SQL template gets a capability/coverage matrix row. Every enabled
   engine/version/operation needs vendor-specific generation tests. The present
   host's live evidence covers PostgreSQL, MySQL, MariaDB, embedded H2 and the
   documented Oracle Free capabilities. It does not certify standard Oracle 19c
   administration, SQL Server, Snowflake or other untested engines' generated scripts.
   Missing vendor fixtures do not justify enabling unverified SQL or quietly claiming universal restore support.

## Feature execution evidence

Final verification on 2026-09-24: 48 focused backend regression tests passed; the six comparison tests were rerun successfully after the last generator change. Complete generated scripts passed on PostgreSQL 16.14, MySQL 8.4.11 and MariaDB 11.4.13. The comparison and existing toolbar browser suites passed. Final Docker inventory matches the original 55 image IDs, with no owned comparison containers or pulled MariaDB image remaining.


- `DatabaseCompareTest`: generated H2 SQL executes successfully; validates partial column selection, preservation of destination-only objects, unique constraints, comments, exact integer data, row counts, stale-data rejection, external dependency blockers, failed-job cleanup, disposal/publication races, shared disk quotas, descending arithmetic, all-schema creation, 125-object pagination and pools of size one.
- `CompareVendorIntegrationTest`: application-generated complete SQL files execute in two independently owned containers per engine. PostgreSQL verifies FK ordering, functions used by defaults, enum dependencies, explicit view column names, view check options, MySQL/MariaDB view security, explicit identity values, named identity generators, unused/descending/cached sequences and values above 2^53. PostgreSQL, MySQL and MariaDB exercise insert missing, upsert, mirror and replace modes while preserving unrelated destination objects.
- `browser-compare.cjs`: covers tested target selections, object/data options, actual row differences, source/destination panes, viewport expansion, narrow layout, generated SQL, complete Save and Copy, no execution request, CSRF, artifact ownership and Cancel disposal.
- The existing focused DBA regression suite and workspace-toolbar browser suite are included in validation. Logs and generated SQL are retained under `target/dba-compare-implementation` and the isolated build's `compare-evidence` directory.
- `test-compare-vendors.ps1` snapshots existing Docker image IDs, labels its own containers, uses random loopback ports, and removes only owned containers and newly pulled images that have no users. Existing user containers/images are preserved.

To rerun in an isolated source copy, compile/test the DBA reactor using `DatabaseCompareTest` plus the focused existing suite; run `test-compare-vendors.ps1 -BuildRoot <isolated-root>` for live fixtures; set `DBA_BROWSER_SUITE=compare` and run the isolated `test-browser.ps1` with the available Playwright module path.


## Connection-template coverage

Generation uses the observed product/version, including PostgreSQL-compatible product detection. A template alone never enables an adapter.

| Template | Comparison generation |
| --- | --- |
| Altibase (`altibase`) | Unavailable; metadata inspection only where supported by the driver |
| Amazon Redshift (`redshift`) | Unavailable; metadata inspection only where supported by the driver |
| Apache Calcite (`calcite`) | Unavailable; metadata inspection only where supported by the driver |
| Apache Hive (`hive`) | Unavailable; metadata inspection only where supported by the driver |
| Azure Cosmos DB for Apache Cassandra (`cosmos-cassandra`) | Unavailable; metadata inspection only where supported by the driver |
| Azure SQL (`azure-sql`) | Unavailable; metadata inspection only where supported by the driver |
| Cassandra (`cassandra`) | Unavailable; metadata inspection only where supported by the driver |
| ClickHouse (`clickhouse`) | Unavailable; metadata inspection only where supported by the driver |
| CockroachDB (`cockroachdb`) | Unavailable; metadata inspection only where supported by the driver |
| CSV (`csv`) | Unavailable; metadata inspection only where supported by the driver |
| Custom (`custom`) | Only if the observed server is one of the enabled engines |
| Databricks (`databricks`) | Unavailable; metadata inspection only where supported by the driver |
| DuckDB (`duckdb`) | Unavailable; metadata inspection only where supported by the driver |
| Elasticsearch (`elasticsearch`) | Unavailable; metadata inspection only where supported by the driver |
| Exasol (`exasol`) | Unavailable; metadata inspection only where supported by the driver |
| Firebird (`firebird`) | Unavailable; metadata inspection only where supported by the driver |
| Google BigQuery (`bigquery`) | Unavailable; metadata inspection only where supported by the driver |
| Greenplum (`greenplum`) | Unavailable; metadata inspection only where supported by the driver |
| H2 (`h2`) | Enabled within the operation boundary above |
| HSQLDB (`hsqldb`) | Unavailable; metadata inspection only where supported by the driver |
| IBM Db2 for i (AS/400) (`db2-i`) | Unavailable; metadata inspection only where supported by the driver |
| IBM Db2 LUW (`db2`) | Unavailable; metadata inspection only where supported by the driver |
| IBM Db2 z/OS (`db2-zos`) | Unavailable; metadata inspection only where supported by the driver |
| Informix (`informix`) | Unavailable; metadata inspection only where supported by the driver |
| JSON (`json`) | Unavailable; metadata inspection only where supported by the driver |
| MariaDB (`mariadb`) | Enabled within the operation boundary above |
| Microsoft SQL Server (`sqlserver`) | Unavailable; metadata inspection only where supported by the driver |
| MongoDB SQL Interface (`mongodb`) | Unavailable; metadata inspection only where supported by the driver |
| MySQL (`mysql`) | Enabled within the operation boundary above |
| Neo4j (`neo4j`) | Unavailable; metadata inspection only where supported by the driver |
| OpenSearch (`opensearch`) | Unavailable; metadata inspection only where supported by the driver |
| Oracle (`oracle`) | Enabled for Oracle 19c+ within the [Oracle capability boundaries](dba-oracle.md#native-comparison); live Oracle Free validation |
| PostgreSQL (`postgresql`) | Enabled within the operation boundary above |
| PrestoDB (`presto`) | Unavailable; metadata inspection only where supported by the driver |
| Redis (Calcite adapter) (`redis`) | Unavailable; metadata inspection only where supported by the driver |
| SAP HANA (`hana`) | Unavailable; metadata inspection only where supported by the driver |
| Snowflake (`snowflake`) | Unavailable; metadata inspection only where supported by the driver |
| SQLite (`sqlite`) | Unavailable; metadata inspection only where supported by the driver |
| StarRocks (`starrocks`) | Unavailable; metadata inspection only where supported by the driver |
| Teradata (`teradata`) | Unavailable; metadata inspection only where supported by the driver |
| Trino (`trino`) | Unavailable; metadata inspection only where supported by the driver |
| YugabyteDB (`yugabytedb`) | Unavailable; metadata inspection only where supported by the driver |


## Database scan-status follow-up

The requested catalog scan monitor is now implemented separately from comparison generation. See [scan monitoring and lifecycle](project-database-context.md#scan-status-and-last-run-details). It provides current and last run timing, phases, counts and failures, per-target queue state, passive browser monitoring, and revision-based MCP waits. Compare remains a script-generation workflow; catalog scan status does not execute comparison output.


## Comparison timeout and structure-only hardening (2026-09-24)

Catalog loading, Compare, and Generate Script have an independent overall deadline,
900 seconds by default. Set **Comparison timeout (seconds)** in DBA resource settings
or use `--dba-compare-timeout` (30–3600 seconds). Connectivity tests and individual
statements retain the ordinary query timeout. Active comparison jobs retain their
admitted deadline when settings change.

**Structure only — no data** is the default data mode. Other modes enable per-table
opt-in; returning to structure-only suppresses retained data selections on both the
client and server. Sequence definitions are independent of the optional **Sync sequence
values** checkbox. Without value synchronization, PostgreSQL sequence-consumer tables
are not scanned for minimum/maximum values. Older API callers that omit `dataMode`
retain their existing explicit per-table behavior.

PostgreSQL captures selected categories and their dependency closure, including
incoming dependents, using one bounded dependency query. Catalog nodes already resolved
in the read transaction are reused rather than resolving each object's browser page
again. Generation repeats the same capture scope and verifies definition/data
fingerprints before publishing an artifact.

JDK 25 virtual workers retain bounded job admission. Independent source/destination
connections can read metadata concurrently (at most two readers per comparison).
A shared connection or a one-job configuration uses serial reads. Both readers share
the parent's deadline and are cancelled and joined before cleanup or terminal status.
Failures distinguish cancellation, overall expiration, individual statement timeout,
and database/validation errors, with phase/object context and elapsed progress.
A failed comparison preserves UI selections; failed generation preserves completed
review evidence and discards incomplete output so generation can be retried.

The Databases step discovers available databases/schemas as selections change, without
requiring a manual Test connection action. **Choose object types** freshly validates
both selected targets, reports either target's failure on the current page, and advances
only when both are ready. Earlier discovery receipts do not substitute for this check.

Validation: 50 targeted Java tests passed, including deadline/cancellation cleanup,
parallel-reader cancellation, structure-only enforcement, stale-data retry, and fatal
connection failures. Three live PostgreSQL 16.14 tests passed, executing generated
scripts and checking a 151-table scoped comparison. The existing postgres:16 image
was reused and the test-owned containers were removed. The browser comparison suite
passed automatic connection failure/retry, retained selections, structure-only output,
independent sequence synchronization, review, copy/save, viewport, and disposal checks.


## PostgreSQL sequence metadata-limit fix (2026-09-24)

Comparison previously routed non-table objects through the general object editor. Even a
single sequence therefore loaded JDBC's complete datatype catalog and editor choice lists.
A large unrelated datatype catalog could exceed the editor's 1 MiB category allowance before
the selected sequence definition was read, on either source or destination.

Object comparison now captures native definition evidence directly, without datatype catalogs,
form choices, UI controls, refresh-schedule editors or redundant per-object dependency display
queries. PostgreSQL retains its bulk dependency scope; generation still recaptures the same
scope and rejects changed definitions. Native queries use the existing comparison allowance
of 10,000 entries and 16 MiB per category, plus the overall inventory budget. Editor limits
remain unchanged, and editor category-limit errors now identify the failing category.

A live PostgreSQL 16.14 regression creates 650 unrelated enum types on each connection and
reproduces the exact original 1 MiB error in the editor reader. Sequence comparison, generation,
script execution and identical repeat comparison all succeed through the dedicated comparison
path; an intervening sequence alteration still blocks generation. Guarded connections assert
that comparison does not request datatype catalogs or editor choice/dependency lists.
Focused tests also cover definitions beyond the editor's 64 KiB field limit, comparison-budget
rejection and cleanup. Comparison and object-editor browser regressions pass.
Validation passed: 29 focused Java checks, four live PostgreSQL checks, two MySQL checks,
and two MariaDB checks. The two PostgreSQL-only cases are skipped on each other engine.
Owned test containers and the newly pulled MariaDB image were removed; all 55 existing
Docker image IDs remain.


## Scoped capture and saved comparison metadata allowance (2026-09-25)

Comparison tables now use native comparison evidence instead of TableDesigner loading.
The 256-column, 8 KiB field and 1 MiB table-editor limits remain editor constraints;
comparison queries and inventory accumulation use their own admitted metadata budget.
Non-table definitions continue to bypass datatype catalogs and editor choices.

PostgreSQL discovers compact object identities and bulk dependency edges before loading
full definitions. Foreign keys, owned indexes, constraint/trigger references, required
objects and incoming dependents expand the evidence scope. Ownership/FK discovery edges
are separate from the generator's ordering edges, preserving explicit FK staging.
Implicit constraint indexes and relation/array types do not load duplicate definitions;
the owning table/type carries their evidence. PostgreSQL and MySQL use native dependency
catalogs; H2 and MariaDB additionally inspect view queries because their adapters do not
have MySQL's view-usage catalogs. This compatibility fallback remains bounded.

Source and destination agree their combined dependency scope before full capture, including
counterparts that exist on only one side. Selected object IDs are mapped across schemas.
Generation discovers and captures the same requested scope again: changes to unselected,
unrelated definitions do not invalidate the review; changes to dependencies do. Definitions
still use per-statement timeouts and the overall comparison deadline, with bounded virtual
readers on independent connections. Discovery does not read table rows.

DBA resource settings now include **Comparison metadata limit (MiB, optional)**. Blank/null
uses 16 MiB; an integer from 1 to 256 overrides it. The setting is persisted atomically in
`compare-settings.json`, is protected by existing browser authentication/CSRF, and survives
restart. Invalid saved settings produce a warning and use the default. Existing comparisons
retain their admitted limit through generation even if settings change. New comparisons
reserve five times the per-side allowance plus their ordinary job reservations; increasing
the metadata setting does not bypass the DBA accounted memory allowance. Data-option catalog
jobs also reserve metadata working space. This is an application accounting limit, not a
hard bound on driver or total JVM allocations.

The setting governs bounded catalog text consumption, complete object evidence, aggregate
inventories and Oracle comparison-review accumulation. It does not increase editor limits,
the 10,000-object/entry caps, script/artifact limits, or Oracle native XML/document safety
caps. Budget failures never silently truncate a definition. Table/data capabilities and
existing unsupported-change blockers are unchanged.

Validation includes settings restart/reset/CSRF, corrupt-file recovery, job/parallel-reader
budget snapshots, memory-admission rejection, cancelled/rejected query cleanup, 300-column
tables, long native constraints, independent source/destination dependency counterparts,
and generation-time dependency revalidation. Disposable vendor tests execute generated
scripts and repeat comparisons; the browser suite covers saved settings, navigation,
connection errors, copy/save, viewport geometry and disposal. Concrete results and image
versions are recorded with the PostgreSQL/MySQL delivery notes.

MySQL dependency adapters follow the distinct catalog columns documented for
[VIEW_TABLE_USAGE](https://dev.mysql.com/doc/refman/8.4/en/information-schema-view-table-usage-table.html)
and [VIEW_ROUTINE_USAGE](https://dev.mysql.com/doc/refman/8.4/en/information-schema-view-routine-usage-table.html).

### Review selection by object type

Each object-type heading in Review differences has a checkbox to select/deselect its
children. A mixed state indicates partial selection. The action applies to all objects
matching the current search and result-status group, including subsequent pages. Other
object types and filtered-out objects retain their selections. Unsupported, destination-only,
and currently disallowed destructive changes cannot be selected through these controls.
Turning off destructive schema changes removes any previously selected destructive changes.
Table-data and sequence-value settings remain independent. Selection changes refresh the
selected-change count and dispose of any previously generated script.
