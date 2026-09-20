# MongoDB collection rename

The native MongoDB adapter accepts a reviewed, same-database `renameCollection`
through the existing command workspace and `dba_request_native_command` tool.
It does not add an unreviewed tree action or broaden SQL/native permissions.

## Usage and exact scope

In a MongoDB native workspace select database `app` and collection `items`, enter:

```json
{"renameCollection":"app.items","to":"app.items.renamed","dropTarget":false}
```

Run opens the existing destructive-operation review. Cancel makes no database
change; Apply once submits one asynchronous job. The MCP tool uses the same
command with explicit connection UUID/name/database/collection (or a binding
that fixes connection/database), an idempotency request ID and a purpose.
Normal agents still require one-time human approval. Startup `--yolo` supplies
automatic consent only; a read-only profile, invalid target or expired session
still fails. Reusable native write permission remains unavailable.

The review identifies both namespaces and explains the locking/cursor risks.
MongoDB requires this command to be sent to `admin`; that transport detail does
not change the selected database or grant general admin-command access.
Only the submitted source/destination pair is executable.

- Source must exactly match the selected database and collection.
- Destination must remain in that database and differ from the source.
- `dropTarget` may be omitted or boolean `false`; replacement is never enabled.
- No extra command fields, custom write concern, system collections or
  `admin`/`config`/`local` database renames are accepted.
- Source/destination namespaces are limited to 235 UTF-8 bytes, a conservative
  shared bound across standalone, replica-set and sharded deployments.
- An exact-name, one-record metadata lookup verifies an ordinary/capped source.
  Views, time-series collections and missing sources fail before submission.
  Definitions exceeding 256 KiB are not expanded for this check.
- Database credentials need permission to inspect the source definition as
  well as perform the rename; denied inspection does not fall back to blind DDL.

Authorization, target/profile/binding revision and cancellation are rechecked
after connection/metadata work, immediately before dispatch. Existing audit,
deadline, job/result limits and resource accounting remain in effect.
There is no automatic write retry, no database inventory and no document copy
retained by the application.

## Effects and recovery

MongoDB preserves the collection and indexes for same-database renames, but locks
the involved collections and invalidates cursors/change streams. Old-name views,
queries and application code may require updates. See the
[MongoDB command reference](https://www.mongodb.com/docs/manual/reference/command/renamecollection/).

Successful results include `renamed: {database, from, to}` and `outcome`:
`acknowledged`. Refresh the tree and explicitly select the new collection.
Existing command drafts/workspaces are deliberately not retargeted or rewritten.
There is no dedicated graphical rename editor in this increment.

The server's non-replacement behavior protects an existing destination even if
another client creates it after review. Metadata checks do not lock out external
source deletion/recreation during review: this is exact namespace consent, not a
retained collection-UUID plan or a frozen database snapshot.

After transport loss/cancellation, inspect both namespaces before any new
request. A failed status or accepted cancellation is not proof of rollback.
No automatic undo or replay is attempted. An existing destination failure
preserves both collections; the application still conservatively reports an
uncertain outcome when a command was dispatched but did not acknowledge success.

## Reproducible validation

Use an isolated source copy; do not rebuild mapped files used by a running server:

```powershell
./scripts/Test-IsolatedReactor.ps1 -Name mongo-rename -Modules code-graph-dba `
  -Tests 'NativeMongoRenameTest,NativeReviewTest,NativeFoundationTest,AgentRequestsTest,YoloTest'
# In the printed source directory, sequentially:
./code-graph-dba/test-native-vendors.ps1 -Engine mongodb
./code-graph-dba/test-mongo-topologies.ps1 -Topology replica_set
./code-graph-dba/test-mongo-topologies.ps1 -Topology sharded
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster
```

The harnesses use the recorded MongoDB 8.0 image digest
`sha256:4968f22d0c6c10ef29952f3e807f62872ba22b3312f25803564fbfc08255efc2`
and MongoDB Java 5.12.0. Topology fixtures use one replica member/one shard; they
do not certify multi-shard concurrency, failover, Atlas or other server versions.
Each harness removes only owned containers/volumes and newly introduced unused
images. Existing databases/images are preserved; no broad Docker pruning.

Live tests exercise original UUIDs/documents/indexes/capped settings, destination
collisions, ordinary and sharded collections, invalid object kinds, browser-owned
review consumption, normal agent approval and automatic authorization. Unit tests
cover namespace escapes, replacement/type tricks, profile revisions, read-only
profiles and ownership. Browser tests use the real review API with mocked execution
to check exact command display, Cancel/Apply, invalid-target errors and unchanged
workspace targeting; real execution is covered separately by the disposable tests.

The broader native roadmap remains incomplete. This feature does not implement
cross-database moves, transaction sessions, document editors, change streams,
infrastructure administration or MongoDB migration/rehearsal workflows.

## Local acceptance record — 2026-09-20

Windows x64, OpenJDK 25, Maven 3.9.11, Node 22 and Chromium. The pinned MongoDB
standalone, single-member replica-set and single-shard fixtures passed, including
an actually sharded collection. Redis 7.4.1 standalone and Cluster native
regressions passed after adding the shared final pre-write authorization check.

The clean 35-module Maven reactor passed: **849 tests, 801 passed, 48 explicit
optional/live/platform skips, zero failures/errors**. All **19 DBA browser suites**
passed. The new HTTP/MCP parameterized test covers both Redis and MongoDB with
structured results, pending review, CSRF, session ownership and single-use plans.
Syntax checks passed for all 23 DBA JavaScript modules, the native browser test
and the modified PowerShell vendor harness. Skipped gates are not claimed as
live vendor, cloud, permission-matrix or cross-platform desktop certification.

Evidence is retained under
`target/mcp-efficiency-coverage/mongo-rename-f8c8d050c3904809963a5661864c26e6/`:

- `maven.log`: three original regression failures reproducing the missing adapter
  and absent namespace validation.
- `focused-final.log`, `mongodb-final.log`, `mongo-replica-final.log`,
  `mongo-sharded-final.log`, `redis-final.log`, `redis-cluster-final.log`:
  corrected focused/live runs.
- `reactor-final.log`, `browser-all.log`: full clean build and complete browser pass.
- `mongo-replica.log`: retained intermediate test-compilation failure after the
  pre-write callback signature changed; its direct Redis test caller was updated
  and the gate rerun successfully, not omitted from results.

Changed source files were hash-compared with the isolated candidate before the
full build. Newly downloaded MongoDB images and owned fixture containers/volumes
were removed; existing Redis images and user database containers were preserved.
There was no broad prune or production database mutation.

The optional code-graph skill was used for navigation guidance. The application
was listening on ports 3000/8137, but this coding task exposed no code-graph MCP
tools, so implementation used focused local reads. This is distinct from the
programmatic HTTP/MCP regression tests above, which did run through the transport.
The running application was not replaced. No commit or push was performed.
