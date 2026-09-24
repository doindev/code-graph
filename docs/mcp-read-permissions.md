# Configurable MCP read permissions

Use **Allow SELECTs…** in a pending approval, or **Workspace settings → Database permissions** without an application project. Allow once and existing exact/category approvals remain available with their original eligibility and scope.

## Scope and duration

A read permission belongs to one agent identity and contains explicitly selected targets:

| Selection | Includes |
|---|---|
| Tables/views | The selected qualified names and their supported metadata/definitions |
| Schema | Every table/view in that schema, including future objects |
| Database | All schemas and objects in that database, including future objects |
| Connection | Every database/schema reachable through that saved connection |
| Custom | Any combination above across explicitly chosen connections |

The picker uses connection → database → schema → table/view. MySQL and MariaDB combine database and schema. Names are literal identifiers, not wildcard expressions. An object permission follows its qualified name, not a rename. New connections are never included automatically. Broad scopes may include system catalogs. Database account privileges still apply.

Duration is independent of scope: **This MCP session** is memory-only; **Until revoked** survives restart. A new approval draft selects only the request's objects and defaults to session lifetime. Settings-created session grants require a live session belonging to the selected recipient. Labels identify sessions without exposing session credentials.

All token-free local clients share **Trusted local agents**. Persistent grants retain that shared identity behavior. A session grant is usable only by its originating MCP session. Named agent identities remain separate.

Opening the selector, loading target choices, or cancelling creates no grant and executes no pending SQL. **Grant and run** shows the recipient, targets, metadata access, and duration, validates coverage, then submits the pending request once through the existing idempotent approval flow. Validation errors preserve the draft. Native prompts offer quick scopes and a browser handoff for custom selections.

Settings support create, edit, disable/re-enable, and revoke, and show scope, lifetime and last use. Grants are additive: if several grants overlap, revoking only one may leave access available through another. Edits use optimistic revisions to avoid overwriting concurrent changes.

## Supported reads

New version-2 read policies support PostgreSQL, MySQL, and MariaDB through enforced read-only transactions. The separate positive SQL analyzer supports joins, nested SELECTs, nonrecursive read-only CTEs, UNION/UNION ALL, grouping, HAVING, ordering and CASE expressions. It distinguishes CTE names and aliases from physical relations.

Verified built-ins are COUNT, SUM, AVG, MIN, MAX, COALESCE, NULLIF, ABS, LOWER, UPPER, LENGTH and CHAR_LENGTH. PostgreSQL function/operator lookup uses pg_catalog; user-created overloads in that namespace and custom column types used with functions require explicit review. Function signatures are conservatively bounded to the supported built-in forms. Custom or unverified function calls, recursive/data-changing CTEs, locking reads, file operations, writes, multiple statements and unsupported syntax do not qualify. Other vendors keep their existing behavior.

Every physical relation must be covered. Multiple active grants for the same identity/session can collectively authorize a query. SQL runs on one concrete connection and execution database/schema. MySQL database-qualified joins and PostgreSQL cross-schema joins work; connection federation and PostgreSQL cross-database queries are not introduced. Reading an approved view authorizes its output under database view privileges, without granting direct access to its underlying tables.

Identifiers are resolved under the vendor's rules, and the execution product and database/schema are verified. The analyzer qualifies source relations and retains bound parameters. Existing SQL, parameter, deadline, row, byte, cancellation and concurrency limits remain in force. Unsupported operations remain available through explicit one-time review where the existing approval channel supports them.

## Metadata and catalogs

Read permissions include scoped listings, columns, keys, indexes and supported definitions. dba_get_metadata accepts kind (databases, schemas, tables, columns, keys or indexes) and offset. Supported dba_get_object_ddl objectType values include table, view, function, procedure and database; MySQL/MariaDB support SHOW CREATE DATABASE. Routine definitions require a covering schema/database/connection permission. Definition completeness depends on the existing vendor template and database privileges.

For example, approval for codegraph_local authorizes its application metadata through scoped metadata tools. It does not authorize arbitrary SELECTs from information_schema: raw catalog SQL needs explicit coverage for that catalog. SQL WHERE clauses never confer authorization.

Catalog rows are filtered before being returned, searched or paginated in cached queries. Table grants cannot expose unrelated objects or same-named routines. Live metadata pages report the source offset and permission filtering; an empty filtered page can have more source rows. Whole-schema snapshot workflows require whole-schema coverage. Partial-scope status omits unfiltered snapshot inventories/history.

## Lifecycle and storage

Version-2 policies use the existing private reusable-approvals.json storage with atomic replacement; session policies remain in memory. Limits are 256 selectors per read policy, the existing 128 policies per identity / 1,024 total, and the 2 MiB durable storage ceiling. No SQL parameters, credentials or result rows are stored in policies or grant-use audit records.

Policy identity, session, revisions, connection revisions and coverage are checked before execution and result delivery. Narrowing, disabling, revoking or ending a session blocks queued access, cancels dependent reads, and prevents retained-result retrieval under the removed authorization. Previously delivered data cannot be withdrawn. Connection authorization changes invalidate affected selectors; deletion removes those selectors while retaining unaffected connections in a mixed grant.

Human browser APIs use the existing browser session and CSRF protection:

- POST /api/dba/agents/{id}/policies creates a read policy.
- PUT /api/dba/agents/{id}/policies/{policyId} updates it with an expected revision.
- Existing permission listing, PATCH enabled, and DELETE revoke routes remain available.
- POST /api/dba/permissions/targets supplies bounded asynchronous target discovery.
- Restricted review sessions expose only their request's permission-targets routes, with existing lease, ownership and expiry checks; they do not expose general permission administration.

No MCP tool can create, widen or approve its own permissions. Human changes and automatic uses are audited with policy IDs/revisions and without SQL parameters or results.

## Validation

Run Java suites in an isolated build. Focused coverage includes ReadQueriesTest, ReadPermissionsTest, ReadPermissionsHttpTest, ReadPermissionVendorIntegrationTest, ApprovalBrokerTest and DesktopApprovalsTest. The read-permissions browser suite covers standalone settings, defaults, multiple selections, pagination, conflicts, editing, persistence, disable/revoke, approval cancellation and focus. Existing approvals, approval-review and project-context browser suites exercise shared paths.

The test-reusable-vendors.ps1 harness uses owned disposable PostgreSQL/MySQL fixtures and also runs legacy reusable-approval and schema-workflow regressions. It never reuses application tables for write tests.

Verified in an isolated build on Windows: the DBA Java suite, focused MCP contracts, all 11 native approval tests, and the read-permissions, approvals, approval-review, approval-settings and project-context browser suites. Live fixtures used PostgreSQL 16.14 / JDBC 42.7.13 and MySQL 8.4.11 / Connector/J 9.7.0. Live checks include cross-schema/database joins, quoted identifiers, views, metadata, exact decimals, built-in function resolution, retained-result revocation and cancellation of blocked reads.
