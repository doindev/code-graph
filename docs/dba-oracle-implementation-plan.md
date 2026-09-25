# Oracle DBA implementation plan

Target Oracle 19c and newer. Preserve existing engines and the application's authentication,
CSRF, reviewed mutations, asynchronous jobs, cancellation, resource bounds and styling.
The PostgreSQL comparison fixes, saved-connection editor fix, embedded Maven TLS options,
and code-graph onboarding guidance have been delivered before Oracle implementation.

## Shared foundations

1. Introduce one Oracle capability/target layer: resolved service, PDB/container, owner,
   authenticated user, server version, edition and observed privileges. A selected owner
   never substitutes for a service/PDB. Validate actual targets before reads or mutations;
   bind approvals, previews, comparison evidence and artifacts to that identity.
2. Preserve service/SID/descriptors, TLS/wallet properties and explicitly selected Oracle
   administrative roles in connection profiles. Test ordinary and administrative users.
3. Parse mixed SQL/PLSQL, q/nq quoting and slash delimiters. Preserve parameter order and
   bounds. Add routine OUT/REF CURSOR results and bounded DBMS_OUTPUT. Accurately describe
   Oracle implicit commits and partial execution.
4. Provide Oracle paging/filtering, exact decimal and temporal values, bounded LOBs,
   exports and reviewed grid updates with verified row identity/concurrency checks.

## Metadata and existing workflows

Complete the existing 17 browser categories with package/type bodies, grants, dependencies,
compilation errors and status. Missing privileges must be explicit, not empty-success
catalogs. Integrate catalog current/last-run status, counts, cancellation and terminal states.
Reuse the shared capability layer in table/object designers, native definitions, query
builder, plans, saved queries, authorized agent reads, captures and migrations. Preserve
unexposed attributes when editing definitions.

## Comparison

Capture Oracle source and destination independently, using selected kinds plus dependencies
and incoming dependents. Compare tables, constraints, indexes, sequences, views/materialized
views, routines, packages/types, triggers, synonyms, grants and supported scheduler objects.
Map owner-qualified identifiers structurally; order operations through a dependency graph.
Block unresolved dependencies, unsupported cycles, dynamic SQL remapping, credentials and
external assets that cannot be transferred safely. Revalidate destination compatibility and
captured definitions/data before publishing the script.

Structure-only is the default and must read no table rows. Per-object data selection and
all existing data modes remain available where verified. Optional sequence synchronization
must never consume source NEXTVAL; label exact observations versus cache bounds and preserve
attributes. Handle identity-owned sequences through their owning identity definitions.
Comparison only produces copy/save/cancel output; it never executes that output.

## Oracle Administration workspace

Provide privilege-aware users, roles, profiles, grants, quotas, tablespaces/files, sessions,
locks, active SQL and diagnostics. Compilation/statistics and other mutations use reviewed
plans and async jobs. Monitor Data Pump export/import with stop/resume and discovery after
restart. Provide downloadable RMAN scripts for manual execution. OS management, RAC/ASM
provisioning and cloud infrastructure management are outside scope.

## Verification and delivery

Use deterministic parser/target/capability tests and application-generated SQL against
owned disposable Oracle destinations. Test privilege differences, decimal/temporal/LOB
behavior, dependency cycles, stale revalidation, sequences/identities and Data Pump lifecycle.
Exercise application UI flows and supported-engine regressions. Record container versions
and image digests; preserve existing Docker resources, remove only task-owned containers
and unused images pulled by this task. Full standard Oracle 19c administration certification
remains pending a suitable standard 19c instance; a Free container is not that certification.
Commit and push the completed Oracle changes after applicable checks pass.

## Current status

