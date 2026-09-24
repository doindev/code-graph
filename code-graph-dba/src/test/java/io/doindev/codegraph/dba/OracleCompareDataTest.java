package io.doindev.codegraph.dba;

import java.io.*;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OracleCompareDataTest {
    private ResultSet result(Reader value){return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(proxy,method,args)->method.getName().equals("getCharacterStream")?value:null);}
    @Test void nullAndEmptyLobsRemainDistinctAndOversizedTextFails()throws Exception{
        var column=Profiles.JSON.createObjectNode().put("type","NCLOB");
        assertTrue(OracleCompareData.cell(null,result(null),1,column).path("value").isNull());
        var empty=OracleCompareData.cell(null,result(new StringReader("")),1,column);assertEquals("oracle_nclob",empty.path("type").asText());assertEquals("",empty.path("value").asText());
        var closed=new boolean[1];Reader large=new StringReader("x".repeat((1<<19)+1)){@Override public void close(){closed[0]=true;super.close();}};
        var failure=assertThrows(IllegalArgumentException.class,()->OracleCompareData.cell(null,result(large),1,column));assertTrue(failure.getMessage().contains("512 KiB"));assertTrue(closed[0]);
    }
    @Test void scriptChunksPreserveSupplementaryCharactersAndReleaseTemporaryLobs()throws Exception{
        String value="x".repeat(499)+"😀'\\\n"+"Ω".repeat(1200);var parts=OracleCompareData.chunks(value);assertEquals(value,String.join("",parts));for(String part:parts)assertFalse(Character.isHighSurrogate(part.charAt(part.length()-1)));
        String encoded=OracleCompareData.unicode("😀'\\\n");assertTrue(encoded.chars().allMatch(c->c<128));assertTrue(encoded.contains("\\d83d\\de00"));assertTrue(encoded.contains("''"));assertTrue(encoded.contains("\\005c"));
        var values=Profiles.JSON.createArrayNode();values.addObject().put("type","oracle_nclob").put("value",value);values.addObject().put("type","oracle_blob").put("value","ff".repeat(3000));
        var writer=new StringWriter();OracleCompareData.insert(null,writer,"stage",List.of("body","bytes"),values);String script=writer.toString();
        assertEquals(1,SqlScript.extract(script,"oracle",1<<20).size());assertTrue(script.contains("EXCEPTION WHEN OTHERS"));assertTrue(script.contains("FREETEMPORARY(v0)"));assertTrue(script.contains("FREETEMPORARY(v1)"));assertTrue(script.contains("WRITEAPPEND(v1,1000,"));
    }
    @Test void onlyKeysWithVerifiedMatchingSemanticsAreOffered(){
        var column=Profiles.JSON.createObjectNode().put("type","VARCHAR2").put("collation","USING_NLS_COMP");assertFalse(OracleCompareData.keySupported(column));column.put("collation","BINARY");assertTrue(OracleCompareData.keySupported(column));column.put("type","CHAR");assertFalse(OracleCompareData.keySupported(column));column.put("type","TIMESTAMP(9) WITH TIME ZONE");assertFalse(OracleCompareData.keySupported(column));column.put("type","NUMBER").put("generated","virtual");assertFalse(OracleCompareData.keySupported(column));
    }
}
