package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Owner-issued object/column permissions are definition evidence, never implicit session grants. */
final class OracleCompareGrants {
    private static final Set<String> PRIVILEGES=Set.of("SELECT","READ","INSERT","UPDATE","DELETE","REFERENCES","ALTER","INDEX","EXECUTE","DEBUG","FLASHBACK","UNDER","ON COMMIT REFRESH","QUERY REWRITE");
    static void capture(QueryJobs.Job job,Connection c,String owner,Map<String,ObjectNode> roster)throws Exception{
        for(ObjectNode object:roster.values())if(str(object,"schema").equals(owner))object.putArray("oracleGrants");
        for(JsonNode row:query(job,c,"SELECT table_name,grantor,grantee,privilege,grantable,hierarchy,type,common,inherited FROM SYS.ALL_TAB_PRIVS WHERE table_schema=? ORDER BY table_name,type,grantee,privilege,grantor",owner)){
            String kind=OracleCompare.kind(str(row,"type"));ObjectNode object=roster.get(key(owner,kind,str(row,"table_name")));
            if(object==null&&kind.equals("tables"))object=roster.get(key(owner,"materialized_views",str(row,"table_name")));
            if(object!=null){ObjectNode grant=((ObjectNode)row).deepCopy();grant.remove(List.of("table_name","type"));grant.put("column","");object.withArray("oracleGrants").add(grant);}
        }
        for(JsonNode row:query(job,c,"SELECT table_name,grantor,grantee,column_name,privilege,grantable,common,inherited FROM SYS.ALL_COL_PRIVS WHERE table_schema=? ORDER BY table_name,grantee,privilege,column_name,grantor",owner)){
            ObjectNode object=roster.get(key(owner,"tables",str(row,"table_name")));if(object==null)object=roster.get(key(owner,"views",str(row,"table_name")));if(object==null)object=roster.get(key(owner,"materialized_views",str(row,"table_name")));
            if(object!=null){ObjectNode grant=((ObjectNode)row).deepCopy();grant.remove(List.of("table_name","column_name"));grant.put("column",str(row,"column_name")).put("hierarchy","NO");object.withArray("oracleGrants").add(grant);}
        }
    }
    private static SortedMap<String,ArrayNode> groups(JsonNode object,Map<String,String> owners){
        var result=new TreeMap<String,ArrayNode>();if(object==null)return result;
        for(JsonNode grant:object.path("oracleGrants")){
            if(!str(grant,"grantor").equals(str(object,"schema")))throw new IllegalArgumentException("Delegated grant chains require independent grantor/dependency review: "+str(object,"name"));
            if(str(grant,"common").equals("YES")||str(grant,"inherited").equals("YES"))throw new IllegalArgumentException("Common or inherited grants require review in their originating container");
            if(!PRIVILEGES.contains(str(grant,"privilege")))throw new IllegalArgumentException("Unsupported Oracle object privilege: "+str(grant,"privilege"));
            ObjectNode normalized=((ObjectNode)grant).deepCopy();normalized.remove(List.of("grantor","common","inherited"));normalized.put("grantee",owners.getOrDefault(str(grant,"grantee"),str(grant,"grantee")));
            String key=str(normalized,"grantee")+"\u0000"+str(normalized,"privilege");result.computeIfAbsent(key,k->Profiles.JSON.createArrayNode()).add(normalized);
        }
        for(var entry:result.entrySet()){var sorted=new ArrayList<JsonNode>();entry.getValue().forEach(sorted::add);sorted.sort(Comparator.comparing(row->str(row,"column")));entry.getValue().removeAll().addAll(sorted);}return result;
    }
    static void review(QueryJobs.Job job,Connection c,ObjectNode source,ObjectNode destination,String owner,Map<String,String> owners,ArrayNode changes)throws Exception{
        var desired=groups(source,owners);var old=groups(destination,Map.of());var keys=new TreeSet<String>();keys.addAll(desired.keySet());keys.addAll(old.keySet());
        for(String key:keys){ArrayNode wanted=desired.getOrDefault(key,Profiles.JSON.createArrayNode()),current=old.getOrDefault(key,Profiles.JSON.createArrayNode());if(wanted.equals(current))continue;
            JsonNode sample=wanted.isEmpty()?current.get(0):wanted.get(0);String grantee=str(sample,"grantee"),privilege=str(sample,"privilege");
            if(!wanted.isEmpty()&&!grantee.equals("PUBLIC")){
                boolean exists=!query(job,c,"SELECT USERNAME FROM SYS.ALL_USERS WHERE USERNAME=?",grantee).isEmpty();
                if(!exists){try{exists=!query(job,c,"SELECT ROLE FROM SYS.DBA_ROLES WHERE ROLE=?",grantee).isEmpty();}catch(SQLException missing){if(fatal(missing))throw missing;throw new IllegalArgumentException("Cannot verify destination grantee "+grantee+"; grant catalog access or create/authorize it independently");}}
                if(!exists)throw new IllegalArgumentException("Destination grantee does not exist: "+grantee);
            }
            boolean revoke=false;for(JsonNode held:current){JsonNode next=null;for(JsonNode grant:wanted)if(str(grant,"column").equals(str(held,"column"))){next=grant;break;}
                if(next==null||str(held,"grantable").equals("YES")&&!str(next,"grantable").equals("YES")||str(held,"hierarchy").equals("YES")&&!str(next,"hierarchy").equals("YES"))revoke=true;
            }
            if(revoke&&privilege.equals("REFERENCES"))throw new IllegalArgumentException("Revoking REFERENCES can remove foreign keys; review the dependent constraints manually");
            ArrayNode sql=Profiles.JSON.createArrayNode();String qualified=OracleDialect.qualified(owner,str(source,"name")),recipient=grantee.equals("PUBLIC")?"PUBLIC":OracleDialect.identifier(grantee);
            if(revoke)sql.add("REVOKE "+privilege+" ON "+qualified+" FROM "+recipient);
            for(JsonNode grant:wanted){if(!revoke&&contains(current,grant))continue;String column=str(grant,"column");if(!column.isEmpty()&&!Set.of("INSERT","UPDATE","REFERENCES").contains(privilege))throw new IllegalArgumentException("Unsupported Oracle column privilege "+privilege);
                sql.add("GRANT "+privilege+(column.isEmpty()?"":" ("+OracleDialect.identifier(column)+")")+" ON "+qualified+" TO "+recipient+(str(grant,"hierarchy").equals("YES")?" WITH HIERARCHY OPTION":"")+(str(grant,"grantable").equals("YES")?" WITH GRANT OPTION":""));
            }
            if(!sql.isEmpty()){var change=changes.addObject().put("clause",revoke?"REPLACE_GRANT":"GRANT").put("name",grantee).put("attribute",privilege).put("destructive",revoke);change.set("sql",sql);}
        }
    }
    private static boolean contains(ArrayNode array,JsonNode item){for(JsonNode value:array)if(value.equals(item))return true;return false;}
}
