package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ObjectEditorMetadataTest {
    @TempDir Path root;
    static Object invoke(Object target,Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    static ResultSet oversizedTypes(AtomicInteger reads,AtomicInteger closed){
        var metadata=(ResultSetMetaData)Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(),new Class<?>[]{ResultSetMetaData.class},(p,m,a)->switch(m.getName()){
            case "getColumnCount"->1;case "getColumnType"->Types.VARCHAR;case "getColumnLabel"->"TYPE_NAME";default->throw new UnsupportedOperationException(m.getName());
        });
        return (ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->switch(m.getName()){
            case "getMetaData"->metadata;case "next"->reads.incrementAndGet()<=100;case "getCharacterStream"->new StringReader("t".repeat(16384));case "close"->{closed.incrementAndGet();yield null;}default->throw new UnsupportedOperationException(m.getName());
        });
    }
    static Connection observed(Connection c,AtomicInteger calls,AtomicInteger reads,AtomicInteger closed){
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("getMetaData"))return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(p2,m2,a2)->{
                if(m2.getName().equals("getTypeInfo")){calls.incrementAndGet();return oversizedTypes(reads,closed);}
                return invoke(c.getMetaData(),m2,a2);
            });
            return invoke(c,m,a);
        });
    }
    @Test void creationEditorsSkipUnrelatedDatatypesAndKeepOversizedHintsNonfatal()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,30),o->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();try(var c=connections.open(id)){
                var job=jobs.new Job("human",id);job.beginActiveBudget(30);
                for(String kind:List.of("schemas","views","indexes","types","roles","extensions","table_constraints","table_foreign_keys","table_triggers","sequences","domains")){
                    var calls=new AtomicInteger();var reads=new AtomicInteger();var closed=new AtomicInteger();
                    var snapshot=ObjectDesigner.load(job,observed(c,calls,reads,closed),ObjectDesignerTest.newObject(kind,"PUBLIC"),false);
                    assertTrue(snapshot.path("fields").has("name"),kind);assertFalse(snapshot.path("details").has("Datatypes"),kind);
                    if(List.of("sequences","domains").contains(kind)){
                        assertEquals(1,calls.get(),kind);assertEquals(1,closed.get(),kind);assertTrue(reads.get()<10,"Hints must stop reading at their small allowance");
                        assertTrue(snapshot.path("warnings").toString().contains("Optional suggestions omitted"),snapshot.toString());
                        assertTrue(snapshot.path("controls").toString().contains("Datatype")||snapshot.path("controls").toString().contains("Base datatype"));
                    }else assertEquals(0,calls.get(),"Unrelated editor fetched driver datatypes: "+kind);
                }
                var snapshot=ObjectDesigner.load(job,c,ObjectDesignerTest.newObject("sequences","PUBLIC"),false);
                assertTrue(snapshot.path("details").path("Datatypes").isArray(),"Small datatype reference catalogs remain available");
            }
        }
    }
    @Test void otherDatabaseAdaptersUseTheSameCapabilityScopedHintPolicy()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,30),o->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();try(var c=connections.open(id)){
                var job=jobs.new Job("human",id);job.beginActiveBudget(30);
                // Driver-contract coverage; these are not live installations of the named servers.
                for(String product:List.of("Oracle","Microsoft SQL Server","DB2","Snowflake","HSQL Database Engine","SQLite","DuckDB","Other JDBC database")){
                    var wrapped=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                        if(m.getName().equals("getMetaData"))return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(p2,m2,a2)->{
                            if(m2.getName().equals("getDatabaseProductName"))return product;
                            if(m2.getName().equals("getTypeInfo"))throw new AssertionError(product+" loaded unrelated datatype metadata");
                            return invoke(c.getMetaData(),m2,a2);
                        });return invoke(c,m,a);
                    });
                    for(String kind:List.of("schemas","sequences","views","indexes","functions","procedures","types","domains","roles","table_columns","table_constraints","table_foreign_keys","table_triggers")){
                        var snapshot=ObjectDesigner.load(job,wrapped,ObjectDesignerTest.newObject(kind,"PUBLIC"),false);
                        assertEquals(VendorMetadata.engine(product),snapshot.path("engine").asText());assertTrue(snapshot.path("controls").size()>0,product+" "+kind);assertFalse(snapshot.path("details").has("Datatypes"),product+" "+kind);
                    }
                    // Any adapter which exposes editable datatypes gets bounded, non-fatal hints.
                    var out=Profiles.JSON.createObjectNode().put("engine",VendorMetadata.engine(product));out.putObject("choices");out.putObject("details");out.putArray("categories");out.putArray("warnings");out.putArray("controls").addObject().put("id","type").put("editable",true);
                    var reads=new AtomicInteger();var closed=new AtomicInteger();ObjectCatalog.editorHints(job,observed(c,new AtomicInteger(),reads,closed),out);
                    assertFalse(out.path("details").has("Datatypes"));assertTrue(out.path("warnings").toString().contains("Optional suggestions omitted"),product);assertEquals(1,closed.get(),product);
                }
            }
        }
    }
    @Test void oversizedRoleSuggestionsAndCombinedSnapshotBudgetPreserveTheEditor()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,30),o->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();try(var c=connections.open(id);var st=c.createStatement()){
                c.setAutoCommit(true);st.execute("CREATE TABLE PG_ROLES AS SELECT 'role_' || X AS ROLNAME FROM SYSTEM_RANGE(1,201)");
                var job=jobs.new Job("human",id);job.beginActiveBudget(30);
                var out=Profiles.JSON.createObjectNode().put("engine","postgresql").put("ddl","preserved definition");out.putObject("choices");out.putArray("warnings");out.putArray("controls").addObject().put("id","owner").put("editable",true);
                ObjectCatalog.editorHints(job,c,out);assertEquals("preserved definition",out.path("ddl").asText());assertTrue(out.path("choices").isEmpty());assertFalse(out.path("controls").get(0).has("choices"));assertTrue(out.path("warnings").toString().contains("200 entries"));assertNull(job.statement);
                out=Profiles.JSON.createObjectNode().put("engine","h2").put("ddl","x".repeat((1<<20)-10000));out.putObject("choices");out.putObject("details");out.putArray("categories");out.putArray("warnings");out.putArray("controls").addObject().put("id","type").put("editable",true);
                ObjectCatalog.editorHints(job,c,out);assertEquals((1<<20)-10000,out.path("ddl").asText().length());assertFalse(out.path("details").has("Datatypes"));assertTrue(out.path("warnings").toString().contains("remaining editor budget"));assertTrue(Profiles.JSON.writeValueAsBytes(out).length<1<<20);
            }
        }
    }
    @Test void requiredDefinitionLimitsAndCancellationStillFailAndReleaseResources()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,30),o->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();try(var c=connections.open(id)){
                var job=jobs.new Job("human",id);job.beginActiveBudget(30);var out=Profiles.JSON.createObjectNode();out.putArray("warnings");
                var failure=assertThrows(ObjectCatalog.MetadataLimitException.class,()->ObjectCatalog.optional(c,out,"Required definition",()->ObjectCatalog.query(job,c,"SELECT REPEAT('x',70000) AS definition")));
                assertTrue(failure.getMessage().contains("Required definition: A definition exceeds the 64 KiB"));assertTrue(out.path("warnings").isEmpty());assertNull(job.statement);
                var reads=new AtomicInteger();var closed=new AtomicInteger();try(var rs=oversizedTypes(reads,closed)){assertThrows(ObjectCatalog.MetadataLimitException.class,()->ObjectCatalog.rows(job,rs));}assertEquals(1,closed.get());
                job.cancelled=true;closed.set(0);reads.set(0);
                assertThrows(CancellationException.class,()->ObjectDesigner.load(job,observed(c,new AtomicInteger(),reads,closed),ObjectDesignerTest.newObject("sequences","PUBLIC"),false));assertEquals(1,closed.get());
            }
        }
    }
}
