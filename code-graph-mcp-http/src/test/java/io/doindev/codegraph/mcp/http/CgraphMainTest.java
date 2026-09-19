package io.doindev.codegraph.mcp.http;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CgraphMainTest {
    @Test void yoloHasNoImplicitDesktopAndRequiresDba(){
        var args=List.of(CgraphMain.effectiveArguments(new String[]{"--yolo","--no-ui"}));
        assertTrue(args.contains("--dba"));assertFalse(args.contains("--dba-approval-mode"));
        for(String[] invalid:List.of(new String[]{"--yolo=false"},new String[]{"--yolo=true"},new String[]{"--yolo","false"},new String[]{"--no-dba","--yolo"},new String[]{"--no-defaults","--yolo"}))
            assertThrows(IllegalArgumentException.class,()->CgraphMain.effectiveArguments(invalid));
    }
    @Test void defaultsMatchDesktopInstallAndDoNotOnboardTheCurrentDirectory() {
        var args=List.of(CgraphMain.effectiveArguments(new String[0]));
        assertEquals("3000",value(args,"--port")); assertEquals("8137",value(args,"--viz"));
        assertTrue(args.containsAll(List.of("--viz-admin","--dba")));
        assertEquals("hybrid",value(args,"--graph-storage"));assertEquals("1g",value(args,"--graph-memory"));
        assertEquals("desktop",value(args,"--dba-approval-mode"));assertFalse(args.contains("--root"));
    }
    @Test void explicitValuesOverrideDefaultsWithoutDuplicateFlagsAndPathsStayIntact() {
        var args=List.of(CgraphMain.effectiveArguments(new String[]{"--port=3001","--viz","8138","--graph-memory","512m","--dba-approval-mode","browser","--root","C:/repo with spaces","--root","/another repo"}));
        for(String flag:List.of("--port","--viz","--graph-memory","--dba-approval-mode"))assertEquals(1,Collections.frequency(args,flag));
        assertEquals("3001",value(args,"--port"));assertEquals("512m",value(args,"--graph-memory"));
        assertEquals("browser",value(args,"--dba-approval-mode"));assertTrue(args.contains("C:/repo with spaces"));
        assertEquals(2,Collections.frequency(args,"--root"));
    }
    @Test void optOutsAndHeadlessModeAreExplicit() {
        var args=List.of(CgraphMain.effectiveArguments(new String[]{"--no-ui","--dba-approval-mode","none"}));
        assertFalse(args.contains("--viz"));assertFalse(args.contains("--viz-admin"));assertTrue(args.contains("--dba"));
        args=List.of(CgraphMain.effectiveArguments(new String[]{"--no-dba","--no-admin"}));
        assertFalse(args.contains("--dba"));assertFalse(args.contains("--dba-approval-mode"));assertFalse(args.contains("--viz-admin"));
        assertArrayEquals(new String[]{"--port","3010"},CgraphMain.effectiveArguments(new String[]{"--no-defaults","--port","3010","--print-config"}));
        assertThrows(IllegalArgumentException.class,()->CgraphMain.effectiveArguments(new String[]{"--no-ui","--viz","8137"}));
        assertThrows(IllegalArgumentException.class,()->CgraphMain.effectiveArguments(new String[]{"--no-dba","--dba-memory","512m"}));
    }
    static String value(List<String> args,String flag){return args.get(args.indexOf(flag)+1);}
}
