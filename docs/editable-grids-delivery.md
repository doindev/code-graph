# Editable grids delivery and acceptance

Validation date: 2026-09-19. Environment: Windows, OpenJDK 25, Maven 3.9.11,
Chromium through Playwright, Docker Desktop Linux containers.
The running application and the earlier MCP checkpoint commit/push remain separate.
No production database, application restart, commit, or push is part of this delivery.

## Implemented gates

| Gate | Evidence |
|---|---|
| Compact footer, page-local selection, keyboard editing, bounded draft state | Pure JavaScript state tests and editable-grid browser suite |
| Server-derived editability, typed originals, atomic save, ownership/audit | GridResults, GridSafety, GridPrivileges and GridHttp tests |
| First/adjacent/last paging and authored query-limit preservation | 451-row, final-200-row fixtures on five engines; limited-query tests |
| CSV/XLSX/TXT/SQL export and cleanup | Format/escaping tests, browser downloads, full-query live exports and resource-limit fixtures |
| Dirty lifecycle and shared Script/Table/view/query-builder integration | Grid controller, Table, view and query-builder browser suites |
| Confined-memory admission | Wide pages and million-row streaming under a 192 MiB test JVM; 64 MiB DBA accounting refuses new contexts without evicting existing snapshots |
| Graph, DBA, HTTP/MCP regressions | Full Maven reactor; MCP schemas and agent result limits are unchanged |

Required invariants are enforced: no row writes until Save; unique identity plus
original-value checks; no preview-only predicates; fixed target and revision;
atomic rollback; no automatic write retry after uncertain commits; one retained
page per context; no procedural/DML-source re-execution for full exports.

The focused JVM run passed 32 tests; its one skipped vendor entry was run
separately against Docker. Six pure JavaScript state tests passed. Streaming
fixtures generated a real million-row export without retaining a million-row
array, rejected row 1,000,001 and a file exceeding 64 MiB, and verified cleanup
after cancellation and failure. Crash cleanup preserved recent and non-owned files.

## Live database matrix

All five engines passed ordinary-table update/delete/insert, DEFAULT/NULL,
ordered paging, original-value conflict rollback and full-query export fixtures.
The four Docker engines also passed cross-database isolation against identically
named tables. H2 additionally covers real SELECT-only credentials, audit failure,
session loss, cancellation after DML, cascade rollback and simulated lost commit
acknowledgement.

| Engine/version | JDBC version | Table writes/paging | View writes |
|---|---|---|---|
| PostgreSQL 16.14 | 42.7.13 | Passed | Direct automatically updatable view; check-option rollback passed |
| MySQL 8.4.11 | 9.7.0 | Passed, InnoDB | Verified direct view; check-option rollback passed |
| MariaDB 11.4.13 | 3.5.7 | Passed, InnoDB | Verified direct view; check-option rollback passed |
| H2 2.4.240 | 2.4.240 | Passed | Disabled, not verified |
| SQL Server 2022 / 16.00.4295 | 13.4.0.jre11 | Passed | Disabled, not verified |

Recorded Docker image identities:

| Recipe | Image identity used |
|---|---|
| postgres:16 | sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b |
| mysql:8.4 | sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a |
| mariadb:11.4 | sha256:70cc072b29b4a89ae07abb2d4da2c64678a7f2dfe092751bb51c87d67dc1338b |
| SQL Server manifest | sha256:4402d880dd4c34bfa7d8705e56a86cd6c88da80a1f6bbbe741f999e76264a090 |

The harness records resolved images on every run because mutable tags can change.
SQL Server Developer tests require explicit EULA acceptance. Tests run sequentially.
Owned containers/volumes and newly introduced unused MariaDB/SQL Server images
were removed. Pre-existing PostgreSQL/MySQL images and user resources were preserved.

## Browser coverage

The 19 existing/extended suites passed: native, yolo, tree-context,
workspace-toolbar, script-selection, grid, editable-grid, table-designer,
view-query, query-builder, object-creation, object-designer, grid-edit,
project-context, catalog, editor-pairing, approvals, approval-review and core.

