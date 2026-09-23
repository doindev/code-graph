package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.Types;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisualJoinTypesTest {
 static ObjectNode model(){var m=VisualQueryTest.model(2);m.withArray("roots").removeAll().add(VisualQueryTest.join(VisualQueryImport.source("s1"),VisualQueryImport.source("s2"),"LEFT"));pair(m).put("id","p1");return m;}
 static ObjectNode column(ObjectNode m,int source){return (ObjectNode)m.path("sources").get(source).path("columns").get(0);}
 static ObjectNode pair(ObjectNode m){return (ObjectNode)m.path("roots").get(0).path("pairs").get(0);}
 static ObjectNode mismatch(){var m=model();column(m,1).put("type","VARCHAR").put("jdbcType",Types.VARCHAR).put("precision",40);return m;}
 static ObjectNode compile(ObjectNode m,String engine){var request=Profiles.JSON.createObjectNode().put("engine",engine).put("quote","\"");request.set("model",m);return VisualQuery.compile(request);}
 static JsonNode assessment(ObjectNode m,String engine){return compile(m,engine).path("joinTypes").get(0);}
 @Test void compatibleTypesAndUnresolvedImportsAreDistinguished(){
  var m=model();column(m,1).put("type","BIGINT").put("jdbcType",Types.BIGINT);assertTrue(compile(m,"h2").path("valid").asBoolean());
  m=mismatch();var result=compile(m,"h2");assertFalse(result.path("valid").asBoolean());assertTrue(result.path("structurallyValid").asBoolean(),result.toString());assertEquals("join_type_resolution",result.path("diagnostics").get(0).path("code").asText());assertTrue(result.path("sql").asText().contains("LEFT JOIN"));
  var a=assessment(m,"h2");assertEquals("VARCHAR(40)",a.path("rightType").asText());assertEquals("VARCHAR(40)",a.path("conversions").get(0).path("castType").asText());assertEquals("INTEGER",a.path("conversions").get(1).path("castType").asText());
 }
 @Test void onlySelectedJoinOperandIsCastAndDraftRetainsDecision(){
  var m=mismatch();pair(m).put("rightCast","INTEGER");m.withObject("detail").withArray("order").addObject().put("direction","DESC").set("expression",VisualQueryTest.col("s1"));
  var result=compile(m,"h2");assertTrue(result.path("valid").asBoolean(),result.toString());String sql=result.path("sql").asText();assertTrue(sql.startsWith("SELECT \"t1\".\"ID\""));assertTrue(sql.contains("\"t1\".\"ID\" = CAST(\"t2\".\"ID\" AS INTEGER)"),sql);assertTrue(sql.endsWith("ORDER BY \"t1\".\"ID\" DESC"));assertEquals("converted",result.path("joinTypes").get(0).path("status").asText());assertEquals(m,VisualQuery.validateDraft(m));
 }
 @Test void acknowledgmentIsBoundToMetadataColumnsOperatorAndDialectButSurvivesEquivalentSwap(){
  var m=mismatch();var p=pair(m);p.put("op","<");p.put("typeAcknowledgment",assessment(m,"postgresql").path("signature").asText());assertTrue(compile(m,"postgresql").path("valid").asBoolean());
  JsonNode left=p.path("left");p.set("left",p.path("right"));p.set("right",left);p.put("op",">");assertTrue(compile(m,"postgresql").path("valid").asBoolean(),compile(m,"postgresql").toString());
  p.put("op",">=");assertFalse(compile(m,"postgresql").path("valid").asBoolean());p.put("op",">");column(m,1).put("precision",80);assertFalse(compile(m,"postgresql").path("valid").asBoolean());column(m,1).put("precision",40);assertFalse(compile(m,"h2").path("valid").asBoolean());
 }
 @Test void compositePredicatesRequireIndividualDecisions(){
  var m=mismatch();var p=pair(m);p.put("rightCast","INTEGER");((ArrayNode)m.path("roots").get(0).path("pairs")).add(p.deepCopy().put("id","p2").remove(List.of("rightCast")));
  assertFalse(compile(m,"h2").path("valid").asBoolean());var second=(ObjectNode)m.path("roots").get(0).path("pairs").get(1);second.put("typeAcknowledgment",compile(m,"h2").path("joinTypes").get(1).path("signature").asText());assertTrue(compile(m,"h2").path("valid").asBoolean());assertTrue(compile(m,"h2").path("sql").asText().contains(" AND "));
 }
 @Test void unknownTypesAndUnsupportedEnginesOfferAcknowledgmentWithoutInventedCasts(){
  var m=mismatch();column(m,1).remove("jdbcType");var a=assessment(m,"h2");assertFalse(a.path("resolved").asBoolean());for(var c:a.path("conversions")){assertFalse(c.path("available").asBoolean());assertFalse(c.path("reason").asText().isBlank());}
  pair(m).put("typeAcknowledgment",a.path("signature").asText());assertTrue(compile(m,"h2").path("valid").asBoolean());
  for(var c:assessment(mismatch(),"custom").path("conversions"))assertFalse(c.path("available").asBoolean());
 }
 @Test void dialectTargetsAndPrecisionAreBounded(){
  var expected=Map.of("postgresql",List.of("VARCHAR(40)","INTEGER"),"h2",List.of("VARCHAR(40)","INTEGER"),"mysql",List.of("CHAR(40)","SIGNED"),"sqlserver",List.of("VARCHAR(40)","INTEGER"),"oracle",List.of("VARCHAR2(40)","NUMBER(10,0)"));
  for(var entry:expected.entrySet()){var a=assessment(mismatch(),entry.getKey());assertEquals(entry.getValue().get(0),a.path("conversions").get(0).path("castType").asText(),entry.getKey());assertEquals(entry.getValue().get(1),a.path("conversions").get(1).path("castType").asText(),entry.getKey());}
  var m=mismatch();column(m,0).put("jdbcType",Types.DECIMAL).put("type","DECIMAL").put("precision",18).put("scale",5);assertEquals("DECIMAL(18,5)",assessment(m,"h2").path("conversions").get(1).path("castType").asText());column(m,0).remove("precision");assertFalse(assessment(m,"h2").path("conversions").get(1).path("available").asBoolean());
  for(String bad:List.of("INTEGER); DELETE FROM ITEM", "VARCHAR(0)", "DECIMAL(4,8)","UUID", "INTEGER(20)","VARCHAR(MAX)"))assertThrows(IllegalArgumentException.class,()->VisualJoinTypes.validateCast(bad,"mysql"),bad);
  assertEquals("VARCHAR(40)",VisualJoinTypes.validateCast("character varying (40)","postgresql"));
 }
 @Test void unsupportedSavedCastsRemainRepairableAndNeverGenerateExecutableSql(){
  var m=mismatch();pair(m).put("rightCast","INTEGER); DELETE FROM ITEM");var result=compile(m,"h2");assertFalse(result.path("valid").asBoolean());assertEquals("",result.path("sql").asText());assertFalse(result.path("joinTypes").get(0).path("resolved").asBoolean());assertEquals("INTEGER",result.path("joinTypes").get(0).path("conversions").get(1).path("castType").asText());
  for(String engine:List.of("postgresql","greenplum","yugabytedb","cockroachdb","h2","hsqldb","duckdb","trino","presto","redshift","snowflake","mysql","mariadb","sqlserver","azure-sql","oracle")){
   m=mismatch();var choice=assessment(m,engine).path("conversions").get(0);assertTrue(choice.path("available").asBoolean(),engine+": "+choice);pair(m).put("leftCast",choice.path("castType").asText());assertTrue(compile(m,engine).path("valid").asBoolean(),engine+": "+compile(m,engine));
  }
 }
 @Test void generatedAndPostgresColumnCastsImportWithoutLosingAnchors()throws Exception{
  for(String operand:List.of("CAST(t2.ID AS INTEGER)","t2.ID::integer")){
   var reader=new VisualQueryImport();reader.aliases.put("t1","s1");reader.aliases.put("t2","s2");ArrayNode pairs=Profiles.JSON.createArrayNode();reader.pairs(net.sf.jsqlparser.parser.CCJSqlParserUtil.parseCondExpression("t1.ID = "+operand),pairs);var m=mismatch();((ObjectNode)m.path("roots").get(0)).set("pairs",pairs);assertTrue(compile(m,"postgresql").path("valid").asBoolean(),compile(m,"postgresql").toString());assertEquals(VisualQueryTest.col("s2"),pairs.get(0).path("right"));
  }
  var reader=new VisualQueryImport();reader.aliases.put("t1","s1");reader.aliases.put("t2","s2");assertThrows(IllegalArgumentException.class,()->reader.pairs(net.sf.jsqlparser.parser.CCJSqlParserUtil.parseCondExpression("t1.ID = TRY_CAST(t2.ID AS INTEGER)"),Profiles.JSON.createArrayNode()));
 }
}
