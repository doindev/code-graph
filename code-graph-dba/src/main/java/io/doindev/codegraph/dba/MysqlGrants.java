package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.Connection;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Explicit object grants retain MySQL's distinct user and host identity. */
final class MysqlGrants {
    static void capture(QueryJobs.Job job,Connection c,ObjectNode object)throws Exception {
        String kind=str(object,"kind"),schema=str(object,"schema"),name=str(object,"name");
        if(Set.of("tables","views").contains(kind)) {
            object.set("mysqlGrants",query(job,c,"SELECT User AS user,Host AS host,'' AS column_name,Table_priv AS privileges FROM mysql.tables_priv WHERE Db=? AND Table_name=? UNION ALL SELECT User,Host,Column_name,Column_priv FROM mysql.columns_priv WHERE Db=? AND Table_name=? ORDER BY user,host,column_name",schema,name,schema,name));
        } else if(Set.of("functions","procedures").contains(kind)) {
            object.set("mysqlGrants",query(job,c,"SELECT User AS user,Host AS host,'' AS column_name,Proc_priv AS privileges FROM mysql.procs_priv WHERE Db=? AND Routine_name=? AND Routine_type=? ORDER BY user,host",schema,name,kind.equals("functions")?"FUNCTION":"PROCEDURE"));
        }
    }
    static String account(JsonNode grant){return "'"+str(grant,"user").replace("'","''")+"'@'"+str(grant,"host").replace("'","''")+"'";}
    static List<String> privileges(JsonNode grant){List<String> out=new ArrayList<>();for(String value:str(grant,"privileges").split(",")){String privilege=value.toUpperCase(Locale.ROOT).replace('_',' ').strip();if(privilege.isEmpty())continue;if(privilege.equals("GRANT"))privilege="GRANT OPTION";if(!Set.of("GRANT OPTION","SELECT","INSERT","UPDATE","DELETE","CREATE","DROP","REFERENCES","INDEX","ALTER","CREATE VIEW","SHOW VIEW","TRIGGER","DELETE HISTORY","EXECUTE","ALTER ROUTINE").contains(privilege))throw new IllegalArgumentException("Unsupported native object privilege: "+privilege);out.add(privilege+(str(grant,"column_name").isEmpty()?"":" ("+MysqlDialect.quote(str(grant,"column_name"))+")"));}return out;}
    static void validate(QueryJobs.Job job,Connection c,CompareSql.Plan plan)throws Exception {
        Set<String> seen=new HashSet<>();for(var choice:plan.selected.values())for(JsonNode grant:choice.source().path("mysqlGrants"))if(seen.add(str(grant,"user")+"\0"+str(grant,"host"))) {
            if(query(job,c,"SELECT 1 FROM mysql.user WHERE User=? AND Host=?",str(grant,"user"),str(grant,"host")).size()!=1)throw new IllegalArgumentException("Destination account does not exist: "+account(grant)+". Create/review accounts separately before comparison.");
        }
    }
    static void finish(CompareSql.Plan plan,CompareSql.Choice choice){
        var source=choice.source();var destination=choice.destination();if(!source.has("mysqlGrants")||destination!=null&&source.path("mysqlGrants").equals(destination.path("mysqlGrants"))&&CompareSql.same(plan,source,destination))return;
        String type=switch(str(source,"kind")){case "functions"->"FUNCTION ";case "procedures"->"PROCEDURE ";default->"";};
        // Routine replacement drops its grants; table/view changes retain existing explicit grants.
        boolean replaced=destination!=null&&!type.isEmpty()&&!CompareSql.same(plan,source,destination);
        if(destination!=null&&!replaced)for(JsonNode grant:destination.path("mysqlGrants")){List<String> privileges=privileges(grant);if(privileges.isEmpty())continue;CompareSql.requireDestructive(plan,"Replace explicit object grants on "+str(source,"name"));plan.finish.add("REVOKE "+String.join(", ",privileges)+" ON "+type+plan.target(source)+" FROM "+safeAccount(plan,grant));}
        for(JsonNode grant:source.path("mysqlGrants")){List<String> privileges=privileges(grant);if(privileges.isEmpty())continue;plan.finish.add("GRANT "+String.join(", ",privileges)+" ON "+type+plan.target(source)+" TO "+safeAccount(plan,grant));}
    }
    static String safeAccount(CompareSql.Plan plan,JsonNode grant){boolean escaped=MysqlScript.Mode.parse(plan.source.mysqlSqlMode).noBackslashEscapes();return MysqlDesigner.literal(str(grant,"user"),escaped)+"@"+MysqlDesigner.literal(str(grant,"host"),escaped);}
    private MysqlGrants(){}
}
