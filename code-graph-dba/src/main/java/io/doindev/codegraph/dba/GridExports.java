package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

/** Bounded, session-owned exports. Ordinary query pages never use these temporary files. */
final class GridExports implements AutoCloseable {
    static final long FILE_LIMIT=64L<<20, TOTAL_LIMIT=128L<<20, TTL=600_000;
    static final int ROW_LIMIT=1_000_000;
    private final GridResults grids;
    private final Path directory;
    private final Map<String,Artifact> files=new LinkedHashMap<>();
    private long reserved;
    private record Artifact(String id,String owner,String job,Path path,String format,long bytes,long expires){}
    GridExports(GridResults grids)throws IOException{
        this.grids=grids;directory=grids.config.get().directory().resolve("grid-exports");
        Files.createDirectories(directory);if(Files.isSymbolicLink(directory))throw new IOException("Export directory must not be a symbolic link");Profiles.protect(directory);
        // Only our exact artifact names, older than the maximum export lifetime. No directory recursion.
        try(var entries=Files.newDirectoryStream(directory,"cgraph-grid-*")){for(Path p:entries)
            if(owned(p)&&!Files.isSymbolicLink(p)&&Files.isRegularFile(p)&&Files.getLastModifiedTime(p).toMillis()<System.currentTimeMillis()-TTL)Files.deleteIfExists(p);}
    }
    private boolean owned(Path p){return p.getParent().equals(directory)&&p.getFileName().toString().matches("cgraph-grid-[0-9a-f-]{36}\\.(part|csv|txt|xlsx|sql)");}
    private synchronized void reserve(){if(reserved+FILE_LIMIT>TOTAL_LIMIT)throw new IllegalArgumentException("Export disk allowance full (128 MiB). Finish downloads or wait for expiry.");reserved+=FILE_LIMIT;}
    synchronized ObjectNode telemetry(){return Profiles.JSON.createObjectNode().put("retainedFiles",files.size()).put("reservedDiskBytes",reserved).put("maximumDiskBytes",TOTAL_LIMIT).put("maximumFileBytes",FILE_LIMIT);}
    ObjectNode create(GridResults.Context context,QueryJobs.Job job,Connection c,JsonNode request)throws Exception {
        String format=request.path("format").asText(),scope=request.path("scope").asText();
        if(!Set.of("csv","txt","xlsx","sql").contains(format)||!Set.of("page","selected","query").contains(scope))throw new IllegalArgumentException("Choose a supported export scope and format.");
        if(scope.equals("query")&&!context.fullExport)throw new IllegalArgumentException("Full-query export is unavailable for this result.");
        if(format.equals("sql")&&!GridResults.sqlExport(context))throw new IllegalArgumentException("SQL export requires verified complete columns without generated/identity values. Project supported columns or use CSV/XLSX.");
        JsonNode descriptors=context.result.path("columns"),selected=request.path("columns");
        if(!selected.isArray()||selected.isEmpty()||selected.size()>256)throw new IllegalArgumentException("Select 1–256 export columns.");
        var indices=new ArrayList<Integer>();var labels=new ArrayList<String>();var types=new ArrayList<Integer>();var names=new ArrayList<String>();Set<String> seen=new HashSet<>();
        for(JsonNode id:selected){if(!id.isTextual()||!seen.add(id.asText()))throw new IllegalArgumentException("Repeated or invalid export column.");
            int found=-1;for(int i=0;i<descriptors.size();i++)if(descriptors.get(i).path("id").asText().equals(id.asText())){found=i;break;}
            if(found<0)throw new IllegalArgumentException("Unknown export column.");indices.add(found);labels.add(descriptors.get(found).path("label").asText());types.add(descriptors.get(found).path("jdbcType").asInt(Types.VARCHAR));
            if(context.relation!=null)names.add(GridRelation.quote(c.getMetaData(),context.relation.columns.get(found).name()));
        }
        var selectedRows=new HashSet<String>();if(scope.equals("selected")){
            JsonNode rows=request.path("selectedRows");if(!rows.isArray()||rows.isEmpty()||rows.size()>context.rowIds.size())throw new IllegalArgumentException("Select saved rows to export.");
            for(JsonNode id:rows)if(!id.isTextual()||!context.rowIds.contains(id.asText())||!selectedRows.add(id.asText()))throw new IllegalArgumentException("Unknown or repeated selected row.");
        }
        reserve();String id=UUID.randomUUID().toString();Path partial=directory.resolve("cgraph-grid-"+id+".part"),complete=directory.resolve("cgraph-grid-"+id+"."+format);boolean published=false;int count=0;
        try{
            try(OutputStream file=Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW);BoundedStream bounded=new BoundedStream(file,FILE_LIMIT);
                RowWriter writer=new RowWriter(bounded,format,labels,types,request.path("spreadsheetSafe").asBoolean(true),
                        context.relation==null?"":context.relation.qualified,names,context.relation==null?"":context.relation.engine)){
                Profiles.protect(partial);
                if(scope.equals("query")){
                    // Forward-only bounded fetching. PostgreSQL cursors require auto-commit off.
                    try(PreparedStatement statement=c.prepareStatement(context.orderedSql==null?context.sql:context.orderedSql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
                        job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());statement.setFetchSize(64);
                        String engine=GridRelation.engine(c.getMetaData().getDatabaseProductName());
                        if(engine.equals("mysql"))statement.setFetchSize(Integer.MIN_VALUE); // Connector/J streaming mode, no full client buffer.
                        GridPaging.bind(statement,context.parameters);
                        try(ResultSet rs=statement.executeQuery()){if(rs.getMetaData().getColumnCount()!=descriptors.size())throw new IllegalArgumentException("Query columns changed; refresh before export.");
                            while(rs.next()){grids.check(context,job);if(++count>ROW_LIMIT)throw new IllegalArgumentException("Export exceeds 1,000,000 rows.");
                                var values=new ArrayList<String>();long rowChars=0;for(int index:indices){String value=cell(rs,index+1);rowChars+=value==null?0:value.length();if(rowChars>1<<20)throw new IllegalArgumentException("Export row exceeds its 1 MiB character allowance.");values.add(value);}writer.row(values);}
                        }
                    }finally{job.statement=null;}
                }else for(int i=0;i<context.result.path("rows").size();i++){
                    if(scope.equals("selected")&&!selectedRows.contains(context.rowIds.get(i)))continue;grids.check(context,job);if(++count>ROW_LIMIT)throw new IllegalArgumentException("Export row limit exceeded.");
                    JsonNode row=context.result.path("rows").get(i);var values=new ArrayList<String>();for(int index:indices)values.add(row.get(index).isNull()?null:row.get(index).asText());writer.row(values);
                }
            }
            grids.check(context,job);c.rollback();Files.move(partial,complete,StandardCopyOption.ATOMIC_MOVE);long size=Files.size(complete);
            synchronized(this){files.put(id,new Artifact(id,context.owner,job.id,complete,format,size,System.currentTimeMillis()+TTL));}published=true;
            return Profiles.JSON.createObjectNode().put("exportId",id).put("format",format).put("rows",count).put("bytes",size).put("previewOnly",!scope.equals("query")&&context.result.path("cellsTruncated").asBoolean()).put("expiresInSeconds",600);
        }finally{if(!published){Files.deleteIfExists(partial);Files.deleteIfExists(complete);synchronized(this){reserved-=FILE_LIMIT;}}}
    }
    // Stream each value with a separate bounded cell allowance; fail rather than silently truncate exports.
    static String cell(ResultSet rs,int column)throws Exception{
        int type=rs.getMetaData().getColumnType(column);
        if(Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB,Types.ARRAY,Types.STRUCT,Types.JAVA_OBJECT,Types.OTHER).contains(type))
            throw new IllegalArgumentException("Full export does not support a binary/structured column; project supported scalar columns explicitly.");
        if(!Set.of(Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB).contains(type)){
            String scalar=rs.getString(column);if(scalar!=null&&scalar.length()>1<<20)throw new IllegalArgumentException("Export cell exceeds 1 MiB.");return scalar;
        }
        try(Reader reader=rs.getCharacterStream(column)){if(reader==null)return null;StringBuilder out=new StringBuilder();char[] chars=new char[4096];int n;while((n=reader.read(chars))!=-1){if(out.length()+n>1<<20)throw new IllegalArgumentException("Export cell exceeds 1 MiB.");out.append(chars,0,n);}return out.toString();}
    }
    void download(String owner,String id,HttpExchange exchange)throws Exception{
        Artifact item;synchronized(this){item=files.get(id);if(item==null||!item.owner.equals(owner)||!grids.alive.test(owner)||item.expires<System.currentTimeMillis())throw new SecurityException("Export is unavailable or belongs to another session.");files.remove(id);}
        try{exchange.getResponseHeaders().set("Content-Type",item.format.equals("xlsx")?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=\"result."+item.format+"\"");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,item.bytes);
            try(InputStream in=Files.newInputStream(item.path);OutputStream out=exchange.getResponseBody()){byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){if(!grids.alive.test(owner))throw new IOException("Session expired");out.write(buffer,0,n);}}
        }finally{Files.deleteIfExists(item.path);synchronized(this){reserved-=FILE_LIMIT;}}
    }
    synchronized void remove(String owner,String id)throws IOException{Artifact a=files.get(id);if(a==null)return;if(!a.owner.equals(owner))throw new SecurityException("Export is not owned by this session.");Files.deleteIfExists(a.path);files.remove(id);reserved-=FILE_LIMIT;}
    synchronized void reap(){for(Artifact a:new ArrayList<>(files.values()))if(a.expires<System.currentTimeMillis()||!grids.alive.test(a.owner))try{remove(a.owner,a.id);}catch(IOException ignored){}}
    synchronized void cancelled(String job){for(Artifact a:new ArrayList<>(files.values()))if(a.job.equals(job))try{remove(a.owner,a.id);}catch(IOException ignored){}}
    public synchronized void close(){for(Artifact a:new ArrayList<>(files.values()))try{remove(a.owner,a.id);}catch(IOException ignored){}}
    static final class BoundedStream extends FilterOutputStream{
        final long limit;long size;BoundedStream(OutputStream out,long limit){super(out);this.limit=limit;}
        public void write(int b)throws IOException{if(++size>limit)throw new IOException("Export exceeds 64 MiB.");out.write(b);}
        public void write(byte[] b,int off,int len)throws IOException{if(size+len>limit)throw new IOException("Export exceeds 64 MiB.");size+=len;out.write(b,off,len);}
    }
    /** Inline-string XLSX, streamed directly into ZIP. No shared-string table or in-memory workbook. */
    static final class RowWriter implements AutoCloseable{
        final String format,target,engine;final List<Integer> types;final List<String> names;final boolean safe;final Writer writer;final ZipOutputStream zip;int row;
        RowWriter(OutputStream out,String format,List<String> labels,List<Integer> types,boolean safe,String target,List<String> names,String engine)throws IOException{
            this.format=format;this.types=types;this.safe=safe;this.target=target;this.names=names;this.engine=engine;
            zip=format.equals("xlsx")?new ZipOutputStream(out,StandardCharsets.UTF_8):null;
            if(zip!=null){entry("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
                entry("_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
                entry("xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Results\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
                entry("xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));writer=new OutputStreamWriter(zip,StandardCharsets.UTF_8);writer.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");xlsx(labels,true);
            }else{writer=new OutputStreamWriter(out,StandardCharsets.UTF_8);if(!format.equals("sql"))delimited(labels);else writer.write("-- Saved grid export. Review the destination before executing. NULL is SQL NULL.\n");}
        }
        void entry(String name,String value)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        void row(List<String> values)throws IOException{
            if(format.equals("xlsx"))xlsx(values,false);else if(format.equals("sql")){var cells=new ArrayList<String>();for(int i=0;i<values.size();i++)cells.add(literal(values.get(i),types.get(i),engine));writer.write("INSERT INTO "+target+" ("+String.join(", ",names)+") VALUES ("+String.join(", ",cells)+");\n");}else delimited(values);
        }
        void delimited(List<String> values)throws IOException{for(int i=0;i<values.size();i++){if(i>0)writer.write(format.equals("csv")?',':'\t');String value=values.get(i);
            if(value==null){writer.write("\\N");continue;}if(value.startsWith("\\"))value="\\"+value;
            if(safe&&value.stripLeading().matches("(?s)^[=+@\\-].*"))value="'"+value;
            if(format.equals("csv"))writer.write("\""+value.replace("\"","\"\"")+"\"");else writer.write(value.replace("\\","\\\\").replace("\t","\\t").replace("\r","\\r").replace("\n","\\n"));}
            writer.write("\r\n");
        }
        void xlsx(List<String> values,boolean header)throws IOException{writer.write("<row r=\""+ ++row+"\">");for(int i=0;i<values.size();i++){String value=values.get(i);if(value!=null&&value.length()>32767)throw new IOException("XLSX cells cannot exceed 32,767 characters.");
            String ref=column(i)+row;boolean numeric=!header&&value!=null&&Set.of(Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL).contains(types.get(i));
            if(numeric)try{numeric=new java.math.BigDecimal(value).precision()<=15;}catch(NumberFormatException invalid){numeric=false;}
            if(numeric)writer.write("<c r=\""+ref+"\"><v>"+xml(value)+"</v></c>");else writer.write("<c r=\""+ref+"\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"+xml(value==null?"\\N":value.startsWith("\\")?"\\"+value:value)+"</t></is></c>");}writer.write("</row>");}
        static String column(int index){StringBuilder s=new StringBuilder();for(int n=index+1;n>0;n=(n-1)/26)s.insert(0,(char)('A'+(n-1)%26));return s.toString();}
        static String xml(String s)throws IOException{for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c<32&&c!='\t'&&c!='\r'&&c!='\n'||c==0xfffe||c==0xffff)throw new IOException("Value contains characters XML cannot represent.");}return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
        static String literal(String value,int type,String engine){
            if(value==null)return "NULL";
            if(Set.of(Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL).contains(type))return new java.math.BigDecimal(value).toPlainString();
            if(type==Types.BOOLEAN||type==Types.BIT)return value.equalsIgnoreCase("true")||value.equals("1")?engine.equals("postgresql")||engine.equals("h2")?"TRUE":"1":engine.equals("postgresql")||engine.equals("h2")?"FALSE":"0";
            if(engine.equals("mysql")||engine.equals("mariadb"))return "CONVERT(X'"+HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8))+"' USING utf8mb4)";
            if(engine.equals("postgresql"))return "E'"+value.replace("\\","\\\\").replace("'","''")+"'";
            return (engine.equals("sqlserver")?"N":"")+"'"+value.replace("'","''")+"'";
        }
        public void close()throws IOException{if(zip!=null){writer.write("</sheetData></worksheet>");writer.flush();zip.closeEntry();zip.close();}else writer.close();}
    }
}
