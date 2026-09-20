# Table Properties designer

## New tables

**Tables → New** opens a separate **New Table** tab on Properties, with the connection
and selected database/schema fixed. The draft starts with a blank name and no columns.
Data, Diagram and Refresh are disabled until creation succeeds. Save remains available
for an incomplete draft and prompts for missing configuration. At least one uniquely
named, typed column is required; a primary key is optional.

Use the same supported table fields and category additions listed below. Save validates
the whole draft and opens SQL review; Apply creates the table. Other object-type New
menus open [dedicated object tabs](dba-object-editors.md). Revert resets only the unsaved creation draft. Unsupported
categories remain read-only, with no metadata requests against a nonexistent table.

Creation uses a browser-only `POST /api/dba/table-properties/new` baseline operation;
`/prepare` accepts `creation: true`, the fixed `target`, baseline `fingerprint` and shared
`draft`. `/apply` uses the ordinary owned review plan. CREATE is followed by supported
keys, constraints, indexes, comments, category additions and settings; ownership changes
are last. PostgreSQL is atomic; H2 may leave a committed prefix on failure. No existing
same-named object is replaced. Target changes or expired reviews require a new review.

Success resolves the real catalog identity, refreshes Properties and the tree while
retaining expansion, and converts the same tab into an ordinary Table tab. It stays on
Properties; Data loads only when selected. Metadata recovery never resubmits CREATE.
Partial/unknown outcomes block Save pending explicit reconciliation; the original draft
remains available for comparison with actual metadata, without automatically replaying
successful changes. Uncreated tabs/drafts are memory-only and excluded from workspace
recovery. Closing dirty drafts offers Save/Discard/Cancel; page unload warns about loss.

Tests: `TableCreationTest`,
`PostgresIntegrationTest.propertiesCreatesAllSupportedCategoriesAtomically`, and
`browser-object-creation.cjs` cover the creation path in addition to alteration tests.

Open a Table tab, then select **Properties**. The full-width form and left-hand category
tabs share one memory-only draft. The footer's Save and Revert affect the whole draft.
Save prepares SQL without executing it; Apply runs the reviewed plan. Risky/nontransactional
plans require acknowledgement. Refresh asks before discarding edits; closing a dirty tab
offers Save, Discard and Cancel. Browser refresh does not restore designer drafts.

## Current capability matrix

| Engine/category | Enabled operations |
| --- | --- |
| PostgreSQL ordinary, non-inherited tables | Rename table, move schema, owner, comment, tablespace; add/drop/rename columns, standard datatype changes, nullability, primary key/order, defaults, comments; identity/generated columns when newly added |
| H2 tables | Rename/comment table; the same basic column operations and new identity/generated columns; nontransactional warning required |
| SQL Server 2022 ordinary disk tables | Reviewed creation, rename, supported scalar columns/defaults/nullability/comments, keys/CHECK/UNIQUE/FK and ordinary indexes; see restrictions below |
| PostgreSQL Constraints / Foreign Keys | Add CHECK, UNIQUE, FK; delete non-primary constraints; primary key is controlled through Columns |
| PostgreSQL Indexes | Add simple column indexes (optionally unique), rename, delete non-constraint indexes |
| PostgreSQL Triggers | Add BEFORE/AFTER INSERT/UPDATE/DELETE triggers invoking an existing zero-argument trigger function; enable, disable, delete |
| PostgreSQL Policies | Add/update USING expression; delete. This does not enable RLS automatically |
| PostgreSQL Rules | Add an INSERT/UPDATE/DELETE DO INSTEAD NOTHING rule; delete |
| PostgreSQL Permissions / Statistics | Grant/revoke listed table privileges to existing roles; explicitly request ANALYZE |
| Other JDBC engines | Available metadata is read-only; unsupported driver calls report an error |
| References / Dependencies / Partitions | Read-only metadata |
| DDL | Copyable definition summary, explicitly **not** a complete restore script |
| Virtual | Placeholder; no database or local virtual metadata changes |

This is not yet the complete all-vendor designer described in the implementation plan.
Unimplemented areas include vendor editing adapters beyond PostgreSQL/H2 and the SQL Server subset below, native partition
editing, advanced trigger/rule/policy/index configuration, full constraint replacement editors,
existing identity/generated-column alteration, full-fidelity DDL export, and automatic
remaining-draft rebasing after partial failures. Controls must not imply those features work.

