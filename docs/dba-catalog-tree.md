# DBA catalog navigation

For the current dedicated object tabs, New/Delete/Truncate and materialized-view Refresh,
see [Database object editors](dba-object-editors.md). The limited modal creation adapter
described below is retained for API compatibility; the tree now opens dedicated tabs.


## Creating objects from category menus

Supported category menus show **New**, a separator, then **Refresh**, using the
Lucide file-plus icon. Right-click and the menu button open the same actions.
For Tables, New opens a [Table Properties draft tab](dba-table-designer.md#new-tables).
Other supported categories open a connection/database/schema-bound dedicated object draft tab.
Opening a draft does not execute SQL.
Review SQL produces a five-minute, browser-session-owned, single-use plan. Only
Apply executes that reviewed plan. Edit draft invalidates the review; Cancel/X
discard it and request cancellation of outstanding work. Database permissions are
checked by the database, not inferred from catalog visibility.

| Tested adapter | New categories |
| --- | --- |
| PostgreSQL | Schemas, Tables, Views, Materialized Views, Sequences, Indexes |
| H2 | Schemas, Tables, Views, Sequences, Indexes |

Tables accept named typed nullable/non-nullable columns; indexes accept a table
and a list of column names. Views accept one SELECT without prepared parameters.
Sequences use native defaults. Advanced definitions remain available in the SQL
editor. This is a limited creation adapter, **not** complete object creation for
every navigable vendor/category. Other engines, routines, users/roles, extensions,
external resources, and table subcategories do not gain New automatically.

Browser-only endpoints: `POST /api/dba/objects/prepare` accepts `connectionId`,
category `kind`, `database`, `schema`, `name`, and structured `columns` or view
`query` or index `table`/`indexColumns`. `POST /api/dba/objects/apply` accepts the
retained review job's `planId` and `confirmed: true`. Both use the existing owned,
bounded job lifecycle, authentication and CSRF checks; neither is an MCP tool.

PostgreSQL uses a transaction and isolated connections for explicit cross-database
targets. H2 creation requires the selected database to match the connection.
No CREATE OR REPLACE, implicit cascade, or existing-object removal is generated.
Creation can lock or scan data; materialized views populate data, and SELECT
functions can have side effects. H2 DDL can commit immediately. On an uncertain
outcome, refresh metadata before retrying—there is no automatic retry. Success
reloads the parent while retaining expanded children. Source SQL and errors stay
in the authorized workspace rather than general application logs.

Validation: `ObjectCreationTest`, `PostgresIntegrationTest.nativeObjectCreation`,
and `browser-object-creation.cjs` exercise this adapter using disposable databases.
Run `mvn -o -q -pl code-graph-dba test`, `code-graph-dba/test-postgres.ps1`, and
`code-graph-dba/test-browser.ps1` (with the documented Playwright NodeModules path).

The connection tree loads metadata only when a branch is expanded. Branches have up/down
chevrons; leaves have no chevron. Only clicking a chevron expands or collapses a node;
clicking or double-clicking its name does not change expansion. Name clicks still select
the owning connection, and keyboard Left/Right navigation remains available.
Labels truncate visually, with their full text in a
tooltip. Every node has a right-aligned actions menu with **Refresh**. Refresh reloads
previously loaded pages and compares incoming descriptors with the mounted nodes by stable
catalog identity. Unchanged rows and their descendants stay mounted; refresh inserts or
removes changed membership, patches changed labels/capabilities, and moves rows only when
ordering changes. The connection-list refresh uses the same approach for saved profiles.
Expanded branches remain visible while their metadata is refreshed, preserving keyboard
focus, selection, and scroll position on unchanged refreshes. Collapsed branches keep their
cached rows and load fresh metadata when reopened. Refreshing a leaf reloads its parent list.
A failed refresh (including a later page) retains that branch's previous children and
displays a sanitized error; it is not reported as an empty database. Removed branches cancel
pending jobs and ignore late results. Retained descendants still count toward tree limits.

`DBA_BROWSER_SUITE=tree-refresh` runs DOM-identity regressions for unchanged refreshes,
additions/removals, changed labels and actions, focus/scroll, lazy branches, pagination,
failures, resource limits and removal during a pending job. It also runs the Redis tree
regressions: refresh still starts a new bounded SCAN while retaining the pattern controls.
The shared renderer applies to JDBC and native database trees.
Validated on 2026-09-25 with the incremental/Redis tree suites, tree context menus,
H2 object-creation workflow, and core browser/sidebar regressions.

Folders and leaves inside each database are alphabetical (case-insensitive primary ordering,
with deterministic ties), including across pages. Root **connections** are the exception:
drag their title rows to arrange them yourself. An insertion line marks the drop position.
Alt+Up/Down on a connection name and its menu's Move up/Move down actions provide keyboard
alternatives. The backend persists this shared connection order across sessions and restarts;
a stale list caused by adding/removing connections must be refreshed before reordering.
Reordering does not change the active Script's execution target or collapse loaded subtrees.

**Edit Connection → General → Connection color** offers transparent (default), preset hues,
and a custom color picker with a preview. The root row uses a soft 24% tint so labels and
selection/focus indicators remain readable. The setting is shared/persisted with the profile,
not applied to child nodes. Color-only Save does not require a connectivity test, read the
vault, replace pools, or change agent grants. Color is an identification aid, not a production
write guard; always check the Script's selected connection before execution.

## Vendor branches

### Table children

Tables expand into lazy, alphabetically ordered categories rather than directly into columns.
**Columns** contains names with JDBC/vendor type annotations. Category and child menus retain
Refresh; column object actions continue to resolve the raw column name, not its annotated label.
Views and other non-table relation nodes retain their existing behavior.

| Adapter | Categories beneath a table |
| --- | --- |
| PostgreSQL | Columns, Constraints, Dependencies, Foreign Keys, Indexes, Partitions, Policies, References, Rules, Triggers |
| Oracle, DB2 LUW | Columns, Constraints, Dependencies, Foreign Keys, Indexes, Partitions, References, Triggers; Oracle also Policies |
| SQL Server | Columns, Constraints, Dependencies, Foreign Keys, Indexes, Partitions, References, Triggers; Policies for server major version 13+ |
| MySQL, MariaDB | Columns, Constraints, Foreign Keys, Indexes, Partitions, References, Triggers |
| H2 | Columns, Constraints, Foreign Keys, Indexes, References, Triggers |
| HSQLDB | Columns, Constraints, Dependencies, Foreign Keys, Indexes, References, Triggers |
| SQLite | Columns, Constraints, Foreign Keys, Indexes, References, Triggers |
| DuckDB | Columns, Constraints, Foreign Keys, Indexes, References |
| Snowflake | Columns, Constraints, Foreign Keys, Policies, References |
| Generic JDBC / uncertified derivative templates | Columns, Foreign Keys, Indexes, References through JDBC; optional metadata calls may be rejected by the driver |

Foreign Keys are outgoing keys; References are incoming foreign keys. Dependencies shows
objects depending on the table, to the extent exposed by the vendor catalog (HSQLDB exposes
view usage; PostgreSQL includes catalog-level dependents). This is not a complete static
analysis of dynamic SQL. Permissions can restrict catalog visibility; a query failure is
shown as an error, not as an empty or successful category. H2 has no supported public
dependency catalog in this adapter, so that category is omitted.

SQLite Constraints parses the stored CREATE TABLE definition for display only. Table-level
constraints are separate entries; inline constraints are grouped by column. Parsing is bounded
to 64 KiB / 500 ms; unsupported definitions produce an actionable error rather than guessed
constraints. No reconstructed DDL is executed. All other metadata uses bounded JDBC reads or
fixed vendor catalog SELECTs with bound table/schema values; pages retain the existing
200-entry limit and tree-wide 2,000-node bound. PostgreSQL partition entries exclude ordinary
inheritance children. Schema-wide branch support remains separate from these table categories.

Catalog references: [PostgreSQL system catalogs](https://www.postgresql.org/docs/current/catalogs.html),
[Oracle partitions](https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/ALL_TAB_PARTITIONS.html),
[SQLite metadata PRAGMAs](https://www.sqlite.org/pragma.html),
[DuckDB metadata](https://duckdb.org/docs/current/sql/meta/duckdb_table_functions), and
[Snowflake policy references](https://docs.snowflake.com/en/sql-reference/functions/policy_references).

Live tests cover PostgreSQL 16, H2 2.4.240, HSQLDB 2.7.4, SQLite JDBC 3.53.4.0 and DuckDB JDBC
1.5.5.1. Oracle, DB2, SQL Server and Snowflake table-category adapters are not live-verified
by those runs; credentials, catalog permissions, database editions and driver versions matter.

### Database and schema children

| Engine | Hierarchy and schema object groups |
| --- | --- |
| PostgreSQL | Databases → accessible database → Schemas, Event Triggers, Extensions, Storage (Tablespaces, Foreign servers), System Info, Roles. Each schema: Tables, Foreign Tables, Views, Materialized Views, Indexes, Functions, Procedures, Sequences, Data types, Aggregate functions. |
| Oracle | Schemas → schema → Tables, Views, Materialized Views, Indexes, Sequences, Queues, Types, Packages, Procedures, Functions, Synonyms, Schema Triggers, Table Triggers, Database Links, Java, Jobs, Scheduler. Scheduler expands to Jobs, Programs, Schedules, Chains. |
| DB2 LUW | Schemas → schema → Tables, Views, Materialized Views (MQTs), Indexes, Sequences, Types, Packages, Procedures, Functions, Aliases, Triggers. |
| Microsoft SQL Server | Databases → database → Schemas → schema → Tables, Views, Indexes, Sequences, Types, Procedures, Functions, Synonyms, Triggers. |
| MySQL / MariaDB | Databases → database → Tables, Views, Indexes, Procedures, Functions, Triggers, Events. A database is the schema namespace in these engines. |
| Snowflake | Databases → database → Schemas → schema → Tables, Views, Materialized Views, External Tables, Sequences, Functions, Procedures, Stages, File Formats, Pipes, Tasks, Streams. |
| H2 / HSQLDB | Schemas → schema → Tables, Views, Indexes, Sequences, Functions, Procedures, Triggers, Domains. |
| DuckDB | Schemas → schema → Tables, Views, Indexes, Sequences, Functions (including macros), Types. |
| SQLite | Schemas (attached databases) → schema → Tables, Views, Indexes, Triggers. Attachments belong to their physical connection; no attachments are automatically created/restored. |
| Custom | Actual database product detection selects a known adapter where possible; otherwise JDBC Schemas → schema → Tables, Views, Functions, Procedures, Types. Unsupported JDBC metadata methods report an error. |

Table-like objects expand to columns. Other object names are leaves. Vendor extensions, server-wide administration,
all version-specific object types, and DB2 for i/z/OS are not claimed as certified coverage.
The table above is the implemented capability list, not a claim that every vendor feature
has an administration screen. Metadata visibility remains subject to database privileges.
For example, Oracle uses `ALL_*` views rather than requiring DBA catalog privileges; an
empty Oracle object branch can mean the current account cannot see objects owned by that schema.

PostgreSQL catalogs are database-local, so browsing another database opens a short-lived
connection to the same saved endpoint/credentials, verifies CONNECT permission, and closes
it after the bounded job. This does not change the saved URL, create extra pools, or change
the connection selected for a Script. Other catalog-based drivers use JDBC `setCatalog`,
restore the original catalog afterward, and reject a switch the driver did not apply.
Browsing metadata does **not** retarget an open SQL Script to that database.

## Object menus

Object menus list **Copy**, **Delete**, **Rename**, a divider, then **Refresh**. Grouping
folders retain Refresh only. Copy uses the object name, without schema qualification,
column-type decorations, or routine signatures. All menu icons are bundled Lucide SVGs;
database entries use `database`, schema entries use `table`, and objects under schema
groups use `sheet`.

Delete opens a confirmation dialog naming the connection, database, object type, and
qualified target. Cancel/close sends no mutation. Database errors remain in the dialog;
the tree is not optimistically changed. After a successful Delete or Rename, the parent
is refreshed in alphabetical order, preserving still-valid expanded siblings. Rename
does not update existing SQL text. Root connection Delete removes the saved connection
only (with the existing removal confirmation), and root Rename changes its display name
only, preserving its ID, color, driver and credentials. Name-bound agent grants require review.

Actions are browser-only bounded jobs with session/CSRF checks, deadlines, cancellation,
secret-redacted database errors and no automatic retries. The server re-reads the selected
catalog page and compares an object fingerprint immediately before execution; it does not
execute SQL or identifiers supplied by the browser. New names are quoted as a single
identifier. A missing/replaced/moved object requires refresh and renewed review. This is
not a database-wide DDL lock: coordinate concurrent schema administration externally.
PostgreSQL OIDs detect replacement with a new OID; name-only JDBC catalogs cannot distinguish
an object dropped and recreated with the same name between requests.

No `CASCADE` or `FORCE` is added. The database can still remove internal dependencies
(for example indexes of a dropped table), auto-commit DDL, or reject an operation due to
external dependencies or privileges. The dialog warns that changes can be irreversible.
These confirmations apply to tree-menu actions, not arbitrary SQL typed in the editor.
Table Truncate and object Delete use the same explicit acknowledgement and exact reviewed SQL.
Materialized-view Delete also displays recognized managed schedule/helper cleanup; external
scheduler jobs remain untouched. Do not treat a timeout or connection failure as proof of rollback.

PostgreSQL adapters resolve OIDs, overloaded routine identities, and undecorated column names.
Common named-object Delete DDL is also mapped for H2/HSQLDB, SQLite, DuckDB, MySQL/MariaDB, Oracle,
SQL Server, DB2 and Snowflake. Delete/Rename capabilities differ: unsupported operations are
disabled with a tooltip, rather than inferred from a label. Catalog Rename is deliberately
limited to PostgreSQL's explicit rename-capable types and H2 tables, columns, schemas,
views, indexes and domains. H2 sequences do not support Rename and remain disabled.
Other dialects remain disabled until a dedicated rename implementation is validated;
having a DROP/ALTER command alone is not evidence of rename support. Examples include generic Custom
drivers, ambiguous overloaded non-PostgreSQL routines, vendor scheduler/queue administration,
SQLite schema/column changes, SQL Server `sp_rename`, and actual catalog database renames.
Root connection-name changes remain available independently. PostgreSQL/H2 object mutations
have live regression coverage; the other engines' new mutation mappings are not live-certified.
For changes in a different catalog, use a saved connection explicitly targeting that database;
cross-database navigation alone does not authorize silently retargeting an execution connection.

`POST /api/dba/metadata/object` takes `connectionId`, `parent` (the tree parent's descriptor
and page `offset`) and the child's stable `key`. The asynchronous result reports `name`,
`type`, `target`, `canDelete`, `canRename`, `reason`, `database` and `fingerprint`.
`POST /api/dba/metadata/object/action` additionally takes `action` (`delete` or `rename`),
that fingerprint, `confirmed: true` for Delete, and `newName` for Rename. Every execution
revalidates the selection; previewing does not mutate anything. `PUT
/api/dba/connections/{id}/rename` takes `name` and `expectedName` and requires no connection
retest or vault rewrite. None of these APIs are exposed to MCP agents.

## Bounds and API

`POST /api/dba/metadata/tree` accepts `connectionId`, `kind` (default `root`), and the
`database`, `schema`, `name`, `oid`, `offset` fields returned by tree nodes. It returns
an ordinary asynchronous DBA job. The completed result contains `engine`, `offset`,
`nodes` and, when another page exists, `nextOffset`. Each node has a stable `key`, `kind`,
`name`, `branch`, `database`, `schema` and optional `oid`.

This endpoint requires a browser session and CSRF token. It accepts no SQL and is not
added to the agent toolset; existing agent object permissions and metadata endpoints remain
unchanged. Catalog SQL uses bound values, with strictly escaped identifiers only where
SQLite/Snowflake require them. SQL errors are sanitized through the profile secret redactor.

Browser administration also provides `PUT /api/dba/connections/{id}/appearance` with
`{"color":"#ed6363"}` (or `"transparent"`), and `PUT /api/dba/connections/order` with
`{"ids":["connection-uuid", "another-uuid"]}`. Appearance rejects other fields. Order must
contain every current connection ID exactly once. Both require CSRF and browser ownership;
neither endpoint is available to MCP agents. New/updated profiles also accept `color`.

Pages contain at most 200 nodes, with explicit **Load more**. The browser retains at most
2,000 loaded metadata nodes across connections and bounded expansion/page state. At the
limit, collapse and refresh a parent to release descendants, or remove a connection.
Ended sessions release the tree. Expansion state is memory-only, not restored on page reload.
Queries use the DBA queue, deadlines, result byte allowance, cancellation and job cleanup.
Catalog SQL sorts on the server before paging. PostgreSQL uses server-side limit/offset.
Other catalog SQL uses fetch hints and JDBC max rows, skipping earlier rows for subsequent pages.
JDBC metadata and Snowflake SHOW results use a bounded priority queue to retain only the
requested alphabetic prefix (at most 2,201 entries, with offsets capped at 2,000), not a
second complete metadata copy. This still scans the source metadata; driver buffering is driver
dependent, not a hard native-memory guarantee. Deep pages may be slower than first pages.
No global cache, background catalog polling, or automatic connection scan was added.

## Validation commands

```powershell
mvn -pl code-graph-dba -am test
mvn -pl code-graph-dba test "-Dtest=MetadataEmbeddedTest" "-Ddba.catalog.integration=true"
powershell -NoProfile -File code-graph-dba/test-postgres.ps1 -Browser -NodeModules "PATH\TO\node_modules"
powershell -NoProfile -File code-graph-dba/test-database-matrix.ps1 -Databases oracle
powershell -NoProfile -File code-graph-dba/test-database-matrix.ps1
```

The explicit embedded integration gate downloads drivers into a disposable test directory.
The Docker matrix owns only uniquely labelled containers and removes newly introduced
images only when unused. Oracle Free uses a 3 GiB container with a disposable writable layer;
other matrix engines use 1 GiB and tmpfs. Existing user containers/images are preserved.

Reference catalogs: [PostgreSQL](https://www.postgresql.org/docs/current/catalogs-overview.html),
[Oracle triggers](https://docs.oracle.com/en/database/oracle/oracle-database/12.2/refrn/ALL_TRIGGERS.html),
[Snowflake](https://docs.snowflake.com/en/sql-reference/info-schema),
[DuckDB](https://duckdb.org/docs/lts/sql/meta/duckdb_table_functions).
Live validation evidence and missing environments are recorded in [dba-validation.md](dba-validation.md).

## Scheduled jobs

The tree detects available database schedulers and loads jobs on expansion. Open a job for Properties, schedule/command editing, SQL review and available run history. See [scheduler coverage and management](dba-scheduled-jobs.md).
