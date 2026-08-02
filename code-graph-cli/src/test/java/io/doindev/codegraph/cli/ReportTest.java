package io.doindev.codegraph.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.analysis.BlastFormula;
import io.doindev.codegraph.rules.Drift;
import io.doindev.codegraph.smells.SmellFinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTest {

    private static CiReport.FileRow row(String path, int score, long dependents, boolean untested) {
        List<BlastFormula.Factor> factors = List.of(
                new BlastFormula.Factor("reach", dependents, 0.875, 0.40, 35.0),
                new BlastFormula.Factor("fanin", 35, 0.778, 0.25, 19.5),
                new BlastFormula.Factor("untested", untested ? 1 : 0, untested ? 1.0 : 0.0, 0.10,
                        untested ? 10.0 : 0.0));
        return new CiReport.FileRow(path, score, BlastFormula.band(score), dependents, untested,
                factors, List.of("java:src/Caller.java#Caller.run/0"), "synthetic explanation");
    }

    private static Drift.Violation violation() {
        return new Drift.Violation("layer", "web -/-> persistence", "web", "persistence",
                List.of("java:src/web/A.java#A.save/1 -> java:src/db/B.java#B.write/1"),
                "layer:web->persistence:A.save/1");
    }

    private static CiReport report(List<CiReport.FileRow> rows, List<Drift.Violation> fresh,
                                   List<String> failOn) {
        return new CiReport("main", "HEAD", "aaa111", "bbb222", 70, failOn, rows, List.of(),
                new Drift.Diff(fresh, 1, 2), List.of());
    }

    @Test
    void markdownStartsWithMarkerAndListsChangedFiles() {
        CiReport report = report(List.of(row("src/A.java", 83, 420, true),
                row("src/B.java", 12, 3, false)), List.of(), List.of("blast", "drift"));
        String md = report.toMarkdown();

        assertTrue(md.startsWith(CiReport.MARKER + "\n"), "report must lead with the marker line");
        assertTrue(md.contains("| Changed file | Blast | Band | Dependents | Untested |"));
        assertTrue(md.contains("| `src/A.java` | 83 | critical | 420 | yes |"));
        assertTrue(md.contains("| `src/B.java` | 12 | low | 3 | no |"));
        assertTrue(md.contains("base `aaa111`"));
        assertTrue(md.contains("head `bbb222`"));
    }

    @Test
    void mandatorySectionAppearsOnlyAtOrAboveThreshold() {
        CiReport above = report(List.of(row("src/A.java", 70, 420, true)), List.of(),
                List.of("blast"));
        assertTrue(above.toMarkdown().contains("## Mandatory risk review"));
        assertTrue(above.toMarkdown().contains("| reach | 420 | 0.875 | 0.4 | 35.0 |"));

        CiReport below = report(List.of(row("src/A.java", 69, 420, true)), List.of(),
                List.of("blast"));
        assertFalse(below.toMarkdown().contains("## Mandatory risk review"));
    }

    @Test
    void newViolationsAndSmellsAreRendered() {
        CiReport report = new CiReport("main", "HEAD", "aaa", "bbb", 70, List.of("drift"),
                List.of(), List.of(), new Drift.Diff(List.of(violation()), 0, 3),
                List.of(new SmellFinding("god-class", "java:src/A.java#A/0", "warning",
                        Map.of("methodCount", "31 >= 25"), "type has too many methods")));
        String md = report.toMarkdown();

        assertTrue(md.contains("**layer** web -/-> persistence — `layer:web->persistence:A.save/1`"));
        assertTrue(md.contains("witness: `java:src/web/A.java#A.save/1 -> java:src/db/B.java#B.write/1`"));
        assertTrue(md.contains("**god-class** `java:src/A.java#A/0` (warning)"));
        assertTrue(md.contains("methodCount: 31 >= 25"));
    }

    @Test
    void exitCodesCoverAllGateCombinations() {
        assertEquals(0, CiReport.exitCode(false, false));
        assertEquals(2, CiReport.exitCode(true, false));
        assertEquals(3, CiReport.exitCode(false, true));
        assertEquals(4, CiReport.exitCode(true, true));

        List<CiReport.FileRow> hot = List.of(row("src/A.java", 90, 420, true));
        List<CiReport.FileRow> cold = List.of(row("src/A.java", 10, 2, false));
        List<Drift.Violation> fresh = List.of(violation());

        assertEquals(0, report(cold, List.of(), List.of("blast", "drift")).exitCode());
        assertEquals(2, report(hot, List.of(), List.of("blast", "drift")).exitCode());
        assertEquals(3, report(cold, fresh, List.of("blast", "drift")).exitCode());
        assertEquals(4, report(hot, fresh, List.of("blast", "drift")).exitCode());
    }

    @Test
    void gatesOnlyFireForCategoriesInFailOn() {
        List<CiReport.FileRow> hot = List.of(row("src/A.java", 90, 420, true));
        List<Drift.Violation> fresh = List.of(violation());

        assertEquals(0, report(hot, fresh, List.of()).exitCode());
        assertEquals(2, report(hot, fresh, List.of("blast")).exitCode());
        assertEquals(3, report(hot, fresh, List.of("drift")).exitCode());
        assertTrue(report(hot, fresh, List.of()).verdict().equals("PASS"));
    }

    @Test
    void jsonCarriesTheSameData() throws Exception {
        CiReport report = report(List.of(row("src/A.java", 83, 420, true)),
                List.of(violation()), List.of("blast", "drift"));
        JsonNode json = new ObjectMapper().readTree(report.toJson());

        assertEquals(4, json.get("exitCode").asInt());
        assertEquals("aaa111", json.get("baseSha").asText());
        assertEquals(70, json.get("threshold").asInt());
        assertEquals("src/A.java", json.get("changedFiles").get(0).get("path").asText());
        assertEquals(83, json.get("changedFiles").get(0).get("score").asInt());
        assertEquals("layer:web->persistence:A.save/1",
                json.get("drift").get("newViolations").get(0).get("fingerprint").asText());
        assertEquals(1, json.get("drift").get("fixed").asInt());
    }

    @Test
    void pathOfExtractsRepoRelativePaths() {
        assertEquals("src/A.java", CiCommand.pathOf("java:src/A.java#A.m/1"));
        assertEquals("src/A.java", CiCommand.pathOf("file:src/A.java"));
        assertEquals("src/A.java", CiCommand.pathOf("src/A.java"));
    }
}
