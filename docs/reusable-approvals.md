# Scoped, reusable MCP approvals

SQL approvals offer a separate **Deny** button and an **Allow once ▾** split button in
the browser and native desktop prompt. Opening the menu does not approve anything.
Every request resets to Allow once. Disabled choices explain why reuse is unavailable.
When all reusable choices are disabled, the native menu and request details also show the
reason directly, including missing-session restrictions even if the SQL itself is eligible.
The prompt displays the classified operation, exact scope, lifetime and limitations.

| Choice | Matching and lifetime |
|---|---|
| Allow once | Only this reviewed request; no new policy. |
| Always allow | Exact SQL text, typed JSON parameters, auto-commit option and target; until revoked. |
| Allow similar for this MCP session | One verified operation category, exact target and logical MCP session; memory-only. |
| Always allow similar | One verified category and exact target; persisted until revoked. |

Request IDs and purpose descriptions do not affect exact-repeat matching. Whitespace in
SQL does: this is exact approval, not SQL normalization. Choosing a reusable option is an
explicit human decision. Agents cannot supply a category, session identity, fingerprint,
scope grant or approval decision to authorize themselves.

## Scope and identity

Standalone targets require a stable connection UUID and matching connection name. Database
and schema default to a verified, displayed profile target, or may be explicitly supplied.
A project is not required. Project-bound requests use bindingId instead: scope includes the
application UUID, canonical environment, binding UUID, role, connection UUID, database and
schema. A bound request cannot override its database or schema. New permissions never apply
to other bindings or future databases merely because the environment is the same.

Profile/binding revisions are included in matching and checked again before execution.
Changes that alter authorization invalidate affected reusable grants. Referenced objects
must resolve within the approved database/schema. Unsupported or ambiguous syntax remains
reviewable once; it does not auto-run. The execution connection's actual vendor, database
and schema are verified, and its schema is selected explicitly before reusable execution.
PostgreSQL relation references are also resolved through the actual session and checked
against the approved schema, including implicit pg_catalog/temporary search-path resolution.

Token-free local clients share **Trusted local agents**. Persistent grants therefore apply
to that shared identity. Temporary grants are isolated even when two clients share it:
HTTP requests use their validated MCP session, and stdio uses its owning transport session.
HTTP DELETE, session expiry (currently one hour), stdio EOF, or server restart removes
temporary authorization. A new initialized session never inherits it. Pending decisions
for an ended session are rejected. Optional named agents keep their separate identities.

Stage/prod have the same explicit choices, with their environment displayed prominently;
they do not silently acquire wider permissions.

## Verified grammar and capability boundary

Categories are independent: read, explain, ddl_inspection, create_table, create_view,
create_procedure and create_function. A new-table grant does not permit view creation;
creating a routine never grants permission to invoke it.

This is a conservative positive grammar, not a general SQL sandbox. An existing AST-based
restricted-read validator checks SELECT/VALUES and expressions, including TRUE/FALSE
literals in projections and comparisons. A bounded dialect-aware
lexer/parser checks creation, names, body structure and the complete statement. Unknown
constructs, multiple statements, executable comments and ambiguous quoting fail closed.

| Operation | PostgreSQL | MySQL / MariaDB | H2 |
|---|---|---|---|
| Restricted SELECT / VALUES | Reusable, enforced read-only transaction | Reusable, enforced read-only transaction | Allow once only |
| Plain estimated EXPLAIN of a verified read | Reusable | Reusable | Allow once only |
| Metadata / DDL inspection | Scope-filtered trusted catalog templates | Metadata and SHOW CREATE TABLE/VIEW/PROCEDURE/FUNCTION | Metadata reviewed once; DDL template unavailable |
| New CREATE TABLE | Built-in types, literal/time defaults, nullable, primary/unique keys | Same restricted subset | Same restricted subset |
| New CREATE VIEW | Restricted SELECT body | Restricted SELECT body | Restricted SELECT body |
| New function/procedure | SQL language, invoker context, single restricted SELECT body | Explicit SQL SECURITY INVOKER, NO SQL or READS SQL DATA; restricted RETURN expression / SELECT body | External aliases/routines unavailable for reuse |

Other database vendors retain one-time review; connectivity does not certify their SQL
syntax. Even supported vendors have unsupported forms: compound routine bodies, PL/pgSQL,
dynamic SQL, procedural execution, arbitrary function calls, type extensions, CREATE AS
SELECT/LIKE, replacement, temporary objects, privilege changes and vendor options require
one-time review. No submitted SQL is rewritten into replacement semantics.

DROP, DELETE, TRUNCATE, UPDATE, INSERT, MERGE, ALTER, routine invocation, administrative
commands and EXPLAIN ANALYZE cannot receive either exact or category reusable approval.
SHOW CREATE is inspection, not creation. Arbitrary functions returning text are not
classified as DDL inspection. Trusted PostgreSQL definition queries are generated by the
server, not accepted as an agent-supplied exemption.

H2 documents that JDBC setReadOnly does not prohibit writes, so the new reusable SQL-read
path deliberately refuses to claim a verified read-only session. Existing legacy grants
retain their previous behavior and are labeled legacy; they are not converted into creation
permissions. PostgreSQL and MySQL/MariaDB also receive an explicit READ ONLY transaction,
in addition to JDBC checks. Least-privilege database accounts remain necessary.

Creation can lock, consume resources, and activate database event triggers. Existing views,
database configuration and dependencies may introduce side effects. Reusable approval is
not a guarantee that creation is harmless, or that a query cannot be expensive.

## Tools, management and storage

