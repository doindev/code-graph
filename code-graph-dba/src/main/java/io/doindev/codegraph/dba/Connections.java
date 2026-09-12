package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.*;
import javax.sql.DataSource;
import java.sql.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.util.logging.Logger;

/** Lazy pools with no minimum idle connections. Credentials are never included in errors. */
final class Connections implements AutoCloseable {
    private record Pool(HikariDataSource source,JarLoader loader,long opened) implements AutoCloseable {
        public void close(){source.close();try{loader.loadClass(DriverCleanup.class.getName()).getMethod("deregister").invoke(null);}catch(Exception ignored){}try{loader.close();}catch(IOException ignored){}}
    }
    private final Profiles profiles;
    private final Map<String,Pool> pools=new HashMap<>();
    Connections(Profiles profiles){this.profiles=profiles;}
    boolean genericOnly(String id){return !Set.of("custom","postgresql","mysql","mariadb","sqlserver","oracle","db2","snowflake","h2","hsqldb","sqlite","duckdb").contains(profiles.get(id).path("templateId").asText("custom"));}
    synchronized Connection open(String id) throws SQLException {
        Pool p=pools.get(id);
        if(p==null){
            ObjectNode profile=profiles.get(id);JarLoader loader=null;
            try{
                loader=loader(profile);
                Driver driver=(Driver)Class.forName(profile.path("driverClass").asText(),true,loader).getDeclaredConstructor().newInstance();
                String url=Profiles.expand(profile.path("url").asText());
                if(!driver.acceptsURL(url))throw new SQLException("Driver does not accept URL");
                HikariConfig cfg=new HikariConfig();cfg.setDataSource(new Source(driver,url,profiles,profile));
                cfg.setMaximumPoolSize(2);cfg.setMinimumIdle(0);cfg.setIdleTimeout(60_000);cfg.setMaxLifetime(300_000);
                cfg.setConnectionTimeout(10_000);cfg.setValidationTimeout(3000);cfg.setInitializationFailTimeout(-1);
                var settings=profile.path("pool");
                if(settings.has("maximumPoolSize"))cfg.setMaximumPoolSize(settings.path("maximumPoolSize").asInt());
                if(settings.has("minimumIdle"))cfg.setMinimumIdle(settings.path("minimumIdle").asInt());
                if(settings.has("connectionTimeout"))cfg.setConnectionTimeout(settings.path("connectionTimeout").asLong());
                if(settings.has("validationTimeout"))cfg.setValidationTimeout(settings.path("validationTimeout").asLong());
                if(settings.has("idleTimeout"))cfg.setIdleTimeout(settings.path("idleTimeout").asLong());
                if(settings.has("maxLifetime"))cfg.setMaxLifetime(settings.path("maxLifetime").asLong());
                cfg.setPoolName("dba-"+id);cfg.setAutoCommit(false);cfg.setReadOnly(!url.startsWith("jdbc:sqlite:")&&!url.startsWith("jdbc:duckdb:")&&profile.path("readOnly").asBoolean(true));
                p=new Pool(new HikariDataSource(cfg),loader,System.currentTimeMillis());pools.put(id,p);
            }catch(Exception e){if(loader!=null)try{loader.close();}catch(IOException ignored){}throw new SQLException("Could not initialize JDBC driver or vault credentials",e);}
        }
        return p.source.getConnection();
    }
    synchronized void remove(String id){Pool p=pools.remove(id);if(p!=null)p.close();}
    synchronized ClassLoader driverLoader(String id){Pool pool=pools.get(id);return pool==null?getClass().getClassLoader():pool.loader;}
    synchronized boolean connected(String id){Pool p=pools.get(id);return p!=null&&!p.source.isClosed()&&p.source.getHikariPoolMXBean().getTotalConnections()>0;}
    record DatabaseConnection(Connection connection,JarLoader loader) implements AutoCloseable {
        public void close()throws Exception{try{connection.close();}finally{loader.close();}}
    }
    // PostgreSQL catalogs belong to one database. Browse another database using a short-lived
    // connection to the same server, without changing the saved profile or creating another pool.
    DatabaseConnection openDatabase(String id,String database,int timeout)throws Exception{
        ObjectNode profile=profiles.get(id);String url=postgresDatabaseUrl(Profiles.expand(profile.path("url").asText()),database);
        JarLoader loader=loader(profile);Properties properties=null;
        try{properties=profiles.credentials(profile);properties.setProperty("PGDBNAME",database);properties.setProperty("loginTimeout",String.valueOf(Math.min(10,timeout)));properties.setProperty("socketTimeout",String.valueOf(timeout));Connection c=driver(loader,profile).connect(url,properties);if(c==null)throw new SQLException("Driver rejected PostgreSQL database URL");return new DatabaseConnection(c,loader);}
        catch(Exception e){loader.close();throw e;}finally{if(properties!=null)properties.clear();}
    }
    static String postgresDatabaseUrl(String url,String database){
        if(!url.startsWith("jdbc:postgresql:")||database==null||database.isEmpty()||database.length()>128)throw new IllegalArgumentException("PostgreSQL database browsing requires a PostgreSQL JDBC URL and database name");
        int query=url.indexOf('?');String base=query<0?url:url.substring(0,query),suffix=query<0?"":url.substring(query);
        if(suffix.matches("(?is).*[?&]PGDBNAME=.*"))throw new IllegalArgumentException("Remove PGDBNAME from URL query parameters to browse other databases");
        String name=java.net.URLEncoder.encode(database,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
        if(base.startsWith("jdbc:postgresql://")){int slash=base.indexOf('/',"jdbc:postgresql://".length());return (slash<0?base+"/":base.substring(0,slash+1))+name+suffix;}
        return "jdbc:postgresql:"+name+suffix;
    }
    // Human SQL can change arbitrary session state. Never return its physical session to a pool.
    synchronized void discard(String id,Connection connection)throws SQLException{Pool p=pools.get(id);if(p!=null)p.source.evictConnection(connection);else connection.close();}
    String humanError(String id,Exception error){return profiles.redactError(id,HumanSql.failure(error));}
    synchronized int count(){return pools.size();}
    synchronized void reap(){long now=System.currentTimeMillis();var it=pools.entrySet().iterator();while(it.hasNext()){Pool p=it.next().getValue();if(now-p.opened>300_000&&p.source.getHikariPoolMXBean().getActiveConnections()==0){p.close();it.remove();}}}
    public synchronized void close(){pools.values().forEach(Pool::close);pools.clear();}
    static final class JarLoader extends URLClassLoader {
        private final Set<Closeable> resourceStreams=java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.CopyOnWriteArrayList<Class<?>> db2Classes=new java.util.concurrent.CopyOnWriteArrayList<>();
        JarLoader(URL jar){super(new URL[]{jar},ClassLoader.getPlatformClassLoader());}
        JarLoader(URL[] jars){super(jars,ClassLoader.getPlatformClassLoader());}
        @Override public void close()throws IOException{
            ClassLoader prior=Thread.currentThread().getContextClassLoader();Thread.currentThread().setContextClassLoader(this);
            try{
                // These loaders are never shared with another pool. Shut down MySQL's owned cleanup worker.
                Class<?> mysql=findLoadedClass("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread");if(mysql!=null)try{mysql.getMethod("checkedShutdown").invoke(null);}catch(ReflectiveOperationException ignored){}
                // JCC owns static housekeeping timers but exposes no shutdown API. Cancel only timers
                // held by this isolated JCC loader; never enumerate/interrupt arbitrary JVM threads.
                for(Class<?> type:db2Classes)for(var field:type.getDeclaredFields())if(java.lang.reflect.Modifier.isStatic(field.getModifiers())&&java.util.Timer.class.isAssignableFrom(field.getType()))try{if(field.trySetAccessible()){var timer=(java.util.Timer)field.get(null);if(timer!=null)timer.cancel();}}catch(ReflectiveOperationException ignored){}
                try{loadClass(DriverCleanup.class.getName()).getMethod("deregister").invoke(null);}catch(Exception ignored){}
                // Snowflake may register a shaded crypto provider globally. Never remove another owner's provider.
                for(var provider:java.security.Security.getProviders())if(provider.getClass().getClassLoader()==this)java.security.Security.removeProvider(provider.getName());
            }finally{Thread.currentThread().setContextClassLoader(prior);for(Closeable resource:resourceStreams)try{resource.close();}catch(IOException ignored){}resourceStreams.clear();super.close();}
        }
        @Override public URL findResource(String name){return uncached(super.findResource(name));}
        @Override public Enumeration<URL> findResources(String name)throws IOException{List<URL> result=new ArrayList<>();var resources=super.findResources(name);while(resources.hasMoreElements())result.add(uncached(resources.nextElement()));return Collections.enumeration(result);}
        private URL uncached(URL original){if(original==null||!original.getProtocol().equals("jar"))return original;try{return new URL(null,original.toString(),new URLStreamHandler(){protected URLConnection openConnection(URL url)throws IOException{URLConnection delegate=original.openConnection();delegate.setUseCaches(false);return new URLConnection(url){
            public void connect()throws IOException{delegate.connect();connected=true;}
            public InputStream getInputStream()throws IOException{InputStream stream=new FilterInputStream(delegate.getInputStream()){@Override public void close()throws IOException{try{super.close();}finally{resourceStreams.remove(this);}}};resourceStreams.add(stream);return stream;}
            public long getContentLengthLong(){return delegate.getContentLengthLong();}
            public String getContentType(){return delegate.getContentType();}
            public long getLastModified(){return delegate.getLastModified();}
            public java.security.Permission getPermission()throws IOException{return delegate.getPermission();}
        };}});}catch(MalformedURLException e){return original;}}
        protected Class<?> findClass(String name)throws ClassNotFoundException {
            if(name.equals(DriverCleanup.class.getName())){
                try(InputStream in=DriverCleanup.class.getResourceAsStream("DriverCleanup.class")){byte[] b=in.readAllBytes();return defineClass(name,b,0,b.length);}catch(IOException e){throw new ClassNotFoundException(name,e);}
            }
            Class<?> result=super.findClass(name);if(name.startsWith("com.ibm.db2.jcc."))db2Classes.add(result);return result;
        }
    }
    static JarLoader loader(ObjectNode profile)throws Exception {
        List<URL> urls=new ArrayList<>();if(profile.path("jars").isArray())for(var jar:profile.path("jars"))urls.add(Path.of(jar.asText()).toUri().toURL());
        else urls.add(Path.of(profile.path("jar").asText()).toUri().toURL());return new JarLoader(urls.toArray(URL[]::new));
    }
    static Driver driver(JarLoader loader,ObjectNode profile)throws Exception{return (Driver)Class.forName(profile.path("driverClass").asText(),true,loader).getDeclaredConstructor().newInstance();}
    private record Source(Driver driver,String url,Profiles store,ObjectNode profile) implements DataSource {
        @Override public String toString(){return "DBA isolated data source";}
        public Connection getConnection()throws SQLException {Properties p=new Properties();try{p=store.credentials(profile);Connection c=driver.connect(url,p);if(c==null)throw new SQLException("Driver rejected connection");return c;}catch(Exception e){throw new SQLException("JDBC connection or credential lookup failed",e);}finally{p.clear();}}
        public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("External credentials unsupported");}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter out){}public void setLoginTimeout(int seconds){}public int getLoginTimeout(){return 10;}
        public Logger getParentLogger(){return Logger.getLogger("code-graph.dba");}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Not a wrapper");}public boolean isWrapperFor(Class<?> type){return false;}
    }
}
