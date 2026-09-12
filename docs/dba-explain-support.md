# Browser Explain support

The adapter is selected from actual JDBC database product/version metadata, with PostgreSQL-compatible server branding resolved through `version()`. Custom connections use the same selection. Template names do not determine support, and generic JDBC handling no longer disables browser Explain. Microsoft/Azure, Calcite-backed file adapters and compatible protocols share implementations; Db2 LUW, z/OS and IBM i remain distinct.

**Implemented** means a planning protocol exists in this release, not that every version, privilege set or driver has been certified. The UI reports missing privileges, prerequisites, driver facilities and unsupported versions separately. Permission failures are never remembered as permanent lack of support. All collection uses the configured JDBC connection, including its authentication, database, network and TLS settings.

| Built-in template | Estimated-plan protocol/outcome | Verification |
|---|---|---|
| Altibase | JDBC EXPLAIN_PLAN_ONLY, prepare, getExplainPlan; restore OFF | Native API documentation; no live server |
| Amazon Redshift | EXPLAIN text | Command contract |
| Apache Calcite | EXPLAIN PLAN FOR | Command contract |
| Apache Hive | EXPLAIN FORMATTED | Command contract |
| Azure Cosmos DB for Apache Cassandra | Unsupported estimated-plan operation in the Cassandra API | Explicit capability contract |
| Azure SQL | SHOWPLAN_XML session mode; requires SHOWPLAN permission | Shared SQL Server cleanup contract |
| Cassandra | Unsupported estimated-plan operation in CQL | Explicit capability contract |
| ClickHouse | EXPLAIN PLAN | Command contract |
| CockroachDB | EXPLAIN text | Command contract |
| CSV | Calcite EXPLAIN PLAN FOR | Shared Calcite command contract |
| Databricks | EXPLAIN FORMATTED | Command contract |
| IBM Db2 LUW | Tagged EXPLAIN PLAN SELECTION; EXPLAIN_OPERATOR/EXPLAIN_STATEMENT; transaction rollback | Command and cleanup contract |
| IBM Db2 for i (AS/400) | Native facility unavailable: IBM i diagnostic/Visual Explain data is not exposed by the current JDBC path | Explicit capability contract; no live server |
| IBM Db2 z/OS | Job-specific QUERYNO and PLAN_TABLE; collision check and transaction rollback | Command and cleanup contract |
| Elasticsearch | SQL EXPLAIN text; requires compatible SQL JDBC server entitlement | Command contract; SQL parser source confirms grammar |
| Exasol | EXPLAIN VIRTUAL, for virtual-schema pushdowns only; local-table profiling is excluded | Command contract |
| Firebird | Jaybird getExplainedExecutionPlan/getExecutionPlan after prepare; no query execution | Native API documentation; no live server |
| Google BigQuery | Unsupported estimated operator plan: dry runs do not return execution-plan diagnostics | Explicit capability contract |
| Greenplum | EXPLAIN (ANALYZE FALSE, FORMAT JSON) | Command contract |
| Informix | Native facility unavailable: AVOID_EXECUTE writes a server file; authenticated file collection is not implemented | Explicit capability contract; no live server |
| JSON | Calcite EXPLAIN PLAN FOR | Shared Calcite command contract |
| DuckDB | EXPLAIN; JDBC text-stream fallback | Live DuckDB 1.5.5.1 |
| H2 | EXPLAIN | Live bundled H2 and browser fixtures |
| HSQLDB | EXPLAIN PLAN FOR; AST-validated supplied literals because EXPLAIN has no JDBC parameter slots | Live HSQLDB 2.7.4 |
| MariaDB | EXPLAIN FORMAT=JSON; requires version 10+ | Live MariaDB 11.4 |
| Microsoft SQL Server | SHOWPLAN_XML ON/query/OFF; discard connection if cleanup fails | Command/failure/cleanup and XML format contracts |
| MySQL | EXPLAIN FORMAT=JSON; requires version 5.6+ | Live MySQL 8.4.11 |
| MongoDB SQL Interface | Native facility unavailable: translated pipeline/queryPlanner is not exposed by the SQL JDBC path | Explicit capability contract; no live service |
| Neo4j | Native facility unavailable: the SQL JDBC path does not expose the native plan result summary | Explicit capability contract; no live server |
| OpenSearch | JDBC native authenticated HTTP transport to SQL explain endpoint; retains TLS/AWS signing | Endpoint, binding, failure, cleanup and bounded-response contracts; no live server |
| Oracle | Job-tagged EXPLAIN PLAN and DBMS_XPLAN.DISPLAY; PLAN_TABLE required; transaction rollback | Live Oracle Free 23.26.3.0.0 |
| PrestoDB | EXPLAIN text | Command contract |
| Redis (Calcite adapter) | Calcite EXPLAIN PLAN FOR | Shared Calcite command contract |
| SAP HANA | Job-tagged EXPLAIN PLAN and EXPLAIN_PLAN_TABLE; transaction rollback | Command and cleanup contract |
| PostgreSQL | EXPLAIN (ANALYZE FALSE, FORMAT JSON); requires version 9+ | Live PostgreSQL 16; JSON structure contract |
| Snowflake | EXPLAIN USING JSON | Command contract |
| SQLite | EXPLAIN QUERY PLAN | Live SQLite 3.53.4.0 |
| StarRocks | EXPLAIN text | Command contract |
| Teradata | EXPLAIN text | Command contract |
| Trino | EXPLAIN text | Command contract |
| YugabyteDB | EXPLAIN (ANALYZE FALSE, FORMAT JSON) | Command contract |
| Custom | Select matching adapter from actual metadata; otherwise explicit unsupported outcome | Metadata selection contracts; live PostgreSQL custom connection |