Standard type text accepts supported scalar type names plus length or precision/scale, for
example `varchar(100)` or `numeric(12,2)`. Arbitrary vendor types/conversion USING expressions
are not generated. Defaults are ordinary SQL defaults; existing NULLs are not backfilled.
Existing generated/identity settings are read-only. Composite PK positions must be distinct.

## Safety, targeting and recovery

- All schema plans are browser-session-owned, expire after five minutes, and are single-use.
  Apply accepts a retained plan job ID, never SQL from the browser. A schema fingerprint is
  rechecked before execution; PostgreSQL additionally takes a bounded-wait table lock.
- The Table tab's fixed database is used. PostgreSQL supports isolated cross-database
  execution; unsupported catalog retargeting is rejected, never silently redirected.
- PostgreSQL plans use one transaction. H2 DDL can commit each operation: execution stops
  on the first failure and reports the committed prefix. No automatic retry is performed.
- Failures preserve the draft and old grid rows. A partial/uncertain outcome blocks Save,
  rereads the actual table where possible, and shows it beside the retained intent. Use
  Refresh to accept actual metadata, then explicitly reapply only remaining changes. If a
  partially completed rename prevents resolution, refresh the tree and reopen that table.
- Revert is not undo for committed DDL. No table replacement, CASCADE, truncate, automatic
  backup, backfill or database-user provisioning is performed. Trigger/default expressions
  can invoke functions with side effects; the designer is not a sandbox for human SQL.
- Application grid jobs on the connection are settled before Apply. Running Scripts block
  Apply. Other clients may still access/change the database; schema fingerprints are not a
  universal cross-vendor concurrency guarantee, particularly identical drop/recreate races.

## SQL Server 2022 ordinary tables

The verified SQL Server adapter supports table/column renaming, supported scalar
datatypes, defaults (including named default-constraint replacement), nullability,
comments, primary keys, CHECK/UNIQUE/foreign-key constraints and ordinary indexes.
New tables use the same Properties draft/review flow. Identifier quoting preserves
embedded brackets; column collation is retained when changing a type.

Unsupported/generated, special storage, CDC/replicated, partitioned, temporal,
ledger, encrypted, graph and memory-optimized objects remain read-only. Owner and
schema relocation are not enabled. Native definition capture is explicitly partial,
not a complete restore script.

Apply obtains the fixed database target, serializes application changes, locks the
table before revalidating its fingerprint and uses XACT_ABORT with a bounded lock
timeout. Verified ordinary-table designer changes are transactional. Migration
artifacts conservatively advertise their own guarantees rather than assuming every
arbitrary SQL Server script is atomic. Cancellation/connection loss never triggers
automatic retry: reconcile actual metadata before preparing another plan.

The disposable SQL Server 2022 / Microsoft JDBC 13.4.0.jre11 gate verifies creation,
populated edits, defaults/comments/indexes, failed-change rollback, native schema
capture and same-named objects in a separate database. Azure SQL, other SQL Server
versions and integrated/Entra authentication are not certified by this gate.

## Interfaces and limits

Browser-only POST endpoints: `/api/dba/table-properties/load`, `/category`, `/prepare`,
`/apply`. Load/prepare take a connection UUID and the existing verified table selection;
prepare adds the fingerprint and shared draft. Apply takes `planId` and `confirmed`.
All operations return normal DBA asynchronous jobs, retaining existing CSRF, owner checks,
timeouts, cancellation, bounded admission and result accounting. MCP is unchanged.

The editor limits a table to 256 columns, 1,000 catalog objects per query, 8 KiB individual
definitions, a 1 MiB snapshot, the existing 128 KiB REST request limit and 128 generated
statements per save. No unbounded COUNT(*) or background metadata polling is introduced.
Fingerprint preparation reads bounded relevant catalog definitions; category rendering is
on demand, but PostgreSQL fingerprint reads are not fully lazy per category.

## Validation

```powershell
mvn -pl code-graph-dba -am test
powershell -NoProfile -File code-graph-dba/test-postgres.ps1
powershell -NoProfile -File code-graph-dba/test-browser.ps1 -NodeModules "PATH\TO\node_modules"
```

`TableDesignerTest`, PostgreSQL integration tests and `browser-table-designer.cjs` exercise
draft compilation, review/apply ownership, preserved rows, defaults, rename/add, stale plans,
atomic rollback, H2 partial commits, category actions, review cancellation, risk confirmation,
tab closure and grid refresh after Save. Test databases are disposable; user tables are not
used. Other vendors, cancellation/connection-loss races, exhaustive concurrency/resource
stress and all advanced editing combinations remain unverified release gates.
