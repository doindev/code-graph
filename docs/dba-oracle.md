# Oracle DBA support

Oracle connections, SQL/PLSQL, native catalogs and editors, comparison, and the Administration
workspace are implemented within the capability boundaries below. See [the implementation plan](dba-oracle-implementation-plan.md).
Oracle 19c and newer are the target; full standard 19c administration certification remains pending.

## Connections and targets

Use the Oracle JDBC profile with a service/PDB URL, SID URL, or full TNS descriptor. Keep the
service/PDB in the connection URL and choose the owner/schema independently. The connection
test reports the actual service, container, session user, current schema, version and session
privileges. Selecting a different service/PDB requires its own connection; choosing an owner
never switches containers.

The Authentication & TLS tab accepts wallet/TNS directory and JDBC trust/key-store properties.
`internal_logon` explicitly requests `sysdba`, `sysoper`, `sysbackup`, `sysdg` or `syskm`;
leave it unset for an ordinary connection. The application never elevates a connection by default.
SYSDBA and ordinary least-privileged connections were tested on Oracle Free. Wallet, external
TNS resolution and every administrative role still require environment-specific validation.

## Catalogs and scans

All 17 Oracle schema categories are browsable. Native scans capture package/type bodies,
compilation diagnostics, object and column grants, scheduler arguments, chain steps and rules,
and their program/schedule/rule-set dependencies. Fixed catalog queries use SYS-owned views;
legacy jobs use USER_JOBS for the authenticated owner and DBA_JOBS for other owners.
Scanning another owner requires SELECT_CATALOG_ROLE (or SYS); insufficient access is reported.

Scans retain the five-minute overall deadline and now use the configured DBA statement timeout,
including the network bound, instead of always imposing 30 seconds. Snapshot jobs additionally
respect their remaining job budget. Native materialized-view metadata took about 31 seconds on
the test instance; the complete live scan passed with a 120-second statement setting.
Current/last run details, phase/counts, cancellation and cleanup remain available in scan status.

Native chain DDL refers to a separate rule set without including its rules, so the editor and
scan label it incomplete and expose its associated steps/rules for review. ANYDATA scheduler
arguments are identified explicitly and require native typed reconstruction. Database-link
credentials are not exported, and compiled Java assets still require external files.

The live Oracle Free fixture verifies all categories, 43 captured objects, scheduler details,
column grants, native materialized-view definitions, and ordinary-user owner restrictions.
Deterministic tests verify caller-supplied statement budgets, cancellation cleanup and scan
terminal state. This does not certify every catalog variant or standard Oracle 19c.

## SQL and PL/SQL

The Script editor accepts ordinary SQL separated by semicolons and PL/SQL units terminated by
a slash on its own line. A final PL/SQL unit can end at the end of the selection. Alternative
`q`/`nq` quoting preserves semicolons, question marks and slash text inside literals. Scripts
retain the existing 32-unit, 16,384-character and execution-deadline limits.

Open **SQL variables** to apply a JSON array of parameters in `?` marker order. Plain values
remain ordinary IN parameters. Oracle also supports typed descriptors:

```json
[
  {"mode":"in","type":"NUMBER","value":"12345678901234567890123456789012345678"},
  {"mode":"in","type":"TIMESTAMP_WITH_TIMEZONE","value":"2026-09-24T12:34:56.123456789+05:30"},
  {"mode":"out","type":"REF_CURSOR"},
  {"mode":"inout","type":"VARCHAR","value":"initial"}
]
```

Supported scalar types are VARCHAR, NVARCHAR, NUMBER, DATE, TIMESTAMP,
TIMESTAMP_WITH_TIMEZONE, CLOB and NCLOB. Use ISO date/time values, including an offset for
TIMESTAMP_WITH_TIMEZONE. Use decimal strings in typed NUMBER parameters to preserve precision
through the browser. REF_CURSOR is output-only and appears as a read-only result grid.
A routine must actually declare an IN OUT argument for an INOUT parameter to return a value.
SQL variables are retained only in the open tab, not workspace recovery or SQL exports.

Enable **Server output** before execution to collect Oracle DBMS_OUTPUT. The database buffer
is 100,000 bytes; collection is limited to 256 lines and 100,000 bytes. Scalar and LOB output
previews are capped at 8,192 characters. All cursor results share the normal row and byte
allowance. Truncation and incomplete collection are reported explicitly.

