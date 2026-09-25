package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.ObjectCatalog.*;
import static io.doindev.codegraph.dba.ObjectDesigner.*;

/** Native Oracle definition and diagnostic properties; table rows are never read. */
final class OracleMetadata {
    private OracleMetadata(){}
    static String ddlType(String kind){return switch(kind){
        case "tables","table"->"TABLE";case "views","view"->"VIEW";case "materialized_views","materialized_view"->"MATERIALIZED_VIEW";
        case "indexes","index"->"INDEX";case "sequences","sequence"->"SEQUENCE";case "functions","function"->"FUNCTION";
        case "procedures","procedure"->"PROCEDURE";case "packages","package"->"PACKAGE_SPEC";case "package_body"->"PACKAGE_BODY";
        case "types","type"->"TYPE_SPEC";case "type_body"->"TYPE_BODY";case "triggers","trigger","schema_triggers","table_triggers"->"TRIGGER";
        case "scheduler_jobs","scheduler_job","scheduler_programs","scheduler_program","scheduler_schedules","scheduler_schedule","scheduler_chains","scheduler_chain"->"PROCOBJ";
        case "synonyms","synonym"->"SYNONYM";case "queues","queue"->"AQ_QUEUE";case "java_source"->"JAVA_SOURCE";
        default->"";
    };}
    static final String CHAIN_DEFINITION_NOTE="Native chain DDL references a separate rule set and does not include its rules. Review captured chain steps, rules and rule-set dependencies before reconstructing it.";
    record NativeDetail(String label,String key,String sql){}
    static List<NativeDetail> schedulerDetails(String kind){return switch(kind){
        case "scheduler_program","scheduler_programs"->List.of(new NativeDetail("Program arguments","schedulerArguments","SELECT argument_name,argument_position,argument_type,metadata_attribute,default_value,CASE WHEN default_anydata_value IS NULL THEN 'NO' ELSE 'YES' END AS anydata_value_present,out_argument FROM SYS.ALL_SCHEDULER_PROGRAM_ARGS WHERE owner=? AND program_name=? ORDER BY argument_position"));
        case "scheduler_job","scheduler_jobs"->List.of(new NativeDetail("Job arguments","schedulerArguments","SELECT argument_name,argument_position,argument_type,value,CASE WHEN anydata_value IS NULL THEN 'NO' ELSE 'YES' END AS anydata_value_present,out_argument FROM SYS.ALL_SCHEDULER_JOB_ARGS WHERE owner=? AND job_name=? ORDER BY argument_position"));
        case "scheduler_chain","scheduler_chains"->List.of(
            new NativeDetail("Chain steps","chainSteps","SELECT * FROM SYS.ALL_SCHEDULER_CHAIN_STEPS WHERE owner=? AND chain_name=? ORDER BY step_name"),
            new NativeDetail("Chain rules","chainRules","SELECT rule_owner,rule_name,condition,action,comments FROM SYS.ALL_SCHEDULER_CHAIN_RULES WHERE owner=? AND chain_name=? ORDER BY rule_name"));
        default->List.of();
    };}
    static String definition(QueryJobs.Job job,Connection c,String type,String owner,String name)throws Exception{
        if(type.equals("TRIGGER"))return OracleDocuments.captureScript(job,c,type,owner,name);
        return query(job,c,"SELECT SYS.DBMS_METADATA.GET_DDL(?,?,?) AS ddl FROM SYS.DUAL",type,name,owner).path(0).path("ddl").asText("");
    }
    static String actionRevision(QueryJobs.Job job,Connection c,String kind,String owner,String name)throws Exception{
        String type=ddlType(kind);if(type.isEmpty())return "";
        OracleDialect.identifier(owner);OracleDialect.identifier(name);
        var objects=query(job,c,"SELECT OBJECT_ID,OBJECT_TYPE,STATUS,LAST_DDL_TIME,EDITION_NAME FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL ORDER BY OBJECT_TYPE,OBJECT_ID",owner,name);
        if(objects.isEmpty())throw new IllegalArgumentException("Oracle object is unavailable or catalog privileges are missing; refresh the tree");
        // GET_DDL for materialized views can expand a large internal metadata graph. Tree
        // refresh/drop review needs the stable native identity and definition properties only.
        if(kind.equals("materialized_views")){
            var materialized=query(job,c,"SELECT MVIEW_NAME,CONTAINER_NAME,QUERY,REFRESH_MODE,REFRESH_METHOD,BUILD_MODE,REWRITE_ENABLED FROM SYS.ALL_MVIEWS WHERE OWNER=? AND MVIEW_NAME=?",owner,name);
            if(materialized.size()!=1)throw new IllegalArgumentException("Materialized-view definition is unavailable; refresh the tree");
            return CatalogScanner.hash(objects+"\n"+materialized);
        }
        String ddl=definition(job,c,type,owner,name);if(ddl.isBlank())throw new IllegalArgumentException("Native Oracle definition is required to review this object action");
        if(kind.equals("packages")||kind.equals("types"))for(JsonNode object:objects){String body=object.path("object_type").asText();if(body.equals("PACKAGE BODY")||body.equals("TYPE BODY"))ddl+="\n"+definition(job,c,body.replace(' ','_'),owner,name);}
        return CatalogScanner.hash(objects+"\n"+ddl);
    }
    static void populate(QueryJobs.Job job,Connection c,ObjectNode out,JsonNode node)throws Exception{
        String kind=str(out,"kind"),owner=str(out.path("fields"),"schema"),name=str(out.path("fields"),"name");
        OracleDialect.Target target=OracleDialect.target(job,c,job.remainingSeconds());out.set("oracleTarget",target.json());out.put("database",target.database());
        ((ObjectNode)out.path("fields")).put("owner",owner);
        optional(c,out,"Oracle object status",()->detail(out,"Status",query(job,c,"SELECT object_id,object_type,status,created,last_ddl_time,temporary,generated,secondary,edition_name,editionable FROM SYS.all_objects WHERE owner=? AND object_name=? AND subobject_name IS NULL ORDER BY object_type",owner,name)));
        optional(c,out,"Oracle compilation errors",()->detail(out,"Compilation errors",query(job,c,"SELECT type,sequence,line,position,attribute,message_number,text FROM SYS.all_errors WHERE owner=? AND name=? ORDER BY type,sequence",owner,name)));
        optional(c,out,"Oracle dependencies",()->detail(out,"Dependencies",query(job,c,"SELECT owner,name,type,referenced_owner,referenced_name,referenced_type,referenced_link_name,dependency_type FROM SYS.all_dependencies WHERE (owner=? AND name=?) OR (referenced_owner=? AND referenced_name=?) ORDER BY owner,name,type,referenced_owner,referenced_name",owner,name,owner,name)));
        optional(c,out,"Oracle object grants",()->detail(out,"Permissions",query(job,c,"SELECT grantor,grantee,privilege,grantable,hierarchy FROM SYS.all_tab_privs WHERE table_schema=? AND table_name=? ORDER BY grantee,privilege",owner,name)));
        optional(c,out,"Oracle column grants",()->detail(out,"Column grants",query(job,c,"SELECT grantor,grantee,column_name,privilege,grantable FROM SYS.all_col_privs WHERE table_schema=? AND table_name=? ORDER BY grantee,column_name,privilege",owner,name)));
        if(Set.of("packages","types","procedures","functions").contains(kind))optional(c,out,"Oracle routine arguments",()->detail(out,"Parameters",query(job,c,"SELECT package_name,object_name,overload,subprogram_id,argument_name,position,sequence,data_level,data_type,in_out,defaulted,type_owner,type_name,type_subname FROM SYS.all_arguments WHERE owner=? AND (object_name=? OR package_name=?) ORDER BY package_name,object_name,subprogram_id,sequence",owner,name,name)));
        String type=ddlType(kind);
        if(kind.equals("java")){
            JsonNode objects=out.path("details").path("Status");String actual="";
            for(JsonNode object:objects)if(object.path("object_id").asText().equals(node.path("oid").asText()))actual=object.path("object_type").asText();
            type=actual.equals("JAVA SOURCE")?"JAVA_SOURCE":"";
            if(type.isEmpty())warning(out,"Java class/resource metadata requires its external compiled asset; no reconstructable SQL definition is available");
        }
        String nativeType=type;
        if(!type.isEmpty())optional(c,out,"Oracle native definition",()->{
            String ddl=definition(job,c,nativeType,owner,name);out.put("ddl",ddl).put("ddlComplete",!ddl.isBlank());
            if(nativeType.equals("TRIGGER")&&!ddl.isBlank())out.put("nativeMultiUnit",SqlScript.extract(ddl,"oracle",OracleDocuments.MAX_CHARACTERS).size()>1);
            if(Set.of("packages","types").contains(kind)){
                String bodyType=kind.equals("packages")?"PACKAGE BODY":"TYPE BODY";
                boolean present=false;for(JsonNode object:out.path("details").path("Status"))if(object.path("object_type").asText().equals(bodyType))present=true;
                if(present){out.put("ddlComplete",false);String body=definition(job,c,bodyType.replace(' ','_'),owner,name);detail(out,"Body",Profiles.JSON.createObjectNode().put("ddl",body));out.put("ddl",ddl.strip()+"\n/\n"+body.strip()+"\n/").put("nativeMultiUnit",true).put("ddlComplete",true);}
            }
        });
        String view=switch(kind){case "sequences"->"SYS.all_sequences";case "materialized_views"->"SYS.all_mviews";case "views"->"SYS.all_views";case "indexes"->"SYS.all_indexes";case "triggers","schema_triggers","table_triggers"->"SYS.all_triggers";case "synonyms"->"SYS.all_synonyms";case "queues"->"SYS.all_queues";case "database_links"->"SYS.all_db_links";case "scheduler_jobs"->"SYS.all_scheduler_jobs";case "scheduler_programs"->"SYS.all_scheduler_programs";case "scheduler_schedules"->"SYS.all_scheduler_schedules";case "scheduler_chains"->"SYS.all_scheduler_chains";default->"";};
        String nameColumn=switch(kind){case "sequences"->"sequence_name";case "materialized_views"->"mview_name";case "views"->"view_name";case "indexes"->"index_name";case "synonyms"->"synonym_name";case "queues"->"name";case "database_links"->"db_link";case "scheduler_jobs"->"job_name";case "scheduler_programs"->"program_name";case "scheduler_schedules"->"schedule_name";case "scheduler_chains"->"chain_name";default->"trigger_name";};
        String ownerColumn=kind.equals("sequences")?"sequence_owner":"owner";
        if(!view.isEmpty())optional(c,out,"Oracle native attributes",()->detail(out,"Advanced",query(job,c,"SELECT * FROM "+view+" WHERE "+ownerColumn+"=? AND "+nameColumn+"=?",owner,name)));
        for(var related:schedulerDetails(kind))optional(c,out,"Oracle "+related.label(),()->{
            var rows=query(job,c,related.sql(),owner,name);detail(out,related.label(),rows);
            for(JsonNode argument:rows)if(argument.path("anydata_value_present").asText().equals("YES")){out.put("ddlComplete",false);warning(out,"Scheduler ANYDATA argument values require native typed reconstruction; they are not exported as scalar text");}
        });
        if(kind.equals("scheduler_chains")){out.put("ddlComplete",false);warning(out,CHAIN_DEFINITION_NOTE);}
        if(kind.equals("jobs"))optional(c,out,"Oracle legacy job",()->detail(out,"Advanced",query(job,c,"SELECT job,log_user,priv_user,schema_user,broken,failures,last_date,next_date,interval,what,instance FROM "+(owner.equals(target.user())?"SYS.USER_JOBS":"SYS.DBA_JOBS")+" WHERE schema_user=? AND job=TO_NUMBER(?)",owner,name)));
        if(kind.equals("database_links"))warning(out,"Database links require independently supplied destination credentials and network services; credential-bearing creation DDL is not exported");
    }
}
