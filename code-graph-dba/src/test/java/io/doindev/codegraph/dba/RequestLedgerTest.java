package io.doindev.codegraph.dba;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestLedgerTest {
    @Test void fullLedgerFailsClosedAndExpiresOnlyWithItsSession(){
        AtomicLong now=new AtomicLong(1000);
        McpSessions sessions=new McpSessions(now::get);
        sessions.register("one","agent",2000);
        RequestLedger ledger=new RequestLedger();
        for(int i=0;i<2048;i++)ledger.reserve("agent","one","request-"+i,"hash-"+i,"operation-"+i);
        assertThrows(IllegalArgumentException.class,()->ledger.reserve("agent","one","overflow","hash","id"));
        ledger.reap(sessions);
        assertEquals("operation-0",ledger.lookup("agent","one","request-0","hash-0"));
        assertThrows(IllegalArgumentException.class,()->ledger.lookup("agent","one","request-0","different"));
        now.set(2001);ledger.reap(sessions);
        assertNull(ledger.lookup("agent","one","request-0","hash-0"));
        sessions.register("two","agent",3000);
        ledger.reserve("agent","two","new","hash","new-operation");
        assertEquals("new-operation",ledger.lookup("agent","two","new","hash"));
    }
    @Test void trustedLocalSessionsNeverShareRequestIds(){
        RequestLedger ledger=new RequestLedger();
        ledger.reserve("trusted-local","session-a","same-id","first","a");
        ledger.reserve("trusted-local","session-b","same-id","second","b");
        assertEquals("a",ledger.lookup("trusted-local","session-a","same-id","first"));
        assertEquals("b",ledger.lookup("trusted-local","session-b","same-id","second"));
        assertNull(ledger.lookup("another-agent","session-a","same-id","first"));
    }
}
