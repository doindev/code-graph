# Bounded MongoDB transactions

The native MongoDB command workspace and `dba_request_native_command` support an
operation-owned transaction on **one exact existing ordinary collection**. This
is not an interactive session, cross-collection transaction editor, migration,
change stream, or infrastructure-administration capability.

## Command and scope

Optional documentGuard supports a narrowly scoped, byte-exact complete-document
replacement on replica-set profiles only. It does not change ordinary transaction
behavior. See [the editor and guard contract](mongodb-document-editor.md) for the
canonical type/size, immutable ID, UUID, concurrency and recovery requirements.

Use a native profile explicitly configured as `replica_set` or `sharded`, with
**Allow reviewed writes** enabled. Supply the existing exact binding, or the
standalone connection UUID/name/database, plus `collection`. For example, with
collection `items`, the command is:

```json
{
  "transaction": [
    {"insert": "items", "documents": [{"_id": "new-item", "quantity": 1}]},
    {"update": "items", "updates": [
      {"q": {"_id": "existing-item", "quantity": 3}, "u": {"$set": {"quantity": 2}}}
    ]}
  ]
}
```

The existing request ID, purpose, approval/status/job/cancel/release contracts
apply. Browser users review the complete command before **Apply once**. Normal
agents need exact one-time approval; native writes never inherit reusable SQL
policies. Startup YOLO supplies consent only, not a target, writable profile,
credentials, server privileges or supported topology.

- 1–32 CRUD command objects, at most 100 total document-write entries and the
  existing 128 KiB/32-level command limit. The configured result-row/byte limits
  can require a smaller batch. All commands validate before connecting.
- Only `insert`, single-document `update`/replacement/upsert, and `delete` with
  `limit: 1`. `ordered` must be true or omitted. No raw session controls,
  concern overrides, nested transactions, reads, DDL or different collections.
- Each update/delete entry must match exactly one document. A zero-match filter
  aborts the entire batch. Include expected original fields in `q` for optimistic
  concurrency; a filter containing only `_id` is not an original-value check.
- Every insert entry must be acknowledged. Server validators, unique indexes,
  permissions and transaction restrictions remain authoritative.
- Standalone/SRV transaction modes, system databases/collections, views, capped
  collections and time-series collections are rejected. SRV connectivity is
  separate from certifying transactions through that discovery mode.
- Preflight verifies live topology/session support and bounded collection metadata.
  The collection UUID is checked again before commit to reject replacement races.

