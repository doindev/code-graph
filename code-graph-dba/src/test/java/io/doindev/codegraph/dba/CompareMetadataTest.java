package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.lang.reflect.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CompareMetadataTest {
    @TempDir Path root;
    static Connection withoutEditorCatalogs(Connection connection){
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("getMetaData"))return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(ignored,metadataMethod,metadataArgs)->{
                if(metadataMethod.getName().equals("getTypeInfo"))throw new AssertionError("Comparison must not load the JDBC datatype catalog");
                try{return metadataMethod.invoke(connection.getMetaData(),metadataArgs);}catch(InvocationTargetException e){throw e.getCause();}
            });
            if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql){
                if(sql.startsWith("SELECT nspname AS name")||sql.startsWith("SELECT rolname AS name")||sql.startsWith("SELECT lanname AS name")||sql.startsWith("SELECT spcname AS name")||sql.startsWith("SELECT amname AS name")||sql.startsWith("SELECT pg_describe_object"))throw new AssertionError("Comparison requested editor-only metadata: "+sql);
            }
            try{return method.invoke(connection,args);}catch(InvocationTargetException e){throw e.getCause();}
        });
    }
    @Test void comparisonDefinitionsSkipEditorCatalogsAndAcceptLargerNativeSql()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,30),owner->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(Connection c=connections.open(id);Statement st=c.createStatement()){
                c.setAutoCommit(true);st.execute("CREATE SCHEMA CMP_META");st.execute("CREATE VIEW CMP_META.LARGE_V AS SELECT '"+"x".repeat(70000)+"' AS NOTE");st.execute("CREATE SEQUENCE CMP_META.COUNTER START WITH 17 INCREMENT BY 3");
                var job=jobs.new Job("human",id);job.beginActiveBudget(30);
                var target=new CompareCatalog.Target(id,c.getCatalog(),"CMP_META",false,"");
                var inventory=CompareCatalog.capture(job,withoutEditorCatalogs(c),target,Set.of("views","sequences"),false);
                var view=inventory.objects.get(CompareCatalog.key("CMP_META","views","LARGE_V"));assertTrue(view.path("supported").asBoolean(),view.toString());assertTrue(view.path("ddl").asText().length()>65536);
                var sequence=inventory.objects.get(CompareCatalog.key("CMP_META","sequences","COUNTER"));assertTrue(sequence.path("supported").asBoolean(),sequence.toString());assertEquals("17",sequence.path("fields").path("start").asText());
                var nodes=CompareCatalog.pages(job,c,"sequences",c.getCatalog(),"CMP_META");var selection=Profiles.JSON.createObjectNode();selection.putObject("parent").put("kind","sequences").put("schema","CMP_META");
                var evidence=ObjectCatalog.comparison(job,withoutEditorCatalogs(c),"h2",selection,nodes.get(0));assertFalse(evidence.has("choices"));assertFalse(evidence.has("controls"));assertFalse(evidence.path("details").has("Datatypes"));
                var failure=assertThrows(IllegalArgumentException.class,()->ObjectCatalog.query(job,c,"SELECT REPEAT('x',70000) AS definition"));assertTrue(failure.getMessage().contains("64 KiB editor limit"));assertNull(job.statement);
            }
        }
    }
    @Test void comparisonRetainsItsOwnBoundAndClosesRejectedMetadataQueries()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,30),owner->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();try(Connection c=connections.open(id)){
                var job=jobs.new Job("human",id);job.beginActiveBudget(30);
                var failure=assertThrows(IllegalArgumentException.class,()->CompareCatalog.query(job,c,"SELECT REPEAT('x',9000000) AS definition"));assertTrue(failure.getMessage().contains("16 MiB"));assertNull(job.statement);
                assertEquals("1",CompareCatalog.query(job,c,"SELECT 1 AS ok").get(0).path("ok").asText());
            }
        }
    }
}
