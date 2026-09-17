# Restricted DBA read-mode validation — 2026-09-04

## Connection editor and script workspace increment

Human SQL increment: browser Run is no longer restricted to SELECT. H2 unit tests verify
CREATE/INSERT/UPDATE/DELETE/DROP, prepared values, persisted commits, error redaction and
continued agent denial. Live disposable PostgreSQL tests verify SERIAL/DATE DDL, the exact
`type "data" does not exist` error, multi-result SELECTs, rollback after a failing script,
VACUUM auto-commit, and physical-session isolation after SET. Browser tests execute those
DDL/DML commands, switch result sets, display the database error, and verify literal Tab
insertion without moving focus. No commands target the user's saved databases. Driver
cursor limits, auto-commit/DDL side effects and uncertain outcomes remain documented limits,
not blanket transactional or hard-memory guarantees. Agent writes remain unavailable.

Line-number regression: the editor now has a fixed gutter between the execution rail and
non-wrapping SQL text. Browser checks cover empty and trailing lines, CRLF input, typing,
deletion, tab changes, horizontal/vertical scrolling, responsive resizing, and opening a
10,000-line file with fewer than 40 rendered line labels. The long-file check caught
fractional line-height drift; matching 26px line heights fixed it and the suite passed.
Screenshot: `code-graph-dba/target/script-line-numbers.png`.

Human-function regression: `select version();` now passes through the browser query endpoint
and returns PostgreSQL's version. Browser validation is separate from the function-blocking
agent grammar. Unit tests cover nested functions, aggregates, and continued denial of writes,
multiple statements, nested SELECT INTO and locking. Live PostgreSQL tests verify human Run
and Explain and rejection of the same function query by agent read/explain/analysis tools.
The browser regression executes the exact reported query and asserts its result.

Latest layout regression: full-width icon-only File toolbar above both panes, no horizontal
script toolbar or DDL UI, vertical Run/Explain/Cancel rail, and per-tab connection-name
dropdowns. Automated checks cover desktop/narrow geometry, icon tooltips/accessibility names,
keyboard connection selection, disconnected drafts, defaults for New/Open, independent tab
targets, active-tab Save/Save As, target locking during submission, and deterministic mocked
job cancellation/release. H2 and disposable PostgreSQL browser checks passed; PostgreSQL
Run/Explain remain live database checks. The cancellation mock initially missed the `/cancel`
URL; correcting that test route and rerunning passed. Full MCP HTTP reactor package/tests
passed. The user's PostgreSQL container on port 5434 was not used or removed.

Statement-aware Data Grid regression: the human runner now extracts exact semicolon or
client-marker units, validates all parameters before execution, records statement provenance,
and supports savepoint-backed Continue, Skip all similar, Cancel, and expiry decisions. H2
tests cover quoted/comment/dollar-quoted semicolons, `GO` and `/` batches, unsupported directives,
result provenance, parameter distribution, partial-result retention, decisions, CSRF, ownership,
and stale decision IDs. Browser automation covers the reusable grid command strip, exact SQL
viewer with eight resize directions, filter expansion, explicit modal dismissal, direct and
modal drag reordering, Alt+Arrow reordering, stable widths, sticky headers/row numbers, and
virtualized scrolling after reordering.

Validated on this Windows/Java 25 host:

- Automatic `/dba` browser access, retained CSRF/session ownership, no login dialog.
- Alphabetical 12-item picker, Custom blank values, driver class discovery, unsaved Test,
  success modal with actual H2 version-query rows, failure modal and saved-profile editing.
- Multiple independent connection targets across script tabs, last-tree-selection defaults
  for opened scripts, dirty markers, grouped toolbar, file-open/download fallback, and
  Save/Save As handle logic (the native browser handle is simulated in headless automation).
- Named MCP connection requirements; UUID/name matching, unauthorized names, duplicate-name
  prevention, rename denial and no permissions inherited by a replacement connection.
- Twelve setup/key tests: ephemeral test has no profile/vault writes, receipt ownership,
  invalidation after driver changes, untested validation, descriptors, chunked secret
  keep/replace/remove, RSA strength, encrypted keys, wrong/missing passphrases, key rotation,
  unsupported formats and exception redaction.
- Explicit Maven download gate loaded all eleven named drivers, inspected their JDBC property
  information and URL acceptance, verified hashes/cache reuse, and removed its temporary files.
  Tested versions: DB2 12.1.5.0; DuckDB 1.5.5.1; H2 2.5.250; HSQLDB 2.7.4;
  MariaDB 3.5.10; SQL Server 13.4.0.jre11; MySQL 26.7.0; Oracle 23.26.3.0.0;
  PostgreSQL 42.7.13; Snowflake 4.3.4; SQLite 3.53.4.0.
- Live Docker matrix: PostgreSQL 16.14, MariaDB 11.4.13, MySQL 8.4.11. Each passed unsaved
  login/version query, explicit save, pooled prepared SELECT, metadata and removal/vault cleanup.
  Test containers and newly pulled MariaDB/MySQL images were removed. Pre-existing images
  and user containers were preserved.
