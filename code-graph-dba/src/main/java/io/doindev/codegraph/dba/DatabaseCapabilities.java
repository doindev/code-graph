package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.sql.Connection;
import java.sql.SQLException;

/** Observation and adapter reporting, deliberately separate from permission evaluation. */
final class DatabaseCapabilities {
    static ObjectNode observe(Connection connection,JsonNode profile,JsonNode scope,boolean approvalsEnabled)throws SQLException {
        var metadata=connection.getMetaData();
        var observation=Profiles.JSON.createObjectNode().put("engine",ExplainPlans.engine(metadata)).put("finishedAt",System.currentTimeMillis());
        observation.putObject("version").put("product",metadata.getDatabaseProductName()).put("server",metadata.getDatabaseProductVersion())
                .put("major",metadata.getDatabaseMajorVersion()).put("minor",metadata.getDatabaseMinorVersion())
                .put("driver",metadata.getDriverName()).put("driverVersion",metadata.getDriverVersion());
        var out=describe(profile,scope,observation,approvalsEnabled);
        out.remove("generation");out.put("verificationStatus","live_jdbc_observation").put("freshness","live");
        out.withObject("operations").withObject("cachedCatalog").put("available",false);
        var restrictions=out.putArray("restrictions");restrictions.add("Capabilities do not grant authorization")
                .add("JDBC product identity may conceal a derivative; vendor-specific features still require verified adapters")
                .add("No user SQL, schema scan, driver installation or migration was executed")
                .add("A version observation is not certification of every operation on that server");
        return out;
    }
    static ObjectNode describe(JsonNode profile,JsonNode scope,JsonNode snapshot,boolean approvalsEnabled) {
        boolean observed=snapshot.path("version").hasNonNull("product");
        String engine=observed?snapshot.path("engine").asText("custom"):"unknown";
        ObjectNode out=Profiles.JSON.createObjectNode().put("state","complete").put("configuredTemplate",profile.path("templateId").asText("custom"))
                .put("engine",engine).put("verificationStatus",observed?"cached_database_observation":"not_observed")
                .put("freshness",observed?"last_catalog_scan":"unknown").put("approvalChannelAvailable",approvalsEnabled);
        out.set("target",ApprovalScope.display(scope));
        if(observed){out.set("version",snapshot.path("version").deepCopy());out.put("generation",snapshot.path("generation").asLong()).put("observedAt",snapshot.path("finishedAt").asLong());}
        else out.put("nextStep","For a bound target, use dba_refresh_catalog after catalog permission is granted. Standalone targets have no cached catalog until explicitly observed; configuration is not evidence of the actual server version.");
        var operations=out.putObject("operations");
        operations.putObject("cachedCatalog").put("available",observed).put("coverage",snapshot.path("coverage").asText("unknown"));
        boolean oracle=observed&&engine.equals("oracle")&&snapshot.path("version").path("major").asInt()>=19;
        var adapter=ExplainPlans.REGISTRY.get(engine);
        var explain=operations.putObject("estimatedPlan").put("adapterObserved",adapter!=null&&observed)
                .put("mcpVendorSupported",observed&&(Set.of("postgresql","mysql","mariadb","h2").contains(engine)||oracle))
                .put("requiresPermission",true).put("verification",oracle?"Oracle exact-review agent plan tested on Free 23.26.3; standard 19c certification pending":"Existing adapter; not a workflow-expansion live-test certification");
        if(adapter!=null)explain.put("protocol",adapter.protocol()).put("restriction",adapter.reason());
        // Advertise only delivered workflows. JDBC connectivity must never enable migration syntax.
        operations.putObject("schemaCapture").put("available",true).put("requiresPermission",true).put("coverage","Bounded JDBC/native observation; 100 objects/metadata rows maximum; incomplete inventories cannot prove removal")
                .put("verification",Set.of("postgresql","mysql","mariadb","h2").contains(engine)?"Basic schema observations verified; full vendor-category coverage is incomplete":"Generic adapter; workflow vendor verification pending");
        operations.putObject("schemaComparison").put("available",true).put("requiresPermission",true).put("coverage","Owned retained captures; structured property and separate definition-text differences; no rename inference");
        boolean verified=observed&&(Set.of("postgresql","mysql","mariadb","h2","sqlserver").contains(engine)||oracle);
        operations.putObject("contractValidation").put("available",true).put("requiresPermission",true).put("coverage","Static SQL, Java/JPA/MyBatis and JavaScript/TypeScript/Prisma/TypeORM mappings; dynamic expressions remain uncertain");
        operations.putObject("migrationPreparation").put("available",verified).put("requiresPermission",true).put("reason",verified?"Verified engine adapter; retained reviewed artifact only":"Live verified PostgreSQL, MySQL, MariaDB, H2, SQL Server or Oracle 19c+ identity required");
        operations.putObject("migrationRehearsal").put("available",verified&&approvalsEnabled).put("requiresPermission",true).put("reason",verified&&approvalsEnabled?"Distinct disposable target, synthetic fixtures and exact one-time review required":"Verified adapter and interactive approval channel required");
        operations.putObject("migrationApplication").put("available",verified&&approvalsEnabled).put("requiresPermission",true).put("reason",verified&&approvalsEnabled?"Exact one-time review, unchanged target and schema fingerprint required":"Verified adapter and interactive approval channel required");
        operations.putObject("queryPlanComparison").put("available",observed&&adapter!=null).put("requiresPermission",true).put("coverage","Normalized structural evidence plus retained raw vendor plans; cost units are never compared across engines");
        out.putArray("restrictions").add("Capabilities do not grant authorization")
                .add("No connection, driver download or broad scan was performed by this request")
                .add("A cached version can become stale after server replacement; re-observe before planning")
                .add("Explain mechanisms and DDL guarantees are vendor-specific; EXPLAIN ANALYZE is never automatic");
        return out;
    }
    private DatabaseCapabilities() {}
}
