# YOLO implementation and acceptance report

Validation date: 2026-09-18. Environment: Windows 11 amd64, OpenJDK 25+36,
Maven 3.9.11, Node 22.22.3, Docker Desktop engine 29.7.2.
See [configuration and reproducible commands](yolo.md).

## Implemented

- [x] Startup-only, case-sensitive configuration; DBA requirement; launcher defaults
  and approval-mode override; no graphical probe or approval channel in YOLO.
- [x] Shared automatic authorization for validated local HTTP/stdio identities,
  including named agents with no stored grants.
- [x] Exact targets, live-session checks, revocation, stale revisions, audit-before-
  execution, retained-plan ownership and restricted read-tool semantics.
- [x] Complete migration initialization before authorization/submission; duplicate
  requests return retained status without replaying a consumed plan.
- [x] Bounded asynchronous driver installation, unchanged-draft testing, explicit
  save-untested, profile administration and optional-binding partial outcomes.
- [x] Separate active admission, bounded completed history and session-aware
  duplicate protection. No automatic retry and no reusable permission creation.
- [x] Persistent browser warning, settings/telemetry/context/capability visibility,
  no approval polling, unchanged browser confirmations and editor pairing.
- [x] Tool schemas/descriptions, launcher help, README/security/install guidance
  and the shared agent skill updated.

## Verification evidence

The full Maven reactor passed. The focused automatic-authorization suite includes
14 H2/startup/session/resource tests, two replay-ledger tests, and six HTTP/stdio
tests (including inherited normal-mode transport regressions). Final targeted
regressions also cover ordinary approvals after the last target-validation changes.

Key checks include explicit Maven installation of H2 2.4.240 followed by a draft
test/save, credential replacement/removal and redaction, the 100-row agent cap,
more than 64 completed requests without active-admission exhaustion, ledger
capacity/expiry, queued cancellation and stale-profile rejection, no desktop
probe, no saved policies, SQL parameter validation, mixed-target rejection,
session-bound migrations and restart with YOLO off.

All 16 browser suites passed, including the dedicated YOLO warning/confirmation
suite and the restricted approval-only review site. All DBA JavaScript modules
passed Node syntax checks. The warning screenshot was also visually inspected.
The normal browser SQL, grids, table designer, query builder, project bindings,
editor pairing, approvals and session-recovery regressions remain covered.

Installer checks: 34 Java assertions, PowerShell bootstrap regression suite, and
three skill-install helper tests passed. Nine opt-in Windows Swing approval tests
and two real Windows credential-vault round-trip tests also passed; owned test
credential entries were removed. The packaged launcher passed positive and
negative argument smoke checks without starting a server. The skill-creator frontmatter validator
passed; its missing PyYAML dependency was installed only under the project build
directory. No client configuration or installed permissions were changed.

### Disposable live databases

| Engine | Server | JDBC | YOLO and normal regressions |
|---|---|---|---|
| H2 | 2.4.240 embedded | 2.4.240 | Passed |
| PostgreSQL | 16.14 | 42.7.13 | Passed |
| MySQL | 8.4.11 | 9.7.0 | Passed |
| MariaDB | 11.4.13 | 3.5.7 | Passed |

The live harness verifies tested connection creation, reads, Explain, DDL
inspection, DML, table/view/routine creation and invocation, migrations, binding
create/update/delete, catalog refresh, capabilities/workspace visibility, saved
connection tests and destructive cleanup. Existing reusable-approval and schema/
rehearsal workflows run beside YOLO against separate disposable fixtures.

Image identities used:

- PostgreSQL: sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b
- MySQL: sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a
- MariaDB: sha256:70cc072b29b4a89ae07abb2d4da2c64678a7f2dfe092751bb51c87d67dc1338b

Per-engine evidence is retained under
`code-graph-dba/target/surefire-reports/yolo-{vendor}.txt`, alongside
`reusable-{vendor}.txt` and `workflow-{vendor}.txt`.
Ordinary unit/transport reports are in each module's `target/surefire-reports`.

### Scope and cleanup

All owned PostgreSQL/MySQL/MariaDB test containers and volumes were removed.
The newly pulled MariaDB image was removed after each run. Pre-existing images
and the user's three existing database containers were preserved; no broad prune
was used. No user-owned or production database was provisioned or mutated.

The default reactor intentionally skips optional external-vendor, real-vault and
platform-specific tests whose prerequisites are absent. Those skips are not
reported as passes. This change's four database gates were run explicitly.
macOS/Linux native desktop/vault behavior and paid/hosted databases were not
re-certified by this Windows validation; their existing limitations remain.

Windows held several prior generated build files in mapped sections. Those
outputs were renamed to preserved backups under their module targets before
fresh compilation; no source files or user caches were deleted.

The user's normal application remains stopped. No commit or push was performed.
