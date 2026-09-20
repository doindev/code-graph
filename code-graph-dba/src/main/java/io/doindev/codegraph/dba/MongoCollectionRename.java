package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bson.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Exact same-database rename. The admin command transport never expands its target scope. */
final class MongoCollectionRename {
    static String validate(NativeTarget target,JsonNode command){
        command.fieldNames().forEachRemaining(field->{
            if(!Set.of("renameCollection","to","dropTarget").contains(field))
                throw new IllegalArgumentException("Unsupported renameCollection field: "+field);
        });
        if(Set.of("admin","config","local").contains(target.database()))
            throw new IllegalArgumentException("System database renames require a dedicated administrative workflow");
        String prefix=target.database()+".",source=NativeTarget.text(command,"renameCollection",384),destination=NativeTarget.text(command,"to",384);
        if(target.collection().isEmpty()||!source.equals(prefix+target.collection()))
            throw new IllegalArgumentException("renameCollection must exactly match the selected database and collection");
        if(!destination.startsWith(prefix))throw new IllegalArgumentException("Collection rename must stay within the selected database; cross-database moves are not enabled");
        for(String namespace:new String[]{source,destination}){
            String name=namespace.substring(prefix.length());
            if(name.isBlank()||name.startsWith("system.")||name.contains("$")||namespace.getBytes(StandardCharsets.UTF_8).length>235)
                throw new IllegalArgumentException("Rename requires non-system collection names and namespaces of at most 235 UTF-8 bytes");
        }
        if(source.equals(destination))throw new IllegalArgumentException("The destination collection name must differ from the source");
        if(command.has("dropTarget")&&(!command.path("dropTarget").isBoolean()||command.path("dropTarget").asBoolean()))
            throw new IllegalArgumentException("Replacing an existing collection is not enabled; dropTarget must be false or omitted");
        return destination.substring(prefix.length());
    }

    static void describe(ObjectNode review,JsonNode command){
        review.putArray("affectedNamespaces").add(command.path("renameCollection").asText()).add(command.path("to").asText());
        review.put("executionDatabase","admin");
        review.put("transactionNotice","Rename changes only the namespace within the selected database and never replaces a destination. It obtains collection locks, invalidates cursors/change streams, and may break views or scripts referring to the old name. Views, time-series and system collections are unsupported. This is not an undoable transaction; reconcile cancellation or an uncertain outcome before retrying. Open workspaces are not retargeted; refresh the tree after success.");
    }

    static void checkSource(NativeConnections.Lease lease,NativeTarget target,QueryJobs.Job job){
        if(job.cancelled)throw new CancellationException();
        // Exact-name lookup, one raw definition; never enumerate the database or retain its documents.
        try(var cursor=lease.mongo.getDatabase(target.database()).listCollections(RawBsonDocument.class)
                .filter(new BsonDocument("name",new BsonString(target.collection()))).batchSize(1)
                .maxTime(job.remainingSeconds(),TimeUnit.SECONDS).iterator()){
            if(!cursor.hasNext())throw new IllegalArgumentException("Source collection no longer exists; refresh metadata before retrying");
            RawBsonDocument source=cursor.next();
            if(source.getByteBuffer().remaining()>256*1024)throw new IllegalArgumentException("Collection definition exceeds the bounded rename validation allowance");
            if(!source.getString("type",new BsonString("")).getValue().equals("collection")
                    ||source.getDocument("options",new BsonDocument()).containsKey("timeseries"))
                throw new IllegalArgumentException("Only ordinary or capped collections can be renamed; views and time-series collections are unsupported");
        }
        if(job.cancelled)throw new CancellationException();
    }
    private MongoCollectionRename(){}
}
