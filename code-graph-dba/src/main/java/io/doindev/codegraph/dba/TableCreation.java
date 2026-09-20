package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;

/** Creation baseline and native CREATE plans sharing the existing Properties draft compiler. */
final class TableCreation {
    static ObjectNode initialize(Connection c,JsonNode target,boolean generic)throws Exception {
        String engine=ObjectCreation.engine(c),schema=target.path("schema").asText();
        if(generic||!Set.of("postgresql","h2","sqlserver").contains(engine))throw new IllegalArgumentException("Table creation is not supported by this designer adapter");
        if(engine.equals("sqlserver")&&c.getMetaData().getDatabaseMajorVersion()<16)throw new IllegalArgumentException("SQL Server designer requires verified SQL Server 2022 or later metadata");
        TableDesigner.q(schema);boolean found=false;
        try(var rs=c.getMetaData().getSchemas()){while(rs.next())if(schema.equals(rs.getString("TABLE_SCHEM")))found=true;}
        if(!found)throw new IllegalArgumentException("Selected schema is unavailable; refresh the tree");
        ObjectNode out=Profiles.JSON.createObjectNode().put("creation",true).put("editable",true).put("engine",engine).put("database",c.getCatalog()).put("schema",schema).put("name","").put("reason","New table — Save validates and opens SQL review. Apply creates the table.");
        out.putObject("fields").put("name","").put("schema",schema).put("owner","").put("comment","").put("tablespace","");
        for(String key:List.of("columns","constraints","indexes","triggers","policies","rules"))out.putArray(key);
        ArrayNode categories=out.putArray("categories");TableMetadata.categories(engine,c.getMetaData().getDatabaseMajorVersion()).forEach(categories::add);categories.add("Statistics").add("Permissions").add("DDL").add("Virtual");
        out.set("creationTarget",MetadataTree.request(target));if(engine.equals("sqlserver"))SqlServerDesigner.capabilities(out);
        ObjectNode identity=Profiles.JSON.createObjectNode().put("url",c.getMetaData().getURL()).put("user",c.getMetaData().getUserName()).put("database",c.getCatalog()).put("engine",engine).put("schema",schema);
        out.put("fingerprint",TableDesigner.hash(identity));return out;
    }
    static void absent(Connection c,String schema,String name)throws Exception {
        String esc=c.getMetaData().getSearchStringEscape();
        String sp=schema.replace(esc,esc+esc).replace("_",esc+"_").replace("%",esc+"%"),np=name.replace(esc,esc+esc).replace("_",esc+"_").replace("%",esc+"%");
        try(var rs=c.getMetaData().getTables(c.getCatalog(),sp,np,null)){if(rs.next())throw new IllegalArgumentException("An object with this name already exists. No existing object was changed.");}
    }
    static ObjectNode prepare(Connection c,ObjectNode baseline,JsonNode request)throws Exception {
        if(!baseline.path("fingerprint").equals(request.path("fingerprint")))throw new IllegalArgumentException("Creation target changed. Close this draft and start New again.");
        JsonNode draft=request.path("draft"),fields=draft.path("fields");String name=TableDesigner.str(fields,"name"),schema=TableDesigner.str(fields,"schema");
        TableDesigner.q(name);if(!schema.equals(baseline.path("schema").asText()))throw new IllegalArgumentException("Use New from the desired schema; the creation target is fixed");
        absent(c,schema,name);
        Set<String> names=new HashSet<>();for(JsonNode col:draft.path("columns"))if(!col.path("deleted").asBoolean()){
            names.add(TableDesigner.str(col,"name"));int pk=col.path("pk").asInt();if(pk<0||pk>32||!col.path("pk").isIntegralNumber())throw new IllegalArgumentException("Primary-key position must be an integer from 0 to 32");
            if(!TableDesigner.str(col,"generated").isBlank()&&!TableDesigner.str(col,"default").isBlank())throw new IllegalArgumentException("Generated expression and default cannot be combined");
        }
        for(JsonNode object:draft.path("objects")){
            String action=object.path("action").asText(),category=object.path("category").asText();
            if(!action.equals("add")&&!(category.equals("Permissions")&&Set.of("grant","revoke").contains(action))&&!(category.equals("Statistics")&&action.equals("collect")))throw new IllegalArgumentException("Only new additions can be staged before creation");
            for(JsonNode col:object.path("columns"))if(!names.contains(col.asText()))throw new IllegalArgumentException("Unknown draft column: "+col.asText());
            if(category.equals("Foreign Keys")&&object.path("columns").size()!=object.path("references").size())throw new IllegalArgumentException("Foreign-key column counts must match");
        }
        ObjectNode current=baseline.deepCopy();current.put("name",name);((ObjectNode)current.path("fields")).put("name",name);
        ObjectNode ordered=request.deepCopy();ObjectNode orderedDraft=(ObjectNode)ordered.path("draft");List<JsonNode> additions=new ArrayList<>();draft.path("objects").forEach(additions::add);
        additions.sort(Comparator.comparingInt(o->switch(o.path("category").asText()){case "Constraints"->o.path("kind").asText().equals("FOREIGN KEY")?2:0;case "Indexes"->1;case "Foreign Keys"->2;case "Permissions"->4;case "Statistics"->5;default->3;}));
        ArrayNode orderedObjects=orderedDraft.putArray("objects");additions.forEach(orderedObjects::add);
        ObjectNode plan=TableDesigner.prepare(current,ordered);ArrayNode rest=Profiles.JSON.createArrayNode();List<String> definitions=new ArrayList<>();String prefix="ALTER TABLE "+TableDesigner.target(current)+(current.path("engine").asText().equals("sqlserver")?" ADD ":" ADD COLUMN ");
        for(JsonNode command:plan.path("commands")){String sql=command.path("sql").asText();if(sql.startsWith(prefix)&&!sql.startsWith(prefix+"PRIMARY KEY")&&!sql.startsWith(prefix+"CONSTRAINT")&&!sql.startsWith(prefix+"DEFAULT"))definitions.add(sql.substring(prefix.length()));else rest.add(command);}
        if(definitions.isEmpty())throw new IllegalArgumentException("Add at least one named column with a supported datatype");
        ArrayNode commands=plan.putArray("commands");TableDesigner.add(commands,"CREATE TABLE "+TableDesigner.target(current)+" (\n  "+String.join(",\n  ",definitions)+"\n)",false);
        String ownerPrefix="ALTER TABLE "+TableDesigner.target(current)+" OWNER TO ";
        for(JsonNode command:rest)if(!command.path("sql").asText().startsWith(ownerPrefix))commands.add(command);
        for(JsonNode command:rest)if(command.path("sql").asText().startsWith(ownerPrefix))commands.add(command);
        if(commands.size()>TableDesigner.MAX_OPERATIONS)throw new IllegalArgumentException("At most 128 schema operations can be saved together");
        plan.put("createTable",true).put("designerPlan",true);return plan;
    }
}