- Windows native vault single-record and chunked record round trips passed in the full reactor run.

Initial driver validation exposed Windows resource-JAR caching, DB2 housekeeping timers,
and Snowflake’s loader-owned crypto provider. Cleanup now disables resource URL caching,
cancels isolated JCC timers and deregisters only loader-owned providers/resources. The full
driver gate then passed. MariaDB testing exposed unsupported numeric `getCharacterStream`;
bounded scalar decoding fixed it and the live matrix passed. These failures were not omitted.

Screenshots: `code-graph-dba/target/connection-picker.png`, `connection-test-success.png`,
`script-workspace.png`. See [connection documentation](dba-connections.md) for commands,
contracts and remaining platform/vendor gates. Driver loading is **not** live Snowflake
authentication, and simulated browser file handles are **not** a native desktop-picker test.

This evidence applies to the restricted-read increment, **not** completion of the entire
JDBC administration plan. The release gates remaining in [dba.md](dba.md) still apply.

## Environment and isolation

Windows host, Java 25, Maven 3.9.11, Node 22, Playwright with headless Microsoft Edge.
PostgreSQL 16 runs in an independently named/ownership-labeled Docker container, with a
random loopback port, 512 MiB container limit, two CPUs, and disposable tmpfs data.
Only that fixture's database/schema/container is mutated or removed. Browser tests use
an isolated profile and a temporary Java fixture, not the production application.

## Checks

| Feature | Evidence |
|---|---|
| Persistent grants and token hashing | `AgentAccessTest`: no raw token in stored configuration/listing; restart authentication; denial/revocation |
| SQL authorization | Exact quoted/unquoted identifiers, joins, nested SELECTs; unqualified/unauthorized objects, writes, CTEs, functions and executing analysis rejected |
| Bounded results | PostgreSQL returns 100 rows even with configured agent cap 1,000; oversized cells/rows trigger byte truncation below 1 MiB; prepared values preserved |
| Explain/analysis | Both tools return estimated PostgreSQL JSON plans with `executed:false`; bounded observations copy node types, costs and cardinality evidence |
| Definitions and metadata | Granted PostgreSQL column metadata and catalog definition fragments; unsupported/unauthorized object requests fail |
| Job lifecycle | Read completion, release, cancellation and cross-owner rejection; no write execution |
| Local MCP access | `DbaHttpAccessTest` / `DbaMcpHttpTest`: token-free HTTP/stdio identity, loopback listener, Host/Origin/remote-peer rejection without a token, invalid-token and cross-identity rejection, stale-session recovery, retained database permissions |
| Headless startup | Same profiles/grants reopen with no UI listener or browser token; graph tools remain; restricted DBA tool catalog only |
| Stdio | Real child-process MCP initialization and authenticated connection listing using an environment token |
| Browser | Automatic local session, SQL parameters/results, PostgreSQL EXPLAIN, DDL fragments, row truncation, create/revoke grant, one-time agent-token clearing, keyboard resizing and live limits; no page errors |
| Existing application | Full MCP HTTP dependency reactor package/tests pass; existing graph tests run with no production restart |
| Windows vault | Explicit opt-in real native create/read/replace/delete test passes |

An initial browser run caught delayed token clearing on dialog close. Clearing is now
synchronous in the close button handler (and also handled on the dialog close event), and
the browser scenario was rerun successfully. Tests never print a real user credential.

## Reproduction

```powershell
mvn -pl code-graph-mcp-http -am package -q "-Ddba.vault.integration=true"
powershell -NoProfile -File code-graph-dba/test-postgres.ps1 -Browser -NodeModules "PATH\TO\node_modules"
node --check code-graph-dba/src/main/resources/codegraph/dba/app.js
```

The node_modules path must contain Playwright; Edge and a Docker Linux engine must be
available. Test reports are in each module's `target/surefire-reports`; browser fixture logs
are under `code-graph-dba/target/browser-*`, and the screenshot is
`code-graph-dba/target/browser-smoke.png`. Subsequent Maven runs replace Surefire reports;
without the disposable PostgreSQL environment variables its integration tests are skipped,
not passed. The PostgreSQL script enables and runs those tests separately.

## Not established by these checks

- Production readiness of agent writes, object/function policies for arbitrary SQL,
  broader transaction-mode administration, or uncertain write outcomes.
- Complete restorable DDL export: this increment deliberately returns catalog fragments,
  marks them non-executable, and describes omitted properties.
- Paired editor/SSE synchronization or a stdio attachment bridge to another running owner.
- Exhaustive offline/corrupt-download recovery, vendor proxy routing, full accessibility
  audits or the entire browser interaction matrix from the original plan. Explicit Maven
  provisioning and current-cache reuse were tested as described above.
- Three independent performance runs, mixed graph/DBA pressure, native/driver accounting,
  hard RAM bounds, or comprehensive overload/connection-failure testing.
- Real macOS vault validation or Windows locked-vault validation. Earlier real Linux
  Secret Service container probes do not substitute for macOS testing.

