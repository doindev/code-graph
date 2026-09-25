# PostgreSQL and MySQL DBA support: audit and implementation plan

Audit baseline: `cdb9d877` (2026-09-24). This document distinguishes implemented behavior
from proposed work. It does not certify features merely because their driver connects.
Prioritize PostgreSQL and MySQL daily workflows and comparison before further Oracle expansion.
Apply the same native-metadata, privilege, cancellation and live-execution validation standards
used for the Oracle work. The original audit changed documentation only; implementation delivery notes follow below.

## Confirmed baseline and gaps

| Area | Current evidence | Work needed |
| --- | --- | --- |
| PostgreSQL comparison | Native ordinary tables, constraints/indexes, enum creation, views, stable-signature routines, sequences, four eligible-table data modes; bulk dependency closure and generation-time revalidation. | Dependency-preserving view/materialized-view rebuilds, routine signature transitions, existing type/domain changes, triggers/rules/policies, grants and ownership policy. Partitioning, specialized storage and conversions need explicit adapters. |
| MySQL comparison | Native new ordinary tables, foreign keys, views and four eligible-table data modes. | Existing table alterations are explicitly rejected; routine generation is explicitly unavailable. Add verified native changes and stored-program formatting, dependencies, triggers/events, grants and AUTO_INCREMENT state handling. |
| Table designers | PostgreSQL ordinary tables support creation and reviewed incremental changes. MySQL is absent from the New Table adapter and is read-only in Table Properties. | A native MySQL designer preserving unexposed attributes. PostgreSQL specialized table support and capability-aware field/size limits. |
| Object editors | PostgreSQL has native property forms for many categories. MySQL can expose native definitions but lacks equivalent property-form support. | Native MySQL definitions, parameters and reviewed edits with explicit SQL modes, definers and implicit-commit outcomes. |
| Metadata isolation | Table and non-table comparison now use comparison-specific metadata limits; selected-object dependency scopes are aligned across source and destination before full definitions are captured. Optional metadata allowance persists across restarts. | Continue native capability and least-privilege coverage; H2/MariaDB view dependency discovery retains a bounded query-text fallback. |
| SQL scripts | PostgreSQL dollar quoting is supported; Oracle has a dialect-specific PL/SQL extraction path. MySQL DELIMITER directives are explicitly rejected. | MySQL-aware lexical extraction for stored programs and client delimiter directives, mode-sensitive escaping and complete statement preservation. JDBC receives statements without client directives. |
| Scans and operations | Shared current/last scan state, cancellation, bounded jobs, saved connections, migration reviews and scoped reads exist for these engines. PostgreSQL pg_cron and MySQL Event Scheduler have providers. | Validate each native category under limited privileges and large catalogs; test terminal outcomes, cleanup, scheduler state and partial failures on each engine. Extend existing providers rather than replacing them. |
| Administration | The dedicated Administration workspace is Oracle-specific; some PostgreSQL/MySQL capabilities exist as tree actions, properties or scheduler operations. | Equivalent engine-aware administration using shared authentication, reviewed plans and asynchronous jobs. |
| Validation | Live comparison/scan evidence: PostgreSQL 16.14 and MySQL 8.4.11, plus MariaDB 11.4.13 regression protection. PostgreSQL has broader dedicated workflow tests. | A per-feature/version matrix with independent source/destination instances, least-privileged accounts and realistic catalog/data complexity. MySQL and MariaDB require separate results. |

Relevant implementation anchors:

- `code-graph-dba/src/main/java/io/doindev/codegraph/dba/CompareSql.java`: explicit existing-table, routine, type and dependency-rebuild gates.
- `code-graph-dba/src/main/java/io/doindev/codegraph/dba/CompareCatalog.java`: capture, table-designer coupling, scoped definitions and data restrictions.
- `code-graph-dba/src/main/java/io/doindev/codegraph/dba/TableCreation.java` and `TableDesigner.java`: creation/editing capability gates and metadata limits.
- `code-graph-dba/src/main/java/io/doindev/codegraph/dba/ObjectForms.java`: native property-form coverage.
- `code-graph-dba/src/main/java/io/doindev/codegraph/dba/SqlScript.java`: script extraction and MySQL delimiter rejection.
- `code-graph-dba/src/main/java/io/doindev/codegraph/dba/ScheduledJobs.java`: existing native scheduler providers.
- `code-graph-dba/src/test/java/io/doindev/codegraph/dba/CompareVendorIntegrationTest.java`, `PostgresIntegrationTest.java`, and the workflow/read-permission vendor tests: existing live evidence.

