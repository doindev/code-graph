# Oracle DBA support

The Oracle expansion is in progress; see [the implementation plan](dba-oracle-implementation-plan.md).
The connection and SQL execution foundation described here is implemented. Complete Oracle
comparison, catalog coverage and the administration workspace are still being developed.
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
verified JDBC JAR; it checks parameters, cursor grids, server output, repeated executions and reviewed package/body changes.
