package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.SourceSpan;
import java.util.List;

/** Syntax evidence, not evaluated project code. Empty strings mean no module/name. */
public record ModuleEvidence(List<Binding> bindings, List<Export> exports, List<Shadow> shadows, boolean unsafeExports) {
    public static final ModuleEvidence EMPTY = new ModuleEvidence(List.of(), List.of(), List.of());
    public ModuleEvidence(List<Binding> bindings,List<Export> exports,List<Shadow> shadows) {this(bindings,exports,shadows,false);}
    public ModuleEvidence {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        exports = exports == null ? List.of() : List.copyOf(exports);
        shadows = shadows == null ? List.of() : List.copyOf(shadows);
    }
    public record Binding(String module, String exported, String local, String kind, boolean typeOnly,
                          SourceSpan site, SourceSpan scope) {}
    public record Export(String exported, String local, String module, boolean star, boolean typeOnly, SourceSpan site) {}
    public record Shadow(String name, SourceSpan site, SourceSpan scope) {}
}
