package io.doindev.codegraph.model;

/**
 * Cheap AST metrics collected at parse time, powering the smell detectors (god class, long
 * method, ...) and enriching blast scoring. All values are language-agnostic approximations
 * from the syntax tree — no compiler-grade semantics.
 *
 * @param loc                 lines of code spanned by the declaration
 * @param methodCount         methods/functions declared directly in a type (types only)
 * @param fieldCount          fields declared directly in a type (types only)
 * @param paramCount          declared parameters (functions only)
 * @param maxNestingDepth     deepest block nesting inside the declaration body
 * @param cyclomaticApprox    1 + count of branch/loop/case/logical-operator nodes
 * @param internalCallDensity fraction of a type's intra-type call pairs that exist (cohesion, 0..1; NaN if unknown)
 */
public record Metrics(int loc, int methodCount, int fieldCount, int paramCount,
                      int maxNestingDepth, int cyclomaticApprox, float internalCallDensity) {

    /** Placeholder for nodes without metrics (repository, module, unparsed files). */
    public static final Metrics NONE = new Metrics(0, 0, 0, 0, 0, 0, Float.NaN);
}
