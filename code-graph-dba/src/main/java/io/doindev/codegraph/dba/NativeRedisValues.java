package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.lettuce.core.*;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded single-command bitmap, bitfield, cardinality and geospatial workflows. */
final class NativeRedisValues {
    static final String NOTICE="One exact command on a pinned primary; no automatic retry. PFCOUNT can update Redis's cached cardinality and requires write review. Result limits do not bound Redis scan/merge work. Cancellation cannot undo an acknowledged or uncertain mutation. Bit writes are limited to the first 64 KiB; geo searches require COUNT <= 100.";
    static final Set<String> READS=Set.of("BITCOUNT","BITPOS","BITFIELD_RO","GEODIST","GEOPOS","GEOHASH","GEOSEARCH");
    static final Set<String> WRITES=Set.of("SETBIT","BITFIELD","PFADD","PFCOUNT","PFMERGE","GEOADD","GEOSEARCHSTORE");
    record Spec(String name,List<Integer> keys,int rows,boolean read){}
    static boolean handles(JsonNode c){return c.isArray()&&!c.isEmpty()&&c.get(0).isTextual()&&(READS.contains(word(c,0))||WRITES.contains(word(c,0)));}
    static NativeCommand.Classification classify(NativeTarget target,JsonNode c){var s=parse(target,c);return new NativeCommand.Classification("redis.value."+(s.read?"read":"mutation"),s.read?NativeCommand.Effect.READ:NativeCommand.Effect.WRITE,s.read,NOTICE);}
    static Spec parse(NativeTarget target,JsonNode c){
        if(!handles(c))throw bad("Unsupported Redis value operation");
        String name=word(c,0);var keys=new ArrayList<Integer>();keys.add(1);var binary=new HashSet<Integer>();int rows=1;
        switch(name){
            case "SETBIT" -> {arity(c,4,4);number(c,2,0,524287);number(c,3,0,1);}
            case "BITCOUNT" -> {arity(c,2,5);if(c.size()==3)throw bad("BITCOUNT range requires start and end");if(c.size()>=4)range(c,2,3,c.size()==5&&word(c,4).equals("BIT")?524287:65535);if(c.size()==5)choice(c,4,"BIT","BYTE");}
            case "BITPOS" -> {arity(c,3,6);number(c,2,0,1);if(c.size()>=4)number(c,3,0,c.size()==6&&word(c,5).equals("BIT")?524287:65535);if(c.size()>=5)range(c,3,4,c.size()==6&&word(c,5).equals("BIT")?524287:65535);if(c.size()==6)choice(c,5,"BIT","BYTE");}
            case "BITFIELD","BITFIELD_RO" -> {
                arity(c,5,194);int i=2,count=0;boolean pendingOverflow=false;
                while(i<c.size()){
                    String op=word(c,i++);
                    if(op.equals("OVERFLOW")){if(name.equals("BITFIELD_RO")||pendingOverflow||i>=c.size())throw bad("OVERFLOW must precede a BITFIELD write subcommand");choice(c,i++,"WRAP","SAT","FAIL");pendingOverflow=true;continue;}
                    boolean get=op.equals("GET");if(!get&&!Set.of("SET","INCRBY").contains(op)||name.equals("BITFIELD_RO")&&!get)throw bad("BITFIELD_RO accepts GET only; BITFIELD accepts GET, SET, INCRBY and OVERFLOW");
                    if(i+1>=c.size()||!get&&i+2>=c.size())throw bad("Incomplete BITFIELD subcommand");
                    String encoding=text(c,i++);if(!encoding.matches("[iu][1-9][0-9]?"))throw bad("Use i1..i64 or u1..u63 bitfield encoding");
                    int width=Integer.parseInt(encoding.substring(1));if(width>(encoding.startsWith("i")?64:63))throw bad("Unsupported bitfield width");
                    String offset=text(c,i++);long start=parseNumber(offset.startsWith("#")?offset.substring(1):offset,0,524287);if(offset.startsWith("#"))start*=width;if(start+width>524288)throw bad("Bitfield exceeds the 64 KiB addressed range");
                    if(!get){number(c,i++,Long.MIN_VALUE,Long.MAX_VALUE);pendingOverflow=false;}
                    if(++count>32)throw bad("At most 32 bitfield operations per command");
                }
                if(pendingOverflow)throw bad("Trailing OVERFLOW has no write subcommand");rows=count;
            }
            case "PFADD" -> {arity(c,3,102);for(int i=2;i<c.size();i++)binary.add(i);}
            case "PFCOUNT" -> {arity(c,2,101);for(int i=2;i<c.size();i++)keys.add(i);}
            case "PFMERGE" -> {arity(c,3,101);for(int i=2;i<c.size();i++)keys.add(i);}
            case "GEOADD" -> {
                arity(c,5,305);int i=2;if(Set.of("NX","XX").contains(word(c,i)))i++;if(i<c.size()&&word(c,i).equals("CH"))i++;
                if((c.size()-i)%3!=0||c.size()==i||(c.size()-i)/3>100)throw bad("GEOADD requires 1..100 longitude/latitude/member triples after optional NX|XX and CH");
                for(;i<c.size();i+=3){coordinate(c,i,-180,180);coordinate(c,i+1,-85.05112878,85.05112878);binary.add(i+2);}
            }
            case "GEODIST" -> {arity(c,4,5);binary.add(2);binary.add(3);if(c.size()==5)unit(c,4);}
            case "GEOPOS","GEOHASH" -> {arity(c,3,102);for(int i=2;i<c.size();i++)binary.add(i);rows=c.size()-2;}
            case "GEOSEARCH","GEOSEARCHSTORE" -> {
                boolean store=name.equals("GEOSEARCHSTORE");arity(c,9,22);int i=store?3:2;if(store)keys.add(2);
                String from=word(c,i++);if(from.equals("FROMMEMBER")){binary.add(i++);}else if(from.equals("FROMLONLAT")){coordinate(c,i++,-180,180);coordinate(c,i++,-85.05112878,85.05112878);}else throw bad("Geo search requires FROMMEMBER or FROMLONLAT");
                String area=word(c,i++);if(!Set.of("BYRADIUS","BYBOX").contains(area))throw bad("Geo search requires BYRADIUS or BYBOX");positive(c,i++);if(area.equals("BYBOX"))positive(c,i++);unit(c,i++);
                var seen=new HashSet<String>();boolean count=false;
                while(i<c.size()){
                    String option=word(c,i++);String group=Set.of("ASC","DESC").contains(option)?"ORDER":option;
                    if(!seen.add(group))throw bad("Duplicate geo search option");
                    switch(option){
                        case "COUNT" -> {rows=(int)number(c,i++,1,100);count=true;if(i<c.size()&&word(c,i).equals("ANY"))i++;}
                        case "ASC","DESC" -> {}
                        case "WITHCOORD","WITHDIST","WITHHASH" -> {if(store)throw bad("GEOSEARCHSTORE does not return WITH* projections");}
                        case "STOREDIST" -> {if(!store)throw bad("STOREDIST requires GEOSEARCHSTORE");}
                        default -> throw bad("Unsupported geo search option; COUNT is required");
                    }
                }
                if(!count)throw bad("Geo search requires explicit COUNT 1..100");
            }
            default -> throw bad("Unsupported value operation");
        }
        binary.addAll(keys);
        for(int i=1;i<c.size();i++){
            if(binary.contains(i)){byte[] value=NativeRedisArguments.bytes(c,i);int max=keys.contains(i)?8192:65536;if(value.length>max||keys.contains(i)&&value.length==0)throw bad("Redis key/member exceeds its allowance or key is empty");}
            else text(c,i);
        }
        if(target.topology().equals("cluster")){int slot=SlotHash.getSlot(NativeRedisArguments.bytes(c,keys.getFirst()));for(int key:keys)if(SlotHash.getSlot(NativeRedisArguments.bytes(c,key))!=slot)throw bad("Cluster multi-key commands require the same hash slot; use an explicit shared hash tag");}
        return new Spec(name,List.copyOf(keys),rows,READS.contains(name));
    }
    static ObjectNode execute(NativeConnections.Lease lease,NativeTarget target,JsonNode c,QueryJobs.Job job,Runnable authority){
        int seconds=job.remainingSeconds();job.progress="Executing bounded Redis value operation";
        return run(lease,target,c,new Control(job.rowLimit,job.byteLimit,seconds,guard(()->job.cancelled,authority,seconds),outcome->job.outcome=outcome,result->{job.result=result;job.bytes=result==null?0:result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}));
    }
    static ObjectNode read(NativeConnections.Lease lease,NativeTarget target,JsonNode c,NativeReadExecutor.Limits limits,java.util.function.BooleanSupplier cancelled){
        if(!parse(target,c).read)throw bad("This command requires the reviewed write workflow");
        return run(lease,target,c,new Control(limits.rows(),limits.bytes(),limits.timeoutSeconds(),guard(cancelled,()->{},limits.timeoutSeconds()),_-> {},_-> {}));
    }
    private record Control(int rows,int bytes,int seconds,Runnable check,java.util.function.Consumer<String> outcome,java.util.function.Consumer<ObjectNode> retain){}
    private static Runnable guard(java.util.function.BooleanSupplier cancelled,Runnable authority,int seconds){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        return ()->{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();authority.run();if(System.nanoTime()>=deadline)throw bad("Redis operation deadline exceeded; reconcile any uncertain mutation");if(cancelled.getAsBoolean())throw new CancellationException();};
    }
    private static ObjectNode run(NativeConnections.Lease lease,NativeTarget target,JsonNode c,Control control){
        Spec spec=parse(target,c);control.outcome.accept("not_started");
        if(spec.rows>control.rows)throw bad("Command result count exceeds the configured row allowance; submit a smaller request");
        control.check.run();
        try(var session=NativeRedisSession.open(lease,target,control.seconds)){
            var socket=session.transactionConnection(NativeRedisArguments.bytes(c,spec.keys.getFirst()));socket.setTimeout(Duration.ofSeconds(control.seconds));
            var args=new CommandArgs<byte[],byte[]>(ByteArrayCodec.INSTANCE);for(int i=1;i<c.size();i++)args.add(NativeRedisArguments.bytes(c,i));
            var output=new BoundedOutput();control.check.run();
            if(!spec.read)control.outcome.accept("partial_or_unknown");
            // BITFIELD_RO is supported by Redis but absent from Lettuce's CommandType enum.
            ProtocolKeyword keyword=()->spec.name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            var future=socket.async().dispatch(keyword,output,args);
            try{
                while(true){
                    try{
                        var raw=future.get(100,TimeUnit.MILLISECONDS);control.outcome.accept(spec.read?"read":"acknowledged");
                        var result=project(spec,raw,control.rows,control.bytes,output.clipped);
                        result.set("target",target.json());result.set("classification",classify(target,c).json());result.put("outcome",spec.read?"read":"acknowledged");
                        if(Profiles.JSON.writeValueAsBytes(result).length>control.bytes)throw bad("Result metadata exceeds allowance; reconcile mutation receipt");
                        control.retain.accept(result);control.check.run();return result;
                    }catch(TimeoutException pending){control.check.run();}
                }
            }finally{if(!future.isDone())future.cancel(false);}
        }catch(SecurityException denied){control.retain.accept(null);throw denied;}
        catch(ExecutionException failure){
            if(failure.getCause() instanceof RedisCommandExecutionException rejected){control.outcome.accept(spec.read?"not_started":"rejected");throw rejected;}
            throw new IllegalStateException("Redis command failed; outcome may be uncertain, reconcile before retry",failure.getCause());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException();}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode Redis receipt; reconcile before retry",failure);}
    }
    static ObjectNode project(Spec spec,List<?> raw,int rowLimit,int byteLimit,boolean clipped){
        if(raw.size()>spec.rows)throw bad("Redis returned more entries than the reviewed allowance");
        var rows=new NativeResults("redis_value",rowLimit,byteLimit);var out=rows.finish().put("operation",spec.name).put("notice",NOTICE).put("consistency","live_non_snapshot").put("hardMemoryLimit",false);
        for(int i=0;i<raw.size();i++){var entry=Profiles.JSON.createObjectNode().put("index",i);entry.set("value",value(raw.get(i),0));if(!rows.add(entry))break;}
        if(clipped)rows.incomplete("binary_value_previewed");return rows.finish();
    }
    private static JsonNode value(Object v,int depth){
        if(depth>4)throw bad("Unexpected nested Redis reply");if(v==null)return NullNode.instance;
        if(v instanceof byte[] bytes)return NativeResults.binary(bytes,8192);
        if(v instanceof Long n)return n>=-9007199254740991L&&n<=9007199254740991L?LongNode.valueOf(n):Profiles.JSON.createObjectNode().put("$numberLong",n.toString());
        if(v instanceof Double d)return DoubleNode.valueOf(d);
        if(v instanceof List<?> list){if(list.size()>100)throw bad("Unexpected Redis nested result size");var result=Profiles.JSON.createArrayNode();for(Object entry:list)result.add(value(entry,depth+1));return result;}
        throw bad("Unexpected Redis result type");
    }
    /** Clip binary fields before decoding, not after constructing a complete retained Java result. */
    private static final class BoundedOutput extends NestedMultiOutput<byte[],byte[]>{
        boolean clipped;int fields;
        BoundedOutput(){super(ByteArrayCodec.INSTANCE);}
        @Override public void set(ByteBuffer bytes){if(++fields>1000)throw bad("Redis response field allowance exceeded");if(bytes!=null&&bytes.remaining()>8192){bytes=bytes.duplicate();bytes.limit(bytes.position()+8192);clipped=true;}super.set(bytes);}
    }
    private static void range(JsonNode c,int a,int b,long max){if(number(c,a,0,max)>number(c,b,0,max))throw bad("Range end precedes start");}
    private static void positive(JsonNode c,int i){double v=coordinate(c,i,0,Double.MAX_VALUE);if(v==0)throw bad("Geo dimensions must be positive");}
    private static double coordinate(JsonNode c,int i,double min,double max){try{String text=text(c,i);if(!text.matches("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))throw new NumberFormatException();double v=Double.parseDouble(text);if(!Double.isFinite(v)||v<min||v>max)throw new NumberFormatException();return v;}catch(NumberFormatException invalid){throw bad("Numeric coordinate/dimension is outside the supported range");}}
    private static void unit(JsonNode c,int i){if(!Set.of("m","km","ft","mi").contains(text(c,i)))throw bad("Use m, km, ft or mi for geo units");}
    private static void choice(JsonNode c,int i,String... values){if(!Set.of(values).contains(word(c,i)))throw bad("Expected "+String.join(" or ",values));}
    private static long number(JsonNode c,int i,long min,long max){return parseNumber(text(c,i),min,max);}
    private static long parseNumber(String text,long min,long max){try{if(!text.matches("-?[0-9]{1,19}"))throw new NumberFormatException();long n=Long.parseLong(text);if(n<min||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException invalid){throw bad("Integer argument must be "+min+".."+max);}}
    private static String text(JsonNode c,int i){if(i>=c.size())throw bad("Incomplete Redis command");return NativeRedisArguments.text(c,i);}
    private static String word(JsonNode c,int i){return text(c,i).toUpperCase(Locale.ROOT);}
    private static void arity(JsonNode c,int min,int max){if(c.size()<min||c.size()>max)throw bad("Unsupported Redis argument count");}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
    private NativeRedisValues(){}
}
