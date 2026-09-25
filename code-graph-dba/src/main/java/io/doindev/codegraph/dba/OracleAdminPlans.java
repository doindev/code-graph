package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.OracleDialect.identifier;
import static io.doindev.codegraph.dba.TableDesigner.literal;
import static io.doindev.codegraph.dba.ObjectCatalog.query;

/** Typed Oracle administration forms. No client SQL or passwords are retained in a review. */
final class OracleAdminPlans {
    record Field(String key,String label,String type,String value,List<String> options){}
    record Action(String id,String label,String category,String privilege,boolean password,List<Field> fields){}
    static Field text(String key,String label){return new Field(key,label,"text","",List.of());}
    static Field option(String key,String label,String... options){return new Field(key,label,"select",options[0],List.of(options));}
    static Field number(String key,String label,int value){return new Field(key,label,"number",""+value,List.of());}
    static Field check(String key,String label){return new Field(key,label,"checkbox","false",List.of());}
    static Field name(){return text("name","Name");}static Field owner(){return text("owner","Owner/schema");}
    static Action action(String id,String label,String category,String privilege,Field... fields){return new Action(id,label,category,privilege,false,List.of(fields));}
    static final List<Action> CORE_ACTIONS=List.of(
        new Action("create_user","Create user","users","CREATE USER",true,List.of(name(),text("tablespace","Default tablespace"),text("temporaryTablespace","Temporary tablespace"),text("profile","Profile"))),
        new Action("user_password","Set password","users","ALTER USER",true,List.of(name())),
        action("user_lock","Lock account","users","ALTER USER",name()),action("user_unlock","Unlock account","users","ALTER USER",name()),
        action("user_profile","Set profile","users","ALTER USER",name(),text("profile","Profile")),
        action("user_tablespace","Set default tablespace","users","ALTER USER",name(),text("tablespace","Tablespace")),
        action("drop_user","Drop user","users","DROP USER",name(),check("cascade","Include owned objects (CASCADE)")),
        action("create_role","Create role","roles","CREATE ROLE",name()),action("drop_role","Drop role","roles","DROP ANY ROLE",name()),
        action("create_profile","Create profile","profiles","CREATE PROFILE",name(),option("resource","Resource","SESSIONS_PER_USER","CPU_PER_SESSION","CPU_PER_CALL","CONNECT_TIME","IDLE_TIME","FAILED_LOGIN_ATTEMPTS","PASSWORD_LIFE_TIME","PASSWORD_REUSE_TIME","PASSWORD_REUSE_MAX","PASSWORD_LOCK_TIME","PASSWORD_GRACE_TIME"),text("limit","Limit (number, DEFAULT or UNLIMITED)")),
        action("alter_profile","Change profile limit","profiles","ALTER PROFILE",name(),option("resource","Resource","SESSIONS_PER_USER","CPU_PER_SESSION","CPU_PER_CALL","CONNECT_TIME","IDLE_TIME","FAILED_LOGIN_ATTEMPTS","PASSWORD_LIFE_TIME","PASSWORD_REUSE_TIME","PASSWORD_REUSE_MAX","PASSWORD_LOCK_TIME","PASSWORD_GRACE_TIME"),text("limit","Limit (number, DEFAULT or UNLIMITED)")),
        action("drop_profile","Drop profile","profiles","DROP PROFILE",name(),check("cascade","Reset assigned users to DEFAULT")),
        action("grant_system","Grant system privilege","grants","GRANT ANY PRIVILEGE",name(),text("privilege","System privilege"),check("adminOption","With admin option")),
        action("revoke_system","Revoke system privilege","grants","GRANT ANY PRIVILEGE",name(),text("privilege","System privilege")),
        action("grant_role","Grant role","grants","GRANT ANY ROLE",name(),text("role","Role"),check("adminOption","With admin option")),
        action("revoke_role","Revoke role","grants","GRANT ANY ROLE",name(),text("role","Role")),
        action("grant_object","Grant object privilege","grants","GRANT ANY OBJECT PRIVILEGE",name(),owner(),text("object","Object"),option("privilege","Privilege","SELECT","READ","INSERT","UPDATE","DELETE","EXECUTE","REFERENCES","ALTER","INDEX","DEBUG","FLASHBACK"),check("grantOption","With grant option")),
        action("revoke_object","Revoke object privilege","grants","GRANT ANY OBJECT PRIVILEGE",name(),owner(),text("object","Object"),option("privilege","Privilege","SELECT","READ","INSERT","UPDATE","DELETE","EXECUTE","REFERENCES","ALTER","INDEX","DEBUG","FLASHBACK")),
        action("quota","Set quota","quotas","ALTER USER",name(),text("tablespace","Tablespace"),number("quotaMB","Quota MiB (-1 for unlimited)",100)),
        action("create_tablespace","Create tablespace","tablespaces","CREATE TABLESPACE",name(),text("fileName","Server file path (empty uses Oracle managed files)"),number("sizeMB","Initial size MiB",100),number("maxMB","Autoextend maximum MiB (0 disables)",0),check("temporary","Temporary tablespace")),
        action("tablespace_status","Change tablespace status","tablespaces","ALTER TABLESPACE",name(),option("status","Status","ONLINE","OFFLINE NORMAL","READ ONLY","READ WRITE")),
        action("tablespace_add_file","Add file","tablespaces","ALTER TABLESPACE",name(),text("fileName","Server file path (empty uses Oracle managed files)"),number("sizeMB","Initial size MiB",100),number("maxMB","Autoextend maximum MiB (0 disables)",0)),
        action("drop_tablespace","Drop tablespace","tablespaces","DROP TABLESPACE",name(),check("includingContents","Include contents"),check("deleteFiles","Delete database files")),
        action("file_resize","Resize file","files","ALTER DATABASE",number("fileId","File ID",1),option("kind","File kind","DATAFILE","TEMPFILE"),number("sizeMB","Size MiB",100)),
        action("file_autoextend","Set file autoextend","files","ALTER DATABASE",number("fileId","File ID",1),option("kind","File kind","DATAFILE","TEMPFILE"),number("maxMB","Maximum MiB (0 disables)",0)),
        action("disconnect_session","Disconnect session","sessions","ALTER SYSTEM",number("sid","Session ID",1),number("serial","Serial number",1),option("mode","Mode","POST_TRANSACTION","IMMEDIATE")),
        action("kill_session","Kill session","sessions","ALTER SYSTEM",number("sid","Session ID",1),number("serial","Serial number",1)),
        action("cancel_sql","Cancel active SQL","active_sql","ALTER SYSTEM",number("sid","Session ID",1),number("serial","Serial number",1),text("sqlId","SQL ID")),
        action("compile","Compile object","compilation","",owner(),name(),option("objectType","Object type","FUNCTION","PROCEDURE","PACKAGE","PACKAGE BODY","TYPE","TYPE BODY","TRIGGER","VIEW")),
        action("gather_statistics","Gather table statistics","statistics","ANALYZE ANY",owner(),name()),
        action("lock_statistics","Lock table statistics","statistics","ANALYZE ANY",owner(),name()),
        action("unlock_statistics","Unlock table statistics","statistics","ANALYZE ANY",owner(),name())
    );
    static final List<Action> ACTIONS=java.util.stream.Stream.concat(CORE_ACTIONS.stream(),OracleDataPump.actions().stream()).toList();
    static ArrayNode actions(){ArrayNode result=Profiles.JSON.createArrayNode();for(Action action:ACTIONS){var item=result.addObject().put("id",action.id()).put("label",action.label()).put("category",action.category()).put("privilege",action.privilege()).put("requiresPassword",action.password());var fields=item.putArray("fields");for(Field field:action.fields()){var f=fields.addObject().put("key",field.key()).put("label",field.label()).put("type",field.type()).put("value",field.value());var options=f.putArray("options");field.options().forEach(options::add);}}return result;}
    static Action action(JsonNode request){String id=request.path("action").asText();return ACTIONS.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown Oracle administration action"));}
    static ObjectNode request(JsonNode input){
        Action action=action(input);var allowed=new HashSet<>(List.of("connectionId","database","action"));action.fields().forEach(f->allowed.add(f.key()));input.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw new IllegalArgumentException("Unexpected administration field: "+key);});
        ObjectNode out=Profiles.JSON.createObjectNode();for(String key:List.of("connectionId","database","action"))out.put(key,input.path(key).asText());
        for(Field field:action.fields())switch(field.type()){
            case "checkbox"->{if(input.has(field.key())&&!input.path(field.key()).isBoolean())throw new IllegalArgumentException(field.label()+" must be boolean");out.put(field.key(),input.path(field.key()).asBoolean());}
            case "number"->out.put(field.key(),OracleAdministration.number(input,field.key(),Integer.parseInt(field.value()),field.key().equals("quotaMB")?-1:0,1_048_576));
            default->{String value=input.path(field.key()).asText(field.value()).strip();if(value.length()>1024||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException(field.label()+" is invalid");if(!field.options().isEmpty()&&!field.options().contains(value))throw new IllegalArgumentException("Unsupported "+field.label());out.put(field.key(),value);}
        }return out;
    }
    static String password(String value){if(value==null||value.isEmpty()||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128||value.indexOf('"')>=0||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Supply a password of 1..128 UTF-8 bytes without double quotes or control characters");return value;}
    static ObjectNode prepare(QueryJobs.Job job,Connection c,ObjectNode request)throws Exception{
        Action action=action(request);var target=OracleAdministration.target(job,c,request);String id=action.id(),name=request.path("name").asText(),schema=request.path("owner").asText();
        var privileges=query(job,c,"SELECT PRIVILEGE FROM SYS.SESSION_PRIVS ORDER BY PRIVILEGE");var known=new HashSet<String>();for(JsonNode row:privileges)known.add(row.path("privilege").asText());
        String required=action.privilege();if((id.endsWith("_object")||id.endsWith("statistics"))&&schema.equals(target.user()))required="";
        if(id.equals("compile")&&!schema.equals(target.user()))required=switch(request.path("objectType").asText()){case "TYPE","TYPE BODY"->"ALTER ANY TYPE";case "TRIGGER"->"ALTER ANY TRIGGER";case "VIEW"->"ALTER ANY TABLE";default->"ALTER ANY PROCEDURE";};
        if(!required.isEmpty()&&!known.contains(required))throw new IllegalArgumentException("Oracle privilege required for "+action.label()+": "+required);
        ArrayNode observed=Profiles.JSON.createArrayNode(),commands=Profiles.JSON.createArrayNode();String sql="";
        if(id.startsWith("datapump_"))sql=OracleDataPump.definition(job,c,request,target,observed);
        else if(id.equals("create_user")){absent(query(job,c,"SELECT USER_ID FROM SYS.DBA_USERS WHERE USERNAME=?",name),"User");identifier(name);String suffix="";if(!request.path("tablespace").asText().isEmpty())suffix+=" DEFAULT TABLESPACE "+identifier(request.path("tablespace").asText());if(!request.path("temporaryTablespace").asText().isEmpty())suffix+=" TEMPORARY TABLESPACE "+identifier(request.path("temporaryTablespace").asText());if(!request.path("profile").asText().isEmpty())suffix+=" PROFILE "+identifier(request.path("profile").asText());secret(commands,"CREATE USER "+identifier(name)+" IDENTIFIED BY ",suffix);}
        else if(id.startsWith("user_")||id.equals("drop_user")||id.equals("quota")){
            var user=query(job,c,"SELECT USER_ID,USERNAME,ACCOUNT_STATUS,DEFAULT_TABLESPACE,TEMPORARY_TABLESPACE,PROFILE,AUTHENTICATION_TYPE,COMMON,ORACLE_MAINTAINED FROM SYS.DBA_USERS WHERE USERNAME=?",name);present(user,"User");if(user.get(0).path("oracle_maintained").asText().equals("Y")||user.get(0).path("common").asText().equals("YES"))throw new IllegalArgumentException("Oracle-maintained and common accounts require separate native administration review");observed.add(user);
            String prefix="ALTER USER "+identifier(name);sql=switch(id){case "user_lock"->prefix+" ACCOUNT LOCK";case "user_unlock"->prefix+" ACCOUNT UNLOCK";case "user_profile"->prefix+" PROFILE "+identifier(request.path("profile").asText());case "user_tablespace"->prefix+" DEFAULT TABLESPACE "+identifier(request.path("tablespace").asText());case "drop_user"->"DROP USER "+identifier(name)+(request.path("cascade").asBoolean()?" CASCADE":"");case "quota"->prefix+" QUOTA "+(request.path("quotaMB").asInt()==-1?"UNLIMITED":request.path("quotaMB").asInt()+"M")+" ON "+identifier(request.path("tablespace").asText());default->"";};if(id.equals("user_password"))secret(commands,prefix+" IDENTIFIED BY ","");
        }else if(id.endsWith("_role")&&(id.equals("create_role")||id.equals("drop_role"))){var roles=query(job,c,"SELECT ROLE,ORACLE_MAINTAINED,COMMON FROM SYS.DBA_ROLES WHERE ROLE=?",name);if(id.equals("create_role"))absent(roles,"Role");else{present(roles,"Role");if(roles.get(0).path("oracle_maintained").asText().equals("Y"))throw new IllegalArgumentException("Oracle-maintained roles require native administration review");}observed.add(roles);sql=(id.equals("create_role")?"CREATE ROLE ":"DROP ROLE ")+identifier(name);
        }else if(id.endsWith("_profile")){
            var rows=query(job,c,"SELECT RESOURCE_NAME,RESOURCE_TYPE,LIMIT FROM SYS.DBA_PROFILES WHERE PROFILE=? ORDER BY RESOURCE_NAME",name);if(id.equals("create_profile"))absent(rows,"Profile");else{present(rows,"Profile");observed.add(rows);}if(name.equals("DEFAULT"))throw new IllegalArgumentException("The DEFAULT profile requires native administration review");
            if(id.equals("drop_profile"))sql="DROP PROFILE "+identifier(name)+(request.path("cascade").asBoolean()?" CASCADE":"");else{String limit=request.path("limit").asText().toUpperCase(Locale.ROOT);if(!limit.matches("(?:DEFAULT|UNLIMITED|[0-9]{1,9}(?:\\.[0-9]{1,6})?)"))throw new IllegalArgumentException("Profile limit must be DEFAULT, UNLIMITED or a nonnegative numeric literal");sql=(id.equals("create_profile")?"CREATE":"ALTER")+" PROFILE "+identifier(name)+" LIMIT "+request.path("resource").asText()+" "+limit;}
        }else if(id.startsWith("grant_")||id.startsWith("revoke_")){
            boolean grant=id.startsWith("grant_");String privilege=request.path("privilege").asText().toUpperCase(Locale.ROOT),subject="";identifier(name);
            if(id.endsWith("system")){present(query(job,c,"SELECT NAME FROM SYS.SYSTEM_PRIVILEGE_MAP WHERE NAME=?",privilege),"System privilege");subject=privilege;observed.add(query(job,c,"SELECT GRANTEE,PRIVILEGE,ADMIN_OPTION FROM SYS.DBA_SYS_PRIVS WHERE GRANTEE=? AND PRIVILEGE=?",name,privilege));}
            else if(id.endsWith("role")){String role=request.path("role").asText();present(query(job,c,"SELECT ROLE FROM SYS.DBA_ROLES WHERE ROLE=?",role),"Role");subject=identifier(role);observed.add(query(job,c,"SELECT GRANTEE,GRANTED_ROLE,ADMIN_OPTION FROM SYS.DBA_ROLE_PRIVS WHERE GRANTEE=? AND GRANTED_ROLE=?",name,role));}
            else{String object=request.path("object").asText();observed.add(object(job,c,schema,object,""));subject=privilege+" ON "+OracleDialect.qualified(schema,object);observed.add(query(job,c,"SELECT GRANTOR,GRANTEE,PRIVILEGE,GRANTABLE FROM SYS.ALL_TAB_PRIVS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND GRANTEE=? AND PRIVILEGE=? ORDER BY GRANTOR",schema,object,name,privilege));}
            sql=(grant?"GRANT ":"REVOKE ")+subject+(grant?" TO ":" FROM ")+identifier(name)+(grant&&request.path("adminOption").asBoolean()?" WITH ADMIN OPTION":grant&&request.path("grantOption").asBoolean()?" WITH GRANT OPTION":"");
        }else if(id.contains("tablespace")){
            var rows=query(job,c,"SELECT TABLESPACE_NAME,STATUS,CONTENTS,BIGFILE FROM SYS.DBA_TABLESPACES WHERE TABLESPACE_NAME=?",name);if(id.equals("create_tablespace"))absent(rows,"Tablespace");else{present(rows,"Tablespace");observed.add(rows);if(Set.of("SYSTEM","SYSAUX").contains(name)||rows.get(0).path("contents").asText().equals("UNDO"))throw new IllegalArgumentException("System and undo tablespaces require native administration review");}
            String fileKind=(id.equals("create_tablespace")?request.path("temporary").asBoolean():rows.get(0).path("contents").asText().equals("TEMPORARY"))?"TEMPFILE":"DATAFILE";
            sql=switch(id){case "create_tablespace"->"CREATE "+(request.path("temporary").asBoolean()?"TEMPORARY ":"")+"TABLESPACE "+identifier(name)+" "+fileKind+" "+file(request);case "tablespace_add_file"->"ALTER TABLESPACE "+identifier(name)+" ADD "+fileKind+" "+file(request);case "tablespace_status"->"ALTER TABLESPACE "+identifier(name)+" "+request.path("status").asText();case "drop_tablespace"->{if(request.path("deleteFiles").asBoolean()&&!request.path("includingContents").asBoolean())throw new IllegalArgumentException("Deleting files requires including contents");yield "DROP TABLESPACE "+identifier(name)+(request.path("includingContents").asBoolean()?" INCLUDING CONTENTS":"")+(request.path("deleteFiles").asBoolean()?" AND DATAFILES":"");}default->throw new IllegalArgumentException("Unsupported tablespace operation");};
        }else if(id.startsWith("file_")){
            String kind=request.path("kind").asText();int fileId=request.path("fileId").asInt();var file=query(job,c,"SELECT FILE_ID,FILE_NAME,TABLESPACE_NAME,BYTES,AUTOEXTENSIBLE,MAXBYTES FROM SYS."+(kind.equals("TEMPFILE")?"DBA_TEMP_FILES":"DBA_DATA_FILES")+" WHERE FILE_ID=?",fileId);present(file,"File");observed.add(file);if(Set.of("SYSTEM","SYSAUX").contains(file.get(0).path("tablespace_name").asText()))throw new IllegalArgumentException("System files require native administration review");sql="ALTER DATABASE "+kind+" "+literal(file.get(0).path("file_name").asText())+(id.equals("file_resize")?" RESIZE "+positive(request,"sizeMB")+"M":autoextend(request));
        }else if(id.endsWith("session")||id.equals("cancel_sql")){
            int sid=positive(request,"sid"),serial=positive(request,"serial");var session=query(job,c,"SELECT SID,SERIAL#,USERNAME,LOGON_TIME,CON_ID,TYPE FROM SYS.V_$SESSION WHERE SID=? AND SERIAL#=?",sid,serial);present(session,"Session");if(!session.get(0).path("type").asText().equals("USER"))throw new IllegalArgumentException("Select an ordinary user session");observed.add(session);
            String identity=sid+","+serial;if(id.equals("cancel_sql")){String sqlId=request.path("sqlId").asText();if(!sqlId.matches("[0-9a-z]{13}"))throw new IllegalArgumentException("Specify the exact active SQL ID");present(query(job,c,"SELECT SID FROM SYS.V_$SESSION WHERE SID=? AND SERIAL#=? AND SQL_ID=?",sid,serial,sqlId),"Active SQL");sql="ALTER SYSTEM CANCEL SQL '"+identity+","+sqlId+"'";}else sql="ALTER SYSTEM "+(id.equals("kill_session")?"KILL SESSION '"+identity+"' IMMEDIATE":"DISCONNECT SESSION '"+identity+"' "+request.path("mode").asText());
        }else if(id.equals("compile")){
            String type=request.path("objectType").asText();observed.add(object(job,c,schema,name,type));String suffix=type.endsWith(" BODY")?" BODY":"";String base=type.replace(" BODY","");sql="ALTER "+base+" "+OracleDialect.qualified(schema,name)+" COMPILE"+suffix+(base.equals("VIEW")?"":" REUSE SETTINGS");
        }else if(id.endsWith("statistics")){
            observed.add(object(job,c,schema,name,"TABLE"));String procedure=switch(id){case "gather_statistics"->"GATHER_TABLE_STATS";case "lock_statistics"->"LOCK_TABLE_STATS";default->"UNLOCK_TABLE_STATS";};sql="BEGIN SYS.DBMS_STATS."+procedure+"(ownname=>"+literal(schema)+",tabname=>"+literal(name)+(id.equals("gather_statistics")?",estimate_percent=>SYS.DBMS_STATS.AUTO_SAMPLE_SIZE,degree=>1,cascade=>TRUE":"")+"); END;";
        }
        if(!sql.isEmpty())commands.addObject().put("sql",sql);if(commands.isEmpty())throw new IllegalArgumentException("No supported administration command was generated");
        var plan=Profiles.JSON.createObjectNode().put("oracleAdminPlan",true).put("action",id).put("label",action.label()).put("requiresPassword",action.password()).put("atomic",false).put("expiresAt",System.currentTimeMillis()+300000);plan.set("target",target.json());plan.set("request",request.deepCopy());plan.set("commands",commands);plan.put("notice","Oracle administration can commit implicitly. Review the exact target and SQL; cancellation can leave an unknown outcome.");
        plan.put("fingerprint",CatalogScanner.hash(target.json()+"\n"+request+"\n"+commands+"\n"+observed+"\n"+privileges));return plan;
    }
    private static ArrayNode object(QueryJobs.Job job,Connection c,String owner,String name,String type)throws Exception{identifier(owner);identifier(name);var rows=query(job,c,"SELECT OBJECT_ID,OBJECT_TYPE,LAST_DDL_TIME,STATUS,EDITION_NAME FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL AND (? IS NULL OR OBJECT_TYPE=?) ORDER BY OBJECT_TYPE,OBJECT_ID",owner,name,type,type);present(rows,"Object");return rows;}
    private static int positive(JsonNode request,String key){int value=request.path(key).asInt();if(value<1)throw new IllegalArgumentException(key+" must be positive");return value;}
    private static String file(JsonNode request){String path=request.path("fileName").asText();if(path.length()>1024||path.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid server file path");int size=positive(request,"sizeMB"),maximum=request.path("maxMB").asInt();if(maximum>0&&maximum<size)throw new IllegalArgumentException("Autoextend maximum must be at least the initial size");return (path.isBlank()?"":literal(path)+" ")+"SIZE "+size+"M"+autoextend(request);}
    private static String autoextend(JsonNode request){int maximum=request.path("maxMB").asInt();return maximum==0?" AUTOEXTEND OFF":" AUTOEXTEND ON NEXT 1M MAXSIZE "+maximum+"M";}
    private static void secret(ArrayNode commands,String prefix,String suffix){commands.addObject().put("sql",prefix+"<password supplied at Apply>"+suffix).put("secretPrefix",prefix).put("secretSuffix",suffix);}
    private static void present(ArrayNode rows,String label){if(rows.isEmpty())throw new IllegalArgumentException(label+" is not visible; verify its name and catalog privileges");}
    private static void absent(ArrayNode rows,String label){if(!rows.isEmpty())throw new IllegalArgumentException(label+" already exists; review its current definition first");}
}
