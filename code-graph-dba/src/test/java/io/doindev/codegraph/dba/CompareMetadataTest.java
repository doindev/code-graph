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
    @Test void comparisonTableCaptureBypassesColumnAndFieldEditorLimits()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,30),owner->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();try(Connection c=connections.open(id);Statement st=c.createStatement()){
                c.setAutoCommit(true);String columns=java.util.stream.IntStream.range(0,300).mapToObj(i->"C"+i+" INT").collect(java.util.stream.Collectors.joining(","));
                st.execute("CREATE SCHEMA WIDE_META");st.execute("CREATE TABLE WIDE_META.WIDE("+columns+")");st.execute("COMMENT ON TABLE WIDE_META.WIDE IS '"+"x".repeat(12000)+"'");
                var job=jobs.new Job("human",id);job.beginActiveBudget(30);var target=new CompareCatalog.Target(id,c.getCatalog(),"WIDE_META",false,"");
                var inventory=CompareCatalog.capture(job,withoutEditorCatalogs(c),target,Set.of("tables"),false);
                var table=inventory.objects.get(CompareCatalog.key("WIDE_META","tables","WIDE"));assertTrue(table.path("supported").asBoolean(),table.toString());assertEquals(300,table.path("columns").size());
                var nodes=CompareCatalog.pages(job,c,"tables",c.getCatalog(),"WIDE_META");var selection=Profiles.JSON.createObjectNode();selection.putObject("parent").put("kind","tables").put("schema","WIDE_META");
                assertThrows(IllegalArgumentException.class,()->TableDesigner.loadCatalogObject(job,c,selection,nodes.get(0)));
                assertEquals(12000,CompareTableMetadata.capture(job,c,selection,nodes.get(0)).path("fields").path("comment").asText().length());
            }
        }
    }
    @Test void optionalBudgetIsCapturedByJobsAndInheritedByParallelReaders()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,192L<<20,2,100,100,30),owner->true)){
            String a=profiles.put(null,new DbaTest().input().put("name","Source")).path("id").asText(),b=profiles.put(null,new DbaTest().input().put("name","Destination")).path("id").asText();
            jobs.comparisonMetadataBytes(1L<<20);var old=jobs.new Job("human",a);old.beginActiveBudget(30);jobs.comparisonMetadataBytes(4L<<20);
            try(Connection c=connections.open(a)){
                assertThrows(CompareCatalog.MetadataLimitException.class,()->CompareCatalog.query(old,c,"SELECT REPEAT('x',600000) AS definition"));assertNull(old.statement);
                var fresh=jobs.new Job("human",a);fresh.beginActiveBudget(30);assertEquals(600000,CompareCatalog.query(fresh,c,"SELECT REPEAT('x',600000) AS definition").get(0).path("definition").asText().length());
                old.cancelled=true;assertThrows(java.util.concurrent.CancellationException.class,()->CompareCatalog.query(old,c,"SELECT REPEAT('x',600000) AS definition"));assertNull(old.statement);
            }
            var parent=jobs.new Job("human",a);parent.beginActiveBudget(30);jobs.comparisonMetadataBytes(8L<<20);
            var pair=jobs.comparisonReads(parent,a,b,child->child.comparisonMetadataBytes,child->child.comparisonMetadataBytes);assertEquals(4L<<20,pair.source());assertEquals(4L<<20,pair.destination());
        }
    }

}
