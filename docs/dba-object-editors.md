# Database object editors

Object categories in the connection tree expose **New**, a separator, and **Refresh metadata**.
Object children expose **New**, **Delete**, applicable actions, a separator, **Open object
tab**, and the existing copy/rename and metadata actions. New on an object uses its
parent category and fixed connection/database/schema for creation. Supported PostgreSQL
objects can be moved to another schema through the existing-object form. Group folders are not bulk-delete
targets. Double-click or Enter opens the object without expanding its branch.

Each dedicated object tab contains **Properties** and an inner vertical category control.
General, Definition, type-specific categories, dynamically discovered Advanced properties,
driver Datatypes and **DDL** share one draft. The same tab creates, inspects and edits the
object; a successful creation resolves its real catalog identity. Existing tabs are reused.
Views and materialized views retain Data and Diagram. Tables keep their established
Table Properties editor.

## Property and definition coverage

| Adapter | Form-generated changes | Native definition and advanced properties |
| --- | --- | --- |
| PostgreSQL | Schemas; sequences; views/materialized views; functions/procedures; indexes; enum types/domains; table columns, constraints, policies, triggers and rules; extensions; roles; foreign servers; event triggers | Native catalog rows, version-specific columns, driver datatypes, roles, schemas, languages, tablespaces and access methods; routine/index/view definitions; dependencies and available permissions |
| H2 | Schemas, sequences, views, indexes and domains | INFORMATION_SCHEMA properties, view/routine source where available, JDBC columns/parameters/permissions and datatypes |
| Oracle | Native SQL in the same tab | DBMS_METADATA definitions; available sequence/view/materialized-view/index/trigger properties |
| SQL Server | Native SQL in the same tab | sys.objects/sys.sql_modules definitions and JDBC metadata |
| MySQL/MariaDB | Native SQL in the same tab | SHOW CREATE definitions and JDBC metadata |
| Snowflake | Native SQL in the same tab | GET_DDL and JDBC metadata |
| DuckDB | Native SQL in the same tab | duckdb_* catalog properties/definitions |
| DB2 | Native SQL in the same tab | SYSCAT properties/definitions where exposed |
| HSQLDB and other JDBC drivers | Native SQL in the same tab | Available INFORMATION_SCHEMA/JDBC metadata |

The registry includes all existing database/schema object categories and editable table
child categories. A database's unsupported features are not inferred from catalog
visibility. Read-only/generated properties remain inspectable. The native SQL editor
inside DDL handles definitions and alterations outside a form adapter, including
vendor-specific object types, parameter/signature changes and advanced clauses.
This is **not** an assertion that every vendor property has a generated form writer.
Metadata failures are reported as capability notes, not silently presented as an empty
successful catalog. Missing/incomplete native DDL is explicitly identified.

**Use native SQL for this save** selects the DDL draft instead of generating SQL from the
form. It requires review and explicit acknowledgement and may affect objects beyond the
tab. It runs as one JDBC statement by default to preserve routine bodies with semicolons.
Optional splitting uses the Script editor's bounded lexical parser (16 KiB/32 units).
Native single statements support 64 KiB; no database client DELIMITER commands are added.
Native SQL is not automatically rewritten into ALTER or CREATE OR REPLACE.

## Preservation and review

Save prepares a session-owned review, valid for five minutes and usable once. Apply
accepts only that retained plan ID and explicit acknowledgement, never fresh SQL.
The server verifies the current catalog fingerprint and connection identity again.
Form-generated PostgreSQL operations use a transaction except database/tablespace
operations. Native SQL and other engines can commit immediately; the review states
this, and failures report partial/unknown outcomes without automatic replay.

Sequence values are strings throughout the form, so values beyond JavaScript's safe
integer range round-trip exactly. Metadata reads do not consume sequence values.
Changing cache/increment preserves current sequence state; Restart is explicit.
Routine body edits start from PostgreSQL's native declaration to retain defaults,
settings and advanced attributes. New overloads resolve using their new catalog OID.

PostgreSQL materialized-view query changes use reviewed DROP RESTRICT/CREATE within
one transaction, restoring indexes, index/view/column comments, column statistics and
storage, access method, tablespace, options, grants, grant options and security labels.
Dependent objects block replacement. Column grants/options, extended statistics,
extension dependencies, column security labels or different grant ownership require
an explicit native DDL workflow rather than lossy reconstruction.

Dirty tabs offer Save/Discard/Cancel. Revert discards only the draft. Refresh asks before
discarding edits. Partial/unknown outcomes block another Save until reconciliation.
A successful commit followed by failed metadata resolution offers metadata recovery,
without repeating CREATE. Workspace recovery persists existing object identities only;
creation drafts and unsaved property/DDL edits remain memory-only.

## Materialized-view Refresh and table Truncate

- Materialized-view **Refresh** executes immediate native refresh on PostgreSQL,
  Oracle or DB2; it does not edit a configured schedule. Engines without a supported
  explicit refresh operation show it disabled. **Refresh metadata** only reloads the tree.
- Table **Truncate** displays the fully qualified target, exact SQL, consequences,
  and a required acknowledgement before execution. It preserves structure and does
  not add CASCADE. PostgreSQL and H2 explicitly continue identity values; other
  databases use their native identity behavior.
- Delete uses the same explicit acknowledgement and exact SQL display. Object
  identity is rechecked. Successful actions invalidate affected open tabs/results
  and refresh the parent while preserving tree expansion.

These browser endpoints retain the existing CSRF, session ownership, bounded jobs,
timeouts and cancellation. They do not add agent/MCP write tools:

- POST /api/dba/object-properties/load
- POST /api/dba/object-properties/prepare
- POST /api/dba/object-properties/apply
- POST /api/dba/metadata/object/action (delete, rename, truncate, refresh)

## Validation

ObjectDesignerTest covers H2 round trips, large sequence values, stale metadata,
review ownership/confirmation/single use and truncate. ObjectDesignerPostgresTest
uses the disposable PostgreSQL fixture for the main object forms, routine defaults,
manual refresh and materialized-view preservation. browser-object-designer.cjs covers
the nested tabs, creation/editing, reuse, metadata search, workspace restoration and
confirmed truncate. Existing Table, tree, grid and query-builder tests remain applicable.
Native definition adapters beyond PostgreSQL/H2 are not all live-certified by these tests.

References: [JDBC metadata](https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/DatabaseMetaData.html),
[ALTER FUNCTION](https://www.postgresql.org/docs/current/sql-alterfunction.html),
[ALTER MATERIALIZED VIEW](https://www.postgresql.org/docs/current/sql-altermaterializedview.html),
[REFRESH MATERIALIZED VIEW](https://www.postgresql.org/docs/current/sql-refreshmaterializedview.html).
