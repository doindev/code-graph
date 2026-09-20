package io.doindev.codegraph.dba;

import org.bson.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** One exact, raw collection definition, bounded before BSON object expansion. */
final class MongoCollectionMetadata {
    static RawBsonDocument load(NativeConnections.Lease lease,NativeTarget target,QueryJobs.Job job){
        return load(lease.mongo.getDatabase(target.database()),target,job);
    }
    static RawBsonDocument load(com.mongodb.client.MongoDatabase database,NativeTarget target,QueryJobs.Job job){
        if(job.cancelled)throw new CancellationException();
        try(var cursor=database.listCollections(RawBsonDocument.class)
                .filter(new BsonDocument("name",new BsonString(target.collection()))).batchSize(1)
                .maxTime(job.remainingSeconds(),TimeUnit.SECONDS).iterator()){
            if(!cursor.hasNext())throw new IllegalArgumentException("Source collection no longer exists; refresh metadata before retrying");
            RawBsonDocument source=cursor.next();
            if(source.getByteBuffer().remaining()>256*1024)throw new IllegalArgumentException("Collection definition exceeds the bounded metadata validation allowance");
            if(job.cancelled)throw new CancellationException();
            return source;
        }
    }
    private MongoCollectionMetadata(){}
}
