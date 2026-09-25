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

    @Test void nativeDiagnosticCommentsBlockGenerationEvenBesideExecutableChanges()throws Exception{
        String xml=envelope("<ALTER_LIST_ITEM><SQL_LIST><SQL_LIST_ITEM><TEXT>ALTER TABLE x ADD y NUMBER</TEXT></SQL_LIST_ITEM><SQL_LIST_ITEM><TEXT>-- ORA-39297: Cannot alter materialized view attribute: SUBQUERY</TEXT></SQL_LIST_ITEM></SQL_LIST></ALTER_LIST_ITEM>");
        assertTrue(OracleDocuments.alterations(xml).getFirst().blocker().contains("ORA-39297"));
        assertFalse(OracleDocuments.alterations(envelope("<ALTER_LIST_ITEM><SQL_LIST><SQL_LIST_ITEM><TEXT>-- metadata only</TEXT></SQL_LIST_ITEM></SQL_LIST></ALTER_LIST_ITEM>")).getFirst().blocker().isBlank());
    }
    @Test void sqlExpressionRemappingChangesQualifiedOwnersAndPreservesLiteralsAndNames()throws Exception{
        String xml="<VIEW xmlns='http://xmlns.oracle.com/ku'><NAME>OLD.name</NAME><SUBQUERY>SELECT 'OLD.x',q'[OLD.y]' FROM OLD.ITEMS</SUBQUERY><DEFAULT>OLD.COUNTER.NEXTVAL</DEFAULT><VIRTUAL>OLD.FUNC(x)</VIRTUAL><CONDITION>x &gt; 0</CONDITION><DEFAULT_EXPRESSION>OLD.FUNC(x)</DEFAULT_EXPRESSION></VIEW>";
        var result=OracleDocuments.parse(OracleDocuments.remapSqlExpressions(xml,java.util.Map.of("OLD","NEW")));
        assertEquals("OLD.name",result.getElementsByTagNameNS("http://xmlns.oracle.com/ku","NAME").item(0).getTextContent());
        String query=result.getElementsByTagNameNS("http://xmlns.oracle.com/ku","SUBQUERY").item(0).getTextContent();assertTrue(query.contains("'OLD.x'"));assertTrue(query.contains("q'[OLD.y]'"));assertTrue(query.contains("\"NEW\".ITEMS"));
        assertEquals("\"NEW\".COUNTER.NEXTVAL",result.getElementsByTagNameNS("http://xmlns.oracle.com/ku","DEFAULT").item(0).getTextContent());
    }

}
