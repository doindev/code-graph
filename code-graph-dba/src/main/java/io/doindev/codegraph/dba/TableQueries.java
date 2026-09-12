package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;

/** Table identities are resolved from catalog records, never from a display label. */
final class TableQueries {
    static final Set<String> RELATIONS=Set.of("tables","views","materialized_views");
    static ObjectNode prepare(QueryJobs.Job job,Connection c,ObjectNode selection,int timeout)throws Exception {
        JsonNode parent=selection.path("parent");
        if(!RELATIONS.contains(parent.path("kind").asText()))throw new IllegalArgumentException("Select a table, view or materialized view");
        for(JsonNode node:MetadataTree.browse(job,c,parent,timeout).path("nodes")) {
            if(!node.path("key").asText().equals(selection.path("key").asText()))continue;
            String name=node.path("name").asText(),schema=node.path("schema").asText(),database=parent.path("database").asText(c.getCatalog());
            if(name.endsWith("…"))throw new IllegalArgumentException("The catalog returned a shortened identifier; use the SQL editor with its full name");
            return Profiles.JSON.createObjectNode().put("sql",sql(c.getMetaData(),database,schema,name)).put("database",database==null?"":database).put("name",name).put("schema",schema).put("kind",parent.path("kind").asText());
        }
        throw new IllegalArgumentException("Table changed or is no longer on this page; refresh Tables and reopen it");
    }
    static String sql(DatabaseMetaData metadata,String database,String schema,String name)throws SQLException {
        String engine=VendorMetadata.engine(metadata.getDatabaseProductName());
        String quote=metadata.getIdentifierQuoteString();
        if(quote==null||quote.isBlank())throw new SQLFeatureNotSupportedException("The driver does not advertise safe identifier quoting; use the SQL editor");
        var parts=new ArrayList<String>();
        if(Set.of("mysql","mariadb").contains(engine)) {if(database!=null&&!database.isEmpty())parts.add(database);else if(!schema.isEmpty())parts.add(schema);}
        else {if(!schema.isEmpty())parts.add(schema);}
        parts.add(name);
        String q=quote.trim(),end=q.equals("[")?"]":q;
        String target=String.join(".",parts.stream().map(part->quoted(part,q,end)).toList());
        if(!Set.of("mysql","mariadb").contains(engine)&&database!=null&&!database.isEmpty()&&metadata.supportsCatalogsInDataManipulation()) {
            String catalog=quoted(database,q,end),separator=metadata.getCatalogSeparator();
            if(separator==null||!Set.of(".",":").contains(separator))throw new SQLFeatureNotSupportedException("Unsupported JDBC catalog separator");
            target=metadata.isCatalogAtStart()?catalog+separator+target:target+separator+catalog;
        }
        return "SELECT * FROM "+target;
    }
    private static String quoted(String name,String start,String end) {
        if(name==null||name.isEmpty()||name.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid table identifier");
        return start+name.replace(end,end+end)+end;
    }
}
