# Oracle DBA support

The Oracle expansion is in progress; see [the implementation plan](dba-oracle-implementation-plan.md).
The connection, SQL execution and native comparison foundations described here are implemented.
Complete Oracle catalog coverage, data comparison and the administration workspace are still being developed.
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

## Native properties, definitions and scans

Object properties include native definitions, status, compilation errors, incoming/outgoing
catalog dependencies, grants and routine arguments. Packages and object types retain separate
specification and body definitions. **Use definition as draft** selects Oracle-aware splitting
when both units are present. Review and apply preserve native attributes; an invalid compilation
reports its diagnostics and any committed DDL instead of reporting a successful save.

Scoped scans verify the actual PDB and capture package/type bodies, catalog dependencies,
grants and invalid-object diagnostics. Each optional catalog category reports unavailable
privileges/provider metadata independently. Existing scan progress and current/last-run details
remain available. External Java assets and database-link credentials are explicitly marked;
they are not silently reconstructed. Scheduler and queue properties are captured, but complete
restoration of those categories and Oracle comparison remain under development.

## Native comparison

Oracle 19c+ now uses the existing comparison wizard for native definition review and script
output. Capture follows selected types, required dependencies and incoming dependents.
Independent connections exchange captured XML documents; no database link is required.
Oracle's metadata transforms generate destination CREATE/ALTER statements without executing
those statements. Generation revalidates captured definitions and resolved target identities.

Tables, views, sequences, package/type specifications and bodies have live create/alter and
repeat-comparison coverage. Native adapters also inspect indexes, materialized views, routines,
triggers and synonyms; unsupported transformations, external assets, dynamic SQL, ambiguous
identifier remapping and dependency cycles stop generation with an explicit reason. Complete
scheduler/grant generation and broader Oracle data variants remain in progress.
Unvalidated queue, database-link, Java and scheduler definitions are shown as blocked.

Schema remapping uses Oracle metadata transforms plus Oracle-aware identifier tokens for
routine/query text. Ordinary and alternative-quoted literals remain unchanged. Cross-owner
catalog capture requires SELECT_CATALOG_ROLE (or SYS); an owner can capture its own metadata
without that role. Destructive changes require visibility of incoming dependencies across
owners. Destination owners must already exist.

Optional sequence synchronization advances ordinary non-cyclic sequences to an observed
catalog/cache boundary and never consumes source NEXTVAL. Exact cached values are not claimed.
Scalable, sharded, session and identity-owned sequence state is blocked pending dedicated
validation. With synchronization off, a new sequence starts at its initial bound. DDL commits
implicitly; copy/save/cancel remain the only actions in the generated-script screen.

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
