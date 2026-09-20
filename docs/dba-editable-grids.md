# Editable, pageable result grids

The same Data Grid is used by Script results, Table/View Data tabs, and the
Visual Query Builder. Its row-data Save/Cancel actions are distinct from the
query builder's view-definition Save/Revert actions. Native MongoDB/Redis
document results remain display-only; this is a JDBC row-editing feature.

## Working with rows

Click a row number to select a row, Shift-click for a range, and Ctrl/Cmd-click
to toggle rows. Selection is local to the loaded page. First/Previous/Next/Last
navigate loaded rows and, when verified paging is available, adjacent pages.
Last loads the final **N** rows, not the remainder of a numbered page.

Double-click a writable cell or press F2. Enter accepts the cell draft, Escape
abandons that cell edit, and Tab/Shift+Tab moves between editable cells. The
value-mode selector distinguishes text (including an empty string), SQL NULL,
and database DEFAULT. Text is bound as a value, never executed as SQL.

Add row and Delete stage local changes. Save applies the entire draft in one
transaction, in delete/update/insert order. Deletions require confirmation with
the exact target and counts. Grid settings also offer inspection of prepared
SQL/parameters. Cancel abandons pending changes; it cannot undo a commit.
Refresh, paging, sorting/filtering, export, and closure require
Save/Discard/Stay when there are edits. Switching tabs preserves drafts.
Editing stops scheduled refresh. Page unload warns about pending edits.

Grid settings control column order, visibility, width reset, NULL display and
date/time presentation. Settings do not change database values and survive only
for the result lifetime. Row data and drafts are not restored after a page reload.

## Editability and paging boundaries

| Engine | Ordinary transactional table | Simple view writes | Paging |
|---|---|---|---|
| PostgreSQL | Verified | Direct single-base-table, automatically updatable views with mapped unique identity | Verified eligible projections |
| MySQL | Verified InnoDB only | Direct InnoDB base-table views with verified mapping/check options | Verified eligible projections |
| MariaDB | Verified InnoDB only | Direct InnoDB base-table views with verified mapping/check options | Verified eligible projections |
| H2 | Verified | Disabled; not verified | Verified eligible projections |
| SQL Server | Verified ordinary tables | Disabled; not verified | Verified eligible projections |
| Other JDBC vendors | Read-only | Disabled | Loaded-row navigation only |

An eligible projection must contain all columns of an unambiguous primary key
or non-null unique key. The server verifies metadata, source mapping, and
transaction support; browser labels and JDBC writability hints do not authorize
edits. SQL Server source mapping additionally uses the server's result-description
metadata rather than assuming a missing JDBC table name.

Joins, computed projections, aggregates, materialized views, duplicate mappings,
keyless results, truncated values and unsupported datatypes remain read-only,
with a capability reason. Generated/identity columns cannot be edited.
Supported scalar comparisons include ordinary character, integer, decimal,
Boolean, date, time and timestamp values. Binary/LOB, structured, floating-point,
timezone and other unverified equality semantics are not editable.
SQL Server legacy DATETIME/SMALLDATETIME and MySQL/MariaDB unsigned integers,
duration-style TIME and multi-bit BIT are also read-only in this iteration.
Their JDBC type labels alone do not establish safe precision/comparison behavior.

