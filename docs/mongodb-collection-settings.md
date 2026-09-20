# Reviewed MongoDB collection settings

The existing browser native workspace and `dba_request_native_command` support
additional `collMod` settings. Select an exact connection/database/collection.
The profile must allow reviewed writes. Browser changes require Review → Apply
once; agents require exact one-time approval unless explicit startup YOLO is active.
No reusable permission is created. Headless restrictions are unchanged.

## Supported settings

Each command contains exactly one settings family; cappedSize/cappedMax may be
combined. Existing validator and verified view-definition commands remain separate.

| Family | Accepted values | Preconditions |
|---|---|---|
| Existing TTL index | `index: {name, expireAfterSeconds}`; integer seconds 0–2147483647 | Exact named single-field TTL index on an ordinary, non-capped collection; no index conversion |
| Time-series retention | `expireAfterSeconds`: integer seconds 0–2147483647 or exact string `off` | Existing time-series collection |
| Time-series granularity | `timeseries: {granularity}`: seconds, minutes or hours | Existing time-series collection, same or larger granularity; custom bucketing is unsupported |
| Capped limits | `cappedSize`: integer bytes 1–999999999999999; `cappedMax`: integer 0–2147483647 | Existing capped collection; 0 removes the count limit, not the byte limit |

These numeric ranges are application restrictions. Strings, fractions, booleans,
overflow, mixed families, unknown fields, system databases/collections, wildcard
indexes and implicit index conversion fail before execution. Server privileges and
version restrictions still apply; resizing capped collections requires MongoDB 6+.
The implementation follows [MongoDB's collMod reference](https://www.mongodb.com/docs/manual/reference/command/collmod/).

Native workspace examples (select `app` and `events`):

```json
{"collMod":"events","index":{"name":"expiry","expireAfterSeconds":7200}}
```

For a selected time-series collection `measurements`:

```json
{"collMod":"measurements","expireAfterSeconds":"off"}
```

For a selected capped collection `recent_events`:

```json
{"collMod":"recent_events","cappedSize":2097152,"cappedMax":500}
```

MCP uses these same command documents with explicit `connectionId`, exact
`connectionName`, `database`, `collection`, `requestId` and `purpose`, or a binding
that fixes the connection/database. Keep using the returned request/job IDs for
status, cancellation and release; do not resubmit after a timeout.

## Safety, memory and recovery

Retention changes and capped resizing are conservatively classified destructive,
including increases and disabling expiration. Reductions can permanently remove
data, including asynchronous expiry after the command returns. Increasing a limit
or selecting `off` never restores deleted data. Granularity changes are reviewed
writes. All reviews show the exact namespace, command and operation-specific risks.

Preparation is offline and never connects or changes settings. Before Apply writes,
the adapter reads one exact-name raw collection definition (maximum 256 KiB).
TTL index lookup streams single-definition batches, inspecting at most 128 indexes
and 1 MiB aggregate, with a 256 KiB per-definition limit. Missing, oversized or
incompatible metadata fails closed. No collection records are retained for validation.
The 64 MiB native-job reservation, normal job concurrency, cancellation and deadlines
remain authoritative accounting bounds, not a hard process-memory cap.

Session/target/profile authorization is checked again after metadata inspection and
immediately before sending the command. Reviews bind the exact request, not a retained
collection UUID or schema fingerprint: an external drop/recreate or metadata change
can still race the preflight. The server makes the final operation checks. This is
not an atomic script, an undo mechanism or an automatic-retry workflow.

After an acknowledged change, refresh tree/catalog metadata. On cancellation or
transport failure after submission, inspect actual options/indexes before retrying.
An uncertain outcome does not mean the setting remained unchanged. The application
does not recreate indexes, replace collections, retarget open workspaces, or silently
undo changes. It does not expose these settings on system buckets or the oplog.

The native tree now includes time-series collections under Collections without
their internal `system.buckets.*` objects. This does not introduce a dedicated
graphical collection-settings editor, custom bucketing, TTL-index conversion,
index visibility/uniqueness changes, transactions or administrative commands.

## Reproducible validation

Use JDK 25, Maven, Docker and the existing isolated-build/browser harnesses:

```powershell
./scripts/Test-IsolatedReactor.ps1 -Name mongo-settings -Modules code-graph-dba -Tests NativeMongoSettingsTest,NativeMongoRenameTest,NativeFoundationTest,NativeReviewTest
# In the printed isolated source directory:
./code-graph-dba/test-native-vendors.ps1 -Engine mongodb
./code-graph-dba/test-mongo-topologies.ps1 -Topology replica_set
./code-graph-dba/test-mongo-topologies.ps1 -Topology sharded
mvn -B -ntp -Djava.awt.headless=true clean verify
mvn -B -ntp -pl code-graph-dba -am dependency:copy-dependencies -DincludeScope=test -DoutputDirectory=target/test-lib
./code-graph-dba/test-browser.ps1 -NodeModules <directory-containing-playwright>
```

Harnesses pin MongoDB 8.0 by digest, use ownership-labelled loopback fixtures and
remove owned containers/volumes plus newly introduced unused images. Existing
databases/images are preserved. Live tests without a fixture are explicitly skipped,
not passed. Single-node replica and single-shard fixtures are not production failover,
multi-shard, Atlas, all-version or authenticated-privilege-matrix certification.

The tests cover strict input validation, wrong collection kinds, granularity
downgrades, metadata and data preservation, TTL preconditions, review cancellation,
ownership/revisions, normal/YOLO agent approval, cancellation/revocation before writes,
browser warnings and real HTTP/MCP review/CSRF. No production database is used.

## Local acceptance — 2026-09-20

Windows x64, OpenJDK 25, Maven 3.9.11, Node 22 and Chromium:

- All six settings tests passed with the disposable MongoDB 8.0 standalone fixture.
  This includes retention/granularity, capped resizing, existing TTL indexes, a
  greater-than-256-KiB definition rejected before writing, and resource release.
- The single-member replica-set and single-shard fixtures passed reviewed TTL-index
  changes on their respective collections, along with the existing rename regressions.
  Other settings/topology combinations and authenticated privilege matrices are not
  independently certified by these tests.
- Clean 35-module Maven reactor: **856 tests, 806 passed, 50 explicit skips,
  zero failures/errors**. Live/platform/optional skips are not claimed as passes.
- All **19 browser suites** passed, including native destructive review, Cancel/Apply,
  exact command retention and rejection of mixed settings. Real HTTP/MCP tests cover
  the new settings request plus Redis and Mongo rename ownership/CSRF flows.
- All 23 DBA JavaScript modules, the browser test and the modified PowerShell harness
  passed syntax checks. Source hashes matched the isolated tested candidate.

Evidence is retained under
`target/mcp-efficiency-coverage/mongo-settings-cb46c637439c4567940592c14804ec01/`:
`maven.log` preserves the initial failing regression; `focused.log` preserves an
intermediate test-helper return-type compile failure, corrected in `focused-final.log`.
`mongodb-final.log`, `mongo-replica.log`, `mongo-sharded.log` and `live-reports/`
retain the live checks. `reactor-final.log` and `browser-all.log` record final gates.

Owned containers/volumes and newly introduced MongoDB images were removed. Existing
databases/images and the running application were preserved. No restart, commit or
push was performed. Code-graph MCP navigation tools were not exposed to this coding
task, so optional-skill guidance used focused local reads; the HTTP/MCP regressions
above did exercise the actual transport on isolated test servers.
