package io.doindev.codegraph.dba;

import java.util.*;

/** Small duplicate-execution tombstones, independent of result retention. Never evict live-session protection. */
final class RequestLedger {
    private record Entry(String principal,String session,String hash,String id){}
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private String key(String principal,String session,String request){return CatalogScanner.hash(principal+"\n"+session+"\n"+request);}
    synchronized String lookup(String principal,String session,String request,String hash){
        Entry entry=entries.get(key(principal,session,request));if(entry==null)return null;
        if(!entry.hash.equals(hash))throw new IllegalArgumentException("Request ID was already used for different content");
        return entry.id;
    }
    synchronized void reserve(String principal,String session,String request,String hash,String id){
        if(entries.size()>=2048)throw new IllegalArgumentException("Session duplicate-protection capacity reached; no operation was executed. End the MCP session before starting a new workflow");
        entries.put(key(principal,session,request),new Entry(principal,session,hash,id));
    }
    synchronized void reap(McpSessions sessions){entries.values().removeIf(e->e.session!=null&&!sessions.alive(e.session,e.principal));}
    synchronized void clear(){entries.clear();}
}
