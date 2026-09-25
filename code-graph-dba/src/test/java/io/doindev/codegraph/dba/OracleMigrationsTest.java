package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OracleMigrationsTest {
    @Test void targetChecksAndStructuralRemappingPreserveLiterals(){
        String source="CREATE OR REPLACE FUNCTION SOURCE.F RETURN VARCHAR2 IS BEGIN RETURN q'[SOURCE.TABLE; unchanged]'; END;";
        String mapped=OracleMigrations.remap(List.of(source),"SOURCE","DESTINATION").getFirst();assertTrue(mapped.contains("\"DESTINATION\".F"));assertTrue(mapped.contains("q'[SOURCE.TABLE; unchanged]'"));
        for(String sql:List.of("ALTER TABLE SOURCE.T ADD C NUMBER(38)","CREATE UNIQUE INDEX SOURCE.I ON SOURCE.T(C)","COMMENT ON COLUMN SOURCE.T.C IS 'x'","CREATE OR REPLACE PACKAGE BODY SOURCE.P AS PROCEDURE F IS BEGIN NULL; END; END;"))OracleMigrations.statements(List.of(sql),"SOURCE");
        for(String sql:List.of("ALTER TABLE OTHER.T ADD C NUMBER","COMMENT ON COLUMN OTHER.T.C IS 'x'","ALTER SESSION SET CURRENT_SCHEMA=OTHER","CREATE USER X IDENTIFIED BY Y","CREATE DATABASE LINK L","CREATE VIEW SOURCE.V AS SELECT * FROM T@REMOTE","CREATE PROCEDURE SOURCE.P AS BEGIN EXECUTE IMMEDIATE 'DROP TABLE T'; END;"))assertThrows(IllegalArgumentException.class,()->OracleMigrations.statements(List.of(sql),"SOURCE"),sql);
        OracleMigrations.fixture("INSERT INTO SOURCE.T VALUES (1)","SOURCE");assertThrows(IllegalArgumentException.class,()->OracleMigrations.fixture("INSERT INTO OTHER.T VALUES(1)","SOURCE"));
        for(String sql:List.of("INSERT INTO SOURCE.T SELECT ID FROM APPLICATION_RECORDS","INSERT INTO SOURCE.T VALUES(OTHER_FUNCTION())"))assertThrows(IllegalArgumentException.class,()->OracleMigrations.fixture(sql,"SOURCE"));
        OracleMigrations.execution(List.of("-- synthetic fixture\nINSERT INTO SOURCE.T VALUES(1)"),"SOURCE",true);
        var identity=Profiles.JSON.createObjectNode().put("databaseUniqueName","DB").put("containerId","3").put("owner","SOURCE");assertThrows(IllegalArgumentException.class,()->OracleMigrations.distinct(identity,identity.deepCopy().put("service","another-alias")));OracleMigrations.distinct(identity,identity.deepCopy().put("owner","DESTINATION"));
        var partial=Profiles.JSON.createObjectNode();partial.putObject("version").put("major",19);partial.putObject("resolvedTarget").put("database","PDB");assertThrows(IllegalArgumentException.class,()->OracleMigrations.snapshot(partial));
    }
}
