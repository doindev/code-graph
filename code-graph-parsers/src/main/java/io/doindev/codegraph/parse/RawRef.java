package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.SourceSpan;

/**
 * An unresolved reference produced by pass 1, resolved against the global {@link SymbolTable}
 * in pass 2.
 *
 * @param from         the symbol (or file) containing the reference site
 * @param kind         what the reference means once resolved
 * @param name         referenced simple name (method or type)
 * @param receiverHint receiver/qualifier text if syntactically present ({@code foo} in
 *                     {@code foo.bar()}), else {@code null}
 * @param arity        argument count for calls, {@code -1} when not applicable
 * @param site         where the reference occurs
 */
public record RawRef(NodeId from, RefKind kind, String name, String receiverHint, int arity,
                     SourceSpan site, String locationPrecision, CallContext context, String resolutionStatus) {
    public RawRef(NodeId from,RefKind kind,String name,String receiverHint,int arity,SourceSpan site,String locationPrecision,CallContext context) {
        this(from,kind,name,receiverHint,arity,site,locationPrecision,context,null);
    }
    public RawRef unresolved(String reason) { return new RawRef(from,kind,name,receiverHint,arity,site,locationPrecision,context,reason); }
    public RawRef(NodeId from,RefKind kind,String name,String receiverHint,int arity,SourceSpan site,String locationPrecision){
        this(from,kind,name,receiverHint,arity,site,locationPrecision,null);
    }
    public RawRef(NodeId from,RefKind kind,String name,String receiverHint,int arity,SourceSpan site){
        this(from,kind,name,receiverHint,arity,site,kind==RefKind.CALL?"reference_expression":"containing_declaration");
    }
    public RawRef { if(locationPrecision==null)locationPrecision=kind==RefKind.CALL?"reference_expression":"containing_declaration"; }
}
