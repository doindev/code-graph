# CI Runner

`code-graph-cli` is the headless counterpart to the MCP server: a single shaded jar
(`code-graph.jar`, main class `io.doindev.codegraph.cli.Main`) that turns a pull request into
a risk report and a gating exit code. Build it with `mvn -pl code-graph-cli package` — the jar
lands at `code-graph-cli/target/code-graph.jar` and bundles all ten language analyzers.

```
code-graph index [--root DIR] [--snapshot-out FILE]
code-graph ci    --base <ref> [--head <ref>] [--root DIR] [--report FILE] [--format md|json]
                 [--github-comment] [--gitlab-comment] [--pr N]
                 [--fail-on blast,drift] [--threshold N]
code-graph check --target <symbolId|path> [--root DIR]
```

Flags accept `--flag value` and `--flag=value`; run with `--enable-native-access=ALL-UNNAMED`
to silence the tree-sitter native-load warning on current JDKs.

- **`index`** — full index of `--root` (default `.`), prints file/symbol/edge counts, and can
  persist the graph as a single snapshot file (`--snapshot-out graph.snap`) readable by the
  same binary codec the stores use.
- **`check`** — indexes, then prints the full blast-score factor table (the same auditable
  table `get_blast_score` returns), top dependents, and any architecture violations whose
  witnesses touch the target. The target is a canonical node id (`java:src/A.java#A.m/1`,
  `file:src/A.java`) or a plain repo-relative path.
- **`ci`** — the PR pipeline below.

## The `ci` pipeline

1. **Changed files** — `git diff --name-only --find-renames --diff-filter=ACMR <base>...<head>`
   (`head` defaults to `HEAD`): exactly what the PR adds, copies, modifies or renames.
   Deleted files carry no forward risk and are excluded.
2. **Baseline** — the merge-base sha (`git merge-base base head`) keys a snapshot cache at
   `<root>/.code-graph/snapshots/<sha>.snap`. On a miss, the merge-base is checked out into a
   detached temporary `git worktree`, indexed, snapshotted, and the worktree is removed —
   with one retry on Windows, where antivirus/indexer file locks occasionally hold the first
   removal (a failure degrades to a warning plus a `git worktree prune` hint, never a failed run).
3. **Head measurements** — the working tree is indexed, then:
   - a blast score per changed file (a file scores as the max over its contained symbols;
     files that vanished or aren't indexable are listed as skipped),
   - drift = `Drift.diff(head evaluation, baseline fingerprints)` — only violations whose
     stable fingerprint is **new** vs the merge-base can fail the gate,
   - code smells scoped to the changed files.
4. **Report** — markdown (or `--format json`) written to `--report` (stdout otherwise). The
   markdown leads with the literal marker line `<!-- code-graph-report -->`, then the gate
   verdict, the changed-file table (`Blast · Band · Dependents · Untested`), a **Mandatory
   risk review** section with the full factor table for every file at/above the threshold,
   new architecture violations (fingerprint + witnesses), smell findings, and a base/head sha
   footnote.
5. **Posting** — `--github-comment` / `--gitlab-comment` upsert the markdown as a PR/MR
   comment: the poster finds its previous comment by the marker line and edits it in place,
   so a PR keeps one living report instead of a trail of stale ones.
6. **Gate** — the exit code, computed only for categories in `--fail-on` (default:
   `gating.failCiOn` from `code-graph.json`, itself defaulting to `blast,drift`).
   `--threshold` overrides `gating.threshold` (default 70) for this run.

### Exit codes

| code | meaning |
|---|---|
| 0 | pass |
| 1 | execution error (bad ref, index failure, posting failure, …) |
| 2 | blast gate failed — a changed file scored at/above the threshold |
| 3 | drift gate failed — new architecture violations vs the merge-base |
| 4 | both gates failed |
| 64 | usage error (unknown flag, missing required flag) |

### Comment posting environment

| flag | needs |
|---|---|
| `--github-comment` | `GITHUB_TOKEN`, `GITHUB_REPOSITORY`; PR number from `--pr` or `GITHUB_REF` (`refs/pull/<n>/merge`). API base honors `GITHUB_API_URL` (GHES). |
| `--gitlab-comment` | `GITLAB_TOKEN` (sent as `PRIVATE-TOKEN`) **or** the job's `CI_JOB_TOKEN`; `CI_PROJECT_ID`; MR iid from `--pr` or `CI_MERGE_REQUEST_IID`. API base honors `CI_API_V4_URL`. |

## GitHub Actions

```yaml
name: code-graph
on:
  pull_request:

permissions:
  contents: read
  pull-requests: write   # for the report comment

jobs:
  risk-gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0            # ci needs the merge-base commit, not a shallow tip

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25

      # baseline snapshots are keyed by merge-base sha — safe to share across runs
      - uses: actions/cache@v4
        with:
          path: .code-graph/snapshots
          key: code-graph-snapshots-${{ github.event.pull_request.base.sha }}
          restore-keys: code-graph-snapshots-

      - name: code-graph ci
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          java --enable-native-access=ALL-UNNAMED -jar code-graph.jar ci \
            --base origin/${{ github.base_ref }} \
            --report code-graph-report.md \
            --github-comment

      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: code-graph-report
          path: code-graph-report.md
```

Fetch the jar however your org distributes binaries (release asset, artifact repository, or a
prior `mvn -pl code-graph-cli package` step in the same workflow).

## GitLab CI

```yaml
code-graph:
  stage: test
  image: eclipse-temurin:25
  variables:
    GIT_DEPTH: "0"                  # full history: ci resolves the merge-base
  cache:
    key: code-graph-snapshots
    paths:
      - .code-graph/snapshots
  script:
    - java --enable-native-access=ALL-UNNAMED -jar code-graph.jar ci
        --base "origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME"
        --report code-graph-report.md
        --gitlab-comment
  artifacts:
    when: always
    paths:
      - code-graph-report.md
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
```

`CI_JOB_TOKEN` is used automatically for the MR note; export `GITLAB_TOKEN` instead if job
tokens can't comment in your instance. Note that MR pipelines run on a detached merge-ref
head — exactly what you want gated.

## Notes

- Add `.code-graph/` to `.gitignore` (or cache it, as above): it holds baseline snapshots,
  one per merge-base sha, in the compact binary codec — cache hits skip the whole worktree
  index step.
- On self-hosted **Windows** runners the baseline worktree removal is retried once; if a
  scanner still holds a lock the run continues with a warning — delete the leftover
  directory and run `git worktree prune` at your convenience.
- `--fail-on drift` turns the run into a pure architecture gate; `--fail-on blast` ignores
  drift. An empty config gates on both by default. The report always shows everything —
  `--fail-on` only controls what can break the build.
