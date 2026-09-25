package io.doindev.codegraph.dba;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class OracleDesignerTest {
    @Test void datatypesAreBoundedOracleDeclarations(){
        for(String value:List.of("NUMBER","number(38,-84)","NUMBER(*,127)","FLOAT(126)","VARCHAR2(4000 BYTE)","NVARCHAR2(2000)","NCHAR(1000)","RAW(2000)","TIMESTAMP(9) WITH LOCAL TIME ZONE","TIMESTAMP(0) WITH TIME ZONE","BLOB","NCLOB","INTEGER"))assertDoesNotThrow(()->OracleDesigner.type(value),value);
        for(String value:List.of("NUMBER(0)","NUMBER(39)","NUMBER(38,-85)","NUMBER(*,128)","FLOAT(127)","VARCHAR2(4001)","NVARCHAR2(2001)","NCHAR(1001)","RAW(2001)","RAW(8 CHAR)","TIMESTAMP(10)","VARCHAR2(5) DEFAULT 'x'","NUMBER); DROP TABLE ITEMS;--","MY_TYPE","BOOLEAN"))assertThrows(IllegalArgumentException.class,()->OracleDesigner.type(value),value);
    }
}
