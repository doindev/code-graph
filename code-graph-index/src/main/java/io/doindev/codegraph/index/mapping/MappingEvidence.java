package io.doindev.codegraph.index.mapping;

import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.SourceFile;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** File-local bounded metadata. No SQL text, literal values, database observations or credentials. */
final class MappingEvidence {
    static final int MAX_RECORDS=512, MAX_CHARS=128*1024;
    final SourceFile source;
    final List<Node> nodes=new ArrayList<>();
    final List<Edge> edges=new ArrayList<>();
    private final Set<String> keys=new HashSet<>();
    private int chars;
    private boolean limited;
    MappingEvidence(SourceFile source){this.source=source;}
    void add(int offset,String framework,String schema,String table,String column,String relation,double confidence,Map<String,String> extra){
        if(limited)return;
        if(nodes.size()>=MAX_RECORDS||chars>MAX_CHARS){limited=true;return;}
        var attrs=new TreeMap<String,String>();
        attrs.put("framework",framework);attrs.put("schema",clean(schema));attrs.put("table",clean(table));
        attrs.put("column",clean(column));attrs.put("relationship",relation);attrs.put("confidence",String.valueOf(confidence));
        attrs.put("coverage","Static evidence only; unresolved runtime SQL/naming/reflection may be absent");
        extra.forEach((key,value)->attrs.put(key,clean(value)));
        int position=Math.max(0,Math.min(offset,source.content().length()));int line=1,col=1;
        for(int i=0;i<position;i++){if(source.content().charAt(i)=='\n'){line++;col=1;}else col++;}
        String identity=position+"/"+attrs;
        if(!keys.add(identity))return;
        chars+=identity.length();
        String name=column==null||column.isBlank()?table:column;if(name==null||name.isBlank())name="unresolved database mapping";
        var id=new SymbolId("dbmap",source.relPath(),"mapping."+hash(identity),0);
        var node=new Node(id,NodeKind.DATABASE_MAPPING,name,name,new SourceSpan(source.relPath(),line,col,line,col),Metrics.NONE,attrs);
        nodes.add(node);edges.add(new Edge(new FileId(source.relPath()),id,EdgeKind.CONTAINS));
    }
    void uncertain(int offset,String framework,String reason){add(offset,framework,"","","","unresolved",0,Map.of("uncertainty",reason));}
    void finish(){if(limited){limited=false;nodes.add(new Node(new SymbolId("dbmap",source.relPath(),"mapping.limit",0),NodeKind.DATABASE_MAPPING,"mapping limit","mapping limit",new SourceSpan(source.relPath(),1,1,1,1),Metrics.NONE,Map.of("relationship","unresolved","confidence","0","uncertainty","Mapping inventory exceeded file-local 512 record / 128 KiB bound")));}}
    static String clean(String value){if(value==null)return "";return value.length()>512?value.substring(0,512):value;}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,24);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
