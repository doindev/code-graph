package io.doindev.codegraph.dba;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OracleDocumentsTest {
    private static String envelope(String content){return "<ALTER_XML xmlns=\"http://xmlns.oracle.com/ku\"><ALTER_LIST>"+content+"</ALTER_LIST></ALTER_XML>";}
    @Test void unavailableAndUnknownChangesCannotMasqueradeAsSafeSql()throws Exception{
        assertTrue(OracleDocuments.alterations(envelope("")).isEmpty());
        var missing=OracleDocuments.alterations(envelope("<ALTER_LIST_ITEM/>"));assertFalse(missing.getFirst().blocker().isBlank());
        var unavailable=OracleDocuments.alterations(envelope("<NOT_ALTERABLE/>")).getFirst();assertFalse(unavailable.blocker().isBlank());
        assertTrue(new OracleDocuments.Alter("FUTURE_CLAUSE","","",List.of("ALTER TABLE x ..."),"").destructive());
        assertFalse(new OracleDocuments.Alter("MODIFY_COLUMN","x","DEFAULT SIZE_INCREASE",List.of(),"").destructive());
        assertTrue(new OracleDocuments.Alter("MODIFY_COLUMN","x","SIZE_DECREASE",List.of(),"").destructive());
    }
    @Test void xmlRejectsExternalEntitiesAndUnexpectedDocumentTypes(){
        assertThrows(Exception.class,()->OracleDocuments.parse("<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///invalid'>]><x>&leak;</x>"));
        assertThrows(IllegalArgumentException.class,()->OracleDocuments.alterations("<ALTER_XML xmlns=\"untrusted\"/>"));
        assertThrows(IllegalArgumentException.class,()->OracleDocuments.alterations("<TABLE xmlns=\"http://xmlns.oracle.com/ku\"/>"));
    }
    @Test void documentReadsAndParsingRemainBounded()throws Exception{
        String maximum="x".repeat(OracleDocuments.MAX_CHARACTERS);
        assertEquals(maximum.length(),OracleDocuments.bounded(null,new StringReader(maximum)).length());
        assertThrows(IllegalArgumentException.class,()->OracleDocuments.bounded(null,new StringReader(maximum+"x")));
        assertThrows(IllegalArgumentException.class,()->OracleDocuments.parse(maximum+"x"));
    }
    @Test void existingIdentityStartsAreStateWhileOtherColumnAttributesRemainComparable()throws Exception{
        String before="<TABLE xmlns='http://xmlns.oracle.com/ku'><COL_LIST><COL_LIST_ITEM><NAME>ID</NAME><IDENTITY_COLUMN><START_WITH>601</START_WITH><INCREMENT>3</INCREMENT><CACHE>5</CACHE></IDENTITY_COLUMN></COL_LIST_ITEM></COL_LIST></TABLE>";
        String desired=before.replace("601","7").replace("<CACHE>5</CACHE>","<CACHE>10</CACHE>").replace("</COL_LIST>","<COL_LIST_ITEM><NAME>NEW_ID</NAME><IDENTITY_COLUMN><START_WITH>20</START_WITH></IDENTITY_COLUMN></COL_LIST_ITEM></COL_LIST>");
        String normalized=OracleDocuments.preserveIdentityStarts(before,desired);var xml=OracleDocuments.parse(normalized);var starts=xml.getElementsByTagNameNS("http://xmlns.oracle.com/ku","START_WITH");
        assertEquals("601",starts.item(0).getTextContent());assertEquals("20",starts.item(1).getTextContent());assertEquals("10",xml.getElementsByTagNameNS("http://xmlns.oracle.com/ku","CACHE").item(0).getTextContent());
    }

}
