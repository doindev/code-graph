package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

final class CompareRelationalScope {
    static ComparePostgresScope read(QueryJobs.Job job,Connection c,Inventory inventory,Map<String,CatalogObject> catalog,java.util.function.Predicate<CatalogObject> selected)throws Exception{
        var scope=new ComparePostgresScope();
        boolean mysql=Set.of("mysql","mariadb").contains(inventory.engine);
        for(String schema:inventory.schemas){
            String sql=mysql?"SELECT TABLE_SCHEMA AS owner_schema,TABLE_NAME AS owner_name,REFERENCED_TABLE_SCHEMA AS ref_schema,REFERENCED_TABLE_NAME AS ref_name FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_NAME IS NOT NULL AND (TABLE_SCHEMA=? OR REFERENCED_TABLE_SCHEMA=?)":
                "SELECT k.TABLE_SCHEMA AS owner_schema,k.TABLE_NAME AS owner_name,u.TABLE_SCHEMA AS ref_schema,u.TABLE_NAME AS ref_name FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS r JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS k ON k.CONSTRAINT_CATALOG=r.CONSTRAINT_CATALOG AND k.CONSTRAINT_SCHEMA=r.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME=r.CONSTRAINT_NAME JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS u ON u.CONSTRAINT_CATALOG=r.UNIQUE_CONSTRAINT_CATALOG AND u.CONSTRAINT_SCHEMA=r.UNIQUE_CONSTRAINT_SCHEMA AND u.CONSTRAINT_NAME=r.UNIQUE_CONSTRAINT_NAME WHERE k.TABLE_SCHEMA=? OR u.TABLE_SCHEMA=?";
            for(JsonNode row:query(job,c,sql,schema,schema)){
                String owner=key(str(row,"owner_schema"),"tables",str(row,"owner_name")),reference=key(str(row,"ref_schema"),"tables",str(row,"ref_name"));
                if(catalog.containsKey(owner))edge(scope,catalog,owner,reference,str(row,"ref_schema"));
                else if(catalog.containsKey(reference))scope.outside.put(reference,"incoming foreign key from "+str(row,"owner_schema")+"."+str(row,"owner_name"));
            }
        }
        if(inventory.engine.equals("mysql")){
            for(String schema:inventory.schemas)for(JsonNode row:query(job,c,"SELECT VIEW_SCHEMA,VIEW_NAME,TABLE_SCHEMA,TABLE_NAME FROM INFORMATION_SCHEMA.VIEW_TABLE_USAGE WHERE VIEW_SCHEMA=? OR TABLE_SCHEMA=?",schema,schema)){
                String owner=key(str(row,"view_schema"),"views",str(row,"view_name")),reference=key(str(row,"table_schema"),"tables",str(row,"table_name"));
                if(!catalog.containsKey(reference))reference=key(str(row,"table_schema"),"views",str(row,"table_name"));
                if(catalog.containsKey(owner))edge(scope,catalog,owner,reference,str(row,"table_schema"));
                else if(catalog.containsKey(reference))scope.outside.put(reference,"incoming view "+str(row,"view_schema")+"."+str(row,"view_name"));
            }
            for(String schema:inventory.schemas)for(JsonNode row:query(job,c,"SELECT TABLE_SCHEMA AS view_schema,TABLE_NAME AS view_name,SPECIFIC_SCHEMA,SPECIFIC_NAME FROM INFORMATION_SCHEMA.VIEW_ROUTINE_USAGE WHERE TABLE_SCHEMA=? OR SPECIFIC_SCHEMA=?",schema,schema)){
                String owner=key(str(row,"view_schema"),"views",str(row,"view_name")),reference=key(str(row,"specific_schema"),"functions",str(row,"specific_name"));
                if(catalog.containsKey(owner))edge(scope,catalog,owner,reference,str(row,"specific_schema"));
                else if(catalog.containsKey(reference))scope.outside.put(reference,"incoming view "+str(row,"view_schema")+"."+str(row,"view_name"));
            }
        }else{
            // These engines do not expose VIEW_TABLE_USAGE. Only view queries are inspected
            // for dependency names; their full editor/DDL metadata is still deferred to closure.
            for(String schema:inventory.schemas)for(JsonNode row:query(job,c,"SELECT TABLE_NAME AS name,VIEW_DEFINITION AS definition FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA=?",schema)){
                String owner=key(schema,"views",str(row,"name"));if(!catalog.containsKey(owner))continue;
                try{
                    var statement=net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(str(row,"definition"));
                    for(String name:new net.sf.jsqlparser.util.TablesNamesFinder<Void>().getTableList(statement)){
                        String[] parts=name.replace("\"","").replace("`","").split("\\.");String refSchema=parts.length>1?parts[parts.length-2]:schema,refName=parts[parts.length-1];
                        String ref=key(refSchema,"tables",refName);if(!catalog.containsKey(ref))ref=key(refSchema,"views",refName);
                        edge(scope,catalog,owner,ref,refSchema);
                    }
                }catch(Exception invalid){scope.outside.put(owner,"unresolved view references");}
            }
        }
        // Triggers and indexes are owned by tables. Other native stored programs remain
        // explicit generation blockers until their dynamic references can be validated.
        for(String schema:inventory.schemas){
            for(JsonNode row:query(job,c,mysql?"SELECT DISTINCT CONCAT(TABLE_NAME,'.',INDEX_NAME) AS name,TABLE_NAME AS target FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=?":"SELECT INDEX_NAME AS name,TABLE_NAME AS target FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_SCHEMA=?",schema))
                edge(scope,catalog,key(schema,"indexes",str(row,"name")),key(schema,"tables",str(row,"target")),schema);
            for(JsonNode row:query(job,c,"SELECT TRIGGER_NAME AS name,EVENT_OBJECT_TABLE AS target FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA=?",schema))
                edge(scope,catalog,key(schema,"triggers",str(row,"name")),key(schema,"tables",str(row,"target")),schema);
        }
        if(mysql){
            Set<String> inspected=new HashSet<>();boolean changed=true;
            while(changed){changed=false;for(String identity:scope.closure(catalog,selected)){
                var entry=catalog.get(identity);String kind=str(entry.object(),"kind");if(!Set.of("functions","procedures","triggers","events").contains(kind)||!inspected.add(identity))continue;changed=true;String schema=str(entry.object(),"schema"),name=str(entry.object(),"name");
                String sql=switch(kind){case "functions","procedures"->"SELECT ROUTINE_DEFINITION AS body,SQL_MODE AS mode FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=? AND ROUTINE_NAME=?";case "triggers"->"SELECT ACTION_STATEMENT AS body,SQL_MODE AS mode FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? AND TRIGGER_NAME=?";default->"SELECT EVENT_DEFINITION AS body,SQL_MODE AS mode FROM information_schema.EVENTS WHERE EVENT_SCHEMA=? AND EVENT_NAME=?";};var rows=query(job,c,sql,schema,name);if(rows.size()!=1||str(rows.path(0),"body").isBlank()){scope.outside.put(identity,"stored-program body hidden by privileges");continue;}
                var tokens=MysqlTableDefinition.tokens(str(rows.path(0),"body"),MysqlScript.Mode.parse(str(rows.path(0),"mode")));for(int i=0;i<tokens.size();i++){
                    var token=tokens.get(i);if(Set.of("PREPARE","EXECUTE").contains(token.word())){scope.outside.put(identity,"dynamic SQL references");continue;}
                    boolean relation=Set.of("FROM","JOIN","UPDATE","INTO","REFERENCES").contains(token.word()),call=token.word().equals("CALL");int at=relation||call?i+1:i;if(at>=tokens.size())continue;var first=tokens.get(at);if(first.text().equals("("))continue;String refSchema=schema,refName=MysqlProgram.identifier(first);int end=at;if(at+2<tokens.size()&&tokens.get(at+1).text().equals(".")){refSchema=refName;refName=MysqlProgram.identifier(tokens.get(at+2));end=at+2;}
                    boolean function=!relation&&!call&&end+1<tokens.size()&&tokens.get(end+1).text().equals("(");String ref=key(refSchema,call?"procedures":function?"functions":"tables",refName);if(relation&&!catalog.containsKey(ref))ref=key(refSchema,"views",refName);
                    if(relation||call||function&&(catalog.containsKey(ref)||!refSchema.equals(schema))){if(relation&&(Set.of("NEW","OLD").contains(refSchema.toUpperCase(Locale.ROOT))||refName.equalsIgnoreCase("DUAL")))continue;edge(scope,catalog,identity,ref,refSchema);}
                }
            }}
        }
        return scope;
    }
    private static void edge(ComparePostgresScope scope,Map<String,CatalogObject> catalog,String from,String to,String schema){
        if(!catalog.containsKey(from))return;
        if(catalog.containsKey(to)){if(!from.equals(to)){scope.scopeEdges.computeIfAbsent(from,k->new TreeSet<>()).add(to);if(!str(catalog.get(from).object(),"kind").equals("tables"))scope.edges.computeIfAbsent(from,k->new TreeSet<>()).add(to);}}
        else scope.outside.put(from,schema);
    }
    private CompareRelationalScope(){}
}
