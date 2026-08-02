package io.doindev.codegraph.analysis;

import io.doindev.codegraph.config.CodeGraphConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the formula semantics — any change to these numbers is a breaking change to the audit trail. */
class BlastFormulaTest {

    private static final CodeGraphConfig.Scoring DEFAULTS = CodeGraphConfig.Scoring.defaults();

    /** The worked example from the design: D=420, F=35, M=4, L=2, untested → 83 critical. */
    @Test
    void workedExampleScoresEightyThreeCritical() {
        BlastFormula.Result result = BlastFormula.score(420, 35, 4, 2, true, DEFAULTS);
        assertEquals(83, result.score());
        assertEquals("critical", result.band());
        assertEquals(35.0, factor(result, "reach").points(), 0.11);
        assertEquals(19.45, factor(result, "fanin").points(), 0.11);
        assertEquals(16.0, factor(result, "spread").points(), 0.01);
        assertEquals(2.5, factor(result, "lang").points(), 0.01);
        assertEquals(10.0, factor(result, "untested").points(), 0.01);
    }

    @Test
    void isolatedTestedSymbolScoresZero() {
        BlastFormula.Result result = BlastFormula.score(0, 0, 0, 0, false, DEFAULTS);
        assertEquals(0, result.score());
        assertEquals("low", result.band());
    }

    @Test
    void factorsSaturateAtReferenceConstants() {
        // 10x the reference reach must not exceed the factor cap
        BlastFormula.Result atRef = BlastFormula.score(1000, 100, 5, 3, true, DEFAULTS);
        BlastFormula.Result beyond = BlastFormula.score(10_000, 1_000, 50, 30, true, DEFAULTS);
        assertEquals(atRef.score(), beyond.score());
        assertEquals(100, beyond.score());
    }

    @Test
    void bandBoundaries() {
        assertEquals("low", BlastFormula.band(24));
        assertEquals("moderate", BlastFormula.band(25));
        assertEquals("high", BlastFormula.band(50));
        assertEquals("critical", BlastFormula.band(75));
    }

    @Test
    void weightsAreConfigTunable() {
        CodeGraphConfig.Scoring reachOnly = new CodeGraphConfig.Scoring(
                java.util.Map.of("reach", 1.0, "fanin", 0.0, "spread", 0.0, "lang", 0.0, "untested", 0.0),
                1000, 100);
        BlastFormula.Result result = BlastFormula.score(1000, 0, 0, 0, true, reachOnly);
        assertEquals(100, result.score());
    }

    private static BlastFormula.Factor factor(BlastFormula.Result result, String name) {
        return result.factors().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }
}
