package io.doindev.codegraph.store;

import java.util.*;
import java.util.function.*;

/** An isolated, atomically published catalog. Documents are never exposed by code graph queries. */
public interface DocumentStore extends AutoCloseable {
    interface Writer { void put(String key, byte[] value); }
    void replace(Consumer<Writer> producer);
    byte[] get(String key);
    void scan(String prefix, BiConsumer<String,byte[]> visitor);
    <T> T read(Supplier<T> reader);
    void close();
    static DocumentStore memory(long maximumBytes) {
        return new DocumentStore() {
            private Map<String,byte[]> active=new TreeMap<>();
            private boolean closed;
            public void replace(Consumer<Writer> producer) {
                var next=new TreeMap<String,byte[]>();long[] total={0};
                producer.accept((key,value)->{total[0]+=value.length+key.length()*2L+128;if(total[0]>maximumBytes)throw new IllegalArgumentException("Catalog snapshot exceeds its memory allowance");next.put(key,value.clone());});
                synchronized(this){if(closed)throw new IllegalStateException("Catalog closed");active=next;}
            }
            public synchronized byte[] get(String key){byte[] value=active.get(key);return value==null?null:value.clone();}
            public synchronized void scan(String prefix,BiConsumer<String,byte[]> visitor){active.forEach((key,value)->{if(key.startsWith(prefix))visitor.accept(key,value.clone());});}
            public synchronized <T>T read(Supplier<T> reader){return reader.get();}
            public synchronized void close(){closed=true;active=Map.of();}
        };
    }
}
