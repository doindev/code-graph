# JDBC catalog and grid query editing

## Table workspace tabs

Double-click a table name immediately under **Tables** (or focus it and press Enter) to open
its Table tab beside Scripts. Single-click only selects the connection. The connection label
is fixed; the tooltip identifies its database/schema/table. Double-clicking an open table
focuses its existing tab rather than opening a duplicate or rerunning its SQL. Chevrons
still only expand/collapse the tree. The 12-tab workspace limit includes both tab types.

The inner tabs are **Properties**, **Data**, **Diagram**. Data opens first and loads a capped
`SELECT *` generated from verified catalog identifiers with driver-aware quoting and
qualification. Properties provides the [staged table designer](dba-table-designer.md);
Diagram remains a placeholder. File Save/Save As and Script
execution controls do not act on Table tabs.

Data uses the same reusable grid, query overlay, cancellation, sorting/filtering, refresh
schedule and column layout behavior as Script results. Switching views preserves this
state; closing the Table tab cancels its query and disposes its schedules/results. The
selected database travels with every query, including refresh/filter operations, without
changing a Script or saved connection. Unsupported database switching fails explicitly.

Session recovery remembers mixed tab order, the active tab, Table identity and the selected
inner view. Restored Data loads only when visible; rows, filters, column layout and schedules
are not recovered after page refresh. Missing connections show a local error, never a
replacement target. The existing Script recovery format remains compatible.

Browser interfaces: `POST /api/dba/metadata/table-query` accepts a connection ID and existing
tree selection (`parent` plus `key`) and returns a bounded job whose result has `sql`,
`database`, `schema`, and `name`. `POST /api/dba/query/execute` accepts an optional textual
`database` for a single validated, bounded Table SELECT; ordinary Script requests omit it.
Both use existing authenticated, CSRF-protected job polling/cancellation/release endpoints.
No MCP tools or grants are changed. A SELECT can invoke database functions: use appropriate
database privileges; this browser feature is not a new agent authorization boundary.

Validation: `TableQueriesTest`, `TableMetadataTest`, the mixed workspace assertions in
`DbaTest`, `browser-table-tabs.cjs` (part of the smoke suite), and the explicitly gated
PostgreSQL/embedded integration tests. PostgreSQL verifies another database, all ten table
categories and cancellation; H2, HSQLDB, SQLite and DuckDB exercise embedded catalog loaders.
Other vendor adapters require their own live environments and must not be treated as verified.

## Connection templates

The picker has 41 named templates, sorted by displayed name (case-insensitive), followed by Custom. Existing profile IDs are unchanged. No driver is bundled into the application or downloaded on startup. Selection checks Maven Central; downloading remains an explicit user action. Exact versions, classifiers, dependency paths, and SHA-256 hashes are recorded in pinned driver bundles.

New specialized templates use editable vendor JDBC URL examples plus the common credential, driver-property discovery, arbitrary-property, TLS/network and pool editors. They do not currently have dedicated multi-field URL builders. Unknown properties are secret by default. JDBC metadata varies by driver; new templates use generic metadata and do not inherit advanced PostgreSQL operations merely because their wire protocol is compatible.

