package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class GridExportTest {
    @Test void formatsPreserveNullsNumbersAndNeutralizeFormulas()throws Exception{
        for(String format:List.of("csv","txt","xlsx","sql")){
            var output=new ByteArrayOutputStream();
            try(var writer=new GridExports.RowWriter(output,format,List.of("text","number","nullable","empty"),List.of(Types.VARCHAR,Types.DECIMAL,Types.VARCHAR,Types.VARCHAR),true,"\"T\"",List.of("\"a\"","\"b\"","\"c\"","\"d\""),"h2")){
                writer.row(Arrays.asList("=1+1\t雪\n\"'", "12345678901234567890.123",null,""));
            }
            String data=output.toString(StandardCharsets.UTF_8);
            if(format.equals("xlsx")){
                var entries=new HashMap<String,String>();try(var zip=new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)entries.put(entry.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));}
                assertEquals(5,entries.size());data=entries.get("xl/worksheets/sheet1.xml");assertFalse(data.contains("<f>"));assertTrue(data.contains("12345678901234567890.123"));assertTrue(data.contains("\\N"));assertTrue(data.contains("t=\"inlineStr\""));
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
            }else if(!format.equals("sql"))assertTrue(data.contains("'=1+1"));else assertTrue(data.contains("INSERT INTO \"T\""));
            assertTrue(data.contains("12345678901234567890.123"));assertTrue(data.contains(format.equals("sql")?"NULL":"\\N"));
        }
    }
    @Test void limitsAndDialectEscapingNeverSilentlyTruncate()throws Exception{
        var output=new GridExports.BoundedStream(new ByteArrayOutputStream(),3);output.write(new byte[]{1,2,3});assertThrows(IOException.class,()->output.write(4));
        assertEquals("E'a\\\\b''c'",GridExports.RowWriter.literal("a\\b'c",Types.VARCHAR,"postgresql"));
        assertEquals("N'雪''a'",GridExports.RowWriter.literal("雪'a",Types.VARCHAR,"sqlserver"));
        assertEquals("CONVERT(X'610062' USING utf8mb4)",GridExports.RowWriter.literal("a\0b",Types.VARCHAR,"mysql"));
        assertEquals("IV",GridExports.RowWriter.column(255));
        assertThrows(IOException.class,()->GridExports.RowWriter.xml("\0"));
        var writer=new GridExports.RowWriter(new ByteArrayOutputStream(),"xlsx",List.of("a"),List.of(Types.VARCHAR),true,"",List.of(),"");
        assertThrows(IOException.class,()->writer.row(List.of("x".repeat(32768))));writer.close();
    }
}
