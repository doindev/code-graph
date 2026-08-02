# Code Smells

`find_code_smells` runs structural detectors over the graph and AST metrics. Every finding is
auditable: `evidence` carries the measured values against their thresholds and `rule` states
exactly what fired. Tune or disable any detector under `code-graph.json → smells.<id>`.

```jsonc
"smells": {
  "god-class":   { "enabled": true, "severity": "error",
                   "thresholds": { "methodCount": 30, "fieldCount": 20, "loc": 600 } },
  "feature-envy": { "enabled": false }
}
```

## Detectors

| id | fires when (defaults) | needs |
|---|---|---|
| `god-class` | type with ≥25 methods AND ≥15 fields, or ≥500 LOC with ≥13 methods | metrics |
| `long-method` | function ≥75 LOC, or cyclomatic ≥15, or nesting ≥5 | metrics |
| `long-parameter-list` | ≥6 parameters | metrics |
| `large-file` | file ≥1000 LOC | metrics |
| `high-fan-out` | function calling ≥25 distinct callees | graph |
| `hub` | fan-in ≥10 AND fan-out ≥10 AND product ≥400 | graph |
| `cyclic-files` | files that depend on each other (Tarjan SCC) | graph |
| `unstable-dependency` | stable module depending on one ≥0.5 more unstable (I = out/(in+out)) | graph |
| `feature-envy` | method with ≥3 confident calls into one foreign type, more than into its own | graph |
| `data-clumps` | the same ≥3-parameter name group in ≥3 functions across ≥2 files | signatures |
| `refused-bequest` | subtype using <20% of a parent with ≥5 methods (overrides + calls) | graph |
| `temporal-coupling` | files co-changing in ≥5 commits at ≥60% confidence with NO structural edge | git history |
| `duplicated-logic` | file pairs sharing ≥8 six-line normalized code windows | file contents |

The last two need the repo working tree (they run when the server/CLI knows the root; the
graph-only detectors always run). `temporal-coupling` mines `git log --name-only` (bounded to
1 000 commits, commits touching >25 files ignored as bulk noise) and reports only pairs the
graph can NOT explain — hidden contracts. `duplicated-logic` catches copy-paste and
lightly-edited clones; renamed-identifier clones are out of scope for v1.

## Honest limits

Feature envy sees CALLS only (field access is not yet modeled); data clumps recover parameter
names from display signatures, so positional/unnamed styles are invisible; refused bequest
counts same-name overrides, so renamed overrides look like refusal. Severities default to
`info` for the approximate detectors precisely because of this — raise them per repo once
you've validated the signal.
