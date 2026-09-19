# Compare, prepare and rehearse

With startup YOLO active, retained plans are automatically authorized once instead
of opening human review; their session ownership, target and schema-fingerprint
checks are unchanged. Never treat automatic authorization as permission to expand
the requested migration or bypass uncertain-outcome reconciliation.

These workflows require the corresponding tools to be advertised and verified
for the target. Do not emulate missing tools by silently running database writes
through a shell. Use the agent's ordinary file tools to save returned SQL and
manifests when the user requested artifacts.

Authorize both sides of schema capture/comparison independently. Keep snapshots'
engine/version, exact target, generation, coverage and fingerprints with the
comparison. Formatting differences are not necessarily semantic changes; missing
objects in incomplete inventories are unknown, not proven removals. Cross-engine
comparison identifies compatibility gaps, not an automatic portable migration.

For retained-job schema tools, poll each `dba_capture_schema` job to completion
before passing its ID as `leftSnapshotId` or `rightSnapshotId` to
`dba_compare_schemas`. Both snapshots must belong to the same agent and retain
valid target permissions/revisions. Release completed jobs after comparison;
an in-use error means a workflow still holds an accounted result lease. Expired,
released or stale captures require fresh observation. Respect truncation and
category/definition limits; do not combine arbitrary pages into a claim of a
complete schema unless the server explicitly supports generation-bound capture.

For `dba_prepare_migration`, provide explicit structured changes or reviewed SQL.
Never infer renames from similar drop/add pairs, introduce replacement/cascade,
copy real records, or silently backfill/delete data. Review ordered steps,
preconditions, expected postconditions, locking and transaction guarantees.
Save artifacts before expiry when needed; applying uses the server-retained plan,
not a changed SQL string disguised as the same plan.

Rehearsals require an explicitly approved disposable target distinct from the
source, including aliases. The application does not manage Docker. Provisioning
through local tools requires user authority and any vendor licence acceptance.
Use selected schema and bounded synthetic fixtures, not application records.
Review setup definitions too: routines/triggers can have side effects. Disclose
version differences and incomplete reproduction; do not call it a production
simulation when dependencies were omitted.

When advertised, the concrete sequence is: capture the source schema, prepare the
migration, capture the distinct disposable target, call
`dba_prepare_migration_rehearsal` with optional CREATE/COMMENT setup, INSERT-only
synthetic fixtures, and restricted validation SELECTs, then request application of
that retained rehearsal plan. Every setup, fixture and migration statement is shown
in the exact one-time human review. Reusable SQL policies cannot approve the plan.
The application reports post-fingerprint and check evidence but does not tear down
the target; the owner of the disposable environment performs scoped cleanup.

Check fixture preservation, constraints, structural postconditions, rollback and
partial commits. Stop on stale plans or uncertain outcomes and reconcile before
retrying. Clean up only this task's resources; preserve existing images/databases,
remove introduced unused test images, and never broadly prune Docker.
