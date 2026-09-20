package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Bounded structural DDL observations; never executed or represented as a complete restore script. */
final class SqlServerDefinitions {
    interface Reader {ArrayNode rows(String sql,Object... args)throws Exception;}
    static void table(ObjectNode object,Reader read)throws Exception{
        String target=SqlServerDesigner.quote(object.path("schema").asText())+"."+SqlServerDesigner.quote(object.path("name").asText());
        ArrayNode columns=read.rows("SELECT c.name,ty.name AS type,c.max_length,c.precision,c.scale,c.is_nullable,c.is_identity,c.is_computed,c.collation_name,dc.name AS default_name,dc.definition AS default_expression,cc.definition AS computed_expression,ic.seed_value,ic.increment_value FROM sys.columns c JOIN sys.types ty ON ty.user_type_id=c.user_type_id LEFT JOIN sys.default_constraints dc ON dc.object_id=c.default_object_id LEFT JOIN sys.computed_columns cc ON cc.object_id=c.object_id AND cc.column_id=c.column_id LEFT JOIN sys.identity_columns ic ON ic.object_id=c.object_id AND ic.column_id=c.column_id WHERE c.object_id=OBJECT_ID(?) ORDER BY c.column_id",target);
        if(columns.isEmpty())return;
        var definitions=new ArrayList<String>();
        for(JsonNode col:columns){String type=col.path("type").asText();int length=col.path("max_length").asInt();
            if(Set.of("nvarchar","nchar","varchar","char","varbinary","binary").contains(type))type+="("+(length==-1?"max":String.valueOf(type.startsWith("n")?length/2:length))+")";
            else if(Set.of("decimal","numeric").contains(type))type+="("+col.path("precision").asText()+","+col.path("scale").asText()+")";else if(Set.of("datetime2","datetimeoffset","time").contains(type))type+="("+col.path("scale").asText()+")";
            String definition=SqlServerDesigner.quote(col.path("name").asText());
            if(col.path("is_computed").asInt()==1)definition+=" AS "+col.path("computed_expression").asText();
            else{definition+=" "+type;if(!col.path("collation_name").asText().isEmpty())definition+=" COLLATE "+SqlServerDesigner.collation(col.path("collation_name").asText());
                if(col.path("is_identity").asInt()==1)definition+=" IDENTITY("+col.path("seed_value").asText()+","+col.path("increment_value").asText()+")";
                definition+=col.path("is_nullable").asInt()==1?" NULL":" NOT NULL";
                if(!col.path("default_name").asText().isEmpty())definition+=" CONSTRAINT "+SqlServerDesigner.quote(col.path("default_name").asText())+" DEFAULT "+col.path("default_expression").asText();
            }definitions.add(definition);
        }
        var keys=read.rows("SELECT kc.name,kc.type AS kind,c.name AS column_name,ic.key_ordinal,ic.is_descending_key FROM sys.key_constraints kc JOIN sys.index_columns ic ON ic.object_id=kc.parent_object_id AND ic.index_id=kc.unique_index_id JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id WHERE kc.parent_object_id=OBJECT_ID(?) ORDER BY kc.name,ic.key_ordinal",target);
        var grouped=new LinkedHashMap<String,List<JsonNode>>();keys.forEach(key->grouped.computeIfAbsent(key.path("name").asText(),_->new ArrayList<>()).add(key));
        for(var entry:grouped.entrySet())definitions.add("CONSTRAINT "+SqlServerDesigner.quote(entry.getKey())+" "+(entry.getValue().getFirst().path("kind").asText().equals("PK")?"PRIMARY KEY":"UNIQUE")+" ("+String.join(", ",entry.getValue().stream().map(c->SqlServerDesigner.quote(c.path("column_name").asText())+(c.path("is_descending_key").asInt()==1?" DESC":" ASC")).toList())+")");
        var checks=read.rows("SELECT name,definition,is_disabled,is_not_trusted FROM sys.check_constraints WHERE parent_object_id=OBJECT_ID(?) ORDER BY name",target);checks.forEach(check->definitions.add("CONSTRAINT "+SqlServerDesigner.quote(check.path("name").asText())+" CHECK "+check.path("definition").asText()));
        var indexes=read.rows("SELECT i.name,i.is_unique,i.type,i.filter_definition,ic.key_ordinal,ic.is_included_column,ic.is_descending_key,c.name AS column_name FROM sys.indexes i JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id WHERE i.object_id=OBJECT_ID(?) AND i.is_primary_key=0 AND i.is_unique_constraint=0 AND i.type IN (1,2) ORDER BY i.name,ic.is_included_column,ic.key_ordinal,ic.index_column_id",target);
        grouped.clear();indexes.forEach(index->grouped.computeIfAbsent(index.path("name").asText(),_->new ArrayList<>()).add(index));
        StringBuilder ddl=new StringBuilder("-- Observed columns/defaults/keys/checks and ordinary indexes. Foreign keys, permissions, storage, triggers and special features require separate review.\nCREATE TABLE "+target+" (\n  "+String.join(",\n  ",definitions)+"\n);\n");
        for(var entry:grouped.entrySet()){
            JsonNode first=entry.getValue().getFirst();List<String> key=new ArrayList<>(),include=new ArrayList<>();for(JsonNode column:entry.getValue()){String name=SqlServerDesigner.quote(column.path("column_name").asText());if(column.path("is_included_column").asInt()==1)include.add(name);else key.add(name+(column.path("is_descending_key").asInt()==1?" DESC":" ASC"));}
            ddl.append("CREATE ").append(first.path("is_unique").asInt()==1?"UNIQUE ":"").append(first.path("type").asInt()==1?"CLUSTERED ":"NONCLUSTERED ").append("INDEX ").append(SqlServerDesigner.quote(entry.getKey())).append(" ON ").append(target).append(" (").append(String.join(", ",key)).append(")");
            if(!include.isEmpty())ddl.append(" INCLUDE (").append(String.join(", ",include)).append(")");if(!first.path("filter_definition").asText().isEmpty())ddl.append(" WHERE ").append(first.path("filter_definition").asText());ddl.append(";\n");
        }
        object.set("nativeColumns",columns);object.set("constraints",checks);object.set("nativeKeys",keys);object.set("nativeIndexes",indexes);
        object.put("ddl",ddl.toString()).put("definitionSource","sqlserver_catalogs").put("definitionCoverage","partial_structured_native");
    }
    private SqlServerDefinitions(){}
}