Views are modified **through the view**, never redirected to the base table.
Check options and database privileges still apply. Metadata access to the base
object may be necessary to verify identity; failure does not enable editing.
Complex, nested, trigger-backed and other unverified view mappings remain
read-only. See [PostgreSQL](https://www.postgresql.org/docs/current/sql-createview.html)
and [MySQL](https://dev.mysql.com/doc/refman/8.4/en/view-updatability.html).

Updates use a verified unique identity **and every original comparable projected
value** in the predicate. Original values come from a session-owned server
snapshot, not from the browser. The server checks schema/profile revisions and
locks/rechecks the original row before DML. Exactly one row must match each
existing-row action; a conflict rolls back the whole batch. Constraint-dependent
key swaps can fail atomically. Triggers, cascades and external effects may extend
beyond the directly edited rows.

Saves hold target locks through the transaction. The SQL Server adapter currently
uses a table-exclusive lock to stabilize schema/row verification; it can block
other readers/writers until commit. Other adapters use transaction-held locking
reads. All remain subject to the configured deadline and cancellation.

## Paging and resource limits

The initial requested page is 200 rows (or the lower configured UI ceiling).
The footer input accepts 1–7 ASCII digits; Enter loads the first page. Merely
typing does not query. The existing UI row/byte/cell/column and job limits remain
authoritative, so a byte-limited page can contain fewer rows.

Verified paging preserves authored LIMIT/TOP/OFFSET and appends unique ordering
tie-breakers where safe. The executed ordered SQL is shown in the preview, without
changing Script text. Limited queries must already define unique ordering.
Ambiguous ordering or unsupported query shapes retain loaded-row navigation.

Count and page reads share a short serializable transaction; no connection or
open JDBC cursor is retained while browsing. Requests observe current data, not
a frozen result across idle interactions. Concurrent database changes can move
rows between pages. Counts and large offsets can be expensive and remain subject
to cancellation/deadlines.

At most 128 grid contexts are retained across sessions, accounted against the DBA
allowance. Only one page per context is retained, along with bounded prepared
changes. Browser results reserve 28 MiB of the existing 32 MiB allowance, leaving
4 MiB for aggregate drafts. Admission fails rather than evicting dirty drafts.
The memory allowance is not a hard process-RAM cap.

## Export

Choose **selected rows**, **current page**, or **full filtered query** explicitly.
Visiting Last does not change export scope. Resolve drafts first; exports use
saved values. Full-query export re-executes supported SELECT SQL, not procedural
or DML-returning statements. Visible columns use their displayed order; include
hidden columns only through the explicit option.

- CSV: UTF-8, quoted non-null fields, doubled quotes, CRLF records.
- TXT: UTF-8 tab-delimited; backslash/tab/CR/LF use escaped representations.
- XLSX: streaming ZIP/XML, not an in-memory workbook; cells never become formulas.
  Numbers beyond Excel's 15-digit precision are stored as text.
- SQL: dialect-specific INSERT literals and verified target/column identifiers.
  Disabled for unverified mappings, generated/identity projections or truncated
  values rather than producing misleading executable statements.

NULL is represented as the unquoted CSV/TXT marker \N; an empty string is a
non-null empty field. CSV/XLSX prefix non-null strings beginning with a backslash
with another backslash; TXT does this before its ordinary backslash escaping.
This distinguishes NULL from a literal marker. SQL uses actual NULL.
CSV/TXT spreadsheet-safe escaping
is selected by default; raw mode can expose formula-injection risks when opened
in spreadsheet software. Page/selection exports explicitly warn about truncated
previews. Full exports fail rather than silently truncate large/unsupported cells.

Exports alone use application-owned files under the DBA data directory's
grid-exports subdirectory. Limits: **64 MiB/file, 128 MiB reserved aggregate,
1,000,000 data rows/export, ten-minute retention**. Full export cells/rows have
additional 1 MiB bounds; XLSX enforces its 32,767-character cell limit.
Completed downloads, failures, cancellation, session expiry and shutdown remove
owned files. Startup removes only expired files matching the application's exact
artifact naming pattern. Ordinary query results never spool to disk.

## Browser API

All routes are browser-only under /api/dba and retain loopback, session ownership,
Host/Origin and CSRF validation. MCP schemas and agent limits are unchanged.
Human SELECT jobs attach a grid descriptor to each eligible rows result:
opaque context ID, revision, row handles, column capabilities, target,
page metadata and restrictions. There is no client-supplied editable table name.

| Route | Purpose |
|---|---|
| GET /grids/{id} | Owned descriptor/status, including busy/uncertain state |
| POST /grids/{id}/prepare | Validate revision plus structured row/column changes; return a bounded retained plan |
| POST /grids/{id}/apply | Execute retained planId; confirmed is required for deletions |
| POST /grids/{id}/page | First/previous/next/last/refresh with revision and limit |
| POST /grids/{id}/reload | Reread a verified editable source without server pagination |
| POST /grids/{id}/reconcile | Explicitly observe actual data after uncertain outcome; never replay writes |
| POST /grids/{id}/export | Format, scope, column IDs and optional selected row handles |
| DELETE /grids/{id} | Cancel work and release context |
| POST /grids/dispose | Bounded owned ID list, used on page unload |
| GET /grids/exports/{id} | One-transfer session-owned download |
| DELETE /grids/exports/{id} | Discard an export |

POST operations return ordinary asynchronous jobs. Use existing status,
cancellation and release endpoints. Prepare accepts changes with operation
insert/update/delete, opaque rowId, and values keyed by column ID. Each value
has kind value/null/default; only value carries text. Revisions, original values,
schema fingerprints, identifiers and prepared SQL are server-owned.
Settings telemetry includes grid context count and reserved export disk bytes.

## Recovery

- Conflict or ordinary SQL failure: the transaction is rolled back; drafts remain.
  Correct the draft or Cancel and reload. No automatic retry occurs.
- Saved, reload failed: the commit was acknowledged and the draft is cleared.
  Refresh; do not repeat Save.
- Unknown commit: Save is blocked. **Reconcile with database** reloads actual data,
  discards the uncertain draft after explicit confirmation, and does not replay it.
  Inspect the data before making new edits. A still-running job must settle first.
- Target/profile/schema changed: rerun the original query against the intended
  target. Old row handles and prepared plans are not reusable.

Validation commands, live versions, and remaining limitations are recorded in
[the delivery checklist](editable-grids-delivery.md).
