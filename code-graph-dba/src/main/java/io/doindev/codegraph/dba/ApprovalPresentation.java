package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** One bounded, escaped presentation shared by desktop and browser notifications. */
final class ApprovalPresentation {
    private ApprovalPresentation(){}
    static boolean complex(JsonNode r){return Set.of("connection_create","connection_update").contains(r.path("type").asText())||r.path("requiresDriverInstall").asBoolean();}
    static ObjectNode safe(JsonNode source){
        ObjectNode out=Profiles.JSON.createObjectNode();
        for(String key:List.of("id","type","agentId","agentName","identityNotice","operation","permissionScope","approvalChoices","purpose","detail","project","projectId","environment","role","connectionName","database","schema","before","after","target","sql","parameters","classification","mutation","destructive","eligiblePersistentRead","scopeNotice","transactionNotice","expiresAt","requiresDriverInstall"))
            if(source.has(key))out.set(key,source.path(key).deepCopy());
        scrub(out);return out;
    }
    private static void scrub(JsonNode node){
        if(node instanceof ObjectNode o){List<String> keys=new ArrayList<>();o.fieldNames().forEachRemaining(keys::add);
            for(String key:keys){String lower=key.toLowerCase(Locale.ROOT);
                if(lower.matches(".*(password|passphrase|secret|credentialref|vault|private.?key|token).*")&&!lower.equals("hascredential"))o.remove(key);
                else scrub(o.path(key));
            }
        }else if(node instanceof ArrayNode a)a.forEach(ApprovalPresentation::scrub);
    }
    static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    static String html(JsonNode source){
        JsonNode r=safe(source);StringBuilder s=new StringBuilder("<html><body style='font-family: sans-serif; color:#e8edf5; background-color:#202735; margin:14px'>");
        field(s,"Request",r.path("type").asText("live_sql").replace('_',' '));field(s,"Agent",r.path("agentName").asText(r.path("agentId").asText()));field(s,"Identity",r.path("identityNotice").asText());
        if(r.has("operation")){field(s,"Operation category",r.path("operation").path("category").asText());field(s,"Eligibility",r.path("operation").path("reason").asText());field(s,"Limitations",r.path("operation").path("limitations").asText());}
        if(r.has("permissionScope"))field(s,"Exact reusable scope",r.path("permissionScope").toPrettyString());
        if(r.has("approvalChoices"))field(s,"Permission lifetime","Allow once: this request only. Always allow: exact SQL, parameter values and options until revoked. Session similar: this category until the requesting MCP session ends. Always similar: this category until revoked. No other database, schema, binding or role is included.");
        field(s,"Purpose",r.path("purpose").asText());
        field(s,"Browser workspace",r.path("detail").asText());
        if(r.has("environment"))s.append("<p style='color:#ffb768;font-size:18pt'><b>Environment: ").append(escape(r.path("environment").asText().toUpperCase(Locale.ROOT))).append("</b></p>");
        for(String key:List.of("project","environment","role","connectionName","database","schema"))field(s,key,r.path(key).asText());
        for(String key:List.of("target","before","after","parameters","classification"))if(r.has(key))field(s,key,r.path(key).toPrettyString());
        field(s,"SQL",r.path("sql").asText());field(s,"Scope",r.path("scopeNotice").asText());field(s,"Transaction",r.path("transactionNotice").asText());
        return s.append("</body></html>").toString();
    }
    private static void field(StringBuilder s,String label,String value){
        if(value.isBlank())return;s.append("<p><b>").append(escape(label)).append("</b></p><pre>").append(escape(value)).append("</pre>");
    }
}
