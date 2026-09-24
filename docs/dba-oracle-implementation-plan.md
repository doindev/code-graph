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
live Oracle scanner and reviewed editor tests, including browser validation. Catalog completion, comparison and administration
remain in progress; this document is not a claim of completed Oracle support.

See [Oracle support details](dba-oracle.md) for the currently validated scope.
