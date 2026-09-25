# PostgreSQL, MySQL and MariaDB DBA workflows

Open **Workspace settings > PostgreSQL / MySQL Administration** for native catalogs,
reviewed operations and manual backup/restore scripts. The connection and database are
resolved before loading the workspace. Missing privileges are reported per category;
an empty catalog is not proof that an object does not exist. MariaDB is a separate dialect.

## Everyday SQL and table editing

MySQL/MariaDB New Table and Table Properties use SHOW CREATE TABLE and native catalogs.
Reviewed edits preserve unexposed charset/collation, unsigned types, generated/invisible
columns, ON UPDATE expressions and table options. Columns, defaults, comments, primary
keys, indexes, checks and foreign keys support incremental changes. Existing generated
expressions, AUTO_INCREMENT ownership, ENUM/SET type edits and partition reorganization
use the native SQL editor; the form identifies these limits. ALTER can rebuild or lock a
table, and DDL can commit even when a later statement fails.

The Script editor accepts standalone DELIMITER directives around procedures, functions,
triggers and events. Directives are removed before JDBC execution. Quotes, comments,
NO_BACKSLASH_ESCAPES and ANSI_QUOTES determine statement boundaries. Literal SQL-mode
assignments update parsing of later units; dynamic assignments must be last. Arbitrary
version-executable comments remain rejected rather than hiding statement boundaries.
The human editor retains its 32-unit and size budgets. Native definition editors and
migration plans use the same extraction rules. Rehearsals require matching SQL modes.

PostgreSQL/MySQL typed parameters use JSON objects such as
`{"mode":"inout","type":"DECIMAL","value":"12345678901234567890.12345678"}`.
Use strings for exact numbers. IN, OUT and INOUT scalar values support exact decimal,
integer, Boolean, date/time, text and bounded binary values; driver support is checked
at execution. Oracle-only REF CURSOR parameters are rejected by these engines.
Multiple JDBC results remain separate result tabs. Existing query and result limits apply.

## Compare

Select both targets, choose object types, review differences, then generate an ordered
script. The application never executes a comparison script. Copy and Save use the whole
artifact; Cancel disposes it. Dependency objects are visible in review and identified
separately; choose required dependencies when generation reports an unresolved selection.
Structure only remains the default. Table data is individually selected, and sequence
synchronization remains independent. Comparison metadata uses the persisted comparison
allowance, not the object editor's 1 MiB limit.

PostgreSQL supports dependent view/materialized-view rebuilds, native index and comment
restoration, ownership and explicit object/column grants, routine return/signature transitions,
append-only enum additions, domain default/nullability/constraint changes, table triggers,
rules and row security policies, identity attributes and ordinary table storage settings.
Enum additions commit before the transaction that uses new labels. Destination roles and
tablespaces are checked. Newly created objects have destination default ACL additions
removed when necessary to reproduce the reviewed source's explicit privileges.

A bounded declarative partition adapter creates new RANGE/LIST/HASH parent/child families
in dependency order. Families with constraints, indexes or triggers, existing partition
reorganization, inherited/foreign tables, generated PostgreSQL columns, narrowing type
conversions and adding/removing identity ownership require dedicated native migrations.
Partition data synchronization remains disabled. Existing named scheduler jobs are not
recreated by comparison; manage refresh schedules through the existing scheduler editor.
Extension-owned objects must be managed through their extension. Dynamic SQL, unsupported
routine languages, delegated PostgreSQL grant chains and unresolved outside dependencies
stop generation. Cross-phase dependencies such as a domain requiring a new function or
a routine requiring a new view need an explicitly staged migration.

MySQL/MariaDB support native existing-table ALTER plans, views, procedures, functions,
triggers, events and explicit table/column/routine grants. Full native structural table
changes are reviewed together. SHOW CREATE preserves stored-program SQL mode, charset,
security and event time zone. Definers default to preservation with a matching destination
login; an explicit option maps them to the destination login. Object grants retain user
**and host** identity, require visible native grant catalogs, and validate destination
principals. Destructive column/type changes and native object replacement require
including stored programs when incoming program dependencies have not been inspected.
Account/global privileges are managed separately in Administration.

**Advance table AUTO_INCREMENT values** is unchecked by default and independent of row
comparison. It never requests a value below either captured counter and aligns to the
observed destination increment/offset; the server also enforces existing row maxima.
It does not consume source values. Counter state is not a standalone sequence.

Generated scripts preserve destination-only objects. Destructive changes require the
review option. Native table attributes absent from forms remain in native definitions.
MySQL partition-layout changes, trigger-bearing table alterations and unverified data
codecs stop with a specific reason. Generating for a destination older than the source dialect is blocked;
server version compatibility is not a certification claim for untested releases.

## Values, grids and exports

Comparison preserves exact decimal/unsigned values, null versus empty text, Unicode,
bounded binary/text values, JSON, native temporal precision, PostgreSQL enum/domain and
built-in arrays, and MySQL multibit values. MySQL timestamp row capture and generated
scripts use UTC and restore session settings; DATETIME retains its wall-clock value.
Unknown array element codecs, non-finite numeric data, unsafe text matching-key collations,
MySQL zero/invalid calendar dates, row security/triggers requiring behavioral validation and oversized values are explicit
blockers. All four existing data modes retain stale-row verification and cleanup.

Grid editing remains limited to verified scalar codecs and row identities. Unsigned,
multibit, timezone/LOB and other unverified editing types remain read-only; this does not
prevent supported comparison codecs from working. Numeric query-builder filter input is
kept as exact text. SQL export rejects unsupported multibit conversion instead of changing
it to a Boolean; CSV/TSV/XLSX preserve displayed values. XLSX exact numerics beyond Excel's
precision are emitted as text. Existing optimistic concurrency, paging, bounded exports,
agent scopes, saved queries and isolated migration rehearsals remain in use.

## Administration and logical backup artifacts

Catalogs cover native accounts/roles/membership/grants, schemas/databases, sessions and
active SQL, locks, diagnostics, statistics, extensions, available replication observations
and pg_cron/Event Scheduler. PostgreSQL default privileges have a separate catalog and
reviewed grant/revoke operations. Mutations include account creation/password/login state,
role membership, object grants, schema creation, native table maintenance, PostgreSQL
session cancellation/termination and extension operations, and MySQL schema defaults and
event enable/disable.

Reviews expire after five minutes, bind connection revision and resolved server identity,
recheck affected objects and privileges, and can be applied once. Passwords are sent only
on Apply and are never retained in a plan. Reports distinguish acknowledged steps,
not-applied, partial and unknown outcomes. Cancellation retains available outcome details.
PostgreSQL table owners can maintain their tables; global administration requires a
superuser. MySQL administration requires explicit global administrative grants; inherited
role authority is not inferred. These endpoints reuse browser authentication and CSRF and
are unavailable to agents.

Backup/restore produces a **manual Bash artifact**, not a monitored backup job. Choose
pg_dump/pg_restore, mysqldump/mysql, or mariadb-dump/mariadb and absolute execution-host
paths. The artifact checks executable/client-major compatibility, refuses to overwrite an
existing backup path, checks restore-file readability and uses password prompting. TLS
verification is the default with optional CA path; require/disable are explicit choices.
The app does not inspect execution-host paths, archives or passwords, or run these tools.
Use a fresh restore destination and review transaction/nontransactional-table limitations.
Physical backups, operating-system administration and infrastructure provisioning are outside
this workspace. No logical artifact is a claim of physical restore certification.

See [the roadmap and validation evidence](dba-postgresql-mysql-implementation-plan.md).
