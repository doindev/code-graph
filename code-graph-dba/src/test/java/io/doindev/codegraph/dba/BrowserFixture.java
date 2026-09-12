package io.doindev.codegraph.dba;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Disposable QA server, not an application entry point. Stops automatically or via test-only endpoint. */
public final class BrowserFixture {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("code-graph-dba-browser-test-");var stop=new CountDownLatch(1);
        var vault=new DbaTest.MemoryVault();String schema="";java.sql.Connection fixture=null;
        if(System.getenv("DBA_TEST_DISPOSABLE")!=null&&System.getenv("DBA_TEST_DISPOSABLE").matches("code-graph-dba-test-[a-f0-9]+")){
            schema="browser_"+java.util.UUID.randomUUID().toString().replace("-","");
            fixture=java.sql.DriverManager.getConnection(System.getenv("DBA_TEST_URL"),"postgres",System.getenv("DBA_TEST_PASSWORD"));
            try(var s=fixture.createStatement()){s.execute("CREATE SCHEMA "+schema);s.execute("CREATE TABLE "+schema+".items AS SELECT n AS id FROM generate_series(1,1000) n");s.execute("CREATE TABLE "+schema+".menu_target(id int)");s.execute("CREATE VIEW "+schema+".menu_view AS SELECT * FROM "+schema+".menu_target");}
            try(var profiles=new Profiles(root,vault)){profiles.put(null,Profiles.JSON.createObjectNode().put("name","Browser PostgreSQL").put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()).put("url",System.getenv("DBA_TEST_URL")).put("username","postgres").put("password",System.getenv("DBA_TEST_PASSWORD")));}
        }
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        try(var runtime=new DbaRuntime(new DbaConfig(root,64L<<20,2,10,5,10),vault)){
            server.createContext("/",runtime::handle);
            server.createContext("/__test/stop",x->{x.sendResponseHeaders(204,-1);x.close();stop.countDown();});server.start();
            System.out.println("DBA_FIXTURE="+Profiles.JSON.createObjectNode().put("base","http://localhost:"+server.getAddress().getPort()).put("schema",schema).put("jar",Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()));
            stop.await(5,TimeUnit.MINUTES);
        }finally{server.stop(0);if(fixture!=null){try(var s=fixture.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}finally{fixture.close();}}try(var files=Files.walk(root)){for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
