package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Curated connection templates; capabilities do not imply vendor-specific query certification. */
final class DatabaseCatalog {
    record Template(String id,String name,String group,String artifact,String driver,String url,String port){}
    static final List<Template> ALL=java.util.stream.Stream.of(
        new Template("altibase","Altibase","com.altibase","altibase-jdbc","Altibase.jdbc.driver.AltibaseDriver","jdbc:Altibase://localhost:20300/database",""),
        new Template("redshift","Amazon Redshift","com.amazon.redshift","redshift-jdbc42","com.amazon.redshift.jdbc.Driver","jdbc:redshift://localhost:5439/database",""),
        new Template("calcite","Apache Calcite","org.apache.calcite","calcite-core","org.apache.calcite.jdbc.Driver","jdbc:calcite:model=/path/to/model.json",""),
        new Template("hive","Apache Hive","org.apache.hive","hive-jdbc","org.apache.hive.jdbc.HiveDriver","jdbc:hive2://localhost:10000/default",""),
        new Template("cosmos-cassandra","Azure Cosmos DB for Apache Cassandra","com.ing.data","cassandra-jdbc-wrapper","com.ing.data.cassandra.jdbc.CassandraDriver","jdbc:cassandra://account.cassandra.cosmos.azure.com:10350/keyspace",""),
        new Template("azure-sql","Azure SQL","com.microsoft.sqlserver","mssql-jdbc","com.microsoft.sqlserver.jdbc.SQLServerDriver","jdbc:sqlserver://server.database.windows.net:1433;databaseName=database;encrypt=true",""),
        new Template("cassandra","Cassandra","com.ing.data","cassandra-jdbc-wrapper","com.ing.data.cassandra.jdbc.CassandraDriver","jdbc:cassandra://localhost:9042/keyspace?localdatacenter=datacenter1",""),
        new Template("clickhouse","ClickHouse","com.clickhouse","clickhouse-jdbc","com.clickhouse.jdbc.ClickHouseDriver","jdbc:clickhouse://localhost:8123/default",""),
        new Template("cockroachdb","CockroachDB","org.postgresql","postgresql","org.postgresql.Driver","jdbc:postgresql://localhost:26257/defaultdb",""),
        new Template("csv","CSV","org.apache.calcite","calcite-file","org.apache.calcite.jdbc.Driver","jdbc:calcite:schemaFactory=org.apache.calcite.adapter.file.FileSchemaFactory;schema.directory=/path/to/files",""),
        new Template("databricks","Databricks","com.databricks","databricks-jdbc","com.databricks.client.jdbc.Driver","jdbc:databricks://workspace.cloud.databricks.com:443/default;httpPath=/sql/1.0/warehouses/warehouse",""),
        new Template("db2","IBM Db2 LUW","com.ibm.db2","jcc","com.ibm.db2.jcc.DB2Driver","jdbc:db2://localhost:50000/database","50000"),
        new Template("db2-i","IBM Db2 for i (AS/400)","net.sf.jt400","jt400","com.ibm.as400.access.AS400JDBCDriver","jdbc:as400://localhost/library",""),
        new Template("db2-zos","IBM Db2 z/OS","com.ibm.db2","jcc","com.ibm.db2.jcc.DB2Driver","jdbc:db2://localhost:446/database",""),
        new Template("elasticsearch","Elasticsearch","org.elasticsearch.plugin","x-pack-sql-jdbc","org.elasticsearch.xpack.sql.jdbc.EsDriver","jdbc:es://http://localhost:9200",""),
        new Template("exasol","Exasol","com.exasol","exasol-jdbc","com.exasol.jdbc.EXADriver","jdbc:exa:localhost:8563;schema=database",""),
        new Template("firebird","Firebird","org.firebirdsql.jdbc","jaybird","org.firebirdsql.jdbc.FBDriver","jdbc:firebirdsql://localhost:3050/database",""),
        new Template("bigquery","Google BigQuery","com.google.cloud","google-cloud-bigquery-jdbc","com.google.cloud.bigquery.jdbc.BigQueryDriver","jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=project;OAuthType=0",""),
        new Template("greenplum","Greenplum","org.postgresql","postgresql","org.postgresql.Driver","jdbc:postgresql://localhost:5432/database",""),
        new Template("informix","Informix","com.ibm.informix","jdbc","com.informix.jdbc.IfxDriver","jdbc:informix-sqli://localhost:9088/database:INFORMIXSERVER=server",""),
        new Template("json","JSON","org.apache.calcite","calcite-file","org.apache.calcite.jdbc.Driver","jdbc:calcite:schemaFactory=org.apache.calcite.adapter.file.FileSchemaFactory;schema.directory=/path/to/files",""),
        new Template("duckdb","DuckDB","org.duckdb","duckdb_jdbc","org.duckdb.DuckDBDriver","jdbc:duckdb:",""),
        new Template("h2","H2","com.h2database","h2","org.h2.Driver","jdbc:h2:mem:database",""),
        new Template("hsqldb","HSQLDB","org.hsqldb","hsqldb","org.hsqldb.jdbc.JDBCDriver","jdbc:hsqldb:mem:database",""),
        new Template("mariadb","MariaDB","org.mariadb.jdbc","mariadb-java-client","org.mariadb.jdbc.Driver","jdbc:mariadb://localhost:3306/database","3306"),
        new Template("sqlserver","Microsoft SQL Server","com.microsoft.sqlserver","mssql-jdbc","com.microsoft.sqlserver.jdbc.SQLServerDriver","jdbc:sqlserver://localhost:1433;databaseName=database","1433"),
        new Template("mysql","MySQL","com.mysql","mysql-connector-j","com.mysql.cj.jdbc.Driver","jdbc:mysql://localhost:3306/database","3306"),
        new Template("mongodb","MongoDB SQL Interface","org.mongodb","mongodb-jdbc","com.mongodb.jdbc.MongoDriver","jdbc:mongodb://localhost:27017/database",""),
        new Template("neo4j","Neo4j","org.neo4j","neo4j-jdbc-full-bundle","org.neo4j.jdbc.Neo4jDriver","jdbc:neo4j://localhost:7687/neo4j",""),
        new Template("opensearch","OpenSearch","org.opensearch.driver","opensearch-sql-jdbc","org.opensearch.jdbc.Driver","jdbc:opensearch://https://localhost:9200",""),
        new Template("oracle","Oracle","com.oracle.database.jdbc","ojdbc17","oracle.jdbc.OracleDriver","jdbc:oracle:thin:@//localhost:1521/service","1521"),
        new Template("presto","PrestoDB","com.facebook.presto","presto-jdbc","com.facebook.presto.jdbc.PrestoDriver","jdbc:presto://localhost:8080/catalog/schema",""),
        new Template("redis","Redis (Calcite adapter)","org.apache.calcite","calcite-redis","org.apache.calcite.jdbc.Driver","jdbc:calcite:model=/path/to/redis-model.json",""),
        new Template("hana","SAP HANA","com.sap.cloud.db.jdbc","ngdbc","com.sap.db.jdbc.Driver","jdbc:sap://localhost:30015/",""),
        new Template("postgresql","PostgreSQL","org.postgresql","postgresql","org.postgresql.Driver","jdbc:postgresql://localhost:5432/database","5432"),
        new Template("snowflake","Snowflake","net.snowflake","snowflake-jdbc","net.snowflake.client.api.driver.SnowflakeDriver","jdbc:snowflake://organization-account.snowflakecomputing.com/","443"),
        new Template("sqlite","SQLite","org.xerial","sqlite-jdbc","org.sqlite.JDBC","jdbc:sqlite::memory:",""),
        new Template("starrocks","StarRocks","com.starrocks","starrocks-connector-j","com.starrocks.cj.jdbc.Driver","jdbc:starrocks://localhost:9030/default_catalog.database",""),
        new Template("teradata","Teradata","com.teradata.jdbc","terajdbc4","com.teradata.jdbc.TeraDriver","jdbc:teradata://localhost/DATABASE=database",""),
        new Template("trino","Trino","io.trino","trino-jdbc","io.trino.jdbc.TrinoDriver","jdbc:trino://localhost:8080/catalog/schema",""),
        new Template("yugabytedb","YugabyteDB","com.yugabyte","jdbc-yugabytedb","com.yugabyte.Driver","jdbc:yugabytedb://localhost:5433/yugabyte",""),
        new Template("custom","Custom","","","","",""))
        .sorted(Comparator.comparing((Template t)->t.id.equals("custom")).thenComparing(Template::name,String.CASE_INSENSITIVE_ORDER)).toList();
    static String classifier(String id){return switch(id){case "clickhouse","bigquery"->"all";case "hive"->"standalone";default->"";};}
    static Template get(String id){return ALL.stream().filter(t->t.id.equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown database template"));}
    static ArrayNode json(){ArrayNode out=Profiles.JSON.createArrayNode();for(var t:ALL){var n=out.addObject().put("id",t.id).put("name",t.name).put("groupId",t.group).put("artifactId",t.artifact).put("driverClass",t.driver).put("url",t.url).put("port",t.port).put("classifier",classifier(t.id)).put("notes",notes(t.id)).put("aliases",t.id.startsWith("db2")?"DB2 IBM iSeries AS400 AS/400":"").put("icon","/dba/database.svg#"+t.id).put("advancedMcp",t.id.equals("postgresql"));n.set("properties",properties(t.id));}return NativeCatalog.merge(out);}
    static String notes(String id){return switch(id){
        case "custom"->"Use your own trusted JDBC driver. Athena, Derby (retired), Couchbase, CouchDB, Sybase/jConnect, XLSX and XML currently require Custom setup.";
        case "cosmos-cassandra"->"Community Cassandra JDBC wrapper; Apache Cassandra API only. Configure TLS and local datacenter for your account. Live Cosmos compatibility is unverified.";
        case "cassandra"->"Community JDBC wrapper for CQL; not a general relational SQL engine.";
        case "csv","json","redis","calcite"->"Calcite adapter. Configure an existing local directory/model in the URL. Model files can load executable classes; use trusted files only. Files remain user-owned.";
        case "mongodb"->"Requires MongoDB SQL Interface, not an ordinary MongoDB endpoint. Deployment entitlement and driver-native prerequisites may apply.";
        case "elasticsearch"->"SQL JDBC server entitlement required. Choose a driver version compatible with your server; latest is not necessarily compatible.";
        case "db2-zos"->"IBM JCC requires applicable Db2 Connect entitlement for z/OS. Additional licensed JARs must be supplied manually.";
        case "neo4j"->"Native Cypher by default. Optional enableSQLTranslation supports a subset of SQL; advanced PostgreSQL analysis is unavailable.";
        case "bigquery","databricks","redshift"->"Cloud endpoint and authentication configuration required. Database/service charges may apply; installing this driver does not provision resources.";
        default->"Generic JDBC connection and metadata support. Verify the installed driver against your server/version. Vendor licences apply; no agreement is accepted automatically.";
    };}
    static ArrayNode properties(String id){
        ArrayNode out=Profiles.JSON.createArrayNode();
        id=switch(id){case "cockroachdb","greenplum","yugabytedb"->"postgresql";case "azure-sql"->"sqlserver";case "db2-zos"->"db2";default->id;};
        String names=switch(id){
            case "postgresql"->"sslmode,sslcert,sslkey,sslrootcert,sslpassword,connectTimeout,socketTimeout,tcpKeepAlive,ApplicationName,currentSchema,defaultRowFetchSize,prepareThreshold,loginTimeout,options";
            case "mysql"->"sslMode,trustCertificateKeyStoreUrl,trustCertificateKeyStorePassword,clientCertificateKeyStoreUrl,clientCertificateKeyStorePassword,connectTimeout,socketTimeout,tcpKeepAlive,serverTimezone,characterEncoding,useUnicode,allowPublicKeyRetrieval,socksProxyHost,socksProxyPort";
            case "mariadb"->"sslMode,serverSslCert,keyStore,keyStorePassword,trustStore,trustStorePassword,connectTimeout,socketTimeout,tcpKeepAlive,timezone,defaultFetchSize";
            case "sqlserver"->"encrypt,trustServerCertificate,hostNameInCertificate,trustStore,trustStorePassword,authentication,integratedSecurity,authenticationScheme,applicationName,loginTimeout,socketTimeout,queryTimeout,multiSubnetFailover,accessToken";
            case "oracle"->"internal_logon,oracle.net.tns_admin,oracle.net.ssl_server_dn_match,oracle.net.ssl_server_cert_dn,javax.net.ssl.trustStore,javax.net.ssl.trustStorePassword,javax.net.ssl.keyStore,javax.net.ssl.keyStorePassword,oracle.net.CONNECT_TIMEOUT,oracle.jdbc.ReadTimeout,oracle.net.wallet_location,defaultRowPrefetch";
            case "snowflake"->"authenticator,private_key_file,private_key_pwd,warehouse,db,schema,role,loginTimeout,networkTimeout,queryTimeout,CLIENT_SESSION_KEEP_ALIVE,useProxy,proxyHost,proxyPort,proxyUser,proxyPassword,nonProxyHosts";
            case "db2"->"sslConnection,sslTrustStoreLocation,sslTrustStorePassword,sslKeyStoreLocation,sslKeyStorePassword,loginTimeout,blockingReadConnectionTimeout,currentSchema,clientProgramName";
            case "h2"->"MODE,SCHEMA,AUTO_SERVER,DB_CLOSE_DELAY,IFEXISTS,INIT";
            case "hsqldb"->"ifexists,shutdown,readonly,hsqldb.default_table_type";
            case "sqlite"->"foreign_keys,busy_timeout,journal_mode,synchronous,open_mode,read_uncommitted";
            case "duckdb"->"duckdb.read_only,memory_limit,threads,temp_directory";
            case "neo4j"->"enableSQLTranslation,connectionTimeout";
            case "cassandra","cosmos-cassandra"->"localdatacenter,consistency,requesttimeout";
            case "bigquery"->"ProjectId,OAuthType,OAuthServiceAcctEmail,OAuthPvtKeyPath,OAuthPvtKey,OAuthClientId,OAuthClientSecret,OAuthAccessToken,OAuthRefreshToken,DefaultDataset";
            case "databricks"->"httpPath,AuthMech,OAuth2ClientId,OAuth2Secret,SSL,ConnCatalog,ConnSchema,SocketTimeout";
            case "trino","presto"->"SSL,SSLVerification,SSLTrustStorePath,SSLTrustStorePassword,SSLKeyStorePath,SSLKeyStorePassword,source,applicationNamePrefix";
            case "clickhouse"->"ssl,connection_timeout,socket_timeout,compress,database";
            case "hive"->"auth,principal,ssl,sslTrustStore,trustStorePassword,transportMode,httpPath";
            case "calcite","csv","json","redis"->"model,lex,caseSensitive,quotedCasing,unquotedCasing";
            default->"";};
        for(String name:names.split(","))if(!name.isEmpty()){boolean secret=sensitive(name);var property=out.addObject().put("name",name).put("description",description(name)).put("category",category(name)).put("secret",secret).put("source","catalog").put("type","string");if(Set.of("sslConnection","tcpKeepAlive","useProxy","trustServerCertificate","integratedSecurity","allowPublicKeyRetrieval","CLIENT_SESSION_KEEP_ALIVE","duckdb.read_only").contains(name)){property.put("type","boolean");property.putArray("choices").add("true").add("false");}if(id.equals("oracle")&&name.equals("internal_logon")){property.put("category","Authentication & TLS").put("description","Explicit administrative role; leave unset for an ordinary connection. The database must grant this role.");property.putArray("choices").add("sysdba").add("sysoper").add("sysbackup").add("sysdg").add("syskm");}if(id.equals("postgresql")&&name.equals("sslmode"))property.putArray("choices").add("disable").add("allow").add("prefer").add("require").add("verify-ca").add("verify-full");}
        return out;
    }
    static String description(String name){String n=name.toLowerCase(Locale.ROOT);if(n.contains("password")||n.endsWith("pwd"))return "Write-only secret. Keep, replace, or reset; saved values are never returned.";if(n.contains("timeout"))return "Driver timeout override. Units and zero-value behavior are driver/version specific; load installed-driver details.";if(n.contains("proxy"))return "Driver-level proxy option. Applies only to drivers that implement this property; no application SSH tunnel.";if(n.contains("cert")||n.contains("store")||n.contains("sslkey"))return "TLS certificate/key-store configuration. Use existing accessible local files; verify driver format requirements.";if(name.equals("private_key_file"))return "Existing PKCS#8 PEM RSA private key path. File remains user-owned; not uploaded or copied.";if(n.contains("fetch"))return "Fetch/buffering hint, not a result-row limit. Driver support and units vary.";if(name.equals("INIT"))return "Driver initialization SQL can modify an embedded database. Tests require explicit confirmation.";return "Explicit vendor JDBC override. Blank/unset leaves the installed driver's default unchanged.";}
    static String category(String key){String n=key.toLowerCase(Locale.ROOT);if(n.matches(".*(ssl|tls|cert|trust|keystore|auth|password|private_key|token|wallet).*"))return "Authentication & TLS";if(n.matches(".*(proxy|timeout|socket|keepalive|network).*"))return "Network";return "Driver properties";}
    static boolean sensitive(String name){return name.toLowerCase(Locale.ROOT).matches(".*(password|passwd|pwd|secret|token|privatekey|private_key_base64|oauthpvtkey$).* ".strip());}
    static boolean knownPublic(String template,String name){if(sensitive(name))return false;for(var n:properties(template))if(n.path("name").asText().equals(name))return true;return false;}
}
