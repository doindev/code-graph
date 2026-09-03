package io.doindev.codegraph.bench;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.StringDataType;
import org.h2.mvstore.type.ByteArrayDataType;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MvKv implements Kv {
    private final MVStore store;
    private final MVMap<String,byte[]> map;
    private long peakDirtyBytes;
    MvKv(Path directory, long budget) {
        store = new MVStore.Builder().fileName(directory.resolve("graph.mv").toString())
                .cacheSize(Math.toIntExact(budget >> 20)).autoCommitDisabled().open();
        map = store.openMap("records", new MVMap.Builder<String,byte[]>()
                .keyType(StringDataType.INSTANCE).valueType(ByteArrayDataType.INSTANCE));
    }
    public byte[] get(String key) { return map.get(key); }
    public void scan(String prefix, Visitor visitor) {
        var cursor = map.cursor(prefix);
        while (cursor.hasNext()) {
            String key = cursor.next();
            if (!key.startsWith(prefix) || !visitor.visit(key, cursor.getValue())) break;
        }
    }
    public void write(List<Put> changes, Runnable beforeCommit) {
        try {
            for (Put put : changes) { if (put.value() == null) map.remove(put.key()); else map.put(put.key(), put.value()); }
            peakDirtyBytes = Math.max(peakDirtyBytes, store.getUnsavedMemory());
            beforeCommit.run();
            store.commit();
        } catch (RuntimeException e) { store.rollback(); throw e; }
    }
    public void removePrefix(String prefix) {
        try {
            // Unpublish first. A failed bounded removal stays inaccessible and can be retried.
            // Session-wide writer locking prevents readers seeing intermediate deletion batches.
            map.remove(prefix + "meta");
            store.commit();
            String key;
            int deleted = 0;
            while ((key = map.ceilingKey(prefix)) != null && key.startsWith(prefix)) {
                map.remove(key);
                peakDirtyBytes = Math.max(peakDirtyBytes, store.getUnsavedMemory());
                if (++deleted % 1024 == 0 || store.getUnsavedMemory() >= Engine.BATCH_BYTES) store.commit();
            }
            store.commit();
        } catch (RuntimeException e) { store.rollback(); throw e; }
    }
    // In H2 2.4.240 the builder takes MiB but MVStore.setCacheSize takes KiB.
    public void resize(long bytes) { store.setCacheSize(Math.toIntExact(bytes >> 10)); }
    public Map<String,Object> stats() {
        var s = new LinkedHashMap<String,Object>();
        s.put("cacheCapacityBytes", (long) store.getCacheSize() << 20);
        s.put("cacheUsedBytesEstimate", (long) store.getCacheSizeUsed() << 20);
        s.put("unsavedBytesEstimate", store.getUnsavedMemory());
        s.put("peakDirtyBytesEstimate", peakDirtyBytes);
        s.put("storeReadBytes", store.getFileStore().getReadBytes());
        store.getFileStore().populateInfo((k,v)->{if(k.equals("info.FILE_WRITE_BYTES"))s.put("storeWriteBytes",Long.parseLong(v));});
        s.put("cacheHitRatioPercent", store.getFileStore().getCacheHitRatio());
        s.put("storeReadOperations", store.getFileStore().getReadCount());
        s.put("storeWriteOperations", store.getFileStore().getWriteCount());
        s.put("pinnedBytes", null); // API does not expose this; never report unavailable as zero
        return s;
    }
    public void close() { store.close(); }
}
