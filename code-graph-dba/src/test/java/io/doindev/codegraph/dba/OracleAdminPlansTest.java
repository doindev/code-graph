package io.doindev.codegraph.dba;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OracleAdminPlansTest {
    @Test void typedRequestsRejectSecretsAndSqlFragmentsBeforeRetention(){
        var request=Profiles.JSON.createObjectNode().put("connectionId","fixture").put("action","create_user").put("name","EXAMPLE").put("password","do not retain");assertThrows(IllegalArgumentException.class,()->OracleAdminPlans.request(request));request.remove("password");assertFalse(OracleAdminPlans.request(request).has("password"));
        request.put("action","tablespace_status").put("status","ONLINE; DROP USER X");assertThrows(IllegalArgumentException.class,()->OracleAdminPlans.request(request));
        var quota=Profiles.JSON.createObjectNode().put("action","quota").put("quotaMB",-2);assertThrows(IllegalArgumentException.class,()->OracleAdminPlans.request(quota));
        for(String password:new String[]{"", "x\"y","line\nbreak","x".repeat(129)})assertThrows(IllegalArgumentException.class,()->OracleAdminPlans.password(password));assertEquals("a safe value",OracleAdminPlans.password("a safe value"));
    }
}
