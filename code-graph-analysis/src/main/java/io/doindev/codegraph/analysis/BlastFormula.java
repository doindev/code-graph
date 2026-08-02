package io.doindev.codegraph.analysis;

import io.doindev.codegraph.config.CodeGraphConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure blast-score formula — deliberately NOT PageRank: every factor is a count a reviewer
 * can reproduce with two graph queries, and the number must survive an enterprise audit.
 *
 * <pre>
 * score = round(100 × Σ wᵢ·fᵢ)
 *   reach    = min(1, log10(1+D) / log10(1+reachRef))   D = transitive dependents
 *   fanin    = min(1, log10(1+F) / log10(1+faninRef))   F = direct inbound CALLS+REFERENCES
 *   spread   = min(1, M / 5)                            M = distinct modules with dependents
 *   lang     = min(1, (L−1) / 2)                        L = distinct dependent languages
 *   untested = 1 if no dependent is a test file
 * </pre>
 *
 * Weights and reference constants come from config and are echoed in every response.
 * Bands: 0–24 low, 25–49 moderate, 50–74 high, 75–100 critical.
 */
public final class BlastFormula {

    public record Factor(String name, double raw, double normalized, double weight, double points) {
    }

    public record Result(int score, String band, List<Factor> factors) {
    }

    private BlastFormula() {
    }

    public static Result score(long dependents, long fanIn, int moduleSpread, int langCount,
                               boolean untested, CodeGraphConfig.Scoring scoring) {
        double reachNorm = Math.min(1.0, log10p1(dependents) / log10p1(scoring.reachRef()));
        double faninNorm = Math.min(1.0, log10p1(fanIn) / log10p1(scoring.faninRef()));
        double spreadNorm = Math.min(1.0, moduleSpread / 5.0);
        double langNorm = Math.min(1.0, Math.max(0, langCount - 1) / 2.0);
        double untestedNorm = untested ? 1.0 : 0.0;

        List<Factor> factors = new ArrayList<>(5);
        factors.add(factor("reach", dependents, reachNorm, scoring, 0.40));
        factors.add(factor("fanin", fanIn, faninNorm, scoring, 0.25));
        factors.add(factor("spread", moduleSpread, spreadNorm, scoring, 0.20));
        factors.add(factor("lang", langCount, langNorm, scoring, 0.05));
        factors.add(factor("untested", untested ? 1 : 0, untestedNorm, scoring, 0.10));

        int score = (int) Math.round(factors.stream().mapToDouble(Factor::points).sum());
        score = Math.max(0, Math.min(100, score));
        return new Result(score, band(score), List.copyOf(factors));
    }

    public static String band(int score) {
        if (score >= 75) {
            return "critical";
        }
        if (score >= 50) {
            return "high";
        }
        if (score >= 25) {
            return "moderate";
        }
        return "low";
    }

    private static Factor factor(String name, double raw, double normalized,
                                 CodeGraphConfig.Scoring scoring, double defaultWeight) {
        double weight = scoring.weights().getOrDefault(name, defaultWeight);
        double points = round1(100 * weight * normalized);
        return new Factor(name, raw, round3(normalized), weight, points);
    }

    private static double log10p1(double value) {
        return Math.log10(1 + value);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