Capability audit complete. Connection target observation and mixed SQL/PLSQL execution,
typed routine parameters, REF CURSOR results and bounded opt-in DBMS_OUTPUT are implemented.
The first 23 Java checks and Oracle SQL browser checks pass; script-selection and saved
connection browser regressions also pass. Explicit typed NUMBER, DATE and timestamp-with-offset
inputs also pass against the live database.
Native package/type bodies, dependencies, grants, status and compilation diagnostics now pass
live Oracle scanner and reviewed editor tests, including browser validation. Native structure comparison and ordinary sequence advancement now generate destination scripts
through the existing review/copy/save/cancel flow, with live execution and repeat-comparison
validation. An Oracle row adapter now implements per-table data modes, bounded native values,
row revalidation and generated staging SQL. Live checks cover all four data modes, invisible
columns, scalar/LOB fidelity, stale data and foreign-key restoration. The Oracle comparison
browser suite passes data selection/review/generation, structure-only switching and disposal.
Oracle scalar grid paging, filtering, reviewed edits and bounded exports now pass live
precision/date, rollback, concurrent-edit and target-identity checks. The Oracle SQL browser
suite also passes saved national-character edits, refresh and pagination.
Oracle visual queries now load native views and materialized views, import joins, discover
standalone/package scalar signatures and retain precise transient inputs in paging and Explain.
The focused Java regression set passes 44 checks, including a live least-privileged Oracle
builder/plan test; all ten live Oracle integration cases also pass after the parameter changes. Oracle browser checks cover exact numeric/timestamp values, completed
plans and package-function selection. Shared canvas redraw retains focused join controls;
the full non-Oracle builder browser suite passes geometry, lifecycle, typed joins, recovery,
cancellation and failure retention.
Oracle scoped agent reads now enforce ordinary-user read-only transactions with separate PDB/owner permissions, fixed filtered catalog inspection and revocation. Exact-review agent Explain also passes live Oracle validation. The 34 focused Java checks, Oracle SQL/permission-picker browser suite and shared read-permissions browser suite pass. Views, synonyms, custom functions and sequence access remain one-time-review operations.
Oracle table Properties and New Table now use native incremental DDL, bounded Oracle types, new identity/virtual columns, constraints/indexes and complete native-definition conflict checks. Live tests verify invisible-column preservation, storage-change invalidation and partial commits; the focused eight Java checks and Oracle/H2 creation browser flows pass. The full 12-case Oracle integration suite also passed before the cancellation fixture was added. A further 16 focused checks verify JDBC-first cancellation, bounded interruption fallback, uncertain Oracle DDL outcomes, and designer regressions; the shared Compare browser suite passes. Server-side DDL can continue after cancellation, and is reported as unknown rather than not applied.
Oracle tree actions now resolve the actual PDB and revalidate native definitions, including
rename, truncate, object deletion and materialized-view refresh. The schema-wide Table Triggers
routing bug is fixed. Oracle retained schema/migration workflows now support native datatypes,
mixed SQL/PLSQL, exact one-time approval, owner-remapped disposable rehearsals, stale-definition
checks, retained partial steps and compilation errors. The 28 focused Java checks pass, followed
by nine checks covering the final compilation-error and distinct-rehearsal-target behavior.
The expanded Oracle SQL browser suite also passes reviewed rename, truncate and delete,
alongside the existing SQL, grid, designer, builder and permission flows.
The Oracle Administration workspace now provides bounded catalog pages and typed, expiring,
single-use mutation reviews for accounts, roles, profiles, grants, quotas, storage, sessions,
compilation and statistics. Live ordinary/admin tests and the desktop/mobile browser suite pass.
Data Pump schema export/import, reviewed stop/resume, native restart discovery, ordinary-user
catalog fallback and bounded private observation history now pass live lifecycle checks. Manual
RMAN backup/validation artifacts have complete copy/save controls; the downloaded backup passes
Oracle's syntax-only checker. Browser tests cover status monitoring and artifact disposal.
Owner-issued object and column grants now participate in comparison, stale-state revalidation
and generated scripts. Live checks verify role/user grants, grant options, destructive revocation
and a repeat comparison after execution. Trusted metadata packages and catalog reads are
SYS-qualified so owner-local objects cannot shadow them.
Native stored-procedure programs, regular jobs and calendar schedules now participate in
comparison with typed identifier remapping, dependency-aware recreation, preserved grants
and deferred enablement. Dependency objects remain selectable in the review screen.
Twenty focused Java checks and the Oracle comparison browser flow pass. The final ten-check
run also verifies package procedures, inline/disabled jobs, scheduler-only target evidence,
per-object catalog names, stale scheduler state and destination-only dependent blockers.
All 17 catalog categories and a complete 43-object native scan now pass live Oracle validation,
including scheduler arguments, chain steps/rules, column grants and owner privilege restrictions.
Scans honor the configured statement timeout while retaining the overall deadline. Chain DDL
and ANYDATA limitations are explicitly labeled. Three scan lifecycle/budget checks and the
20 existing scheduler/standalone-catalog checks also pass.
Identity sequence synchronization and broader object variants remain
in progress; this is not completed Oracle support.

See [Oracle support details](dba-oracle.md) for the currently validated scope.