## Delivery order

### 1. Shared capture and validation foundations

Separate comparison evidence for tables from property-editor models. Use scoped native catalog
reads, explicit comparison budgets, bounded field/row consumption and shared deadlines. Retain
complete dependency evidence, selected-type closure, incoming dependents and stale-state checks.
Do not silently truncate metadata or weaken a blocker to make a comparison appear successful.
Audit loading, comparison and generation separately; a fix to one path must cover recapture.

Build a capability matrix based on observed engine/version, SQL modes, privileges and target
identity. Existing comparison gates accept PostgreSQL 12+ and MySQL 8+; those gates are not
proof of certification on every release. Start from the tested versions above and add explicit
fixtures for deployment versions before advertising them. Preserve MariaDB compatibility with
separate capability decisions and tests.

Acceptance: large type catalogs on both connections, many schemas/objects, quoted identifiers,
limited catalog privileges, metadata-budget rejection, cancellation/deadline cleanup and no row
reads in structure-only mode. Regression tests must reproduce the original failure before
proving the supported path succeeds.

### 2. MySQL everyday SQL and table editing

Implement a MySQL dialect/capability adapter and native table creation/properties. Capture
SHOW CREATE plus structured catalog attributes, including charset/collation, unsigned types,
defaults/on-update expressions, generated/invisible columns, indexes, constraints, storage
engine and supported partition attributes. Preserve attributes absent from the form; unsupported
changes stay read-only with a precise explanation.

Implement stored-program script extraction for procedures, functions, triggers and events.
Handle DELIMITER outside literals/comments, SQL modes including escaping and identifier quoting,
compound bodies, routine parameters and multiple results. Reuse this extraction in Script,
native editors, migrations and generated files. Preserve exact definitions, security attributes,
definers and schema-qualified references. Definer changes require an explicit mapping/policy.

Review DDL algorithm/lock requirements and report implicit commits, acknowledged steps and
unknown outcomes after connection loss. Do not promise rollback for MySQL schema changes.

Acceptance: actual create/edit/reopen and round-trip native definitions, non-default SQL modes,
Unicode, unsigned/precise values, temporal precision, generated/invisible columns, limited users,
concurrent definition changes, and failure after an earlier DDL step commits.

### 3. Complete useful comparison transitions on both engines

Deliver each object family through capture, difference review, generation, execution on a
disposable destination, and repeat comparison before moving to the next family.

PostgreSQL priorities:

- Views/materialized views and incoming dependencies, preserving grants, comments, ownership,
  storage and supported refresh configuration through reviewed rebuilds.
- Routine overload/signature changes and dependent objects; preserve language, security,
  configuration and native bodies. Dynamic references remain explicit blockers when unresolved.
- Enum/domain transitions, identity definitions, and supported partition/storage changes with
  version checks and separate data-conversion requirements.
- Triggers, rules, row-security policies, grants and default privileges. Extension-owned objects
  require extension-aware handling; do not reconstruct them as unrelated standalone objects.

MySQL priorities:

- Existing-table ALTER plans for columns/defaults, indexes, checks and foreign keys. Sequence
  dependent operations correctly; preserve charset/collation and native table attributes.
- Views, routines, triggers and events with security/definer handling and native attributes.
- AUTO_INCREMENT state as a distinct table-owned capability, accounting for existing rows and
  increment/offset configuration. It must not be presented as a PostgreSQL-style sequence.
- Object/account grants with explicit user-and-host identity, existing destination principals,
  privilege requirements and reviewed destructive changes.

Both engines retain structure-only as default, explicit per-table data selection and independent
supported generator-state options. Compare output remains copy/save/cancel and is never executed
by the application. Destination-only objects remain preserved unless a separately reviewed
feature explicitly supports their removal.

Acceptance: additive and destructive transitions, dependency cycles, outside dependents,
selected subsets, stale definitions/data, attributes not shown in forms, destination compatibility,
complete downloaded scripts and identical repeat comparisons after applying those scripts.

### 4. Data, grids, exports and migrations

Audit each advertised native value type end to end rather than relying on generic JDBC string
conversion. PostgreSQL coverage should include exact numerics, timestamps/time zones, bytea,
JSON/JSONB, arrays, enum/domain values and any additional types explicitly enabled by the adapter.
MySQL coverage should include unsigned integers, decimal precision, BIT, temporal precision and
SQL-mode-sensitive dates, Unicode/collations, JSON, ENUM/SET and bounded binary/text LOBs.
Unsupported codecs remain explicit until round-trip behavior is verified.

