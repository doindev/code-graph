package io.doindev.codegraph.index;

import java.util.LinkedHashMap;
import java.util.Map;

/** Constant-size full-scan telemetry. Counts are work completed, never a guessed percentage. */
public final class IndexingProgress {
    private String phase = "waiting";
    private long discovered, parsed, resolved, failed, started, finished;
    private boolean inventoryComplete;

    public synchronized void start() {
        phase="scanning"; discovered=parsed=resolved=failed=0; inventoryComplete=false;
        started=System.nanoTime(); finished=0;
    }
    public synchronized void phase(String value) { phase=value; }
    public synchronized void discovered() { discovered++; }
    public synchronized void inventory(long count) { discovered=count; inventoryComplete=true; }
    public synchronized void inventoryComplete() { inventoryComplete=true; }
    public synchronized void parsed(boolean success) { if(success)parsed++;else failed++; }
    public synchronized void resolved() { resolved++; }
    public synchronized void finish(boolean success) { phase=success?"ready":"error"; finished=System.nanoTime(); }
    public synchronized Map<String,Object> snapshot() {
        var out=new LinkedHashMap<String,Object>();
        out.put("phase",phase); out.put("discoveredFiles",discovered); out.put("parsedFiles",parsed);
        out.put("resolvedFiles",resolved); out.put("failedFiles",failed); out.put("inventoryComplete",inventoryComplete);
        out.put("elapsedMs",started==0?0:((finished==0?System.nanoTime():finished)-started)/1_000_000);
        return out;
    }
}
