# Guarded MongoDB document editor

The native workspace's **Edit MongoDB document** action provides a bounded,
memory-only complete-document draft. Select an exact collection and enter an
Extended JSON _id (string, ObjectId, Int32 or Int64) to load, or choose **New document**.
Opening the editor performs
no query. **Load document** explicitly requests exact collection metadata and a
bounded find; incomplete, missing or ambiguous results cannot become editable
snapshots. The native JSON command and SQL Script contents are not rewritten.

## Scope and workflow

- Writes require a writable, explicitly configured replica-set profile and an
  existing ordinary collection. Standalone, SRV, sharded, views, capped and
  time-series targets remain inspect-only in this editor.
- Sharded identity/routing support is unfinished: an _id is not necessarily
  globally unique when it is not the shard key. Do not switch to an unguarded
  command to force an edit through. See [MongoDB shard-key restrictions](https://www.mongodb.com/docs/manual/core/sharding-shard-key-indexes/).
- Edit canonical Extended JSON, not relaxed JSON numbers or JavaScript. This
  preserves Int64/Decimal128 precision, binary, dates, nested values, missing
  fields and explicit nulls. Numeric values require the appropriate BSON type
  wrappers. The server rejects noncanonical representations and unsupported IDs.
  [Canonical Extended JSON reference](https://www.mongodb.com/docs/manual/reference/mongodb-extended-json/).
- A loaded document's _id is immutable. Saving that draft is a complete replacement: removing a field from
  the draft removes it from the saved document. No upsert, bulk,
  update-operator or projected-document editing is enabled here.
- Save opens the existing exact native review. Only **Apply once** submits the
  retained reviewed plan. Canceling review preserves the draft.
- **New document** loads only exact collection metadata, then starts an unsaved
  draft with a suggested ObjectId (current timestamp and cryptographically random
  suffix). The ID and fields can be changed before Save. The backend requires an
  explicit typed ID, checks absence in the transaction and never changes an insert
  into an update/upsert or creates a missing collection. An existing or competing
  ID conflicts instead of being overwritten. Other unique indexes and validators
  still apply. The suggestion is not an official driver-generated ObjectId and
  does not constitute an absence guarantee; the reviewed transaction is authoritative.
- New drafts are dirty even before adding fields. Revert resets to their initial
  suggested ID; Close asks before discarding them. Delete is unavailable for an
  uncreated draft. Nothing is inserted until Save, review and Apply succeed.
- Revert restores the loaded original without a write. Reloading or closing a
  dirty draft requires confirmation. Tab switching preserves drafts; profile
  change/removal disables further operations. Drafts are not session-persisted.
- **Delete document** stages removal of the loaded complete document and locks
  the text area. Nothing is sent until **Save document** opens the exact destructive
  review and **Apply once** is selected. Revert cancels all draft changes, including
  deletion. Failed or cancelled reviews retain the pending deletion; confirmed
  commit clears the displayed document. This never drops the collection.
- A confirmed commit leaves the submitted text visible but read-only; explicitly
  Load again before another edit. It is not presented as freshly queried data.
- Conflicts and confirmed rollback preserve the draft. Lost replies, invalid
  receipts or uncertain commit/rollback block Save, Revert and Load. Reconcile
  with authorized reads, then explicitly **Discard reconciled document draft**.
  This never retries, rolls back or repairs a prior write.

The editor reserves 512 KiB from existing browser workspace accounting, admits
at most 32 KiB UTF-8 per original/replacement document, rejects duplicate JSON
field names, and shares existing native result/job limits. It retains no cursor,
socket, background timer, disk copy or second inventory while idle. Driver wire
buffers and transient/DOM overhead are not a hard total-RAM guarantee.

## Server/MCP guard contract

The existing native command tool accepts an optional documentGuard on a managed
MongoDB transaction. Discover its current schema/capability before use:

~~~json
{
  "transaction": [{
    "update": "items",
    "updates": [{
      "q": {"_id": "example"},
      "u": {"_id": "example", "value": {"$numberInt": "2"}},
      "multi": false,
      "upsert": false
    }]
  }],
  "documentGuard": {
    "collectionUuid": "AAAAAAAAAAAAAAAAAAAAAA==",
    "expected": {"_id": "example", "value": {"$numberInt": "1"}}
  }
}
~~~

The UUID above is an illustration, not a usable target identity. Supply the
actual listCollections info.uuid binary subtype 04 as canonical base64. The
expected document must be complete canonical Extended JSON, not a projection
or shortened result. Original and replacement are each at most 32 KiB.

Only one insert, replacement update or single-document delete is allowed. Original/filter/replacement must contain
the same supported, typed _id; guard fields, filters and options are allowlisted.
The application controls simple collation and forbids caller collation overrides.
Preflight verifies replica-set/session support, ordinary collection identity and
the reviewed UUID. Within the existing snapshot transaction it reads the exact
document and compares BSON bytes, including field order and numeric types.
The subsequent update/delete shares that transaction, so concurrent changes cannot be
silently overwritten or removed. Collection identity and authorization are rechecked before
the single non-retrying commit. Existing transaction cancellation, timeouts,
audit, ownership, resource reservations and cleanup remain unchanged.

Byte equality is deliberately conservative: field reordering or a numeric-type
change conflicts, even if MongoDB's ordinary value equality might compare them
as equivalent. Values returning to the exact original bytes are not historical
identity evidence. BSON documents whose duplicate fields or ordering cannot be
faithfully represented through JSON must not be edited this way.

For deletion, use `{"delete":"items","deletes":[{"q":{"_id":"example"},"limit":1}]}`
as the sole transaction entry, with the same complete original and UUID guard.
Additional filter fields, collation overrides, multiple deletes and non-integer
or non-1 limits are rejected. The server uses simple collation for both the
snapshot guard and the delete. Missing/changed documents conflict; a concurrent
write between comparison and deletion aborts the transaction.

For creation, use `{"insert":"items","documents":[{"_id":"example","name":"new"}]}`
as the sole transaction entry and `{"collectionUuid":"...","absentId":"example"}`
as documentGuard. Supply **exactly one** of expected or absentId. Absent ID and
insert ID must have identical canonical BSON types/values. Only the ID is read
during the absence check; existing large documents are not loaded as a side effect.
The collection must already exist with the reviewed UUID. MongoDB's unique ID
constraint rejects a competing insert after the snapshot check; this depends on
the verified replica-set boundary, not sharded global-ID assumptions. See the
[unique-index behavior](https://www.mongodb.com/docs/manual/core/index-unique/).
Collation or other unique constraints may reject additional values; they are not
silently bypassed. A confirmed insert leaves the draft read-only until a fresh load.

Receipts retain kind transaction, atomic true, documentGuard true, explicit
commit/rollback outcome and exactly one insert/update/delete's matched count/state. An HTTP
success or terminal job state alone is not proof of commit. Normal agents still
need exact one-time approval; startup YOLO changes consent, not guard checks.
No reusable write grants, additional tools or database-access authority are added.
Existing unguarded transaction contracts are unchanged. See
[transaction outcomes and recovery](mongodb-transactions.md).

## Validation and delivery

Validated on Windows with OpenJDK 25, Maven 3.9.11, Node 22.22.3 and Playwright,
using an isolated source snapshot. Acceptance checkpoint: 2026-09-20.

| Gate | Result |
| --- | --- |
| Focused DBA, HTTP/MCP schemas, ownership/review and restricted-site checks | 46 passed, four opt-in live skips; zero failures/errors |
| MongoDB replica-set fixture | 17 passed, zero skips/failures; repeated after final Java changes |
| MongoDB sharded-router fixture | 17 passed, zero skips/failures, including explicit document-guard rejection; this does not enable sharded editing |
| Full Maven reactor | 35 modules, 183 suites, 906 tests: 842 passed, 64 explicit opt-in/environment skips, zero failures/errors |
| Browser regressions | All 19 suites passed; final packaged native suite also passed after narrow-layout refinements, with fixture shutdown confirmed |
| JavaScript and maintained optional skill | DBA module/test syntax and skill frontmatter/reference validation passed |
| Installer regressions | Java: 39 CgraphInstaller, 50 SkillInstaller and 149 McpInstaller tests passed; Windows bootstrap passed; Node: 14 passed, two optional real-client CLI checks skipped |

Live document cases cover typed BSON replacement, byte-exact original conflicts,
a concurrent external write between guard-read and update, collection UUID
replacement, cancellation/revocation, and a lost reply after an actual commit.
The lost-reply case verifies one commit attempt and no automatic replay. Normal
agent requests still await exact approval; forged persistent choices fail.
Startup YOLO also preserves the guard. Existing transaction/stream tests run in
the same live harness. Browser mocks cover lifecycle and review presentation,
not live database correctness.

The live runs use MongoDB 8.0 pinned to
mongo@sha256:4968f22d0c6c10ef29952f3e807f62872ba22b3312f25803564fbfc08255efc2
and Java driver 5.12.0. Test JVMs use a 256 MiB heap and 64 MiB direct-memory
limit; owned containers use the harness's 1.5 GiB limit. These are bounded
functional checks, not a new three-run performance or total-memory certification.

Evidence is retained beneath
target/mcp-efficiency-coverage/mongo-document-editor-f9438f99ddfc4f6c94b0aade0885c8d1:
focused-rerun.log, mongo-replica-final.log, mongo-sharded.log, live-reports,
reactor.log, browser-all.log, browser-native-packaged-verified.log,
package-final-ui.log and installer logs. The final narrow screenshot is
source/code-graph-dba/target/mongo-document-editor.png.

Failed attempts remain recorded: a focused assertion initially expected the
wrong notice wording; a layout assertion measured client height instead of the
specified border-box height. Both were corrected and rerun. An earlier browser
wrapper reported unsuccessful shutdown despite passing assertions; the final
explicit check reports Node exit 0 and its owned fixture stopped.

Owned Docker containers/volumes and newly introduced unused images were removed.
The final image inventory matches the pre-test inventory; existing databases,
containers and images were preserved. No broad prune or production-data mutation
was used. Real macOS/Linux desktop behavior and unavailable client environments
remain unverified; skipped gates are not passes.

Before this continuation, the validated Redis stream composer was committed and
pushed as c7fa379 and restarted with MCP 3000, admin/DBA UI 8137, desktop approvals,
hybrid storage, a 1536 MiB graph/cache budget and no automatically onboarded
project. Both UI pages and MCP initialization returned HTTP 200. This MongoDB
increment was implemented and tested locally at that original checkpoint.
Subsequent delivery committed/pushed the editor as cd60bbe, guarded deletion as
bb55e51 and guarded creation as 3f3810b, restarting and checking the UI/MCP after
each delivery. The later checkpoints below supersede the original deployment state.

The connected client exposed no code-graph navigation tools despite the healthy
server. Focused filesystem reads were necessary; no MCP navigation savings are
claimed. The maintained optional skill reference was updated using skill-creator
guidance; global/customized client skills and MCP configuration were not changed.

Reproduction from an isolated checkout:

~~~powershell
mvn -B -ntp -pl code-graph-mcp-http -am test '-Djava.awt.headless=true' '-Dtest=NativeMongoDocumentTest,NativeMongoTransactionTest,NativeFoundationTest,NativeReviewTest,NativeMcpHttpTest,DbaToolSchemaTest,ApprovalReviewServerTest' '-Dsurefire.failIfNoSpecifiedTests=false'
./code-graph-dba/test-mongo-topologies.ps1 -Topology replica_set
./code-graph-dba/test-mongo-topologies.ps1 -Topology sharded
./code-graph-dba/test-browser.ps1 -NodeModules <path-to-playwright-node_modules>
mvn -B -ntp package '-Djava.awt.headless=true'
~~~

The broad native roadmap remains incomplete: sharded document identity/routing,
bulk document editing, aggregation builders, subscriptions, broader
administration and the remaining vendor/platform gates are not completed here.

## Guarded deletion checkpoint

The 2026-09-20 continuation adds staged single-document deletion to this editor
and the existing guarded native transaction contract. It retains the replica-set
boundary, exact target and UUID checks, immutable typed ID, complete original
comparison, one-time approval, resource accounting and non-retrying commit.
No new tool or endpoint is introduced; capabilities advertise guardedDocumentDeletion.

- Focused Java/HTTP/MCP checks: 47 passed, four optional live skips, no failures.
- Owned MongoDB 8.0 replica-set gate: 18 passed, no skips or failures, using the
  pinned image and client above. Includes changed/missing originals, concurrent
  writes, collection replacement, rollback/cancellation, exact human/YOLO agent
  approval and loss of a real commit response without replay. Unsupported
  standalone/SRV/sharded guards remain rejected by unit tests.
- Full Maven reactor: 35 modules, 183 suites, 908 tests; 844 passed, 64 explicit
  optional/platform skips, zero failures/errors. Package succeeded in 4:23.
- All 20 browser suite groups passed sequentially after packaging. The focused
  native suite also passed; deletion staging, Revert, review Cancel, remount,
  read-only state, conflict and uncertainty handling are covered. Narrow-screen
  screenshot inspection confirms wrapped controls do not overlap the editor.
- Eleven grid Node tests, skill validation, Windows bootstrap and 14 Node
  installer/skill tests passed; two real-client checks remained opt-in skips.

Raw local evidence: target/mongo-document-delete-80153cf5108648b0ae5f07af59192223,
including focused.log, mongo-replica.log, live-reports/replica, reactor.log,
browser-native.log, browser-all-final.log and installer logs. An earlier browser
run lost fixture classes during a concurrent Maven compile; it is retained in
browser-all.log and superseded by the complete sequential run. Do not rebuild
the same class directory while browser fixtures are using it.

The owned fixture/container and newly pulled Mongo image were removed. The
55-image Docker baseline is unchanged. Existing databases/images were preserved;
no broad pruning was used. Other OS desktops and a new performance benchmark
were not certified by this functional increment. The shared skill reference was
updated without modifying installed clients. Commit/push/restart is performed
only under the user's explicit per-task delivery instruction.

## Guarded creation checkpoint

The 2026-09-20 follow-up adds memory-only New document drafts and a server-enforced
typed-ID absence guard. No insert occurs during metadata loading, drafting or
review cancellation. Saves target the existing collection UUID and never use
upsert, replacement or implicit collection creation. The existing one-time
approval, target/revision, audit, transaction and resource boundaries apply.

- Focused Java/HTTP/MCP checks: 48 passed, five optional live skips, zero failures.
- Owned MongoDB 8.0 replica-set gate: 20 passed, no skips/failures. Creation checks
  cover duplicate IDs, a competing insert after the absence read, cancellation,
  revoked authority, collection removal/replacement and a lost real commit reply
  without replay. Normal agent approval and YOLO both retain the same guard.
- Full Maven reactor: 35 modules, 183 suites, 910 tests; 845 passed, 65 explicit
  optional/platform skips, zero failures/errors. Package succeeded in 4:01.
- Focused native browser checks cover New/review/Cancel/Save/Revert, typed IDs,
  immutable loaded IDs, draft remount, read-only targets, conflicts, reconciliation
  and narrow layouts. All 20 browser suite groups passed sequentially after the
  completed Maven build; no compiler replaced their classes during the run.
- Skill validation passed. Node installer/skill regressions: 14 passed, two
  optional real-client checks skipped. Eleven grid unit tests and Windows bootstrap
  checks also passed. No installed client settings were changed.

Raw local evidence: target/mongo-document-create-43a87e6fb0894495a9822f0942e7c969,
including focused.log, mongo-replica.log, live-reports/replica, reactor.log,
browser-native.log, browser-all-final.log and installer-node.log. Production/test
source hashes were checked against this isolated tested snapshot before delivery.
The pinned image/client and capped live-test resource settings above are unchanged.

The owned container/volumes and newly introduced Mongo image were removed;
existing Docker resources were preserved. Standalone/SRV/sharded guards remain
rejected, not advertised as verified creation support. No additional platform or
performance certification is implied. The broader roadmap remains incomplete.
