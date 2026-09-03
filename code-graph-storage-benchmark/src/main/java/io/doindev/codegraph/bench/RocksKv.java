package io.doindev.codegraph.bench;

import org.rocksdb.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RocksKv implements Kv {
    static { RocksDB.loadLibrary(); }
    private final LRUCache cache;
    private final Statistics statistics = new Statistics();
    private final Options options;
    private final RocksDB db;
    private final WriteOptions writes = new WriteOptions();
    private final long capacity;
    private final long writeAllowance;
    RocksKv(Path directory, long budget) {
        // Reserve 1/8 of the allowance for two memtables; no unaccounted per-project defaults.
        writeAllowance = Math.max(2L << 20, budget / 8);
        capacity = budget - writeAllowance;
        cache = new LRUCache(capacity, 2, true);
        var tables = new BlockBasedTableConfig().setBlockCache(cache)
                .setCacheIndexAndFilterBlocks(true).setCacheIndexAndFilterBlocksWithHighPriority(true);
        options = new Options().setCreateIfMissing(true).setStatistics(statistics)
                .setTableFormatConfig(tables).setWriteBufferSize(writeAllowance / 2)
                .setMaxWriteBufferNumber(2).setMaxBackgroundJobs(2);
        try { db = RocksDB.open(options, directory.resolve("rocks").toString()); }
        catch (RocksDBException e) { options.close(); writes.close(); statistics.close(); cache.close(); throw fail(e); }
    }
    private static byte[] key(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static RuntimeException fail(Exception e) { return new IllegalStateException("RocksDB operation failed", e); }
    public byte[] get(String k) { try { return db.get(key(k)); } catch (RocksDBException e) { throw fail(e); } }
    public void scan(String prefix, Visitor visitor) {
        try (RocksIterator it = db.newIterator()) {
            for (it.seek(key(prefix)); it.isValid(); it.next()) {
                String k = new String(it.key(), StandardCharsets.UTF_8);
                if (!k.startsWith(prefix) || !visitor.visit(k, it.value())) break;
            }
            it.status();
        } catch (RocksDBException e) { throw fail(e); }
    }
    public void write(List<Put> changes, Runnable beforeCommit) {
        try (WriteBatch batch = new WriteBatch()) {
            for (Put put : changes) { if (put.value() == null) batch.delete(key(put.key())); else batch.put(key(put.key()), put.value()); }
            beforeCommit.run();
            db.write(writes, batch);
        } catch (RocksDBException e) { throw fail(e); }
    }
    public void removePrefix(String prefix) {
        // All namespace prefixes end in '/', whose next ASCII character bounds the prefix range.
        if (!prefix.endsWith("/")) throw new IllegalArgumentException("prefix must end with /");
        try { db.deleteRange(key(prefix), key(prefix.substring(0, prefix.length() - 1) + "0")); }
        catch (RocksDBException e) { throw fail(e); }
    }
    public void resize(long bytes) {
        throw new UnsupportedOperationException("rocksdbjni 9.8.4 Cache has no public live capacity setter; no reopen masquerading as live resize");
    }
    public Map<String,Object> stats() {
        var s = new LinkedHashMap<String,Object>();
        s.put("cacheCapacityBytes", capacity);
        s.put("writeBufferAllowanceBytes", writeAllowance);
        s.put("cacheUsedBytesEstimate", cache.getUsage());
        s.put("pinnedBytes", cache.getPinnedUsage());
        s.put("cacheHits", statistics.getTickerCount(TickerType.BLOCK_CACHE_HIT));
        s.put("cacheMisses", statistics.getTickerCount(TickerType.BLOCK_CACHE_MISS));
        s.put("logicalBytesRead", statistics.getTickerCount(TickerType.BYTES_READ));
        s.put("logicalBytesWritten", statistics.getTickerCount(TickerType.BYTES_WRITTEN));
        try {
            s.put("memtableBytes", db.getLongProperty("rocksdb.cur-size-all-mem-tables"));
            s.put("tableReaderBytes", db.getLongProperty("rocksdb.estimate-table-readers-mem"));
        } catch (RocksDBException e) { throw fail(e); }
        return s;
    }
    public void close() { db.close(); writes.close(); options.close(); statistics.close(); cache.close(); }
}