Oracle DDL commits implicitly, and PL/SQL can commit or execute DDL. Execution details report
this behavior. Cancellation or rollback cannot promise to undo earlier DDL. Error decisions,
result bounds and disposal of the execution session remain in effect.

## Result grids

Direct single-table scalar projections with a verified non-null key support Oracle server
paging, value filtering, counts, reviewed row edits and bounded exports. Source-column
verification handles Oracle's omitted JDBC table provenance and SQL aliases. Grid operations
and exports are bound to the captured service/PDB identity. Joins, ambiguous projections,
unsupported types and incomplete/truncated values remain read-only.

Oracle DATE values retain their time component; edits with fractional seconds are rejected.
Unconstrained NUMBER values retain up to 38 significant digits and are checked against
Oracle's exponent range. National text uses national-character parameter binding. Empty
text follows Oracle's NULL semantics and is rejected for required columns. Transactional
locks, original-value comparisons and affected-row checks protect reviewed saves; a failed
batch rolls back together. SQL exports use explicit timestamp literals and bounded Unicode
chunks. SQL date exports currently require AD years 1–9999.

## Query builder and estimated plans

The visual builder loads native Oracle view and materialized-view SELECT definitions and
retains the resolved PDB. It imports representable joins, preserves unsupported SQL in text
mode, and emits Oracle table aliases. The function picker reads bounded owner-scoped native
signatures, including standalone functions and package overloads, defaulted arguments and
separately quoted package/member names. Composite, output, pipelined and aggregate signatures
remain unavailable in the visual picker; use Script for those routines.

Builder numeric values retain decimal text through the browser and bind as JDBC NUMBER.
Typed NUMBER inputs exceeding 38 significant digits or the supported exponent range are rejected
before execution. Date and timestamp parameters use typed JDBC values, independently of
session date/number formats; timestamp casts retain nine fractional digits. The Oracle 19c
builder offers NUMBER and TIMESTAMP instead of SQL BOOLEAN and TIME controls. Parameter
values remain transient and are excluded from saved/recovered query drafts.

Typed scalar IN values also work in retained grid paging and estimated plans. Callable output
parameters and LOBs are excluded from those retained SELECT inputs. Explain uses a private
statement identifier and rolls PLAN_TABLE writes back to its savepoint; it does not execute
the query. Native PLAN_TABLE and DBMS_XPLAN privileges are still required.

## Table properties and creation

New Table and table Properties support Oracle 19c+ ordinary heap tables through the existing
review-and-apply workflow. Columns use bounded Oracle types, exact numeric declarations,
identity/virtual definitions for new columns, defaults, nullability and comments. Reviewed
constraint/index additions, index rename/removal and table/column renames use native Oracle
syntax. Existing identity/virtual settings and primary-key storage/constraint attributes
require native DDL review when changing them. Owner and tablespace changes are not silently
reconstructed from the form.

The native table definition participates in stale-plan detection and appears in the DDL tab.
Unchanged invisible columns and storage settings are preserved. Partitioned, temporary,
index-organized, nested, external, materialized, custom-type, domain-index and row-policy
objects require native DDL review. Missing native-definition privileges disable structured
editing explicitly. Oracle DDL is non-atomic: apply stops at the first failure and reports
acknowledged committed steps. Cancellation first requests JDBC cancellation with a bounded
worker-interruption fallback. Interrupted non-atomic DDL reports an unknown outcome: Oracle
can keep running a server-side DDL trigger after the client disconnects. Check the destination
before retrying. The cancellation fixture explicitly disconnects its own sleeping session
during cleanup; it does not certify immediate server-side termination. Creating an identity
column may require CREATE SEQUENCE in
addition to CREATE TABLE on the tested server.

## Object actions and migrations

Tree actions use the resolved PDB, owner, object identity and native definition to revalidate
reviewed changes. Tables, columns and indexes support native rename; supported schema objects
support reviewed deletion, tables support truncate, and materialized views support refresh.
Materialized-view action checks use bounded native catalog properties instead of expanding
DBMS_METADATA XML. The schema-wide Table Triggers category is distinct from a table's own
Triggers branch. Missing catalog/DDL privileges stop the operation explicitly.