The new workflow covers Shift/Ctrl selection, F2/Tab/Shift+Tab/Escape, insert Save,
staged deletion/confirmation, Save/Discard/Stay, settings, page size, first/last,
all four downloads, narrow toolbar layout and uncertain-save reconciliation.
It also caught and fixed a scroll/focus race that dismissed new-row editors.
Existing suites cover virtualization, column resizing/reordering, tab remount,
refresh schedules, independent queries, cancellation races and host lifecycle.

## Reproducible checks

Run from the repository with JDK 25, Maven and Node available. No Docker resources
are needed for embedded, protocol or browser tests.

~~~powershell
# Pure state and syntax checks
node --test code-graph-dba/grid-state.test.cjs
node --check code-graph-dba/src/main/resources/codegraph/dba/data-grid.js
node --check code-graph-dba/src/main/resources/codegraph/dba/grid-state.js
node --check code-graph-dba/src/main/resources/codegraph/dba/grid-operations.js
node --check code-graph-dba/src/main/resources/codegraph/dba/grid-interactions.js

# Creates an isolated source copy and runs the entire reactor
./scripts/Test-IsolatedReactor.ps1 -Name editable-grid-validation -All -Package

# Use the source path printed above for focused/repeated validation
$build = '<isolated source path>'
mvn -B -ntp -f "$build/pom.xml" -pl code-graph-dba -am test '-Dtest=Grid*Test' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true' '-Dtest.jvm.args=-Xmx192m'

# All browser suites; requires installed Playwright and its Chromium runtime
Remove-Item Env:DBA_BROWSER_SUITE -ErrorAction SilentlyContinue
& "$build/code-graph-dba/test-browser.ps1" -NodeModules '<node_modules containing playwright>'

# Explicitly authorized disposable databases; requires the pinned JDBC JARs in ~/.m2
./code-graph-dba/test-grid-vendors.ps1 -BuildRoot $build -AcceptSqlServerDeveloperEula
~~~

GridVendorTest is intentionally skipped in ordinary Maven runs without the
fixture environment variables; the separate Docker runs are its live evidence.

## Raw local evidence

Ignored build artifacts are retained locally, not committed:

- Rapid source/test root:
  target/mcp-efficiency-coverage/editable-grid-regressions-1fb069be1fb9459e95f8f20f5d5a3471/source
- Full build root:
  target/mcp-efficiency-coverage/editable-grid-final-502caa9dea0d449a8f9aa5a794ed9925
- Logs: grid-confined-final.log, grid-vendors-delivery.log, grid-vendors-locking.log,
  grid-vendors-final-retest.log, browser-all-delivery.log, browser-*.log, and
  maven-delivery-clean-final.log.
- Detailed JUnit results: code-graph-dba/target/surefire-reports within the build
  source; grid-{vendor}.txt records the individual live runs.

Initial failure logs are retained too; the final/retest logs identify corrected
browser and vendor runs rather than concealing the investigation.

## Explicit limitations / unverified cases

- H2/SQL Server view writes, materialized-view writes, joins, aggregates,
  duplicate/ambiguous projections and keyless targets are disabled.
- Other JDBC vendors retain supported display/selection/export behavior, not
  unverified row-write or server-paging SQL. Native MongoDB/Redis are not JDBC
  editable rows.
- Floating point, timezone, binary/LOB and structured-value comparisons remain
  read-only. SQL Server legacy DATETIME/SMALLDATETIME, MySQL/MariaDB unsigned
  integers, duration-style TIME and multi-bit BIT are explicitly disabled.
  Not every vendor collation or temporal precision variant has a live fixture;
  capability checks are intentionally conservative.
- The suite verifies selected check-option/cascade behaviors, not every possible
  trigger, RLS policy, external side effect or vendor extension.
- Lost acknowledgements and cancellation races use controlled failure injection;
  they are not a certification of every network/driver failure mode.
- Browser coverage is Chromium on Windows. Real screen-reader use and other
  OS/browser combinations are not claimed as passed.
