package io.doindev.codegraph.dba;

/** Child JVM so driver-global threads/native libraries/file handles die before cleanup. Never connects. */
public final class DriverProbe {
    public static void main(String[] args)throws Exception{
        var profile=Profiles.JSON.createObjectNode().put("driverClass",args[0]);var jars=profile.putArray("jars");for(int i=2;i<args.length;i++)jars.add(args[i]);
        try(var loader=Connections.loader(profile)){
            var driver=Connections.driver(loader,profile);if(!driver.acceptsURL(args[1]))throw new AssertionError("Template JDBC URL rejected");
            var properties=new java.util.Properties();properties.setProperty("user","driver-verification");driver.getPropertyInfo(args[1],properties);
        }
        System.exit(0);
    }
}
