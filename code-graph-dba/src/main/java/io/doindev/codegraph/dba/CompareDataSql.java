package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Writes SQL from the reviewed row snapshots; never issues DML through JDBC. */
final class CompareDataSql {
    static void validate(CompareSql.Plan plan,CompareData data){
        Set<String> selected=new HashSet<>();for(var choice:plan.data)selected.add(key(str(choice.source(),"schema"),"tables",str(choice.source(),"name")));
        for(var choice:plan.data){
            CompareData.Table rows=data.tables.get(str(choice.source(),"id"));if(rows==null)throw new IllegalArgumentException("Compare table data before selecting it for generation");
            if(!rows.keyed&&!plan.mode(choice).equals("replace"))throw new IllegalArgumentException("Table "+str(choice.source(),"name")+" has no usable matching key; choose Replace all");
            if(rows.left.rows()>0)for(JsonNode fk:choice.source().path("foreignKeys"))if(!selected.contains(key(str(fk,"schema"),"tables",str(fk,"table"))))throw new IllegalArgumentException("Include and compare parent-table data for "+str(fk,"table")+" to verify foreign key "+str(fk,"name"));
            if(Set.of("replace","mirror").contains(plan.mode(choice)))for(JsonNode table:plan.destination.objects.values())for(JsonNode fk:table.path("foreignKeys")){
                if(str(fk,"table").equals(str(choice.source(),"name"))&&str(fk,"schema").equals(plan.schema(str(choice.source(),"schema")))&&!selected.contains(plan.sourceKeyForDestination(table)))throw new IllegalArgumentException("Deleting referenced rows requires including dependent table data: "+str(table,"name"));
            }
        }
    }
    static void write(QueryJobs.Job job,CompareSql.Plan plan,CompareData data,Writer out)throws Exception{
        Map<String,String> stages=new LinkedHashMap<>();List<CompareSql.Choice> ordered=order(plan.data);Map<String,List<String>> keys=new HashMap<>();
        for(CompareSql.Choice choice:ordered){
            check(job);String id=str(choice.source(),"id");CompareData.Table rows=data.tables.get(id);if(rows==null)throw new IllegalArgumentException("Table data must be compared before generation");
            if(!CompareSql.columnShape(plan,choice.source(),true).equals(CompareSql.columnShape(plan,rows.source,true)))throw new IllegalArgumentException("Include the table column changes before generating its data");
            String mode=plan.mode(choice);if(!rows.keyed&&!mode.equals("replace"))throw new IllegalArgumentException("Table "+str(choice.source(),"name")+" has no usable key; select Replace all");
            if(!mode.equals("replace"))checkUniqueTransitions(choice,rows,mode);
            String stage=CompareSql.q(plan.engine,"cgraph_compare_"+UUID.randomUUID().toString().replace("-",""));stages.put(id,stage);keys.put(id,rows.key);
            String cols=names(plan.engine,rows.columns);
            out.write("-- Captured rows: "+CompareSql.comment(str(choice.source(),"name"))+" ("+rows.left.rows()+")\n");
            out.write("CREATE "+(plan.engine.equals("h2")?"LOCAL ":plan.engine.equals("oracle")?"GLOBAL ":"")+"TEMPORARY TABLE "+stage+(plan.engine.equals("postgresql")?" ON COMMIT DROP":plan.engine.equals("oracle")?" ON COMMIT PRESERVE ROWS":"")+" AS SELECT "+cols+" FROM "+plan.target(choice.source())+" WHERE 1=0;\n");
            try(BufferedReader reader=Files.newBufferedReader(rows.left.file())){JsonNode row;while((row=CompareData.read(reader))!=null){check(job);if(plan.engine.equals("oracle")){OracleCompareData.insert(job,out,stage,rows.columns,row.path("values"));continue;}List<String> values=new ArrayList<>();row.path("values").forEach(v->values.add(str(v,"sql")));out.write("INSERT INTO "+stage+" ("+cols+") VALUES ("+String.join(", ",values)+");\n");}}
        }
        List<String> restoreOracleKeys=new ArrayList<>();
        if(plan.engine.equals("oracle"))for(CompareSql.Choice choice:ordered){
            Map<String,JsonNode> constraints=new LinkedHashMap<>();for(JsonNode fk:choice.source().path("foreignKeys"))constraints.put(str(fk,"name"),fk);
            for(String name:constraints.keySet()){String target=plan.target(choice.source())+" ",constraint=CompareSql.q(plan.engine,name);out.write("ALTER TABLE "+target+"DISABLE CONSTRAINT "+constraint+";\n");restoreOracleKeys.add("ALTER TABLE "+target+"ENABLE VALIDATE CONSTRAINT "+constraint+";\n");}
        }
        List<CompareSql.Choice> reverse=new ArrayList<>(ordered);Collections.reverse(reverse);
        for(CompareSql.Choice choice:reverse){
            String mode=plan.mode(choice),target=plan.target(choice.source()),id=str(choice.source(),"id"),stage=stages.get(id);
            if(mode.equals("replace"))out.write("DELETE FROM "+target+";\n");
            else if(mode.equals("mirror"))out.write("DELETE "+(Set.of("mysql","mariadb").contains(plan.engine)?"d FROM ":"FROM ")+target+" d WHERE NOT EXISTS (SELECT 1 FROM "+stage+" s WHERE "+join(plan.engine,keys.get(id))+");\n");
        }
        for(CompareSql.Choice choice:ordered){
            check(job);String id=str(choice.source(),"id"),stage=stages.get(id),target=plan.target(choice.source()),mode=plan.mode(choice);
            CompareData.Table rows=data.tables.get(id);String join=join(plan.engine,rows.key);List<String> update=rows.columns.stream().filter(n->!rows.key.contains(n)).toList();
            if((mode.equals("upsert")||mode.equals("mirror"))&&!update.isEmpty()){
                if(Set.of("mysql","mariadb").contains(plan.engine))out.write("UPDATE "+target+" d JOIN "+stage+" s ON "+join+" SET "+String.join(", ",update.stream().map(n->"d."+CompareSql.q(plan.engine,n)+"=s."+CompareSql.q(plan.engine,n)).toList())+";\n");
                else out.write("UPDATE "+target+" d SET "+String.join(", ",update.stream().map(n->CompareSql.q(plan.engine,n)+"=(SELECT s."+CompareSql.q(plan.engine,n)+" FROM "+stage+" s WHERE "+join+")").toList())+" WHERE EXISTS (SELECT 1 FROM "+stage+" s WHERE "+join+");\n");
            }
            boolean identity=false;for(JsonNode col:choice.source().path("columns"))if(!str(col,"identity").isEmpty())identity=true;
            out.write("INSERT INTO "+target+" ("+names(plan.engine,rows.columns)+")"+(identity&&Set.of("postgresql","h2").contains(plan.engine)?" OVERRIDING SYSTEM VALUE":"")+" SELECT "+String.join(", ",rows.columns.stream().map(n->"s."+CompareSql.q(plan.engine,n)).toList())+" FROM "+stage+" s"+(mode.equals("replace")?"":" WHERE NOT EXISTS (SELECT 1 FROM "+target+" d WHERE "+join+")")+";\n");
            ensureSequences(plan,choice,rows);
            if(plan.engine.equals("oracle"))out.write("TRUNCATE TABLE "+stage+";\n");
            out.write("DROP TABLE "+stage+";\n\n");
        }
        for(String restore:restoreOracleKeys)out.write(restore);
    }
    static String names(String engine,List<String> names){return String.join(", ",names.stream().map(n->CompareSql.q(engine,n)).toList());}
    static String join(String engine,List<String> keys){return String.join(" AND ",keys.stream().map(n->"d."+CompareSql.q(engine,n)+"=s."+CompareSql.q(engine,n)).toList());}
    static List<CompareSql.Choice> order(List<CompareSql.Choice> data){
        List<CompareSql.Choice> ordered=new ArrayList<>();Set<String> visiting=new HashSet<>(),done=new HashSet<>();Map<String,CompareSql.Choice> map=new TreeMap<>();
        for(var c:data)map.put(key(str(c.source(),"schema"),"tables",str(c.source(),"name")),c);for(String k:map.keySet())visit(k,map,visiting,done,ordered);return ordered;
    }
    static void visit(String k,Map<String,CompareSql.Choice> map,Set<String> visiting,Set<String> done,List<CompareSql.Choice> ordered){
        if(done.contains(k)||visiting.contains(k)||!map.containsKey(k))return;visiting.add(k);var c=map.get(k);
        for(JsonNode fk:c.source().path("foreignKeys"))visit(key(str(fk,"schema"),"tables",str(fk,"table")),map,visiting,done,ordered);
        visiting.remove(k);done.add(k);ordered.add(c);
    }
    static void checkUniqueTransitions(CompareSql.Choice choice,CompareData.Table rows,String mode)throws IOException{
        // Conservatively reject changes to alternate unique keys. A row-by-row update can fail on swaps.
        Set<String> alternate=new HashSet<>();for(JsonNode index:choice.source().path("indexes"))if(index.path("unique").asBoolean()){
            List<String> columns=new ArrayList<>();index.path("columns").forEach(n->columns.add(n.asText()));if(!columns.equals(rows.key))alternate.addAll(columns);}
        if(alternate.isEmpty())return;
        if(rows.counts.path("source_only").asLong()>0&&(mode.equals("insert")&&rows.right.rows()>0||mode.equals("upsert")&&rows.counts.path("destination_only").asLong()>0))throw new IllegalArgumentException("Retained rows may conflict with alternate unique keys; choose Replace all or a separately verified transition");
        try(BufferedReader reader=Files.newBufferedReader(rows.differences)){JsonNode row;while((row=CompareData.read(reader))!=null)if(str(row,"status").equals("different"))
            for(String column:alternate){int i=rows.columns.indexOf(column);if(i>=0&&!row.path("source").path(i).equals(row.path("destination").path(i)))throw new IllegalArgumentException("Updating alternate unique key "+column+" needs Replace all or a dedicated transition adapter");}}
    }
    static void ensureSequences(CompareSql.Plan plan,CompareSql.Choice table,CompareData.Table rows)throws Exception{
        if(!Set.of("postgresql","h2").contains(plan.engine))return;
        for(JsonNode column:table.source().path("columns")){
            String name=str(column,"name");List<CompareSql.Choice> sequences=new ArrayList<>();
            for(CompareSql.Choice c:plan.selected.values())if(str(c.source(),"kind").equals("sequences")&&str(c.source().path("ownership"),"table").equals(str(table.source(),"name"))&&str(c.source().path("ownership"),"schema").equals(str(table.source(),"schema"))&&str(c.source().path("ownership"),"column").equals(name))sequences.add(c);
            boolean h2Identity=plan.engine.equals("h2")&&!str(column,"identity").isEmpty();if(sequences.isEmpty()&&!h2Identity){
                if(plan.engine.equals("postgresql")&&(!str(column,"identity").isEmpty()||str(column,"default").contains("nextval(")))throw new IllegalArgumentException("Select and enable safe value synchronization for the generator of "+name);continue;}
            JsonNode options=h2Identity?column.path("identityOptions"):sequences.getFirst().source().path("fields");BigInteger step=CompareSql.integer(str(options,"increment"));BigInteger extreme=null;
            for(CompareData.Snapshot snapshot:List.of(rows.left,rows.right))try(BufferedReader reader=Files.newBufferedReader(snapshot.file())){JsonNode row;while((row=CompareData.read(reader))!=null){JsonNode value=row.path("values").path(rows.columns.indexOf(name)).path("value");if(value.isNull()||value.isMissingNode())continue;BigInteger v=new BigInteger(value.asText());if(extreme==null||step.signum()>0&&v.compareTo(extreme)>0||step.signum()<0&&v.compareTo(extreme)<0)extreme=v;}}
            if(extreme==null)continue;
            if(h2Identity){
                if(!table.options().path("syncIdentity").asBoolean())throw new IllegalArgumentException("Enable identity value synchronization for "+str(table.source(),"name"));
                BigInteger next=CompareSql.integer(str(column,"identityBase"));if(table.destination()!=null)for(JsonNode old:table.destination().path("columns"))if(str(old,"name").equals(name)&&!str(old,"identityBase").isBlank())next=CompareSql.advance(next,CompareSql.integer(str(old,"identityBase")),step);
                next=CompareSql.advance(next,extreme.add(step.signum()>0?BigInteger.ONE:BigInteger.ONE.negate()),step);bounds(options,next);
                plan.state.add("ALTER TABLE "+plan.target(table.source())+" ALTER COLUMN "+CompareSql.q(plan.engine,name)+" RESTART WITH "+next);
            }else for(CompareSql.Choice c:sequences){
                if(!plan.sync(c))throw new IllegalArgumentException("Enable sequence value synchronization for "+str(c.source(),"name"));
                BigInteger next=CompareSql.next(plan.engine,c.source());if(c.destination()!=null)next=CompareSql.advance(next,CompareSql.next(plan.engine,c.destination()),step);
                if(plan.sequenceMode(c).equals("exact")){
                    BigInteger exact=CompareSql.next(plan.engine,c.source());if(step.signum()>0?exact.compareTo(extreme)<=0:exact.compareTo(extreme)>=0)throw new IllegalArgumentException("Exact sequence state would collide with copied or retained data");continue;}
                next=CompareSql.advance(next,extreme.add(step.signum()>0?BigInteger.ONE:BigInteger.ONE.negate()),step);bounds(options,next);
                plan.state.add("ALTER SEQUENCE "+plan.target(c.source())+" RESTART WITH "+next);
            }
        }
    }
    static void bounds(JsonNode options,BigInteger next){if(next.compareTo(CompareSql.integer(str(options,"minimum")))<0||next.compareTo(CompareSql.integer(str(options,"maximum")))>0)throw new IllegalArgumentException("Copied values exhaust the sequence");}
    private CompareDataSql(){}
}
