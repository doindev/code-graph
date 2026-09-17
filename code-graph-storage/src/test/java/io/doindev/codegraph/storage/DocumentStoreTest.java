package io.doindev.codegraph.storage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DocumentStoreTest {
 @Test void documentGenerationsPublishAtomicallyAndRemainSeparateFromGraphNodes(){for(boolean hybrid:new boolean[]{false,true})try(var storage=new GraphStorage(hybrid,32L<<20);var documents=storage.documents()){
  documents.replace(w->w.put("o/example",new byte[]{1}));assertThrows(IllegalArgumentException.class,()->documents.replace(w->{w.put("o/example",new byte[]{2});throw new IllegalArgumentException("incomplete scan");}));assertArrayEquals(new byte[]{1},documents.get("o/example"));
  documents.replace(w->w.put("o/other",new byte[]{3}));assertNull(documents.get("o/example"));assertArrayEquals(new byte[]{3},documents.get("o/other"));int[] count={0};documents.scan("o/",(k,v)->count[0]++);assertEquals(1,count[0]);
 }}
}
