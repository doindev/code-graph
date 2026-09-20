package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.query.GraphQuery;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;
import static io.doindev.codegraph.tools.ToolSupport.JSON;

/** Non-renewing, bounded freshness observation. Never retains a read scope while waiting. */
final class IndexStatusTool implements GraphTool {
    private static final Semaphore WAITERS=new Semaphore(4);
    private final GraphQuery graph;
    private final Path root;
    private final CodeGraphConfig config;
    private final String instance;
    private record Target(String path,String hash,boolean deleted){}
    IndexStatusTool(GraphQuery graph){this(graph,null,CodeGraphConfig.defaults(),UUID.randomUUID().toString());}
    IndexStatusTool(GraphQuery graph,Path root,CodeGraphConfig config,String instance){
        this.graph=graph;this.root=root;this.config=config;this.instance=instance;
    }
    public ToolSpec spec(){return new ToolSpec("index_status",
            "Observe published index freshness without renewing project TTL. Optionally wait up to 5 seconds for an index instance/generation and up to 20 exact file hashes or deletions. Timeout is not success; no read lock is held while waiting.", """
            {"type":"object","additionalProperties":false,"properties":{
              "indexInstanceId":{"type":"string","format":"uuid","description":"Instance from an earlier status; a replacement index is rejected."},
              "minGeneration":{"type":"integer","minimum":0,"description":"Minimum published generation. Requires indexInstanceId."},
              "waitMillis":{"type":"integer","minimum":0,"maximum":5000,"default":0},
              "files":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"object","additionalProperties":false,
                "required":["path"],"properties":{"path":{"type":"string","minLength":1,"maxLength":4096},
                  "sha256":{"type":"string","pattern":"^[a-fA-F0-9]{64}$","description":"SHA-256 of UTF-8 decoded source, re-encoded as UTF-8."},
                  "deleted":{"type":"boolean","const":true}},
                "oneOf":[{"required":["sha256"],"not":{"required":["deleted"]}},{"required":["deleted"],"not":{"required":["sha256"]}}]}}},
              "dependentRequired":{"minGeneration":["indexInstanceId"]}}
            """);}
    public ToolResponse call(JsonNode args){
        boolean admitted=false;
        try{
            int wait=integer(args,"waitMillis",0,5000);
            long minimum=longInteger(args,"minGeneration",0);
            if(args.has("indexInstanceId")&&(!args.path("indexInstanceId").isTextual()||!instance.equals(args.path("indexInstanceId").asText())))
                throw new IllegalArgumentException("stale_index_instance: project was replaced; obtain current index_status");
            if(args.has("minGeneration")&&!args.has("indexInstanceId"))
                throw new IllegalArgumentException("minGeneration requires indexInstanceId");
            List<Target> targets=targets(args);
            if(wait>0&&!args.has("minGeneration")&&targets.isEmpty())
                throw new IllegalArgumentException("waitMillis requires a generation or file freshness predicate");
            long start=System.nanoTime(),deadline=start+wait*1_000_000L;
            if(wait>0){if(!WAITERS.tryAcquire())throw new IllegalArgumentException("freshness_wait_limit: four waits are already active; retry later");admitted=true;}
            while(true){
                if(Thread.currentThread().isInterrupted())throw new IllegalArgumentException("Freshness wait cancelled");
                var absent = new HashSet<String>();
                for(Target target:targets)if(target.deleted()&&sourceAbsent(target.path()))absent.add(target.path());
                ObjectNode out=graph.read(()->snapshot(targets,minimum,absent));
                boolean satisfied=out.path("freshnessSatisfied").asBoolean();
                long now=System.nanoTime();
                if(satisfied||wait==0||now>=deadline){
                    out.put("waitedMillis",Math.max(0,(now-start)/1_000_000)).put("timedOut",!satisfied&&wait>0);
                    return ToolSupport.finish(out,config);
                }
                LockSupport.parkNanos(Math.min(50_000_000L,deadline-now));
            }
        }catch(RuntimeException e){return ToolResponse.fail(e.getMessage());}
        finally{if(admitted)WAITERS.release();}
    }
    private boolean sourceAbsent(String relative) {
        if(root==null)return false;
        Path current=root;
        for(String segment:relative.split("/")){
            current=current.resolve(segment);
            if(Files.isSymbolicLink(current))return false;
        }
        return Files.notExists(current,LinkOption.NOFOLLOW_LINKS);
    }
    private ObjectNode snapshot(List<Target> targets,long minimum,Set<String> absent){
        var status=graph.status();
        ObjectNode out=JSON.createObjectNode().put("state",status.state()).put("indexInstanceId",instance)
                .put("generation",status.generation()).put("files",status.filesIndexed())
                .put("symbols",status.symbolCount()).put("edges",status.edgeCount()).put("dirtyPending",status.dirtyPending())
                .put("engineVersion",status.engineVersion()).put("renewsProjectTtl",false);
        if(status.lastIndexedAt()!=null)out.put("lastIndexedAt",status.lastIndexedAt().toString());
        status.filesPerLang().forEach(out.putObject("langs")::put);
        boolean satisfied=status.generation()>=minimum;
        if(!targets.isEmpty()){
            var observations=out.putArray("fileRevisions");
            for(Target target:targets){
                var node=graph.node(new FileId(target.path())).orElse(null);
                String hash=node==null?"":node.attrs().getOrDefault("indexedContentHash","");
                // An index miss alone cannot distinguish a deleted file from a failed/excluded parse.
                // For explicit deletions, also observe non-existence at this exact project-relative path.
                boolean diskAbsent=absent.contains(target.path());
                boolean matched=target.deleted()?node==null&&diskAbsent:!hash.isEmpty()&&hash.equalsIgnoreCase(target.hash());
                var row=observations.addObject().put("path",target.path()).put("indexed",node!=null).put("matched",matched);
                if(!hash.isEmpty())row.put("sha256",hash).put("hashAlgorithm",node.attrs().getOrDefault("contentHashAlgorithm","unknown"));
                if(target.deleted())row.put("deleted",diskAbsent).put("absenceEvidence",root==null?"source_root_unavailable":diskAbsent?"source_and_index_absent":"source_not_confirmed_absent");
                satisfied&=matched;
            }
            out.put("coverage","Exact indexed file revisions at this generation, not a guarantee that every reference is resolved. Source can change again after this observation.");
        }
        out.put("freshnessSatisfied",satisfied);
        return out;
    }
    private static List<Target> targets(JsonNode args){
        if(!args.has("files"))return List.of();
        JsonNode rows=args.path("files");
        if(!rows.isArray()||rows.isEmpty()||rows.size()>20)throw new IllegalArgumentException("files requires 1..20 targets");
        var result=new ArrayList<Target>();var paths=new HashSet<String>();
        for(JsonNode row:rows){
            if(!row.isObject()||!row.path("path").isTextual())throw new IllegalArgumentException("Each file requires a relative path");
            String path=row.path("path").asText().replace('\\','/');
            if(path.startsWith("./"))path=path.substring(2);
            if(path.isBlank()||path.length()>4096||path.split("/",-1).length>64||path.startsWith("/")||path.contains(":")||path.contains("\0")||
                    Arrays.stream(path.split("/",-1)).anyMatch(p->p.equals("..")||p.isEmpty())||!paths.add(path))
                throw new IllegalArgumentException("File paths must be unique, bounded project-relative paths without traversal");
            boolean deleted=row.path("deleted").isBoolean()&&row.path("deleted").asBoolean();
            String hash=row.path("sha256").asText("");
            if(deleted?row.has("sha256"):row.has("deleted")||!row.path("sha256").isTextual()||!hash.matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("Each file requires exactly one SHA-256 or deleted:true predicate");
            result.add(new Target(path,hash,deleted));
        }
        return result;
    }
    private static long longInteger(JsonNode args,String field,long fallback){
        if(!args.has(field))return fallback;
        JsonNode value=args.path(field);
        if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0)
            throw new IllegalArgumentException(field+" must be a non-negative integer");
        return value.asLong();
    }
    private static int integer(JsonNode args,String field,int fallback,int max){
        long value=longInteger(args,field,fallback);
        if(value>max)throw new IllegalArgumentException(field+" must be 0.."+max);
        return (int)value;
    }
}
