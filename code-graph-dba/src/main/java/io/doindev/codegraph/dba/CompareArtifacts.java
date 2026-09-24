package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;

/** Private bounded scripts. Repeated downloads are allowed; disposal wins publication races. */
final class CompareArtifacts implements AutoCloseable {
    static final long FILE_LIMIT=64L<<20,TOTAL_LIMIT=128L<<20,TTL=600_000;
    static final int PREVIEW_LIMIT=1<<20;
    final Path directory;
    private final Predicate<String> alive;
    private final Map<String,Artifact> files=new HashMap<>();
    private long reserved;
    private boolean closed;
    private static final class Artifact {
        final String id=UUID.randomUUID().toString(),owner,comparison;
        final Path partial,complete;
        long bytes,expires;int readers;boolean ready,disposed,writing=true;
        Artifact(Path directory,String owner,String comparison){this.owner=owner;this.comparison=comparison;partial=directory.resolve("cgraph-compare-"+id+".part");complete=directory.resolve("cgraph-compare-"+id+".sql");}
    }
    final class Draft implements AutoCloseable {
        final Artifact artifact;final Writer writer;private final GridExports.BoundedStream stream;
        boolean finished;
        Draft(Artifact artifact)throws IOException{this.artifact=artifact;stream=new GridExports.BoundedStream(Files.newOutputStream(artifact.partial,StandardOpenOption.CREATE_NEW),FILE_LIMIT);try{Profiles.protect(artifact.partial);}catch(IOException|RuntimeException failure){stream.close();throw failure;}writer=new BufferedWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8));}
        ObjectNode publish()throws IOException{
            writer.close();synchronized(CompareArtifacts.this){
                artifact.writing=false;
                if(closed||artifact.disposed||!alive.test(artifact.owner))throw new IllegalArgumentException("Comparison closed; script discarded");
                Files.move(artifact.partial,artifact.complete,StandardCopyOption.ATOMIC_MOVE);artifact.bytes=stream.size;artifact.ready=true;artifact.expires=System.currentTimeMillis()+TTL;finished=true;return describe(artifact);
            }
        }
        @Override public void close()throws IOException{try{writer.close();}finally{synchronized(CompareArtifacts.this){artifact.writing=false;if(!finished){artifact.disposed=true;delete(artifact);}}}}
    }
    CompareArtifacts(Path root,Predicate<String> alive)throws IOException{
        this.alive=alive;directory=root.resolve("compare-exports");Files.createDirectories(directory);
        if(Files.isSymbolicLink(directory))throw new IOException("Compare export directory must not be a symbolic link");Profiles.protect(directory);
        try(var paths=Files.newDirectoryStream(directory,"cgraph-compare-*")){for(Path path:paths)if(owned(path)&&!Files.isSymbolicLink(path)&&Files.isRegularFile(path)&&Files.getLastModifiedTime(path).toMillis()<System.currentTimeMillis()-TTL)Files.deleteIfExists(path);}
    }
    private boolean owned(Path path){return path.getParent().equals(directory)&&path.getFileName().toString().matches("cgraph-compare-[a-f0-9-]{36}\\.(part|sql)");}
    synchronized Draft create(String owner,String comparison)throws IOException{
        reap();if(closed||!alive.test(owner))throw new SecurityException("Comparison session expired");
        if(reserved+FILE_LIMIT>TOTAL_LIMIT)throw new IllegalArgumentException("Compare export allowance full (128 MiB); close another comparison");
        Artifact a=new Artifact(directory,owner,comparison);files.put(a.id,a);reserved+=FILE_LIMIT;
        try{return new Draft(a);}catch(IOException failure){a.writing=false;a.disposed=true;delete(a);throw failure;}
    }
    private synchronized Artifact acquire(String owner,String id){Artifact a=files.get(id);if(a==null||!a.owner.equals(owner)||!a.ready||a.disposed||a.expires<System.currentTimeMillis()||!alive.test(owner))throw new SecurityException("Script expired, was discarded, or belongs to another session");a.readers++;return a;}
    private synchronized void release(Artifact a)throws IOException{a.readers--;if(a.disposed)delete(a);}
    ObjectNode preview(String owner,String id)throws IOException{
        Artifact a=acquire(owner,id);try(InputStream in=Files.newInputStream(a.complete)){
            byte[] bytes=in.readNBytes(PREVIEW_LIMIT);int end=bytes.length;
            if(a.bytes>end&&end>0){int start=end-1;while(start>=0&&(bytes[start]&0xc0)==0x80)start--;if(start>=0){int first=bytes[start]&255;int required=first<128?1:first<224?2:first<240?3:4;if(end-start<required)end=start;}}
            return describe(a).put("sql",new String(bytes,0,end,StandardCharsets.UTF_8)).put("previewTruncated",a.bytes>end);
        }finally{release(a);}
    }
    void download(String owner,String id,HttpExchange x)throws IOException{
        Artifact a=acquire(owner,id);try(InputStream in=Files.newInputStream(a.complete)){
            x.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");x.getResponseHeaders().set("Content-Disposition","attachment; filename=\"database-compare.sql\"");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(200,a.bytes);
            byte[] buffer=new byte[16384];int count;while((count=in.read(buffer))!=-1){synchronized(this){if(a.disposed||!alive.test(owner))throw new IOException("Comparison closed");}x.getResponseBody().write(buffer,0,count);}
        }finally{release(a);}
    }
    synchronized void remove(String owner,String id)throws IOException{Artifact a=files.get(id);if(a==null)return;if(!a.owner.equals(owner))throw new SecurityException("Script belongs to another session");a.disposed=true;delete(a);}
    synchronized void discard(String owner,String comparison){for(Artifact a:new ArrayList<>(files.values()))if(a.owner.equals(owner)&&a.comparison.equals(comparison)){a.disposed=true;try{delete(a);}catch(IOException ignored){}}}
    private void delete(Artifact a)throws IOException{if(a.readers>0||a.writing)return;Files.deleteIfExists(a.partial);Files.deleteIfExists(a.complete);if(files.remove(a.id)!=null)reserved-=FILE_LIMIT;}
    synchronized void reap(){for(Artifact a:new ArrayList<>(files.values()))if(a.disposed||!alive.test(a.owner)||a.ready&&a.expires<System.currentTimeMillis()){a.disposed=true;try{delete(a);}catch(IOException ignored){}}}
    synchronized ObjectNode telemetry(){return Profiles.JSON.createObjectNode().put("retainedFiles",files.size()).put("reservedDiskBytes",reserved).put("maximumDiskBytes",TOTAL_LIMIT).put("maximumFileBytes",FILE_LIMIT);}
    private ObjectNode describe(Artifact a){return Profiles.JSON.createObjectNode().put("artifactId",a.id).put("bytes",a.bytes).put("expiresAt",a.expires).put("complete",true);}
    @Override public synchronized void close(){closed=true;for(Artifact a:new ArrayList<>(files.values())){a.disposed=true;try{delete(a);}catch(IOException ignored){}}}
}