Applicable query, plan, metadata, DDL and project-catalog tools use the shared classifier
and policy evaluator when not already covered by unchanged legacy grants. Unapproved live
reads return a structured approval request; approved work returns a job for the existing
status/cancel/release workflow. Poll the returned approval id, not the caller's idempotency
requestId. New standalone access requires both connectionId and connectionName; project
access uses bindingId. SQL result caps and global resource limits are unchanged.

Request status includes operation, approvalChoices and permissionScope. Auto-approved
requests additionally include matchedPolicy and authorizationReason. Agent access lists
exact/category policies, identity, exact scope, lifetime, active-session label and last use.
The browser can disable, re-enable or revoke them. Revocation is rechecked before queued
SQL starts. Open Settings → Agent access → Manage reusable permissions (also available
in Project databases) to choose the agent and manage its exact/category/session grants.
Revocation cannot undo effects already committed. There are no automatic retries.

Browser decisions use the existing leased, CSRF-protected approval endpoint with actions
approve_once, reject, always_exact, session_similar or always_similar. Scope, category and
lifetime are derived server-side. Policy PATCH accepts only enabled; DELETE revokes.
Connection/credential/driver changes still require their detailed one-time review workflow.

Policies are bounded to 1,024 total and 128 per agent, with a 2 MiB persisted-file limit.
Exact matching stores a keyed HMAC, not SQL or parameter values. The protected key and
reusable-approvals.json reside in the configured DBA data directory. Session grants are
never written to disk. Redacted audit records identify policy/category and target hash;
catalog reads also use a bounded rotating reusable-access audit. Storage or audit failures
deny authorization. Existing bounded request retention, deadlines, review leases and
cancellation remain in effect.

Legacy SQL/environment permissions can still be read and revoked, but new approval decisions
cannot mint those broad permissions. Existing profile-inspection/test target-capability
decisions remain available separately; they confer no SQL creation or execution permission.

With approval mode none, management/live-SQL tools remain omitted. Existing matching
reusable read grants may serve restricted read tools; creation grants cannot enable those
omitted tools or bypass the approval-channel requirement.

## Reproducing validation

Use an isolated checkout/build when a running server holds packaged JARs open on Windows.

```powershell
mvn test "-Djava.awt.headless=true"
mvn -pl code-graph-dba -am test "-Dtest=Reusable*Test" "-Dsurefire.failIfNoSpecifiedTests=false"
./code-graph-dba/test-reusable-vendors.ps1 -BuildRoot <isolated-build-root>
$env:DBA_BROWSER_SUITE = "approvals"
./code-graph-dba/test-browser.ps1 -NodeModules <directory-containing-playwright>
Remove-Item Env:DBA_BROWSER_SUITE
./code-graph-dba/test-browser.ps1 -NodeModules <directory-containing-playwright>
$env:DBA_DESKTOP_TEST = "true"
mvn -pl code-graph-dba -am test "-Djava.awt.headless=false" "-Dtest=DesktopApprovalsTest" "-Dsurefire.failIfNoSpecifiedTests=false"
Remove-Item Env:DBA_DESKTOP_TEST
```

The vendor harness uses uniquely named, labeled disposable PostgreSQL 16, MySQL 8.4 and
MariaDB 11.4 containers, ephemeral loopback ports and fixture-only credentials. It requires
cached test drivers PostgreSQL 42.7.13, MySQL Connector/J 9.7.0 and MariaDB 3.5.7. It removes
only its containers/volumes and newly introduced unused images, even after failures; it
never touches saved application connections. Per-vendor reports remain under target.

Interactive macOS/Linux desktop behavior, real screen-reader usability and every other
vendor/version remain separate, unverified release gates. Windows automated Swing tests
do not certify those environments. Restart the server after updating the native approval UI.

### Verification recorded for this change

Windows, OpenJDK 25+36, Maven 3.9.11, Node 22.22.3 and Edge 153.0.4234.32:

- Full 35-module Maven verify/package: **563 tests, zero failures/errors, 33 gated skips**.
- Separate Windows desktop run: **9 tests passed**, including split-menu selection,
  disabled reasons, default reset, no checkbox, dark scrolling, zoom/window controls and cleanup.
- Disposable PostgreSQL 16, MySQL 8.4 and MariaDB 11.4 runs: all passed creation-category,
  read, estimated-plan, DDL-inspection, routine restrictions and revocation checks.
  PostgreSQL additionally rejected an implicitly resolved out-of-scope catalog relation.
- H2 end-to-end queue tests passed session isolation, exact-repeat failure without replacement,
  expired review and revocation before queued execution; reusable H2 SQL reads remain disabled.
- All 14 browser suites passed. Final approval checks also exercised the Agent access link,
  visible scope/lifetime, keyboard disable, re-enable and revoke controls.
- Installer: 34 Java checks, Windows/Unix bootstrap checks and authenticated proxy smoke passed.
- JavaScript syntax and diff whitespace checks passed. Owned disposable containers/volumes
  and newly introduced unused MariaDB images were removed; pre-existing resources were preserved.

The 33 full-reactor skips include the desktop/vendor suites exercised separately, optional
performance/path cases, live GitHub publishing and other explicitly configured database/vault
gates. They are skips, not passes. Other existing PostgreSQL integration suites and real OS
vault suites were not newly certified by this change. Raw Maven XML/text reports, vendor
reports, browser screenshots and logs are retained under the isolated build's target directories.

References: [H2 JDBC read-only semantics](https://h2database.github.io/javadoc/org/h2/jdbc/JdbcConnection.html),
[PostgreSQL SQL functions](https://www.postgresql.org/docs/16/sql-createfunction.html),
[MySQL SHOW CREATE TABLE](https://dev.mysql.com/doc/refman/8.4/en/show-create-table.html).