| Database | Maven coordinates | Classifier |
|---|---|---|
| Altibase | `com.altibase:altibase-jdbc` | — |
| Amazon Redshift | `com.amazon.redshift:redshift-jdbc42` | — |
| Apache Calcite | `org.apache.calcite:calcite-core` | — |
| Apache Hive | `org.apache.hive:hive-jdbc` | standalone |
| Azure Cosmos DB for Apache Cassandra | `com.ing.data:cassandra-jdbc-wrapper` | — |
| Azure SQL | `com.microsoft.sqlserver:mssql-jdbc` | — |
| Cassandra | `com.ing.data:cassandra-jdbc-wrapper` | — |
| ClickHouse | `com.clickhouse:clickhouse-jdbc` | all |
| CockroachDB | `org.postgresql:postgresql` | — |
| CSV | `org.apache.calcite:calcite-file` | — |
| Databricks | `com.databricks:databricks-jdbc` | — |
| DuckDB | `org.duckdb:duckdb_jdbc` | — |
| Elasticsearch | `org.elasticsearch.plugin:x-pack-sql-jdbc` | — |
| Exasol | `com.exasol:exasol-jdbc` | — |
| Firebird | `org.firebirdsql.jdbc:jaybird` | — |
| Google BigQuery | `com.google.cloud:google-cloud-bigquery-jdbc` | all |
| Greenplum | `org.postgresql:postgresql` | — |
| H2 | `com.h2database:h2` | — |
| HSQLDB | `org.hsqldb:hsqldb` | — |
| IBM Db2 for i (AS/400) | `net.sf.jt400:jt400` | — |
| IBM Db2 LUW | `com.ibm.db2:jcc` | — |
| IBM Db2 z/OS | `com.ibm.db2:jcc` | — |
| Informix | `com.ibm.informix:jdbc` | — |
| JSON | `org.apache.calcite:calcite-file` | — |
| MariaDB | `org.mariadb.jdbc:mariadb-java-client` | — |
| Microsoft SQL Server | `com.microsoft.sqlserver:mssql-jdbc` | — |
| MongoDB SQL Interface | `org.mongodb:mongodb-jdbc` | — |
| MySQL | `com.mysql:mysql-connector-j` | — |
| Neo4j | `org.neo4j:neo4j-jdbc-full-bundle` | — |
| OpenSearch | `org.opensearch.driver:opensearch-sql-jdbc` | — |
| Oracle | `com.oracle.database.jdbc:ojdbc17` | — |
| PostgreSQL | `org.postgresql:postgresql` | — |
| PrestoDB | `com.facebook.presto:presto-jdbc` | — |
| Redis (Calcite adapter) | `org.apache.calcite:calcite-redis` | — |
| SAP HANA | `com.sap.cloud.db.jdbc:ngdbc` | — |
| Snowflake | `net.snowflake:snowflake-jdbc` | — |
| SQLite | `org.xerial:sqlite-jdbc` | — |
| StarRocks | `com.starrocks:starrocks-connector-j` | — |
| Teradata | `com.teradata.jdbc:terajdbc4` | — |
| Trino | `io.trino:trino-jdbc` | — |
| YugabyteDB | `com.yugabyte:jdbc-yugabytedb` | — |

Cassandra and Cosmos Cassandra use the community ING driver (CQL, not general relational SQL). Cosmos support is specifically for its Apache Cassandra API and remains live-unverified. MongoDB requires its SQL Interface endpoint. Neo4j defaults to Cypher, with optional SQL translation. CSV/JSON use Calcite's file adapter; Redis and general Calcite require trusted model configuration. Model files may load executable classes and remain user-owned. Elasticsearch requires compatible server/driver versions and applicable SQL JDBC entitlement. Db2 z/OS may require additional licensed JARs. Driver availability does not waive vendor licences or cloud service charges.

Athena, Apache Derby (retired), Couchbase, CouchDB, Sybase/jConnect, XLSX and XML remain Custom-only; no maintained, suitable free Maven client was verified for these entries. Native SDKs and server connector artifacts are not treated as JDBC clients.