Oracle 19c+ schema captures record the resolved container and owner in their fingerprints.
Migration preparation accepts native Oracle built-in types and mixed SQL/PLSQL, preserves
slash-delimited routines, and requires complete bounded native observations. Capture limits
or missing definition privileges prevent preparation. Available metadata does not prove that
inaccessible objects are absent. Dynamic SQL, database links, and administration DDL require
separate native or administration review.

Application and rehearsal both require exact one-time approval. Rehearsal must resolve to a
different database/container or owner; a second connection alias to the source is rejected.
Owner identifiers are remapped structurally without changing SQL literals. Synthetic fixtures
use explicit VALUES and bounded literal conversions, without reading application records.
Plans record the observed server version and recheck the captured schema before executing.
Oracle DDL is non-atomic: failures retain acknowledged steps, interruptions can leave unknown
outcomes, and newly invalid compiled objects cause a failed result with the committed steps
still reported. These workflows were tested on Free 23.26.3; standard 19c certification is pending.

## Scoped agent reads

The existing SELECT-permission dialog supports Oracle 19c+ connections with separate resolved
PDB and owner targets. Reusable reads use Oracle `SET TRANSACTION READ ONLY`, not the JDBC
advisory read-only flag. Ordinary accounts can read exact ordinary tables within their granted
scope, including bounded joins, nonrecursive CTEs, aggregates and OFFSET/FETCH paging. SYS
requires exact one-time review because Oracle does not enforce read-only transactions for SYS.

Sequence pseudocolumns, database links, synonyms, views, custom functions, virtual/custom
columns, external tables, domain indexes and row policies require exact one-time review.
Fixed catalog reads use qualified SYS views and bound identifiers; returned metadata is
filtered by the granted scope. Native DDL inspection uses DBMS_METADATA without executing
its output. Session lifetime, profile revisions, revocation and result-access checks remain
in force. One-time approved estimated plans use the native Oracle adapter and never execute
the selected query.

## Native properties, definitions and scans

Object properties include native definitions, status, compilation errors, incoming/outgoing
catalog dependencies, grants and routine arguments. Packages and object types retain separate
specification and body definitions. **Use definition as draft** selects Oracle-aware splitting
when multiple units are present. Trigger definitions retain their separate enable/disable
statements with native SQL terminators. Review and apply preserve native attributes; an invalid compilation
reports its diagnostics and any committed DDL instead of reporting a successful save.

Scoped scans verify the actual PDB and capture package/type bodies, catalog dependencies,
grants and invalid-object diagnostics. Each optional catalog category reports unavailable
privileges/provider metadata independently. Existing scan progress and current/last-run details
remain available. External Java assets and database-link credentials are explicitly marked;
they are not silently reconstructed. Scheduler and queue properties are captured. Supported
scheduler comparison is described below; queues and advanced scheduler variants require native review.

## Native comparison

Oracle 19c+ now uses the existing comparison wizard for native definition review and script
output. Capture follows selected types, required dependencies and incoming dependents.
Independent connections exchange captured XML documents; no database link is required.
Oracle's metadata transforms generate destination CREATE/ALTER statements without executing
those statements. Generation revalidates captured definitions and resolved target identities.

Tables, views, sequences, package/type specifications and bodies have live create/alter and
repeat-comparison coverage. Generated indexes, materialized views, routines, disabled triggers
and synonyms also execute successfully and produce identical repeat comparisons. Unsupported
transformations, external assets, dynamic SQL, ambiguous
identifier remapping and dependency cycles stop generation with an explicit reason. Owner-issued
grants and supported stored-procedure scheduler objects are covered below. Unvalidated queues,
database links, Java assets and advanced scheduler definitions are shown as blocked.

Schema remapping uses Oracle metadata transforms plus Oracle-aware identifier tokens for
routine/query text. Ordinary and alternative-quoted literals remain unchanged. Cross-owner
catalog capture requires SELECT_CATALOG_ROLE (or SYS); an owner can capture its own metadata
without that role. Destructive changes require visibility of incoming dependencies across
owners. Destination owners must already exist.

