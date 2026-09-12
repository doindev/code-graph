# Visual Query Builder

The browser builder and editable object Diagram tabs share a version 2 structured query model. Tables remain read-only relationship diagrams with a **New query from source** action. Script and Table Data retain their existing grids.

## Canvas and output

Add tables/views from the tree or the searchable, paged schema/object picker. Every source column starts selected. Sources are disconnected until the user connects column handles or explicitly chooses Cross join. The compiler rejects disconnected components. There is no 16-source cap; SQL, output-column and model complexity limits still apply.

Connections belong to an ordered binary join tree. A connection between components creates a join; another column pair between those operands adds an AND predicate to their shared join node. The anchored editor identifies that node, its operands and the rows preserved by an outer join. It supports INNER, LEFT/RIGHT/FULL OUTER, six comparisons, swapping operands, predicate removal and disconnection. Handles and line labels are keyboard operable. Source headings support dragging and arrow-key movement. Connections follow cards, column scrolling and zoom.

The movable, collapsible Query Output card controls selections, aliases, calculated expressions, output order and DISTINCT. Detail and Summary preserve separate output/order lists. Summary begins with COUNT(*), and offers Group by and Aggregates drop targets. Multiple aggregates can reference the same column. Source checkboxes and direct column outputs stay synchronized.

Expression dialogs offer columns, output references, typed values, parameters, arithmetic, nested Boolean conditions, comparisons, CASE and functions. No raw SQL expression editor is provided. Common functions include text/number/null functions and COUNT/SUM/AVG/MIN/MAX with DISTINCT. Database function discovery is paged and bounded. PostgreSQL catalogs provide overloads, ordinary aggregate identification, defaults and variadic argument types; other drivers use JDBC metadata. Only identified scalar functions and ordinary aggregates with supported signatures can be selected. Argument casts preserve the selected overload's declared types. Missing/unsupported signatures remain visible with a reason.

## Grid and operations

The lower pane always contains two permanent tabs, **Data Grid** and **Explain Plan**, without close controls. Data Grid is initially active. Run/Ctrl+Enter immediately selects Data Grid; Explain immediately selects Explain Plan, including during parameter collection. Switching tabs performs no work and retains DOM/grid state. Completion does not change the selected tab.

Canvas edits compile the preview without executing a data query. The always-visible `input.grid-source-preview` and its complete SQL viewer reflect the active query, including WHERE, GROUP BY, HAVING and ORDER BY. Rows and Groups filter dialogs support nested expressions; Groups produces HAVING. Grid aggregate-output filters use Groups. Ordered sorting rules and filters reference stable IDs. Missing dependencies remain visible for repair/removal. Both modes share Rows filters.

Run, Refresh and explicit grid filter/sort actions execute the displayed query. A builder allows one execution operation at a time, with preparation/progress/error/cancel status in the appropriate panel. Named typed parameter values are requested for Run and Explain. Refresh reuses compatible values. Parameter names compile to ordered JDBC bindings, including repeated occurrences.

Data and plans are retained independently after changes and failures. Stale data exposes its executed SQL separately from the current preview. Plans become outdated after query or parameter changes and never rerun automatically. Revision checks prevent late responses replacing newer state. Closing a builder cancels/releases its jobs and releases both retained result allowances.

## Explain

Browser Explain uses the same configured connection and selected database as Run. It is estimated planning only: adapters never substitute ordinary execution, ANALYZE TRUE, or PROFILE. The result includes actual engine metadata, capability status, source SQL, format, original bounded output, optional structured nodes, observations and truncation. JSON/XML plans become expandable property trees; tabular plans retain columns; text and uninterpretable structures retain usable raw output. XML disables external entities and DTD loading. Costs retain engine units and are not elapsed timings.

See [the complete Explain matrix](dba-explain-support.md) for every built-in template, prerequisites, unavailable native facilities and live-versus-contract verification. The browser extension does not change MCP/agent engine restrictions.

## Import, persistence and bounds

SQL import enters visual mode only when the entire query is representable. Nested join groups exported by the canvas round-trip with their operand grouping intact. CTEs, set operations, derived tables, unsupported clauses/functions and other lossy imports preserve exact SQL for inspection and Open in Script. SQL-only older workspaces remain accepted. Import/recovery never executes anything.

Workspace recovery saves the optional versioned draft, disconnected cards, both modes, conditions, ordering, parameter definitions, layout, zoom and selected output tab. It excludes runtime parameter values, rows, plans, catalog caches, schedules and undo history. SQL export includes the complete active query and placeholders; incomplete drafts cannot be exported as executable SQL.

Existing 12-tab, job concurrency, row/byte/deadline and browser result allowances apply. Generated/imported SQL is limited to 16 KiB, outputs to 256 per mode, parameters/occurrences to 128, join/expression depth to 64 and compiler traversal to 8,000 nodes. Drafts are approximately 2 MiB; undo/redo keeps at most 30 snapshots within approximately 1 MiB per stack. Limits produce explicit diagnostics. Retained grid and plan results both count toward the browser allowance.

## Browser APIs

All endpoints retain browser authentication, origin/CSRF, connection ownership and job bounds.

- `POST /api/dba/query-builder/compile`: model plus dialect metadata; pure compilation returns SQL, ordered binding definitions, output descriptors and diagnostics. It does not open a connection or fetch query rows.
- `POST /api/dba/query-builder/functions`: connection/database, schema/search, optional overload key and offset; bounded metadata job.
- `POST /api/dba/query-builder/source`: selected catalog object; safe reference, columns, relationships, quoting, actual engine and Explain capability.
- `POST /api/dba/query-builder/import`: exact SQL and target; complete visual model or preservation notice.
- `POST /api/dba/query/explain`: SQL, ordered parameters and optional database; existing callers remain compatible. Poll/cancel/release use existing job endpoints.

## Validation

`mvn -o -q -pl code-graph-dba test` runs compiler, import, resource, security, job and adapter tests. `test-postgres.ps1` runs disposable PostgreSQL integration including overloaded/optional functions and estimated JSON plans. `test-database-matrix.ps1` exercises selected disposable services. `MetadataEmbeddedTest` with `-Ddba.catalog.integration=true` exercises HSQLDB, SQLite and DuckDB.

Set `DBA_BROWSER_SUITE=query-builder` for the builder UI and lifecycle suites, or `grid` for grid/column-menu/controller/Table Data regressions, and run `test-browser.ps1 -NodeModules <Playwright modules>`. The builder suite covers desktop/narrow layouts, 20 sources, explicit/composite joins, no execution on canvas edits, filtering/sorting, permanent tabs, parameter prompts, stale results, focus, cancellation, recovery and preserved imports. Disposable fixtures are isolated from saved user connections.
