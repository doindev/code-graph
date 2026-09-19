package io.doindev.codegraph.parse;

import java.util.List;

/** One indexed module's export evidence and declaration references; no source text or graph copy. */
public record ModuleFile(String path, ModuleEvidence evidence, List<SymbolTable.Entry> declarations) {
    public ModuleFile { declarations = List.copyOf(declarations); }
    public static ModuleFile of(FileFragment fragment) {
        return new ModuleFile(fragment.file().relPath(), fragment.modules(),
                fragment.declarations().stream().filter(n -> n.id() instanceof io.doindev.codegraph.model.SymbolId
                        && n.kind() != io.doindev.codegraph.model.NodeKind.DATABASE_MAPPING).map(SymbolTable.Entry::of).toList());
    }
}