Column defaults referencing sequences participate in dependency ordering even when Oracle omits
those references from ALL_DEPENDENCIES. Discovery reads expressions without evaluating them.
Materialized-view storage tables and implicit indexes belong to the materialized view. Native
trigger creation and enable/disable statements remain separate executable units. Owner-qualified
SQL inside view queries, defaults and other supported metadata expressions is remapped before
comparison; literal text is preserved. Oracle metadata diagnostics such as "cannot alter" are
blockers, never accepted as executable script comments.

Optional sequence synchronization advances ordinary non-cyclic sequences to an observed
catalog/cache boundary and never consumes source NEXTVAL. Exact cached values are not claimed.
Scalable, sharded, session and cyclic sequence state remains blocked. The global synchronization
option leaves unsupported value controls unchecked. With synchronization off, a new ordinary
sequence starts at its initial bound. DDL commits
implicitly; copy/save/cancel remain the only actions in the generated-script screen.

Identity-owned generators appear as **Identity "table"."column"** under Sequences. They match
across owners using the owning column rather than Oracle's generated sequence name. Their
definition belongs to the table; optional value advancement uses ALTER TABLE and preserves
generation mode, increment, bounds, cache, ordering and replay retention. New owning tables and
all reviewed identity-definition changes must be included before advancing their generators.
Existing START WITH differences are treated as state, so a repeat structure comparison does
not reset an advanced destination. Explicit routine references to generated sequence names
require manual remapping. A real sequence colliding with the identity review name is rejected.

Live tests execute scripts for cached, descending, ahead-of-source and newly created identity
generators, including enabling synchronization during review. They verify unchanged source
boundaries, preserved BY DEFAULT ON NULL/ALWAYS behavior and an identical repeat comparison.
This value synchronization is separate from the current table-data restriction on identity columns.

## Optional table data

Select a data mode and explicitly include individual tables. Structure-only remains the
first/default mode and suppresses retained table-data selections. Catalog inspection does
not evaluate table rows. Numeric primary/unique keys and explicitly binary variable-width
text keys are supported; tables without a verified matching key require Replace all rows.

The Oracle adapter uses native column/key/foreign-key/index metadata. Generated scripts use
session temporary staging tables, explicitly validate restored foreign keys, and dispose of
the staging tables. CREATE TABLE privilege is required when executing a data script. Oracle
DDL commits independently; a failed script may leave changes, staging tables or disabled
constraints requiring review. Date literals use the Gregorian calendar and the script sets
the session calendar and time zone explicitly.

Captured values preserve NUMBER precision, DATE time components, fractional timestamps,
time-zone regions, UTC instants for local timestamps, Unicode, RAW, intervals, bounded binary
floating-point values, and BLOB/CLOB/NCLOB null versus empty values. Non-finite floats are
blocked. Text LOBs are bounded to 512 Ki characters and binary values to 1 MiB, subject to the
shared 2 MiB row, 128 MiB data and 64 MiB script limits. Long literals are emitted in bounded
PL/SQL chunks with temporary-LOB cleanup. Generation revalidates captured source and
destination rows before publishing the script.

Identity transitions, user-defined column types, enabled triggers, row security, specialized
indexes and disabled/unvalidated/deferrable constraints require dedicated validation and are
reported as unavailable for data comparison. Changing table definitions while copying data
requires selecting all reviewed native table changes. Replace/mirror on existing tables require
destination SELECT_CATALOG_ROLE to verify incoming foreign keys across owners. These limits do not disable native
structure review for otherwise supported objects.

## Administration

Open **Workspace settings > Oracle Administration** for a viewport-sized workspace. The resolved
service, container, user, edition and server version remain visible. Catalog pages are bounded
and filtered, and report unavailable dictionary privileges explicitly. Users, roles, profiles,
grants, quotas, tablespaces/files, sessions/locks/active SQL, diagnostics, compilation, statistics,
directories and the Data Pump job roster are available.

Typed actions first generate a five-minute, single-use SQL review bound to the target, connection
revision, privileges and selected catalog state. Applying a stale review fails before mutation.
Account passwords are supplied only at Apply and excluded from retained plans and results.
Reviewed operations cover account/role/profile management, grants, quotas, storage files,
exact-session disconnection/termination and exact-SQL cancellation, compilation and statistics.
DDL commits implicitly; cancellation during execution is reported as uncertain. Acknowledged
steps, start/end times, compilation diagnostics and failures remain visible in the workspace.
Oracle-maintained/common accounts and system/undo storage require separate native review.

