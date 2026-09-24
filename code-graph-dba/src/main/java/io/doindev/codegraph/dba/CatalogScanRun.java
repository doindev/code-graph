package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/** Bounded operational details; never retains SQL, credentials or object names. */
final class CatalogScanRun {
    final String id=UUID.randomUUID().toString();
    final long requestedAt;
    long startedAt,finishedAt,updatedAt,objects,dependencies,bytes;
    String state="queued",phase="queued",errorCode="",error="";
    boolean timeoutRequested;
    CatalogScanRun(long now){requestedAt=updatedAt=now;}
    ObjectNode json(long now){
        return Profiles.JSON.createObjectNode().put("id",id).put("state",state).put("phase",phase)
            .put("requestedAt",requestedAt).put("startedAt",startedAt).put("finishedAt",finishedAt).put("updatedAt",updatedAt)
            .put("elapsedMillis",startedAt==0?0:Math.max(0,(finishedAt==0?now:finishedAt)-startedAt))
            .put("queueMillis",Math.max(0,(startedAt==0?now:startedAt)-requestedAt))
            .put("objects",objects).put("dependencies",dependencies).put("bytes",bytes)
            .put("timeoutRequested",timeoutRequested).put("errorCode",errorCode).put("error",error);
    }
}
