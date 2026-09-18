package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import io.doindev.codegraph.store.DocumentStore;

/** Supplied by the workspace composition layer; DBA has no dependency on code indexing. */
public interface ProjectContextHost {
    JsonNode projects();
    DocumentStore documents();
    default AutoCloseable hold(String projectId){return ()->{};}
    default JsonNode references(String projectId,String schema,String object){return Profiles.JSON.createArrayNode();}
    default JsonNode mappings(String projectId){return Profiles.JSON.createArrayNode();}
    static ProjectContextHost detached(){return new ProjectContextHost(){
        public JsonNode projects(){return Profiles.JSON.createArrayNode();}
        public DocumentStore documents(){return DocumentStore.memory(64L<<20);}
    };}
}
