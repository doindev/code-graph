package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Private external-sort snapshots for exact row review. No staging objects are created in either database. */
final class CompareData implements AutoCloseable {
    static final long LIMIT=128L<<20;static final int CHUNK=4<<20,ROW_LIMIT=1_000_000,LINE_LIMIT=2<<20;
    static final class Budget {
        private long used;final long maximum;
        Budget(long maximum){this.maximum=maximum;}
        synchronized void take(long size){if(size>maximum-used)throw new IllegalArgumentException("Comparison data allowance full; close another comparison");used+=size;}
        synchronized void release(long size){used-=size;}
        synchronized long used(){return used;}
    }
    final Path directory;final Budget budget;private long bytes;private int sequence;private boolean closed;
    final Map<String,Table> tables=new LinkedHashMap<>();
    static final class Table {
        final String id;final ObjectNode source,destination;final List<String> columns,key;final boolean keyed;
        Snapshot left,right;Path differences;ObjectNode counts=Profiles.JSON.createObjectNode();
        Table(String id,ObjectNode source,ObjectNode destination,List<String> columns,List<String> key){this.id=id;this.source=source;this.destination=destination;this.columns=columns;this.key=key;keyed=!key.isEmpty();}
        ObjectNode summary(){ObjectNode n=Profiles.JSON.createObjectNode().put("keyed",keyed);n.set("counts",counts);ArrayNode c=n.putArray("columns");columns.forEach(c::add);ArrayNode k=n.putArray("key");key.forEach(k::add);if(!keyed)n.put("note","No usable matching key. Replace all is available; matched-row differences cannot be established.");return n;}
    }
    record Snapshot(Path file,long rows,String fingerprint){}
    CompareData(Path root)throws IOException{this(root,new Budget(LIMIT));}
    CompareData(Path root,Budget budget)throws IOException{this.budget=budget;directory=Files.createTempDirectory(root,"cgraph-compare-data-");Profiles.protect(directory);}
    synchronized Path path(){return directory.resolve(Integer.toString(sequence++)+".jsonl");}
    synchronized void add(long size){if(bytes+size>LIMIT)throw new IllegalArgumentException("Comparison data exceeds 128 MiB; narrow the table selection");budget.take(size);bytes+=size;}
    void delete(Path path)throws IOException{long size=Files.exists(path)?Files.size(path):0;Files.deleteIfExists(path);synchronized(this){bytes-=size;budget.release(size);}}
    void line(Writer writer,String value)throws IOException{long length=value.getBytes(StandardCharsets.UTF_8).length+1;if(length>LINE_LIMIT)throw new IllegalArgumentException("A data row exceeds the 2 MiB comparison limit");add(length);writer.write(value);writer.write('\n');}
    Table table(CompareDiff.ObjectDiff object,JsonNode option){
        List<String> columns=new ArrayList<>();for(JsonNode col:object.source.path("columns"))if(str(col,"generated").isEmpty())columns.add(str(col,"name"));
        List<String> key=new ArrayList<>();String selected=option.path("key").asText("");
        for(JsonNode candidate:object.source.path("keys"))if(selected.isEmpty()||str(candidate,"name").equals(selected)){candidate.path("columns").forEach(n->key.add(n.asText()));break;}
        if(!selected.isEmpty()&&key.isEmpty())throw new IllegalArgumentException("Selected table key is unavailable");
        Table table=new Table(object.id,object.source,object.destination,List.copyOf(columns),List.copyOf(key));tables.put(table.id,table);return table;
    }
    Snapshot capture(QueryJobs.Job job,Connection c,String engine,Table table,boolean source)throws Exception{
        ObjectNode object=source?table.source:table.destination;Path output=path();if(object==null){Files.createFile(output);return new Snapshot(output,0,hash(""));}Profiles.protect(output.getParent());
        Map<String,JsonNode> available=CompareSql.byName(object.path("columns"));List<String> selected=table.columns.stream().filter(available::containsKey).toList();
        if(!available.keySet().containsAll(table.key))throw new IllegalArgumentException("Matching key is missing from the destination");
        for(String key:table.key){JsonNode col=available.get(key);String type=str(col,"type").toLowerCase(Locale.ROOT);
            if(type.contains("char")||type.contains("text")){
                if((type.startsWith("char(")||type.startsWith("character(")||type.equals("character")||type.equals("char")))throw new IllegalArgumentException("Fixed-width character matching keys require a padding-aware comparison adapter");
                if(Set.of("mysql","mariadb").contains(engine)&&!str(col,"collation").endsWith("_0900_bin"))throw new IllegalArgumentException("Text matching keys require a verified binary, non-padding collation");
                if(type.contains("ignorecase"))throw new IllegalArgumentException("Case-insensitive matching keys are unavailable");
            }}
        if(selected.isEmpty())throw new IllegalArgumentException("No comparable columns in "+str(object,"name"));
        List<Path> chunks=new ArrayList<>();List<String> buffer=new ArrayList<>();int buffered=0;long count=0;
        String sql="SELECT "+String.join(", ",selected.stream().map(n->CompareSql.q(engine,n)).toList())+" FROM "+CompareSql.qualified(engine,str(object,"schema"),str(object,"name"));
        try(Statement st=c.createStatement()){
            job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.setMaxRows(ROW_LIMIT+1);st.setFetchSize(128);
            try(ResultSet rs=st.executeQuery(sql)){ResultSetMetaData meta=rs.getMetaData();
                while(rs.next()){check(job);if(++count>ROW_LIMIT)throw new IllegalArgumentException("Table data exceeds 1,000,000 rows; narrow the selection");
                    ObjectNode row=Profiles.JSON.createObjectNode();ArrayNode values=row.putArray("values");
                    for(String name:table.columns){int index=selected.indexOf(name);if(index<0)values.addObject().put("type","missing").putNull("value");else values.add(cell(rs,index+1,meta.getColumnType(index+1),meta.getColumnTypeName(index+1),engine));}
                    ArrayNode keys=Profiles.JSON.createArrayNode();for(String name:table.key){JsonNode cell=values.get(table.columns.indexOf(name));if(cell.path("value").isNull())throw new IllegalArgumentException("Matching key contains null");keys.add(cell.path("value"));}
                    String key=table.keyed?keys.toString():values.toString();row.put("key",HexFormat.of().formatHex(key.getBytes(StandardCharsets.UTF_8)));
                    String encoded=row.toString();if(encoded.getBytes(StandardCharsets.UTF_8).length>LINE_LIMIT)throw new IllegalArgumentException("A data row exceeds 2 MiB");buffer.add(encoded);buffered+=encoded.length()*2+64;
                    if(buffered>=CHUNK){chunks.add(chunk(buffer));buffer.clear();buffered=0;}if(count%1000==0)job.comparisonProgress("Reading rows",source?"source":"destination",str(object,"schema")+"."+str(object,"name"),(int)count,0);
                }
            }
        }finally{job.statement=null;}
        if(!buffer.isEmpty())chunks.add(chunk(buffer));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");List<BufferedReader> readers=new ArrayList<>();
        record Head(String line,int reader,String key){}
        PriorityQueue<Head> queue=new PriorityQueue<>(Comparator.comparing(Head::key).thenComparing(Head::line));
        try(Writer writer=Files.newBufferedWriter(output,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
            Profiles.protect(output);for(Path chunk:chunks){BufferedReader reader=Files.newBufferedReader(chunk);readers.add(reader);String line=reader.readLine();if(line!=null)queue.add(new Head(line,readers.size()-1,str(Profiles.JSON.readTree(line),"key")));}
            String prior=null;while(!queue.isEmpty()){check(job);Head head=queue.remove();if(table.keyed&&head.key.equals(prior))throw new IllegalArgumentException("Matching key is not unique");prior=head.key;
                line(writer,head.line);digest.update((head.line+"\n").getBytes(StandardCharsets.UTF_8));String next=readers.get(head.reader).readLine();if(next!=null)queue.add(new Head(next,head.reader,str(Profiles.JSON.readTree(next),"key")));}
        }finally{for(Reader reader:readers)reader.close();for(Path chunk:chunks)delete(chunk);}
        return new Snapshot(output,count,HexFormat.of().formatHex(digest.digest()));
    }
    private Path chunk(List<String> rows)throws Exception{
        rows.sort(Comparator.comparing(line->{try{return str(Profiles.JSON.readTree(line),"key");}catch(IOException error){throw new IllegalArgumentException(error);}}));Path file=path();
        try(Writer writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){Profiles.protect(file);for(String row:rows)line(writer,row);}return file;
    }
    void compare(QueryJobs.Job job,Table table)throws Exception{
        table.differences=path();for(String status:List.of("source_only","different","destination_only","identical"))table.counts.put(status,0L);
        try(BufferedReader left=Files.newBufferedReader(table.left.file);BufferedReader right=Files.newBufferedReader(table.right.file);Writer out=Files.newBufferedWriter(table.differences,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
            Profiles.protect(table.differences);JsonNode a=read(left),b=read(right);
            while(a!=null||b!=null){check(job);int order=a==null?1:b==null?-1:str(a,"key").compareTo(str(b,"key"));
                String status;JsonNode source=null,destination=null;
                if(!table.keyed){if(a!=null){status="source_only";source=a;a=read(left);}else{status="destination_only";destination=b;b=read(right);}}
                else if(order<0){status="source_only";source=a;a=read(left);}else if(order>0){status="destination_only";destination=b;b=read(right);}
                else{source=a;destination=b;status=a.path("values").equals(b.path("values"))?"identical":"different";a=read(left);b=read(right);}
                table.counts.put(status,table.counts.path(status).asLong()+1);
                ObjectNode diff=Profiles.JSON.createObjectNode().put("status",status);if(source!=null)diff.set("source",source.path("values"));if(destination!=null)diff.set("destination",destination.path("values"));line(out,diff.toString());
            }
        }
    }
    ObjectNode page(String id,int offset,int limit,String status)throws Exception{
        Table table=tables.get(id);if(table==null)throw new IllegalArgumentException("Table data was not included in this comparison");
        ObjectNode result=table.summary();ArrayNode rows=result.putArray("rows");int index=0,pageBytes=0;boolean more=false;
        try(BufferedReader reader=Files.newBufferedReader(table.differences)){JsonNode n;while((n=read(reader))!=null){if(!status.isEmpty()&&!str(n,"status").equals(status))continue;if(index++<offset)continue;JsonNode preview=previewRow(n,2048);int size=Profiles.JSON.writeValueAsBytes(preview).length;if(size>512*1024){preview=previewRow(n,128);size=Profiles.JSON.writeValueAsBytes(preview).length;}if(rows.size()>=limit||pageBytes+size>1<<20){more=true;break;}rows.add(preview);pageBytes+=size;}}
        if(more)result.put("nextOffset",offset+rows.size());return result;
    }
    static JsonNode previewRow(JsonNode row,int maximum)throws Exception{
        ObjectNode copy=row.deepCopy();for(String side:List.of("source","destination"))for(JsonNode cell:copy.path(side)){
            ObjectNode value=(ObjectNode)cell;value.remove("sql");String text=value.path("value").asText();if(text.length()>maximum)value.put("value",text.substring(0,maximum)).put("truncated",true).put("fingerprint",hash(text));
        }return copy;
    }
    static JsonNode read(BufferedReader reader)throws IOException{String line=reader.readLine();return line==null?null:Profiles.JSON.readTree(line);}
    static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    static ObjectNode cell(ResultSet rs,int index,int type,String typeName,String engine)throws Exception{
        ObjectNode out=Profiles.JSON.createObjectNode();String value,sql,kind;
        switch(type){
            case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB->{try(InputStream stream=rs.getBinaryStream(index)){if(stream==null)return out.put("type","null").putNull("value").put("sql","NULL");byte[] bytes=stream.readNBytes(1<<20);if(stream.read()!=-1)throw new IllegalArgumentException("Binary value exceeds 1 MiB");value=HexFormat.of().formatHex(bytes);sql=engine.equals("postgresql")?"decode('"+value+"','hex')":"X'"+value+"'";kind="binary";}}
            case Types.BOOLEAN,Types.BIT->{boolean b=rs.getBoolean(index);if(rs.wasNull())return out.put("type","null").putNull("value").put("sql","NULL");value=Boolean.toString(b);sql=value.toUpperCase(Locale.ROOT);kind="boolean";}
            case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.DECIMAL,Types.NUMERIC,Types.REAL,Types.FLOAT,Types.DOUBLE->{value=rs.getString(index);if(value==null)return out.put("type","null").putNull("value").put("sql","NULL");try{value=new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();}catch(NumberFormatException failure){throw new IllegalArgumentException("Non-finite numeric data is unavailable");}sql=value;kind="number";}
            case Types.ARRAY,Types.STRUCT,Types.JAVA_OBJECT,Types.REF,Types.ROWID,Types.SQLXML->throw new IllegalArgumentException("Unsupported data codec: "+typeName);
            default->{
                try(Reader reader=rs.getCharacterStream(index)){if(reader==null)return out.put("type","null").putNull("value").put("sql","NULL");StringBuilder text=new StringBuilder();char[] buffer=new char[4096];int n;while((n=reader.read(buffer))!=-1){if(text.length()+n>1<<19)throw new IllegalArgumentException("Text value exceeds 512 KiB");text.append(buffer,0,n);}value=text.toString();}
                if(type==Types.OTHER&&!Set.of("uuid","json","jsonb").contains(typeName.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Unsupported data codec: "+typeName);
                kind=type==Types.DATE?"date":Set.of(Types.TIME,Types.TIME_WITH_TIMEZONE,Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE).contains(type)?"temporal":"text";
                sql=literal(value,engine);if(type==Types.OTHER&&engine.equals("postgresql"))sql+="::"+typeName;
            }
        }
        return out.put("type",kind).put("value",value).put("sql",sql);
    }
    static String literal(String value,String engine){
        if(value.indexOf('\0')>=0&&!Set.of("mysql","mariadb").contains(engine))throw new IllegalArgumentException("NUL text cannot be encoded for this engine");
        if(Set.of("mysql","mariadb").contains(engine))return "CONVERT(X'"+HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8))+"' USING utf8mb4)";
        if(engine.equals("postgresql"))return "E'"+value.replace("\\","\\\\").replace("'","''")+"'";
        return "'"+value.replace("'","''")+"'";
    }
    @Override public synchronized void close(){if(closed)return;closed=true;try(var paths=Files.newDirectoryStream(directory)){for(Path file:paths)if(file.getParent().equals(directory)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))Files.deleteIfExists(file);}catch(IOException ignored){}try{Files.deleteIfExists(directory);}catch(IOException ignored){}budget.release(bytes);bytes=0;}
}
