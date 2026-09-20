package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise real export files/limits with a streaming JDBC fixture, not a retained million-row array. */
class GridExportStressTest {
    @TempDir Path root;
    @Test void millionRowAndFileBoundariesCleanIncompleteArtifacts()throws Exception {
        var fixture=new GridSafetyTest();fixture.root=root;
        try(var f=fixture.new Fixture()){
            var grid=f.query("SELECT * FROM SAFE_GRID").path("grid");
            var context=f.grids.require("human",grid.path("id").asText());
            var request=Profiles.JSON.createObjectNode().put("scope","query").put("format","csv");
            request.putArray("columns").add("c2");
            var job=f.jobs.new Job("human",f.id);
            var complete=f.grids.exports.create(context,job,stream(f.c,1_000_000,"x"),request);
            assertEquals(1_000_000,complete.path("rows").asInt());
            f.grids.exports.remove("human",complete.path("exportId").asText());
            assertThrows(IllegalArgumentException.class,()->f.grids.exports.create(context,job,stream(f.c,1_000_001,"x"),request));
            assertClean(f.grids);
            // 65 one-MiB scalar rows exceed the physical file bound, not the row bound.
            assertThrows(java.io.IOException.class,()->f.grids.exports.create(context,job,stream(f.c,65,"x".repeat(1<<20)),request));
            assertClean(f.grids);
            job.cancelled=true;
            assertThrows(java.util.concurrent.CancellationException.class,()->f.grids.exports.create(context,job,stream(f.c,1,"x"),request));
            assertClean(f.grids);
        }
    }
    @Test void restartCleanupRemovesOnlyOwnedExpiredArtifacts()throws Exception {
        try(var ignored=new Profiles(root,new DbaTest.MemoryVault())){} // Initialize the owned data directory.
        Path directory=Files.createDirectories(root.resolve("grid-exports"));
        Path expired=Files.writeString(directory.resolve("cgraph-grid-"+UUID.randomUUID()+".part"),"partial");
        Path recent=Files.writeString(directory.resolve("cgraph-grid-"+UUID.randomUUID()+".csv"),"recent");
        Path foreign=Files.writeString(directory.resolve("user-export.csv"),"preserve");
        Files.setLastModifiedTime(expired,FileTime.fromMillis(System.currentTimeMillis()-GridExports.TTL-1000));
        Files.setLastModifiedTime(foreign,FileTime.fromMillis(0));
        var fixture=new GridSafetyTest();fixture.root=root;
        try(var f=fixture.new Fixture()){
            assertFalse(Files.exists(expired));assertTrue(Files.exists(recent));assertTrue(Files.exists(foreign));
        }
    }
    private void assertClean(GridResults grids)throws Exception{
        assertEquals(0,grids.exports.telemetry().path("reservedDiskBytes").asLong());
        try(var files=Files.list(root.resolve("grid-exports"))){assertEquals(0,files.count());}
    }
    private static Connection stream(Connection delegate,int count,String value)throws Exception{
        ResultSetMetaData metadata=(ResultSetMetaData)Proxy.newProxyInstance(GridExportStressTest.class.getClassLoader(),new Class[]{ResultSetMetaData.class},(p,m,a)->switch(m.getName()){
            case "getColumnCount"->3;
            case "getColumnType"->Types.VARCHAR;
            default->throw new UnsupportedOperationException(m.getName());
        });
        int[] row={0};
        ResultSet rows=(ResultSet)Proxy.newProxyInstance(GridExportStressTest.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()){
            case "next"->++row[0]<=count;
            case "getMetaData"->metadata;
            case "getCharacterStream"->new java.io.StringReader(value);
            case "getString"->value;
            case "close"->null;
            default->throw new UnsupportedOperationException(m.getName());
        });
        PreparedStatement statement=(PreparedStatement)Proxy.newProxyInstance(GridExportStressTest.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->switch(m.getName()){
            case "executeQuery"->rows;
            case "setQueryTimeout","setFetchSize","close"->null;
            default->throw new UnsupportedOperationException(m.getName());
        });
        return (Connection)Proxy.newProxyInstance(GridExportStressTest.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("prepareStatement"))return statement;
            if(m.getName().equals("rollback"))return null;
            try{return m.invoke(delegate,a);}catch(InvocationTargetException e){throw e.getCause();}
        });
    }
}