No production server restart, commit or push is performed by this validation workflow.
## Catalog tree validation — 2026-09-09

This increment adds the [vendor-specific metadata tree](dba-catalog-tree.md). Validation
was performed on Windows with Java 25 and headless Edge/Playwright:

| Environment | Result |
| --- | --- |
| PostgreSQL 16, disposable Docker | All implemented groups, real table/view/materialized-view/index/sequence metadata, second-database isolation, column expansion; integration and browser suites passed. |
| Oracle Free 23.26.3.0.0, disposable Docker | All 17 schema groups plus Scheduler jobs/programs/schedules/chains queried successfully. The initial ORA-00960 catalog alias failure was corrected and the complete run passed. |
| MariaDB 11.4.13, disposable Docker | Every exposed catalog group queried successfully, plus existing connection/version/read tests. |
| MySQL 8.4.11, disposable Docker | Every exposed catalog group queried successfully, plus existing connection/version/read tests. |
| H2 | Unit coverage for literal schema wildcards, actual objects/columns, 200-node pages, subsequent pages, invalid input and browser-only access; full H2 browser smoke passed. |
| HSQLDB 2.7.4 / SQLite 3.53.4.0 / DuckDB 1.5.5.1 | Disposable embedded driver catalog tests passed for each implemented group. |
| SQL Server / DB2 LUW / Snowflake | Adapters implemented, but **not live-validated in this increment**. No SQL Server/DB2 license acceptance or Snowflake account provisioning was performed. |

Browser coverage includes chevrons only on branches, column leaves, schema-level object
groups, right-edge menus, and restoring nested expansion after ancestor Refresh. Screenshots:
`code-graph-dba/target/metadata-tree-postgres.png` and `metadata-tree-h2.png`. These browser
checks use PostgreSQL and H2; other engines share the renderer but are not claimed as
separate end-to-end browser certifications. Tests do not certify every server version,
permission combination, optional extension or object type beyond the documented matrix.

Subsequent checks also cover alphabetical folders/leaves, independent color selection
(transparent, presets and custom picker), saved color-only edits without connectivity tests,
drag/drop connection ordering, Alt+Up/Down alternatives, and order surviving a server-backed
UI refresh without changing Script targets. `ConnectionAppearanceTest` verifies persistence,
strict color/order validation and unchanged credentials. The HTTP tests enforce CSRF on
appearance/order routes. A Windows SQLite resource-stream leak exposed by temporary-JAR
cleanup was fixed in the isolated driver loader; the embedded tests now verify cleanup as
well as successful catalog reads. The color/order screenshot is
`code-graph-dba/target/connection-colors-order.png`.

Cleanup was verified: no test-labelled container or newly downloaded Oracle/MariaDB/MySQL
image remained. The pre-existing `code-graph-postgres-test` container and cached
`postgres:16` image were preserved. Tests created no saved user connection profiles and
made no changes to the user's PostgreSQL database.


## Canvas query builder and estimated Explain — 2026-09-11

Final DBA Java/PostgreSQL run: 113 tests, zero failures/errors, five optional integration tests skipped. PostgreSQL 16 exercised overload/default-argument discovery, typed overload selection, grouped parameterized compilation and estimated JSON plans. Compiler tests include 20 sources, disconnected validation, nested outer joins and SQL round trips, shared filters across modes, ordered bindings, missing references, cycles and persistence exclusions.

Live estimated-plan checks also passed on bundled H2, HSQLDB 2.7.4, SQLite 3.53.4.0, DuckDB 1.5.5.1, MariaDB 11.4.13, MySQL 8.4.11 and Oracle Free 23.26.3.0.0. These used disposable schemas/connections/containers. The embedded suite exposed HSQLDB's lack of EXPLAIN parameter slots and DuckDB's unsupported text-stream getter; both paths were corrected and rerun successfully.

The expanded builder browser suite passed for canvas compilation without data execution, explicit/composite joins, permanent output tabs, Rows filters and sorting, parameter prompts, Detail/Summary restoration, workspace recovery, preserved SQL, 20-source lifecycle checks, focus retention, stale results, cancellation and release. The shared column-menu, refresh, grid-controller and Table Data suites passed. Desktop and 700px-wide screenshots were inspected; SQL preview, Explain/Run controls and both output tabs are visible, with the canvas scrolling independently. The runtime reactor package build passed with tests skipped after the test runs.

Other Explain engines have command/cleanup or representative-format contracts, not live certification. OpenSearch uses its driver's native authenticated HTTP transport; endpoint/body/error/bounded-response contracts passed. Db2 for i, Informix, MongoDB SQL and Neo4j native plan collection remain implementation gaps with explicit capability messages. Exasol is limited to estimated virtual-schema pushdowns. See [the per-template matrix](dba-explain-support.md).

Artifacts: `code-graph-dba/target/query-builder.png`, `query-builder-narrow.png`, `query-builder-data.png`, `query-builder-plan.png`, `visual-browser-final.log`, `visual-grid-final.log`, `visual-postgres-final.log`, and `visual-database-matrix.log`.
