# Blast Score

The blast score answers "how risky is changing this?" with a number an enterprise can audit.
It is deliberately **not** PageRank: every factor is a count a reviewer can reproduce with two
graph queries, it is stable under incremental re-indexing, and the weights are configuration,
not magic.

## Formula

```
score = round(100 × Σ wᵢ·fᵢ),  Σ wᵢ = 1

reach    = min(1, log10(1+D) / log10(1+reachRef))   D = transitive dependents      w=0.40
fanin    = min(1, log10(1+F) / log10(1+faninRef))   F = direct inbound calls/refs  w=0.25
spread   = min(1, M / 5)                            M = modules containing deps    w=0.20
lang     = min(1, (L−1) / 2)                        L = dependent languages        w=0.05
untested = 1 if dependents exist and none is a test file                           w=0.10
```

- `D` counts the reverse transitive closure over CALLS + REFERENCES + IMPORTS + EXTENDS +
  IMPLEMENTS, depth ≤ 10, capped at 5 000 (cap reached ⇒ `reachTruncated: true`).
- Defaults saturate reach at 1 000 dependents and fanin at 100 direct callers
  (`scoring.reachRef` / `scoring.faninRef`).
- A symbol with **zero** dependents scores 0 — missing tests add risk only when something can break.
- A file or type scores as the **max** over its contained symbols: a file is as risky as its
  riskiest symbol; summing would punish big files.

Bands: 0–24 **low** · 25–49 **moderate** · 50–74 **high** · 75–100 **critical**.

## Worked example (pinned by a unit test)

`AuthService.validateToken/1`: D=420, F=35, M=4, L=2, no test dependents.

| factor | raw | normalized | weight | points |
|---|---|---|---|---|
| reach | 420 | 0.875 | 0.40 | 35.0 |
| fanin | 35 | 0.778 | 0.25 | 19.5 |
| spread | 4 | 0.800 | 0.20 | 16.0 |
| lang | 2 | 0.500 | 0.05 | 2.5 |
| untested | 1 | 1.000 | 0.10 | 10.0 |
| **score** | | | | **83 — critical** |

Every `get_blast_score` response carries exactly this factor table plus the top dependents and
a one-line explanation, so the number is never a black box.

## Threshold gating

`gating.threshold` (default 70):

- **MCP mode** — `get_impact_radius`, `get_symbol` and `get_call_graph` (the tools agents call
  right before editing) automatically attach a mandatory `risk` block when the target scores at
  or above the threshold. This is applied by one decorator, so it cannot be forgotten per-tool.
- **CI mode** — any changed file at/above the threshold makes `code-graph ci` exit with code 2
  and puts a "MANDATORY RISK REVIEW" section at the top of the PR report.

## Tuning

Weights and reference constants live in `code-graph.json → scoring` and are echoed in every
response, so tuning is auditable: if two teams see different scores, the response shows why.
Raise `reachRef` on monorepos where everything transitively reaches everything; raise the
threshold (or drop `attachRiskReport`) if gating is too chatty while you calibrate.
