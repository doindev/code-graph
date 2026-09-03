# Superseded exploratory matrix

This directory preserves the initial interrupted benchmark attempt; it is not the final comparison matrix.

Three memory trials and the first MVStore trial completed. The first MVStore trial spent 148.5 seconds loading and showed substantial GC and file reads during one-transaction project removal. A read-only thread inspection observed `MvKv.removePrefix` / `MVMap.ceilingKey` in the main thread. The second MVStore trial and its owning runner were explicitly stopped so removal could be bounded and retained-heap diagnostics added. Its missing final result is an **aborted exploratory sample**, not a successful or omitted trial.

The revised prototype commits bounded deletion batches after unpublishing the project, records post-GC live class histograms outside timed query phases, and checkpoints results before removal. Use `results/comparison` for the final repeated comparison. Earlier JSON/logs remain here for audit; no performance averages should mix these versions. The exact disposable store left by the aborted second trial was removed after verifying its owning process had exited; source files and pre-existing caches were preserved.
