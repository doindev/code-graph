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

Safe data generation requires parent-table data to be included for referenced rows, and dependent-table data when deleting referenced rows. Conflicting alternate unique-key transitions are blocked. No-key tables require explicit Replace all. Unsupported data codecs, such as arrays and vendor object types, fail the comparison without publishing partial SQL. Fixed-width and unverified case/padding-sensitive matching keys are unavailable. Catalog and data changes after download remain outside a generation-only tool's control; the script documents maintenance/exclusive-access assumptions.

Ownership, grants and server-level configuration are outside the current synchronization scope. Destination-only objects are preserved. The scanner-status follow-up is implemented as a separate catalog monitor; see the follow-up section below.

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
   host's live evidence covers PostgreSQL, MySQL and embedded H2 infrastructure;
   it does not certify Oracle, SQL Server, Snowflake or the other engines' future
   generated scripts. Missing vendor fixtures do not justify enabling unverified
   SQL or quietly claiming universal restore support.

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
| Oracle (`oracle`) | Unavailable; metadata inspection only where supported by the driver |
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
