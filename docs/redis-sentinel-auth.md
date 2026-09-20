# Redis Sentinel authentication

This follow-up completes separate Sentinel discovery authentication from the
native-database roadmap. It does not enable new database commands or bypass
approvals. It also corrects a data-node authentication bug: Lettuce 7.7 retains
the supplied password array, so clearing that array immediately after configuration
erased the password before connecting. URIs now receive independent driver-lifetime
copies; temporary arrays are still cleared. Clients remain lazy, bounded,
revision-bound and idle-expiring.

## Connection editor and MCP

Select Redis and **Network → Topology → sentinel**. Supply the primary name and
Sentinel endpoints, not data-node endpoints. In **Authentication & TLS**, supply:

- **Sentinel username / ACL user** for ACL authentication; omit it for password-only
  or unauthenticated Sentinel.
- **Sentinel password** and its **Keep / Replace / Remove** action.

The General tab's username/password always belong to the data nodes. Discovery
never falls back to those credentials, and data nodes never receive the Sentinel
password. All seeds use the explicit Sentinel credentials. No server ACLs or
Sentinel configuration are changed by connection testing or saving a profile.

Existing profiles without Sentinel credentials retain unauthenticated discovery.
Clear the Sentinel username/master settings and remove its saved password before
switching away from Sentinel. Validation refuses incompatible retained settings
rather than silently dropping secrets. Changing either password invalidates the
successful draft-test receipt and the saved profile/client revision.

MCP connection-create/update proposals use existing fields:

```json
{
  "templateId": "redis-native",
  "name": "Development Redis",
  "url": "redis://localhost:26379",
  "username": "data-user",
  "nativeOptions": {
    "topology": "sentinel",
    "database": "0",
    "sentinelMaster": "primary",
    "sentinelUsername": "discovery-user"
  },
  "secretProperties": { "sentinelPassword": "<write-only secret>" }
}
```

Supply the data-node password separately in `password`. A null
`secretProperties.sentinelPassword` removes only the Sentinel password; omission
keeps it. `replaceSecretProperties: true` clears old secret properties before
applying changes. Only this named secret is supported for native Sentinel;
arbitrary native properties still fail validation. The password is bounded to
32,768 characters and shares the existing chunked OS-vault storage and overall
request/configuration allowances. Never put credentials in endpoint URLs.

Profiles, approval summaries, responses and audits contain no secret values.
Agents receive the configured public Sentinel username, not the password. MCP
client transcripts can retain submitted secret arguments; human entry in the
connection editor remains an alternative. Saving/testing/management retain their
existing review rules; this is not a new reusable native permission.

TLS verification remains enabled. Per-URI tests verify TLS and hostname-validation
settings, but this follow-up does not certify custom client certificates, custom
trust stores or production TLS. Password-only and ACL behavior follow the
[Redis Sentinel authentication contract](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/)
and [Lettuce's separate per-Sentinel credentials](https://github.com/redis/lettuce/blob/main/docs/user-guide/connecting-redis.md).

## Validation

Use an isolated source tree to avoid changing classes used by a running server:

```powershell
./scripts/Test-IsolatedReactor.ps1 -Name sentinel-auth -Modules code-graph-mcp `
  -Tests 'NativeSentinelAuthTest,NativeFoundationTest,NativeTopologyTest,AgentRequestsTest,DbaToolSchemaTest'
$build = '<source path printed by the isolated build>'
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl -BuildRoot $build
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth password -BuildRoot $build
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -BuildRoot $build
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster -BuildRoot $build
```

The harness uses the existing pinned Redis 7.4.1 image and Lettuce 7.7.0.RELEASE,
a 256 MiB test heap and 64 MiB direct-buffer cap. Each run owns one labelled
container with isolated Redis primary/replica/Sentinel processes (or three Cluster
primaries), uses loopback ports, and removes its container/volumes and temporary
configuration in `finally`. Newly introduced unused images are removed;
pre-existing images are preserved. Fixture users deliberately have broad rights
for setup and failover assertions; these are not recommended production ACLs.

Validation covers missing/wrong Sentinel credentials, wrong data credentials,
server-version draft tests without profile persistence, reads, bounded scans,
replication and promotion, old-cursor rejection, secret keep/replace/remove,
revision/fingerprint invalidation, write-only approvals/audits, and browser
save/reopen/remove. The first baseline run reproduced four unsupported-option
failures; candidate tests also caught the password-array ownership bug.

The broad roadmap remains incomplete: custom/client-certificate TLS, native
transactions, streams/subscriptions, full binary editing and infrastructure
administration are separate work. Nothing here claims complete Redis support or
cross-platform interactive certification.

## Local acceptance record — 2026-09-20

Environment: Windows x64, JDK 25, Maven 3.9.11, Node 22 and Chromium; Redis
7.4.1 (`sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275`),
Lettuce 7.7.0.RELEASE. The password-only, ACL and unauthenticated Sentinel fixtures
and the Cluster regression passed sequentially, including second-seed fallback
for authenticated discovery. No owned fixture containers remain; the pre-existing
image was preserved, and no new image was retained.

The clean 35-module Maven reactor passed: 843 tests, 797 passed, 46 optional/live/
platform skips, zero failures/errors. Two supplemental real Windows vault tests
passed and removed their newly created credential entries. Six pure JavaScript
grid-state tests, all 19 DBA browser suites and JavaScript/PowerShell syntax checks
passed. Browser coverage includes the masked Sentinel editor and its real profile
save/reopen/remove flow, plus existing approvals, pairing and editable grids. Skipped gates
are not represented as live vendor or macOS/Linux certification.

Raw local evidence is under
`target/mcp-efficiency-coverage/sentinel-auth-candidate-c5ee8ad19edc430abb3c2a5704b7a75e/`:
`focused.log`, `sentinel-{acl,password,none}-final.log`, `cluster-final.log`,
`reactor-final.log`, `windows-vault.log` and `browser-all.log`. The frozen initial
failure is in the sibling `sentinel-auth-147bbd23cf01449b90cd278b411207d8/` directory.
These are ignored validation artifacts, not application data. The candidate's
changed production/test sources were hash-compared with the workspace.

No code-graph MCP tools were exposed to this coding session; implementation used
focused local reads under the optional skill's fallback guidance. The user's
running application was not replaced. No commit or push was performed.