The four native-facility gaps above (Db2 for i, Informix, MongoDB SQL and Neo4j JDBC) are implementation limitations, not claims that those database products cannot explain queries. They remain visible and do not silently fall back to data execution. Exasol coverage is limited to its estimated virtual-schema operation. This release does not claim live certification for engines marked contract/documentation only.

Collected JSON/XML is interpreted within size/depth/node bounds; unusable structure retains raw output. Tabular plans retain all returned columns within result bounds. Costs use engine-specific units (Db2 LUW timerons). Estimated rows/costs are shown only when supplied by the database. Session settings are restored, failed cleanup discards the connection, and plan-table cleanup rolls back only this job's transaction/savepoint.

## Protocol references

- [BigQuery query-plan diagnostics](https://docs.cloud.google.com/bigquery/docs/query-plan-explanation): dry runs are not execution-plan diagnostics.
- [Altibase SQL plan API](https://manual.altibase.com/7.3/en/dev/jdbc/3.-Advanced-Functions/SQL-Plan/): plan-only mode does not execute the query.
- [Db2 LUW EXPLAIN](https://www.ibm.com/docs/en/db2/12.1.x?topic=statements-explain) and [Db2 z/OS EXPLAIN](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=statements-explain): distinct plan-table protocols.
- [HSQLDB data access and EXPLAIN](https://www.hsqldb.org/doc/guide/dataaccess-chapt.html).
- [Exasol EXPLAIN VIRTUAL](https://docs.exasol.com/saas/sql/explain_virtual.htm): virtual-schema pushdown explanations.
- [Elasticsearch SQL grammar](https://github.com/elastic/elasticsearch/blob/main/x-pack/plugin/sql/src/main/antlr/SqlBase.g4): SQL EXPLAIN parsing, distinct from ES|QL.
- [Neo4j native result summaries](https://neo4j.com/docs/java-manual/current/result-summary/): plans are native summary metadata.
- [OpenSearch SQL endpoints](https://github.com/opensearch-project/sql/blob/main/docs/user/interfaces/endpoint.rst): native explain REST protocol.
