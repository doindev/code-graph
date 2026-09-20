package io.doindev.codegraph.index.mapping;

import io.doindev.codegraph.model.Node;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeMappingsTest {
    @Test void springMappingsRequireImportedFrameworkAndExplicitNamespace(){
        var nodes=CodeDatabaseMappingsTest.extract("Models.java","""
            import org.springframework.data.mongodb.core.mapping.Document;
            import org.springframework.data.mongodb.core.mapping.Field;
            import org.springframework.data.redis.core.RedisHash;
            @Document(collection="People") class Person { @Field("given_name") String name; @Field("age") int age; }
            @RedisHash(value="sessions", timeToLive=60) class Session { String value; }
            """);
        assertTrue(has(nodes,"People","given_name","mongodb","string"));assertTrue(has(nodes,"People","age","mongodb","int"));
        assertTrue(nodes.stream().anyMatch(n->n.attrs().getOrDefault("ttlPolicy","").equals("expiring")));
        assertFalse(CodeDatabaseMappingsTest.extract("Unknown.java","@Document(collection=\"People\") class P { @Field(\"name\") String name; }").stream().anyMatch(n->n.attrs().containsKey("transport")));
    }
    @Test void mongooseAndRedisRetainNamesAndTypesNotApplicationValues(){
        var nodes=CodeDatabaseMappingsTest.extract("models.ts","""
            import mongoose from 'mongoose';
            const redis=require('redis'); const cache=redis.createClient();
            const Person=new mongoose.Schema({name:String, age:{type:Number,required:true}, tags:[String]}, {collection:'People'});
            cache.hSet('sessions:123', 'secret', 'do-not-retain');
            cache.get(prefix + ':123');
            """);
        assertTrue(has(nodes,"People","name","mongodb","string"));assertTrue(has(nodes,"People","age","mongodb","double"));
        assertTrue(has(nodes,"People","tags","mongodb","array"));assertTrue(has(nodes,"sessions","","redis","hash"));
        assertFalse(nodes.toString().contains("do-not-retain"));assertFalse(nodes.toString().contains("sessions:123"));
        assertTrue(nodes.stream().anyMatch(n->n.attrs().getOrDefault("required","").equals("true")));
    }
    @Test void computedSchemasAndUnsupportedClientBindingsAreNotClaimed(){
        var nodes=CodeDatabaseMappingsTest.extract("models.js","""
            import mongoose from 'mongoose';
            const schema=new mongoose.Schema({name:String}, {collection:configuration.name});
            const fake={createClient(){}}; const client=fake.createClient();client.get('users:1');
            """);
        assertTrue(nodes.stream().anyMatch(n->n.attrs().containsKey("uncertainty")));assertFalse(nodes.stream().anyMatch(n->n.attrs().getOrDefault("transport","").equals("redis")));
    }
    private boolean has(List<Node> nodes,String table,String field,String transport,String type){return nodes.stream().anyMatch(n->n.attrs().getOrDefault("table","").equals(table)&&n.attrs().getOrDefault("column","").equals(field)&&n.attrs().getOrDefault("transport","").equals(transport)&&n.attrs().getOrDefault("databaseType","").equals(type));}
}