The application uses a primary read preference, snapshot read concern and a
majority commit. MongoDB's server transaction restrictions still apply; see
[transaction operations](https://www.mongodb.com/docs/v8.0/core/transactions-operations/).
“Atomic” describes these database writes, not zero locking, free execution,
external integrations, or all possible infrastructure side effects.

## Outcomes and cleanup

Results have `kind: "transaction"`, `atomic: true`, bounded per-command counts
and an explicit `outcome`. They do not return whole inserted documents or raw
driver error replies.

| Outcome | Meaning and recovery |
|---|---|
| `not_started` | Validation/admission/preflight failed before transaction writes. |
| `rollback_acknowledged` | No commit was attempted; the server confirmed abort or that no transaction remained. Correct the request and obtain new approval. |
| `rollback_unconfirmed` | No commit was sent, but abort could not be confirmed. The server may retain the transaction until its timeout; investigate before new work. |
| `commit_acknowledged` | All writes committed. Per-command entries say `committed`. |
| `commit_unknown` | Commit was attempted but its acknowledgement was not confirmed. Reconcile with authorized reads; never automatically resubmit. |

Commands run sequentially in one driver session under one transaction-wide
timeout and the existing job deadline, cancellation and 64 MiB native reservation.
No session is held while awaiting human review. Clients/pools retain their
existing lazy/idle lifecycle; closing the operation releases the session.
Driver/native buffers remain outside a hard total-RAM guarantee.

The pinned Java driver forbids per-operation timeout changes inside a transaction.
The transaction therefore owns its timeout, rather than resetting it per command.
No manual `maxTimeMS` is combined with client-side timeoutMS; that combination is
[undefined by the driver contract](https://mongodb.github.io/mongo-java-driver/5.5/apidocs/driver-sync/com/mongodb/client/MongoDatabase.html).
Timeout/cancel is best-effort during socket writes and cleanup.

The application does **not** use the retrying `withTransaction` or
`commitTransaction` convenience helpers. It sends a single
[commit command](https://www.mongodb.com/docs/manual/reference/command/committransaction/)
through the public non-retrying command API, retaining driver-managed session,
transaction number, router pinning and recovery token. Session close may send a
best-effort abort because raw commit does not update the driver's client-side
transaction state; this cannot undo a committed transaction or replay its writes.
Abort failure never becomes a claim of successful rollback. No recovery token,
session token, credentials or raw payload is added to general application logs.

## Reproducible validation

Use an isolated source/build directory, not the directory of a running server:

```powershell
./scripts/Test-IsolatedReactor.ps1 -Name mongo-transactions -Modules code-graph-dba `
  -Tests 'NativeMongoTransactionTest,NativeRedisTransactionTest,NativeReviewTest'
# From the returned source directory, sequentially:
./code-graph-dba/test-mongo-topologies.ps1 -Topology replica_set
./code-graph-dba/test-mongo-topologies.ps1 -Topology sharded
```

The topology harness pins MongoDB 8.0 to
`sha256:4968f22d0c6c10ef29952f3e807f62872ba22b3312f25803564fbfc08255efc2`
with Java driver 5.12.0. It uses a uniquely labelled disposable container,
loopback ports, a 256 MiB test heap and 64 MiB direct-memory limit. Its `finally`
removes only its owned container/volumes and the introduced unused image.

## Acceptance evidence (2026-09-20)

Evidence is retained under
`target/mcp-efficiency-coverage/mongo-transactions-953bbc2520394be192f91e14633d047e/`.

- Replica-set and sharded-router fixtures each passed six tests (five transaction
  cases plus the topology case), with zero failures or skips. See
  `replica_set-final.log`, `sharded-final.log` and `live-reports/*-final/`.
  Coverage includes insert/update/delete, duplicate-key and unmatched-row rollback,
  cancellation, authority revocation, 32-command admission, primary execution from
  a secondary-read profile, normal approval and YOLO, and released leases.
- The uncertain-commit test commits against the real fixture, then a test-only
  proxy drops the acknowledgement. It verifies one commit attempt, retained
  `commit_unknown` evidence and secret redaction. This is not a network partition
  or primary-election test.
- Final 35-module reactor: **867 tests, 813 passed, 54 explicit optional skips,
  zero failures/errors** (`reactor-verified.log`, `reactor-reports-verified/`).
  This includes the final guards rejecting executable `$where`, `$function` and
  `$accumulator` expressions in transaction filters/updates, plus MCP schema,
  ownership, CSRF, cancellation and normal-approval regressions. Literal inserted
  document contents are not interpreted as executable expressions.
- The native browser suite passes exact review/Cancel/Apply, destructive notices,
  failed-job rollback details and draft retention; its review screenshot was
  visually inspected. All **19 isolated browser suites pass**: the first 15 in
  `browser-verified.log`, followed by corrected pairing, approvals, approval-only
  review and core in `browser-*-verified.log`. The first complete attempt exposed
  the pairing assertion described below; its failure log is retained, not erased.
- All 23 DBA JavaScript modules, both changed browser tests and the Mongo topology
  harness pass syntax checks. Skill validation and all 11 installer/bootstrap
  tests pass; no actual client configuration was changed.
- MCP symbol/caller navigation and exact file-hash freshness checks used the
  running hybrid server. The purpose/duration/bytes/generation ledger is
  `target/mongo-transactions-navigation.jsonl`. This is development evidence, not
  a complete filesystem-call count or a new performance benchmark.
- Owned Docker containers/volumes and the newly introduced unused MongoDB image
  were removed. Existing databases and images were preserved; no broad pruning.

The live tests exposed the driver's prohibition on changing `timeoutMS` within
a transaction; the implementation now uses one transaction-wide timeout. Browser
validation also corrected an approval counter after the added transaction case
and a pre-existing flaky pairing-code assertion that rejected valid underscores.
Neither test correction broadens authorization.

The user's requested restart preserves MCP port 3000, admin/DBA UI port 8137,
desktop approvals, hybrid storage and the current installed 1.5 GiB graph/cache
allowance. That allowance is not a total-process memory cap. Repository onboarding
for indexed navigation is explicit through MCP, not an automatic startup root.
No commit or push is part of this increment.

Deployment verified: PID 18480, `/` and `/dba` HTTP 200, fresh MCP initialization,
69 tools and four native-command schema forms, desktop approvals enabled and YOLO
off. Explicit repository onboarding completed: 610 files, 7,794 symbols, 32,853
edges, no pending changes. Startup logs are in
`target/mcp-efficiency-coverage/restart-mongo-bfb98168bf1243b8a8477f3715250a1d/`.
The initial PowerShell readiness check timed out; subsequent direct HTTP checks
and fresh MCP sessions succeeded. The validated candidate is running from its
isolated build directory, which must not be rebuilt or deleted while in use.

Actual multi-shard distributed writes, elections during commit, authenticated
production topologies, SRV/TLS/cloud deployments, interactive desktop/platform
certification and change streams remain unverified or outside this increment.
