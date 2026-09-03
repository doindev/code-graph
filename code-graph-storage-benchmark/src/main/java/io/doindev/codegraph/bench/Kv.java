package io.doindev.codegraph.bench;

import java.util.List;
import java.util.Map;

/** A session-wide store; project prefixes share a single cache, not a cache per project. */
interface Kv extends AutoCloseable {
    record Put(String key, byte[] value) {} // null value deletes
    @FunctionalInterface interface Visitor { boolean visit(String key, byte[] value); }
    byte[] get(String key);
    void scan(String prefix, Visitor visitor);
    void write(List<Put> changes, Runnable beforeCommit);
    void removePrefix(String prefix);
    void resize(long bytes);
    Map<String,Object> stats();
    @Override void close();
}