Validate grid row identity, optimistic concurrency, typed filtering/paging, exports and all four
comparison data modes. Cover null/empty distinctions, key collation, foreign-key cycles, unique-key
transitions, generated columns, trigger-bearing tables and no-key replacement. Preserve transaction
cleanup, bounded memory/disk use and partial-failure reporting. Extend reviewed migrations and
isolated rehearsals using the same native parsers and target/capability rules.

### 5. PostgreSQL and MySQL Administration workspaces

Reuse shared review/job mechanisms while keeping engine-specific SQL and privileges explicit.

- PostgreSQL: roles/membership/grants, databases/schemas, sessions/locks/active SQL, diagnostics,
  statistics and maintenance, extensions, and available replication/scheduler observations.
- MySQL: users/host identities/roles/grants, schemas and defaults, sessions/locks/active SQL,
  diagnostics, statistics/maintenance, Event Scheduler, and available replication observations.
- Backup/restore: generate and review appropriate native-tool plans/artifacts; validate tool and
  server compatibility, paths, target identity and credential handling. Manual scripts must be
  clearly distinguished from monitored jobs. Do not claim physical backup or restore coverage
  from a logical-export test.

Mutations require existing authentication/CSRF protections, scoped privileges, expiring reviewed
plans and accurate outcomes. Operating-system and cloud infrastructure administration remain
outside this work. All dialogs use application styling and accessible viewport layouts.

## Validation and release requirements

Each delivered feature needs a recorded engine/version/privilege result, not only a passing
shared test. Run independent disposable databases and execute application-generated scripts;
verify definitions, selected data, preserved attributes and a repeat comparison. Include large
catalogs, many types, long identifiers/definitions, non-default collations/SQL modes and injected
failures. Exercise browser review/navigation, progress, cancellation, complete copy/save and
artifact disposal. Keep JDK 25 virtual-thread concurrency bounded and measure correctness before
optimizing throughput.

Record image digests and driver versions. Preserve pre-existing Docker resources and remove only
owned containers and unused images pulled for the tests. Publish capability limitations alongside
the tested behavior. Commit and push each completed, validated slice so unrelated engine work
does not delay a useful fix. Passing connection tests, a small schema or a shared-engine test does
not justify marking all PostgreSQL/MySQL support complete.


## Delivery notes: scoped comparison metadata (2026-09-25)

The comparison capture/limits portion of delivery step 1 is implemented. Native table evidence
no longer calls TableDesigner. PostgreSQL and MySQL capture full definitions only for the
selected types/objects and their dependency scope, agree counterparts across targets, and
repeat that scope during generation. Required dependencies and incoming dependents remain
part of stale-state verification. Table-owned implicit definitions avoid duplicate loads.
The optional persisted metadata allowance defaults to 16 MiB and supports 1-256 MiB with
memory admission; editor and independent native safety limits are retained.

This delivery does not mark the rest of this roadmap complete. Native MySQL designers and
stored-program parsing, additional PostgreSQL/MySQL change families, the broader value-fidelity
audit, and administration/backup work remain as described in steps 2-5. Existing unsupported
operations remain explicitly blocked.


Validation for this delivery:

- Full offline reactor: 600 DBA tests, 0 failures/errors, 97 environment-dependent skips;
  28 core tests, 0 failures/errors, 1 skip. The 59 focused capture/settings/comparison/editor/
  Oracle-document tests also passed.
- Independent live source/destination containers: PostgreSQL 16.14 (5 checks passed),
  MySQL 8.4.11 (3 passed; 2 PostgreSQL-only checks skipped), and MariaDB 11.4.13
  (3 passed; 2 PostgreSQL-only checks skipped). Drivers: PostgreSQL 42.7.13,
  MySQL 9.7.0, MariaDB 3.5.7. Generated scripts were applied only by the test harness.
- Browser comparison suite passed, including saved/reset metadata allowance, connection
  failures, object/data options, review, copy/save, viewport geometry and artifact disposal.
- All task-owned containers were removed. The newly pulled MariaDB image was removed when
  unused; all 55 distinct pre-existing image IDs remained present. No existing containers
  were started, stopped or removed by these tests.
- Oracle SQL/document/compare unit regressions passed. This delivery does not add live Oracle
  certification; standard Oracle 19c administration certification remains pending.

| Fixture image | Tested digest |
| --- | --- |
| `postgres:16` | `sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b` |
| `mysql:8.4` | `sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a` |
| `mariadb:11.4` | `sha256:70cc072b29b4a89ae07abb2d4da2c64678a7f2dfe092751bb51c87d67dc1338b` |
