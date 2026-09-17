# MCP Server

code-graph serves its code-intelligence tools over the Model Context Protocol so any MCP-capable
agent (Claude Code, IDE assistants) can query blast radii instead of reading files. The stdio
transport and loopback-only streamable HTTP are both local modes; HTTP ships in
`code-graph-mcp-http` and binds only to `127.0.0.1`. Local DBA clients need no token or agent
registration. Token-free clients share the built-in local identity, but database reads and
changes still require reviewed permissions/approvals. Optional named tokens remain available
for separate identities. See [DBA access](../dba.md).

## Registering with Claude Code

```
claude mcp add code-graph -- java --enable-native-access=ALL-UNNAMED \
    -cp <code-graph-mcp jar + dependencies> io.doindev.codegraph.mcp.Main --root C:/repos/acme
```

When roots are supplied, startup indexes them and starts their file watchers. Without roots
or `CODE_GRAPH_ROOT`, startup leaves the workspace empty; onboard with `add_project` below.
Progress goes to stderr; stdout belongs to the protocol.

## Tool reference

### Agent skill for Copilot, Claude Code, and Codex

The portable [code-graph skill](../../skills/code-graph/SKILL.md) teaches agents
to prefer the connected MCP server for symbol navigation, callers/dependencies,
and change impact, while retaining file reads and text search for exact contents
and unindexed files. It uses only standard `name`/`description` frontmatter and
does not configure or start an MCP server.

Copy the `skills/code-graph` folder into the target repository's skill directory:

- GitHub Copilot: `.github/skills/code-graph/SKILL.md`.
- Claude Code: `.claude/skills/code-graph/SKILL.md`.
- Codex: `.agents/skills/code-graph/SKILL.md`.

The distributed file is not automatically installed by cloning this repository.
Connect the MCP server separately using the agent's normal configuration. Skill
selection remains host-controlled; the file guides applicable tasks, not every
shell operation. See the official [Copilot skill guide](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/add-skills),
[Claude Code skill guide](https://code.claude.com/docs/en/skills), and
[Codex skill guide](https://learn.chatgpt.com/docs/build-skills).

### Available tools

| Tool | Purpose |
|---|---|
| `search_symbols` | Find symbols by name — call this FIRST to obtain valid symbol IDs |
| `get_symbol` | Signature, location, dependency counts for one ID |
| `get_impact_radius` | Direct + transitive dependents/dependencies, compressed to counts and spread |
| `get_call_graph` | Callers (up) / callees (down) as compact index-pair adjacency |
| `get_blast_score` | Auditable 0–100 change-risk score, factor by factor |
| `find_dead_code` | Unreferenced symbols with confidence tiers and caveats |
| `find_code_smells` | Structural code smells with measured evidence |
| `compare_architectural_drift` | Blueprint violations and module cycles |
| `index_status` | Freshness: generation, counts, pending changes |
| `reindex` | Async re-index; never touches source |
| `list_projects` | Available project names and the default project |
| `add_project` | Index and watch a server-side directory; returns when ready |
| `remove_project` | Drop a project's graph and stop its watcher; source is untouched |

### Onboarding projects through MCP

Both HTTP and stdio support onboarding without the UI or UI admin mode:

```text
→ add_project {"path": "C:/repos/acme"}
← {"project": "acme", "state": "ready"}
```

`path` must identify an existing directory on the **server machine**. Prefer absolute paths;
relative paths resolve against the server's working directory. The call waits for initial
indexing, so increase the client's request timeout for large projects. The returned project
name is deduplicated by name and can be passed to the analysis tools' `project` argument.
Omitting that argument uses the first onboarded project. Runtime additions are session-local.

Duplicate roots and children of already onboarded or in-flight roots are rejected before
scanning. Real-path checks include symlink resolution and Windows case handling; a textual
prefix alone is not a conflict (`acme-other` is a valid sibling of `acme`). The check is
directional: adding a parent after its child is allowed. Removing a project releases its root
restriction. The same safety rule applies to UI onboarding and startup roots.

### Idle project expiry

Both entry points accept `--project-ttl 1h` (default); `90s`, `30m`, and `2d` are also valid.
Each resolved project-specific MCP call resets that project's idle timer, including `index_status`.
When `project` is omitted, only the resolved default project is renewed. `list_projects` is
passive: its `lastActivityAt`, `expiresAt`, `remainingSeconds`, `activeOperations`, and `instanceId`
fields let clients observe lifetimes without keeping projects alive. Tool discovery and MCP
connection traffic do not count as project use.

Initial indexing finishes before the timer starts. Explicit reindex jobs and in-flight queries
hold usage leases until completion. A five-second sweep removes idle projects, stops watchers,
and drops graph references. Expired-project queries return an error; call `add_project` to load
the directory again. UI users can change the global timeout for the current server session;
that affects MCP projects too, without refreshing their last-activity timestamps.

### Symbol IDs

`<lang>:<repo-relative-path>#<qualifiedName>/<arity>`, e.g.
`java:src/main/java/com/acme/auth/AuthService.java#com.acme.auth.AuthService.validateToken/1`.
IDs are stable across re-indexing — agents can hold them between calls.

### Worked session

```json
→ search_symbols {"query": "AuthService.validate", "kind": "function"}
← {"symbols": [{"id": "java:...#AuthService.validateToken/1", "kind": "function",
     "sig": "validateToken(String jwt)", "line": 88}], "total": 1, "truncated": false}

→ get_impact_radius {"target": "java:...#AuthService.validateToken/1", "depth": 3}
← {"dependents": {"direct": ["java:...#LoginController.login/1", "..."],
     "byDepth": [35, 172, 213], "total": 420, "modules": ["api","auth","billing"],
     "langs": ["java","ts"], "topByFanIn": ["java:...#ApiGatewayFilter.doFilter/3"]},
   "risk": {"score": 83, "band": "critical", "gate": "fail",
            "note": "MANDATORY RISK REVIEW: blast score 83 >= gating threshold 70. ..."}}
```

The `risk` block is the **threshold gate**: it is attached automatically to
`get_impact_radius`, `get_symbol` and `get_call_graph` whenever the target's blast score
reaches `gating.threshold` — an agent about to edit high-risk code always sees the risk report.

## Token frugality guarantees

- Hard caps: `limits.maxResults` (default 50) per list, `limits.maxResponseBytes`
  (default 32 768) per response; oversized responses become an error asking the agent to narrow
  the query — never a silent dump.
- Every truncated list carries `"truncated": true` plus an `omitted` count.
- **No tool ever returns file contents** — signatures, IDs and counts only.
- Call graphs are node-array + `[callerIdx, calleeIdx]` pairs, so each ID string appears once.

## Security posture

Local-first: the serve path makes zero outbound connections and never writes outside
`.code-graph/`. There is no telemetry.

MCP clients can onboard directories readable by the server's account, independently of
`--viz-admin`. Only expose the HTTP endpoint to trusted clients behind appropriate network
controls. The child-path guard prevents nested onboarding; it is not a filesystem allowlist.