- Resource tests verify accounting, admission, streaming and hard export ceilings;
  they do not promise a hard process-RAM cap or a frozen result between page requests.

See [user/API/recovery documentation](dba-editable-grids.md) for the supported
contract and [the existing DBA documentation](dba.md) for global limits/security.

## Row-limit control regression — 2026-09-20

- Decoupled bounded SELECT row limits from editable-result/server-paging
  eligibility. Read-only computed projections and joins can reload the first
  requested rows without claiming that they support ordered paging or writes.
- Enter applies the numeric field; typing alone does not execute. Applied limits
  survive refresh, filtering, sorting and Query Builder execution. Server-side
  validation retains configured ceilings and SQL-authored limits; procedural
  results cannot be replayed through the reload endpoint.
- Live H2 HTTP/browser fixtures verified 17/300-row reloads, explicit database
  targets, Query Builder limit retention, invalid values, CSRF and authored LIMIT
  preservation. Browser assertions now observe retained-query reloads as well as
  new query submissions, including cancellation and schedule disposal.
- Isolated full Maven reactor: 907 tests, 843 passed, 64 skipped, no failures or
  errors. All 19 browser suite groups passed across the final main run and the
  corrected grid-edit/remainder run. JavaScript syntax checks and six grid
  state/controller Node tests passed. Skipped live/platform cases are not passes.
- Evidence: target/grid-row-limit-75a07b141ee8436083ff7f2abd7ea119
  contains reactor.log, browser-all-verified.log, browser-remainder.log and the
  isolated source/test reports. Earlier investigation failures remain alongside
  these logs; the obsolete refresh-route assertion in the main browser run is
  superseded by the passing grid-edit suite in browser-remainder.log.
- The user's running application was not restarted; no commit or push was made.

## Loaded-page Find/Replace — 2026-09-20

- Replaced the magnifying-glass placeholder in the shared DataGridView with a
  collapsible, dark two-row toolbar positioned clear of both scrollbars. Added
  independent ten-entry search/replacement histories, case/regex/Unicode
  whole-word/selected-row matching, occurrence navigation, highlighting and
  local matching-row filtering.
- Replace/current and Replace All use typed, atomic draft staging. Existing
  Save/Cancel and read-only gates remain authoritative; Find never issues SQL.
  Histories/options survive tab remounts, while closing clears search-only
  effects without discarding row drafts. Filtered keyboard editing and Shift
  selection follow visible rows, not hidden source indices.
- Added bounded off-thread matching with timeouts, cancellation on close/unmount,
  stale-replacement rejection and shared worker-input accounting. The matcher
  and batch draft operations have focused Node tests.
- Validation: all 20 browser suite groups passed in browser-all-final.log,
  including real H2 search, staged changes/Cancel/Save, captures, history,
  read-only skips, invalid-value atomicity, virtualization, a reusable non-Script
  host, pathological-regex termination and worker cleanup. Browser verification
  used Chromium/Edge on Windows; other browsers/OS accessibility environments
  were not independently certified.
- Full Maven reactor: 907 tests, 843 passed, 64 skipped, zero failures/errors.
  JavaScript checks and 11 Node search/draft tests passed. Final packaging passed;
  the six bundled grid assets were hash-checked against the working tree.
- Reproduce: node --test code-graph-dba/grid-search.test.cjs
  code-graph-dba/grid-state.test.cjs; run code-graph-dba/test-browser.ps1 with
  Playwright's NodeModules path (DBA_BROWSER_SUITE=grid-search selects the focused
  suite), and mvn -B -ntp package -Djava.awt.headless=true for the reactor.
- Raw local evidence lives under
  target/grid-find-0d6d66bd6d244adb9f1551a272bd14b9: reactor.log,
  package-final.log, browser-all-final.log, isolated test reports and
  source/code-graph-dba/target/grid-find-replace.png. Earlier diagnostic failures
  remain in separate logs and are superseded by the final passing run.
- Browser fixtures closed; no new Docker images were introduced. The user's
  running server was preserved. No restart, commit or push was performed.