Provider references: [Google BigQuery](https://docs.cloud.google.com/bigquery/docs/jdbc-for-bigquery), [StarRocks](https://docs.starrocks.io/docs/integrations/JDBC_driver/), [OpenSearch](https://github.com/opensearch-project/sql-jdbc), [Cassandra wrapper](https://github.com/ing-bank/cassandra-jdbc-wrapper), [Calcite file adapter](https://calcite.apache.org/docs/file_adapter.html), [MongoDB SQL Interface](https://www.mongodb.com/docs/sql-interface/install-driver/).

## Grid sorting and filtering

Menu-generated predicates use a bare column name for one unaliased table, the declared alias
when present, or an unambiguous table qualifier for joins. Same-named joined tables in different
schemas retain schema qualification. Table count matters even within one schema. Quoted
schema/table identifiers are preserved; aliases containing dots currently require manual SQL
editing because the parser cannot safely round-trip those qualifiers.

Column actions rewrite one originating SELECT with a structural SQL parser and rerun it on the result's original connection. They never rerun surrounding script DDL/DML or change Script editor text. Existing sort priorities are retained when direction changes; newly selected columns are appended. Filter actions replace only the matching comparison operator, preserving other range bounds. Values from cells/custom-value dialogs use prepared bindings (including numeric values without lossy default DECIMAL casts).

Duplicate output labels retain stable ordinal IDs. Where resolvable, their headers show table or derived-table aliases, and edits target that source expression. Explicit aliased joins, subqueries, read-only CTEs, and one resolvable wildcard are supported. Incomplete JDBC origin metadata, self-join/multiple-wildcard ambiguity, aggregates/expression projections, set queries, matching predicates inside OR, duplicate matching predicates, locking SELECTs and data-modifying CTEs require manual SQL editing. No guessed origin is used. Parser normalization can change formatting/comments; the Script text is untouched. SELECT functions can have database side effects: this is not a sandbox for human SQL.

The filter textarea shows **additional** grid predicates, initially combined with AND. Original predicates stay outside the textarea; when their matching operator is changed via the menu, the updated predicate remains in the base query. The play button parses the entire editable expression, preserving OR and parentheses, and combines it with the base predicates as `(base) AND (expression)`. An empty expression removes the additional filters only. Removing all filters explicitly removes the entire WHERE; removing orderings does not change visual column order. Free-form expressions use SQL literals, not unbound question marks. A valid new SQL preview is shown before query submission; a failed edit/query retains the prior data and restores the prior SQL, with an error warning.

Browser-only `POST /api/dba/query/grid-edit` validates/rebuilds SQL without executing it; the normal bounded asynchronous `/query/execute` path performs the rerun. It uses the existing session, loopback/Host/Origin and CSRF protection. Inputs include SQL/parameters, action, stable source ordinal/column metadata and optional base SQL/parameters or expression. Outputs include SQL/parameters, base SQL/parameters and filterExpression. No MCP grants or agent SQL restrictions are broadened. Filtering, query reruns and sort results remain subject to existing row/byte/time/concurrency limits. Find/Replace and the advanced Columns/Custom dialog's filter builder are still separate unfinished features.

## Custom value dialog and typography

Custom comparison actions (=, <>, >, <) open a multiline value dialog with the column/operator immediately above the textarea. Ok closes the dialog and starts the grid query, making its progress/Cancel overlay accessible. X and Cancel close without applying; Escape, backdrop clicks and Enter neither apply nor dismiss. Submitted values remain as per-column/operator drafts while the grid exists, including after validation errors; reopening the dialog restores them.

Integer/decimal, Boolean and ISO date/time values are validated from JDBC type metadata; invalid values and unsupported types are rejected. Text remains text, including whitespace and quotes. Both filter and SQL previews render the converted values, with quoted strings and explicit temporal casts where appropriate. The backend still executes parameterized SQL and bindings, not the interpolated display preview. The grid-edit response includes a separate `displaySql` for this purpose. Vendor-specific types may still require manual SQL expressions.

DBA inputs, textareas, Data Grid headers/cells, tree node labels (including connection addresses) and the custom predicate line share the Script editor's 14 px font size (`--dba-input-font-size`). Filter-expression and custom-value textareas disable browser spell checking.

## Result footer toolbar

Each executable result owns a `GridQueryController`; the reusable `DataGridView` observes it without depending on Script controls. All grid queries show a nearly transparent local overlay with elapsed seconds (including preparation and queue time), an animated striped bar and Cancel. Reduced-motion preferences disable the animation. Covered controls are inert, but other tabs and the Script editor remain usable. Different grids may execute concurrently within the existing server limits; one grid never overlaps its own queries.

Cancel requests cancellation and waits for terminal status before removing the overlay. It retains the refresh interval, which resumes after the usual completion-to-next-refresh delay. If cancellation remains unconfirmed for 30 seconds, the controller reports an unknown outcome and blocks replacement execution; it does not claim that the database query stopped. Failures stop scheduling and appear locally without a success toast. Script Run/Explain and connection changes dispose owned grid schedules and wait for queries to settle before replacement; an unknown completion prevents the replacement operation. Closing a view for tab switching does not dispose its controller; closing its result does.

Changing grid filters or SQL sort order clears the automatic refresh schedule, including filter-expression typing before Play. Play always clears the schedule, even when its expression is unchanged; in that case it reruns the current query without rewriting the filters. Column resizing and visual column reordering do not clear the schedule.

Every enabled bottom-toolbar button clears the existing schedule before its action, including opening the Refresh menu and the Edit/Add/Delete placeholders. Selecting a timed option then installs a new schedule. The overlay's Cancel button is separate and retains the selected interval.

Initial column widths measure headers and up to 200 returned rows, with a minimum of 96 px and an approximate 36-character maximum. Cells hide overflow with an ellipsis. User column resizing (up to the existing 800 px limit) and column order survive filtering, sorting, refreshing and view remounts without rewriting the underlying rows. Layout is discarded with the result, not recalculated on every query.

The database-tree sidebar width is saved in browser `sessionStorage` after mouse or keyboard resizing and restored on reload in the same browser tab. It is bounded to 180–500 px, independent of backend workspace persistence. Unavailable browser storage leaves resizing functional without persistence.

Successful grid refreshes and filter/order reruns do not show a success toast. Refresh and Play with an unchanged filter rerun the current displayed query (using its underlying prepared SQL and bindings), without rebuilding its WHERE clause. Changed filter expressions are still applied before execution. Failures remain visible and preserve previous results.

Each Data Grid has a full-width footer outside the horizontally/vertically scrollable data. The Refresh split button offers manual refresh or 1, 5, 10, 15, 30 and 60 second intervals. Selecting an option replaces its previous schedule and refreshes immediately; subsequent refreshes wait for the previous request to finish before starting the selected delay. Manual Refresh disables automatic scheduling. Refresh reruns only the originating SELECT with its applied filters/parameters, on the original connection; unapplied filter text is preserved. Metadata grids cannot be refreshed this way.

Schedules belong to individual results. A confirmed Result/Script close clears all owned refresh timers and queued refreshes **before** removing tabs or awaiting job cancellation, including hidden Results within a Script. Declining an unsaved-close confirmation leaves schedules untouched. Late refresh responses cannot restart a disposed schedule. Result replacement, session termination and page unload also clear schedules. Automatic refresh pauses while the page is hidden, a modal is open, an input is focused or its Script is busy. Errors stop the schedule and preserve the last successful results. Existing execution/resource limits still apply; SELECT functions may have database side effects.

The button label and default selection always remain **Refresh**. Menu wording is `Refresh`, `Refresh every 1 second`, `... 5 seconds`, `... 10 seconds`, `... 15 seconds`, `... 30 seconds`, `... 60 seconds`. While automatic refresh is enabled, its interval number overlays the top-left corner inside the Refresh button, pulsing gently (static with reduced-motion preferences). The badge does not change button width or intercept clicks. Clicking the main Refresh button also clears the schedule and performs one refresh. No scheduling status text is displayed beside the button.

After Refresh, vertical ellipsis separators group disabled Save/Cancel buttons and icon-only Edit/Add row/Delete placeholders. Add row's icon is green; Delete's icon is red with the tooltip `Delete`. All footer controls use bundled Lucide icons and tooltips. Placeholder actions do not edit data or execute SQL.

## Reproducible validation

```powershell
mvn -o -pl code-graph-dba -am test
mvn -pl code-graph-dba -am test '-Dtest=DriverInstallationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Ddba.driver.integration=true'
# Optional provider subset: '-Ddba.driver.ids=clickhouse,starrocks,presto'
powershell -NoProfile -File code-graph-dba/test-browser.ps1 -NodeModules '<Playwright node_modules directory>'
```

On Windows/OpenJDK 25, the full driver probe run passed on 2026-09-09 after correcting ClickHouse's supporting dependencies. Probes deduplicate shared provider recipes, load the declared class, check URL acceptance, inspect properties and verify checksum/cache reuse; they **do not connect** to cloud/server databases. Each probe runs in a child JVM with a 60-second deadline so driver-global native/file resources are released before the disposable test directory is deleted. Tests enforce 64-artifact/512-MiB bundle limits. No Docker resources were created for this change. Live grid execution tests use isolated H2; live verification of every new database, particularly paid services and Cosmos, remains incomplete.
