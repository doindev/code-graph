package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlServerDesignerTest {
    static ObjectNode baseline(){var out=Profiles.JSON.createObjectNode().put("engine","sqlserver").put("schema","dbo").put("name","items").put("editable",true).put("fingerprint","revision").put("primaryKeyName","");out.putObject("fields").put("name","items").put("schema","dbo").put("owner","").put("comment","").put("tablespace","");out.putArray("columns").addObject().put("id","1").put("name","name").put("type","nvarchar(40)").put("nullable",true).put("pk",0).put("identity","").put("generated","").put("default","('old')").put("default_name","DF_old").put("comment","");out.putArray("constraints");out.putArray("indexes");return out;}
    @Test void stableRenameTypeDefaultAndCommentsCompileWithoutReplacement()throws Exception{
        var current=baseline();((ObjectNode)current.path("columns").get(0)).put("collation","SQL_Latin1_General_CP1_CI_AS");var draft=TableDesignerTest.draft(current);((ObjectNode)draft.path("columns").get(0)).put("name","display]name").put("type","nvarchar(80)").put("default","'new'").put("comment","Public label");
        var request=Profiles.JSON.createObjectNode().put("fingerprint","revision");request.set("draft",draft);var plan=TableDesigner.prepare(current,request);String sql=plan.path("commands").toString();assertTrue(plan.path("atomic").asBoolean());assertTrue(sql.contains("DROP CONSTRAINT [DF_old]"));assertTrue(sql.contains("sp_rename"));assertTrue(sql.contains("ALTER COLUMN [display]]name] nvarchar(80) COLLATE SQL_Latin1_General_CP1_CI_AS NULL"));assertTrue(sql.contains("ADD DEFAULT 'new' FOR"));assertTrue(sql.contains("sp_addextendedproperty"));assertFalse(sql.contains("DROP TABLE"));assertFalse(sql.contains("ADD COLUMN"));
    }
    @Test void unsupportedSettingsAndMalformedDraftsFailBeforeReview()throws Exception{
        var current=baseline();var draft=TableDesignerTest.draft(current);((ObjectNode)draft.path("fields")).put("owner","sa");var request=Profiles.JSON.createObjectNode().put("fingerprint","revision");request.set("draft",draft);assertThrows(IllegalArgumentException.class,()->TableDesigner.prepare(current,request));
        ((ObjectNode)draft.path("fields")).put("owner","");((ObjectNode)draft.path("columns").get(0)).put("generated","name+name");assertThrows(IllegalArgumentException.class,()->TableDesigner.prepare(current,request));
        assertThrows(IllegalArgumentException.class,()->SqlServerDesigner.type("int; DROP TABLE t"));assertEquals("[a]]b]",SqlServerDesigner.quote("a]b"));
    }
}