Data Pump supports reviewed schema export/import, content selection, owner remapping, existing-table
handling, and stop/resume. Directory READ/WRITE grants and input/output file existence are checked
before applying. Dump files are never overwritten. Jobs use one worker across editions and retain
Oracle's master table for restart; resume requires the original job owner. Missing catalog access
falls back to the account's own jobs. Opening the workspace rediscovers Oracle jobs after restart.
Select a job to inspect its start time, state, data progress, phase, errors and restart count, or
refresh status every three seconds while the workspace remains open. Closing the window stops
application polling; Oracle continues until completed or explicitly stopped. Cancelling an
application request does not imply cancelling the Data Pump workers.

The latest status observation for up to 200 jobs is stored privately (at most 2 MiB), scoped to
the connection and resolved target. A missing/inaccessible job remains unknown even if a previous
observation exists. Oracle log files remain authoritative when terminal status was not observed.
Data Pump diagnostic text containing credential markers is omitted from retained observations.

**RMAN scripts** generates backup, database-file validation, and restore-backup validation scripts
for the resolved PDB or database. Scripts contain no credentials and have copy/save controls;
the application has no RMAN execution path. A PDB script instructs the operator to connect RMAN
to the matching CDB root. Backup directory prerequisites and the database-wide control-file
backup are explicit; shared archived logs are optional.

## Validation

Tested using Oracle Free 23.26.3 full, JDBC ojdbc17 23.26.3.0.0 and JDK 25.
Image: `gvenzl/oracle-free:23.26.3-full`, digest
`sha256:26d4e51430b185f7cc51f136b166df4766a288ba34d05d710ec0d96664f35660`.
The owned container uses a 3 GiB limit, two CPUs and a random loopback port.

`OracleIntegrationTest` requires the ownership-gated DBA_ORACLE environment and exercises
real connection tests, resolved targets, explicit SYSDBA, least-privileged users, mixed scripts,
38-digit numbers, Unicode, temporal values, bounded LOB/cursor/output results, INOUT routines
and partial DDL commits, native package/type editing, compilation diagnostics and scoped scans. `OracleSqlTest` and `SqlScriptTest` cover deterministic validation.
The optional `oracle-sql` browser suite requires the same disposable environment plus the
verified JDBC JAR; it checks parameters, cursor grids, server output, repeated executions,
Oracle grid edits/paging, reviewed package/body changes, exact builder inputs, native package-function selection and completed estimated plans.

The optional `oracle-compare` browser suite covers automatic target tests, native definition
review, per-table data selection and previews, structure-only switching, independent sequence synchronization, viewport layout, complete
copy/save and artifact disposal. Live tests execute application-generated scripts against
separate disposable owner connections, verify the destination, reject stale evidence and
repeat the comparison. Deterministic tests cover XML bounds, remapping, sequence advancement
and dependency blockers. Oracle data checks cover all four modes, invisible columns,
38-digit values, named time zones, fractional/local timestamps, Unicode and empty/null LOBs,
stale rows, and foreign-key restoration when a child changes parent. Independent source and
destination row captures and generation-time checks use bounded virtual workers; cancellation
joins both sides before releasing their private snapshots.

Builder validation also covers native view/materialized-view imports, joins, standalone and
package overload signatures, defaulted/output arguments, nondefault NLS settings, nine-digit
timestamps, typed grid paging and PLAN_TABLE cleanup without executing the selected query.

Scoped-read validation covers ordinary-user reads, exact NUMBER values, CTEs and aggregates,
filtered columns/keys/indexes/DDL, Oracle-enforced write rejection, unchanged source sequence
state, custom function/synonym rejection, SYS exclusion, session separation and revoked-result
access. Fixture setup waits for newly created tables to support a read-only snapshot;
production SQL is never automatically replayed after ORA-01466.

