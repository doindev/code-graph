package io.doindev.codegraph.dba;

import java.io.*;
import java.sql.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Bounded Oracle metadata transforms. CONVERT returns text; this class never submits DDL. */
final class OracleDocuments {
    static final int MAX_CHARACTERS=4<<20;
    private OracleDocuments(){}
    record Alter(String clause,String name,String attribute,List<String> statements,String blocker){
        boolean destructive(){return !(clause.equals("ADD_COLUMN")||clause.equals("MODIFY_COLUMN")&&attribute.matches("(?:DEFAULT|SIZE_INCREASE|DROP_NOT_NULL)(?: (?:DEFAULT|SIZE_INCREASE|DROP_NOT_NULL))*"));}
    }
    static void check(QueryJobs.Job job){if(job!=null)CompareCatalog.check(job);}
    static int timeout(QueryJobs.Job job){return job==null?30:job.remainingSeconds();}
    static String bounded(QueryJobs.Job job,Reader reader)throws Exception{
        if(reader==null)return "";try(reader){var value=new StringBuilder();char[] buffer=new char[4096];int count;
            while((count=reader.read(buffer))!=-1){check(job);if(value.length()+count>MAX_CHARACTERS)throw new IllegalArgumentException("Oracle metadata document exceeds 4 MiB; narrow the comparison");value.append(buffer,0,count);}return value.toString();}
    }
    static String capture(QueryJobs.Job job,Connection c,String type,String owner,String name,String format)throws Exception{
        if(!Set.of("XML","SXML","DDL").contains(format))throw new IllegalArgumentException("Invalid Oracle metadata format");
        Statement prior=job==null?null:job.statement;
        try(var statement=c.prepareStatement("SELECT SYS.DBMS_METADATA.GET_"+format+"(?,?,?) FROM SYS.DUAL")){
            if(job!=null)job.statement=statement;statement.setQueryTimeout(timeout(job));statement.setMaxRows(1);statement.setString(1,type);statement.setString(2,name);statement.setString(3,owner);
            try(var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("Oracle metadata was not returned for "+owner+"."+name);return bounded(job,rows.getCharacterStream(1));}
        }finally{if(job!=null)job.statement=prior;}
    }
    static String remap(QueryJobs.Job job,Connection c,String type,String document,String from,String to,boolean sxml,boolean ddl)throws Exception{
        return transform(job,c,type,document,from,to,sxml?"MODIFYSXML":"MODIFY",ddl?(sxml?"SXMLDDL":"DDL"):"");
    }
    static String simplified(QueryJobs.Job job,Connection c,String type,String xml,String from,String to)throws Exception{return transform(job,c,type,xml,from,to,"MODIFY","SXML");}
    private static String transform(QueryJobs.Job job,Connection c,String type,String document,String from,String to,String modify,String output)throws Exception{
        String block="""
            DECLARE h NUMBER; t NUMBER; result CLOB;
            BEGIN
              h:=SYS.DBMS_METADATA.OPENW(?);
              t:=SYS.DBMS_METADATA.ADD_TRANSFORM(h,?);
              SYS.DBMS_METADATA.SET_REMAP_PARAM(t,'REMAP_SCHEMA',?,?);
            """+(!output.isEmpty()?"t:=SYS.DBMS_METADATA.ADD_TRANSFORM(h,'"+output+"');\n":"")+"""
              SYS.DBMS_LOB.CREATETEMPORARY(result,TRUE);
              SYS.DBMS_METADATA.CONVERT(h,?,result);
              SYS.DBMS_METADATA.CLOSE(h); h:=NULL; ?:=result;
            EXCEPTION WHEN OTHERS THEN
              IF h IS NOT NULL THEN SYS.DBMS_METADATA.CLOSE(h); END IF;
              IF SYS.DBMS_LOB.ISTEMPORARY(result)=1 THEN SYS.DBMS_LOB.FREETEMPORARY(result); END IF;
              RAISE;
            END;
            """;
        return call(job,c,block,List.of(type,modify,from,to,document));
    }
    static List<Alter> changes(QueryJobs.Job job,Connection c,String type,String before,String desired)throws Exception{
        String block="""
            DECLARE d NUMBER; w NUMBER; t NUMBER; delta CLOB; result CLOB;
            BEGIN
              d:=SYS.DBMS_METADATA_DIFF.OPENC(?);
              SYS.DBMS_METADATA_DIFF.ADD_DOCUMENT(d,?);
              SYS.DBMS_METADATA_DIFF.ADD_DOCUMENT(d,?);
              delta:=SYS.DBMS_METADATA_DIFF.FETCH_CLOB(d); SYS.DBMS_METADATA_DIFF.CLOSE(d); d:=NULL;
              w:=SYS.DBMS_METADATA.OPENW(?);
              t:=SYS.DBMS_METADATA.ADD_TRANSFORM(w,'ALTERXML');
              SYS.DBMS_METADATA.SET_PARSE_ITEM(w,'CLAUSE_TYPE');
              SYS.DBMS_METADATA.SET_PARSE_ITEM(w,'NAME');
              SYS.DBMS_METADATA.SET_PARSE_ITEM(w,'COLUMN_ATTRIBUTE');
              SYS.DBMS_LOB.CREATETEMPORARY(result,TRUE);
              SYS.DBMS_METADATA.CONVERT(w,delta,result); SYS.DBMS_METADATA.CLOSE(w); w:=NULL;
              ?:=result; SYS.DBMS_LOB.FREETEMPORARY(delta);
            EXCEPTION WHEN OTHERS THEN
              IF d IS NOT NULL THEN SYS.DBMS_METADATA_DIFF.CLOSE(d); END IF;
              IF w IS NOT NULL THEN SYS.DBMS_METADATA.CLOSE(w); END IF;
              IF SYS.DBMS_LOB.ISTEMPORARY(delta)=1 THEN SYS.DBMS_LOB.FREETEMPORARY(delta); END IF;
              IF SYS.DBMS_LOB.ISTEMPORARY(result)=1 THEN SYS.DBMS_LOB.FREETEMPORARY(result); END IF;
              RAISE;
            END;
            """;
        return alterations(call(job,c,block,List.of(type,before,desired,type)));
    }
    private static String call(QueryJobs.Job job,Connection c,String sql,List<String> arguments)throws Exception{
        for(String argument:arguments)if(argument.length()>MAX_CHARACTERS)throw new IllegalArgumentException("Oracle metadata document exceeds 4 MiB");
        Statement prior=job==null?null:job.statement;
        try(var statement=c.prepareCall(sql)){
            check(job);if(job!=null)job.statement=statement;statement.setQueryTimeout(timeout(job));int index=1;
            for(String argument:arguments){if(argument.stripLeading().startsWith("<"))statement.setCharacterStream(index++,new StringReader(argument));else statement.setString(index++,argument);}
            statement.registerOutParameter(index,Types.CLOB);statement.execute();check(job);Clob result=statement.getClob(index);
            if(result==null)return "";try{return bounded(job,result.getCharacterStream());}finally{result.free();}
        }finally{if(job!=null)job.statement=prior;}
    }
    static Document parse(String xml)throws Exception{
        if(xml.length()>MAX_CHARACTERS)throw new IllegalArgumentException("Oracle metadata document exceeds 4 MiB");
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth",128);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
    static List<Alter> alterations(String xml)throws Exception{
        Document document=parse(xml);Element root=document.getDocumentElement();String ns="http://xmlns.oracle.com/ku";
        if(!root.getLocalName().equals("ALTER_XML")||!ns.equals(root.getNamespaceURI()))throw new IllegalArgumentException("Unexpected Oracle alteration document");
        var changes=new ArrayList<Alter>();NodeList list=root.getElementsByTagNameNS(ns,"ALTER_LIST_ITEM");
        if(list.getLength()>10_000)throw new IllegalArgumentException("Too many Oracle alteration clauses");
        for(int i=0;i<list.getLength();i++){
            Element item=(Element)list.item(i);var attributes=new HashMap<String,String>();NodeList parsed=item.getElementsByTagNameNS(ns,"PARSE_LIST_ITEM");
            for(int j=0;j<parsed.getLength();j++){Element entry=(Element)parsed.item(j);attributes.put(text(entry,"ITEM"),text(entry,"VALUE"));}
            var sql=new ArrayList<String>();NodeList statements=item.getElementsByTagNameNS(ns,"SQL_LIST_ITEM");for(int j=0;j<statements.getLength();j++){String value=text((Element)statements.item(j),"TEXT");if(!value.isBlank())sql.add(value);}
            String blocker=text(item,"NOT_ALTERABLE");if(sql.isEmpty()&&blocker.isBlank())blocker="Oracle returned a change without executable SQL";
            changes.add(new Alter(attributes.getOrDefault("CLAUSE_TYPE",""),attributes.getOrDefault("NAME",""),attributes.getOrDefault("COLUMN_ATTRIBUTE",""),List.copyOf(sql),blocker));
        }
        if(root.getElementsByTagNameNS(ns,"NOT_ALTERABLE").getLength()>0&&changes.stream().allMatch(a->a.blocker().isBlank()))changes.add(new Alter("","","",List.of(),"Oracle cannot alter this definition in place"));
        return List.copyOf(changes);
    }
    private static String text(Element element,String name){NodeList nodes=element.getElementsByTagNameNS("http://xmlns.oracle.com/ku",name);return nodes.getLength()==0?"":nodes.item(0).getTextContent();}
}
