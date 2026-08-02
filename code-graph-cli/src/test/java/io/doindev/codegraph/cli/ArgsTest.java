package io.doindev.codegraph.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgsTest {

    private static final Set<String> VALUES = Set.of("--root", "--base", "--threshold");
    private static final Set<String> SWITCHES = Set.of("--github-comment");

    @Test
    void parsesSpaceSeparatedValues() {
        Args args = Args.parse(List.of("--base", "main", "--root", "C:/repo"), VALUES, SWITCHES);
        assertEquals("main", args.value("--base"));
        assertEquals("C:/repo", args.value("--root"));
    }

    @Test
    void parsesEqualsSeparatedValues() {
        Args args = Args.parse(List.of("--base=origin/main", "--threshold=70"), VALUES, SWITCHES);
        assertEquals("origin/main", args.value("--base"));
        assertEquals("70", args.value("--threshold"));
    }

    @Test
    void parsesBareSwitches() {
        Args args = Args.parse(List.of("--github-comment", "--base", "main"), VALUES, SWITCHES);
        assertTrue(args.has("--github-comment"));
        assertFalse(args.has("--gitlab-comment"));
    }

    @Test
    void unknownFlagIsUsageError() {
        assertThrows(Args.UsageException.class,
                () -> Args.parse(List.of("--nope"), VALUES, SWITCHES));
    }

    @Test
    void missingValueIsUsageError() {
        assertThrows(Args.UsageException.class,
                () -> Args.parse(List.of("--base"), VALUES, SWITCHES));
    }

    @Test
    void positionalArgumentIsUsageError() {
        assertThrows(Args.UsageException.class,
                () -> Args.parse(List.of("main"), VALUES, SWITCHES));
    }

    @Test
    void switchWithValueIsUsageError() {
        assertThrows(Args.UsageException.class,
                () -> Args.parse(List.of("--github-comment=yes"), VALUES, SWITCHES));
    }

    @Test
    void requireThrowsWhenAbsentAndFallbackApplies() {
        Args args = Args.parse(List.of(), VALUES, SWITCHES);
        assertNull(args.value("--root"));
        assertEquals(".", args.value("--root", "."));
        assertThrows(Args.UsageException.class, () -> args.require("--base"));
    }

    @Test
    void mainExitsSixtyFourOnBadUsageWithoutCallingSystemExit() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(stderr);

        assertEquals(64, Main.execute(new String[] {}, err, err));
        assertEquals(64, Main.execute(new String[] {"bogus"}, err, err));
        assertEquals(64, Main.execute(new String[] {"ci"}, err, err)); // --base is required
        assertEquals(64, Main.execute(new String[] {"ci", "--base", "main", "--wat", "x"}, err, err));
        assertEquals(64, Main.execute(new String[] {"ci", "--base", "main", "--format", "xml"}, err, err));
        assertTrue(stderr.toString().contains("usage:"));
    }
}