Administration validation covers all 15 catalog pages, ordinary-user privilege failures,
password exclusion, single-use/stale plans, account/role/profile/grant changes, compilation,
statistics, and disposable tablespace/file operations. Live session tests verify cancellation
leaves the connection usable, while reviewed disconnection/termination closes the exact session.
The `oracle-admin` browser suite checks account creation/deletion, review invalidation, password
clearing, catalog selection/filtering, last results and desktop/mobile viewport layouts.

Data Pump tests execute export/stop/rediscovery/resume/import and verify 100 destination rows.
A separate ordinary-user test verifies schema isolation, directory permissions, catalog fallback,
and private observation history after an application restart. RMAN tests verify scope and paths;
Oracle's syntax-only RMAN checker accepts the exact browser-downloaded backup script. Browser
checks also cover deterministic Data Pump status polling, retained observations, terminal-state
handling and complete RMAN copy/save. Standard Oracle 19c certification remains pending.

### Object grants in comparison

Selected objects carry their owner-issued object and column grants. Destination users/roles
must already exist; owner mapping also maps an owner grantee. Adding or extending a grant
is included in the reviewed script. Removal or downgrade requires destructive schema changes;
REFERENCES revocation, delegated grant chains, and common/inherited container grants are
blocked for separate dependency review. Grant changes invalidate captured comparison state.
Live validation covers table creation, role/user and column grants, stale grants, reviewed
revocation, and an identical repeat comparison after applying the generated script.

### Scheduler comparison

Comparison supports native stored-procedure programs and regular jobs callable without job
arguments, plus one-time and calendaring schedules. Native attributes, comments and NLS
settings are retained. Typed scheduler names and procedure references are remapped separately
from ordinary text literals. Procedures/packages and their dependencies remain visible and
selectable in review even when only Scheduler was selected in the preceding step.

Replacing a program or schedule requires reviewing recreation of its incoming jobs, with
destructive changes enabled. Destination-only dependents block replacement. Program grants
are restored after recreation. Jobs are created disabled; programs and jobs are enabled in
dependency order only after definitions, data, constraints and sequence changes are complete.
Run generated scripts during an exclusive maintenance window with scheduler activity paused.
Enabled jobs may start as soon as the script enables them. The comparison service never runs
the generated script or invokes a job action.

Active jobs, ambiguous/missing procedure signatures, job arguments, detached programs,
PL/SQL job blocks, external assets/credentials, custom job classes, events, lightweight jobs,
chains and named calendar composition are blockers for separate review. These variants are
still available in the catalog; the comparer does not silently approximate their definitions.


### Final regression evidence (2026-09-24)

The complete DBA/core Java run passed 547 tests, including all 24 live Oracle integration
cases, with zero failures/errors and 71 environment-gated skips. Separate disposable vendor
runs passed seven live checks: PostgreSQL 16.14 (three), MySQL 8.4.11 (two), and MariaDB 11.4.13
(two). PostgreSQL-only scope cases are skipped on the other two engines. Generated scripts
execute successfully and catalog scans finish; PostgreSQL also verifies a 151-table scope.
All 35 Maven reactor modules compile with JDK 25.

The final browser pass covers Oracle SQL/editors, Oracle comparison, Oracle Administration,
shared comparison, the six-tab connection editor and embedded Maven download settings. All
six suites pass. Embedded Maven's custom PEM and TLS-verification bypass also pass actual
HTTPS tests, including strict-mode rejection, hostname verification and isolated trust settings.

| Fixture | Image digest |
| --- | --- |
| Oracle Free 23.26.3 | `sha256:26d4e51430b185f7cc51f136b166df4766a288ba34d05d710ec0d96664f35660` |
| PostgreSQL 16.14 | `sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b` |
| MySQL 8.4.11 | `sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a` |
| MariaDB 11.4.13 | `sha256:70cc072b29b4a89ae07abb2d4da2c64678a7f2dfe092751bb51c87d67dc1338b` |

Owned containers and the newly pulled, unused Oracle/MariaDB images were removed after testing.
All 55 pre-existing image IDs remain. Vendor fixtures run one engine at a time with one CPU
and 1 GiB per container; Oracle used two CPUs and 3 GiB. Image identities are also recorded
by the vendor harness in its private build evidence. No build artifacts are included in delivery.
This validates the documented capability boundaries; standard Oracle 19c administration
certification remains pending a suitable instance.
