package io.doindev.codegraph.index.mapping;

import java.util.*;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Deliberately static framework adapters. Naming strategies and computed expressions stay uncertain. */
final class OrmMappings {
    record Annotation(String name,List<SourceTokens.Token> args,int offset,int end){}
    static List<Annotation> annotations(List<SourceTokens.Token> tokens,int from,int to){
        var out=new ArrayList<Annotation>();
        for(int i=from;i+1<to;i++)if(tokens.get(i).text().equals("@")){
            int begin=i;String name=tokens.get(++i).text();
            while(i+2<to&&tokens.get(i+1).text().equals(".")){i+=2;name=tokens.get(i).text();}
            List<SourceTokens.Token> args=List.of();
            if(i+1<to&&tokens.get(i+1).text().equals("(")){int end=SourceTokens.end(tokens,i+1,"(",")");if(end<0||end>=to)continue;args=tokens.subList(i+2,end);i=end;}
            out.add(new Annotation(name,args,tokens.get(begin).offset(),i+1));
        }
        return out;
    }
    static String option(Annotation annotation,String key){
        var args=annotation.args();
        for(int i=0;i+2<args.size();i++)if(args.get(i).text().equals(key)&&Set.of("=",":").contains(args.get(i+1).text())){
            var value=args.get(i+2);if(value.dynamic())return null;
            if(i+3<args.size()&&!Set.of(",","}").contains(args.get(i+3).text()))return null;
            return value.string()||Set.of("true","false").contains(value.text())?value.text():null;
        }
        return null;
    }
    static String firstString(Annotation annotation){var args=annotation.args();return !args.isEmpty()&&args.getFirst().string()&&!args.getFirst().dynamic()&&(args.size()==1||args.get(1).text().equals(","))?args.getFirst().text():null;}
    private static String value(Annotation annotation,String key,String fallback){return Objects.toString(option(annotation,key),fallback);}
    static void annotations(MappingEvidence evidence,List<SourceTokens.Token> tokens,boolean java){
        int prior=0;
        for(int i=0;i+2<tokens.size();i++){
            if(tokens.get(i).string()||!Set.of("class","record").contains(tokens.get(i).text()))continue;
            String model=tokens.get(i+1).text();int body=i+2;
            while(body<tokens.size()&&!tokens.get(body).text().equals("{"))body++;
            if(body==tokens.size())break;int end=SourceTokens.end(tokens,body,"{","}");if(end<0)break;
            var annotations=annotations(tokens,prior,i);Annotation entity=null,tableConfig=null;
            for(var annotation:annotations){if(annotation.name().equals("Entity"))entity=annotation;if(annotation.name().equals("Table"))tableConfig=annotation;}
            if(entity==null&&tableConfig==null){prior=end+1;i=end;continue;}
            String framework=java?"jpa":"typeorm",table=model,schema="";boolean explicit=false;
            Annotation config=java?tableConfig:entity;
            if(config!=null){String named=option(config,"name");if(!java&&named==null)named=firstString(config);if(named!=null){table=named;explicit=true;}schema=value(config,"schema","");}
            var base=new HashMap<String,String>();base.put("model",model);base.put("nameResolution",explicit?"explicit_mapping":"default_naming_candidate");
            if(!explicit)base.put("uncertainty","Physical naming strategies and overrides are not evaluated");
            if(config!=null)base.put("database",value(config,java?"catalog":"database",""));
            evidence.add(tokens.get(i).offset(),framework,schema,table,"","maps_table",explicit?.9:.5,base);
            for(var annotation:annotations(tokens,body+1,end)){
                if(!Set.of("Column","JoinColumn","PrimaryColumn","PrimaryGeneratedColumn","CreateDateColumn","UpdateDateColumn","VersionColumn","Id").contains(annotation.name()))continue;
                int next=annotation.end();
                // Skip stacked annotations before the actual field/property declaration.
                while(next<end&&tokens.get(next).text().equals("@")){
                    var rest=annotations(tokens,next,end);if(rest.isEmpty())break;next=rest.getFirst().end();
                }
                var declaration=new ArrayList<SourceTokens.Token>();
                while(next<end&&declaration.size()<32&&!Set.of(";","=","{","}").contains(tokens.get(next).text())){declaration.add(tokens.get(next++));if(declaration.getLast().text().equals("("))break;}
                String field="",type="";
                if(java){
                    var names=declaration.stream().filter(t->!t.string()&&t.text().matches("[\\p{L}_$][\\p{L}\\p{N}_$]*")).toList();
                    if(!names.isEmpty())field=names.getLast().text();if(names.size()>1)type=names.get(names.size()-2).text();
                    if(field.startsWith("get")&&field.length()>3&&declaration.stream().anyMatch(t->t.text().equals("(")))field=Character.toLowerCase(field.charAt(3))+field.substring(4);
                }else{
                    int colon=-1;for(int d=0;d<declaration.size();d++)if(declaration.get(d).text().equals(":")){colon=d;break;}
                    if(colon>=1){int name=colon-1;while(name>0&&Set.of("!","?").contains(declaration.get(name).text()))name--;field=declaration.get(name).text();if(colon+1<declaration.size())type=declaration.get(colon+1).text();}
                }
                String named=option(annotation,"name"),column=Objects.toString(named,field);
                if(column.isBlank()){evidence.uncertain(annotation.offset(),framework,"Computed or unrecognized mapped field declaration");continue;}
                var attrs=new HashMap<>(base);attrs.put("field",field);attrs.put("sourceType",type);
                attrs.put("nameResolution",named==null?"default_naming_candidate":"explicit_mapping");
                if(named==null)attrs.put("uncertainty","Column naming strategy is not evaluated");
                for(String property:List.of("nullable","referencedColumnName")){String v=option(annotation,property);if(v!=null)attrs.put(property,v);}
                if(annotation.name().contains("Primary")||annotation.name().equals("Id"))attrs.put("primaryKey","true");
                if(annotation.name().equals("JoinColumn"))attrs.put("uncertainty","Referenced table requires relationship target resolution; no runtime metadata was loaded");
                String dbType=option(annotation,"type");if(dbType==null&&!java)dbType=firstString(annotation);if(dbType!=null)attrs.put("databaseType",dbType);
                evidence.add(annotation.offset(),framework,schema,table,column,annotation.name().equals("JoinColumn")?"maps_foreign_key":"maps_column",named==null?.5:.85,attrs);
            }
            prior=end+1;i=end;
        }
    }
    static void prisma(MappingEvidence evidence,List<SourceTokens.Token> tokens){
        Set<String> scalar=Set.of("String","Boolean","Int","BigInt","Float","Decimal","DateTime","Json","Bytes","Unsupported");
        for(int i=0;i+2<tokens.size();i++)if(Set.of("model","view").contains(tokens.get(i).text())&&!tokens.get(i).string()){
            String model=tokens.get(i+1).text();int begin=i+2;if(!tokens.get(begin).text().equals("{"))continue;
            int end=SourceTokens.end(tokens,begin,"{","}");if(end<0)break;String table=model,schema="";
            for(int p=begin+1;p+4<end;p++)if(tokens.get(p).text().equals("@")&&tokens.get(p+1).text().equals("@")&&tokens.get(p+3).text().equals("(")&&tokens.get(p+4).string()){
                if(tokens.get(p+2).text().equals("map"))table=tokens.get(p+4).text();if(tokens.get(p+2).text().equals("schema"))schema=tokens.get(p+4).text();
            }
            evidence.add(tokens.get(i).offset(),"prisma",schema,table,"","maps_table",.95,Map.of("model",model));
            for(int p=begin+1;p+1<end;p++){
                var token=tokens.get(p);if(token.string()||!token.text().matches("[\\p{L}_][\\p{L}\\p{N}_]*")||!scalar.contains(tokens.get(p+1).text()))continue;
                String field=token.text(),type=tokens.get(p+1).text(),column=field;int lineEnd=evidence.source.content().indexOf('\n',token.offset());if(lineEnd<0)lineEnd=evidence.source.content().length();
                int stop=p+2;while(stop<end&&tokens.get(stop).offset()<lineEnd)stop++;
                var attrs=new HashMap<String,String>();attrs.put("model",model);attrs.put("field",field);attrs.put("sourceType",type);
                attrs.put("nullable",String.valueOf(p+2<end&&tokens.get(p+2).text().equals("?")));
                for(var annotation:annotations(tokens,p+2,stop)){
                    if(annotation.name().equals("map")){String v=firstString(annotation);if(v!=null)column=v;}
                    if(annotation.name().equals("id"))attrs.put("primaryKey","true");
                }
                evidence.add(token.offset(),"prisma",schema,table,column,"maps_column",.95,attrs);p=stop-1;
            }
            i=end;
        }
        prismaRelations(evidence,tokens);
    }
    private record PrismaModel(String schema,String table,Map<String,String> columns){}
    private static void prismaRelations(MappingEvidence evidence,List<SourceTokens.Token> tokens){
        var models=new HashMap<String,PrismaModel>();
        for(var node:evidence.nodes){var attrs=node.attrs();if(!"prisma".equals(attrs.get("framework")))continue;
            String model=attrs.get("model");if(model==null)continue;
            var descriptor=models.computeIfAbsent(model,_ ->new PrismaModel(attrs.getOrDefault("schema",""),attrs.getOrDefault("table",""),new HashMap<>()));
            if(attrs.containsKey("field"))descriptor.columns().put(attrs.get("field"),attrs.get("column"));
        }
        for(int i=0;i+2<tokens.size();i++)if(tokens.get(i).text().equals("model")&&!tokens.get(i).string()){
            var source=models.get(tokens.get(i+1).text());int begin=i+2;if(source==null||!tokens.get(begin).text().equals("{"))continue;
            int end=SourceTokens.end(tokens,begin,"{","}");if(end<0)break;
            for(int p=begin+1;p+1<end;p++){
                var target=models.get(tokens.get(p+1).text());if(target==null||tokens.get(p).string())continue;
                int lineEnd=evidence.source.content().indexOf('\n',tokens.get(p).offset());if(lineEnd<0)lineEnd=evidence.source.content().length();
                int stop=p+2;while(stop<end&&tokens.get(stop).offset()<lineEnd)stop++;
                for(var relation:annotations(tokens,p+2,stop))if(relation.name().equals("relation")){
                    var fields=arrayOption(relation,"fields");var references=arrayOption(relation,"references");
                    if(fields.isEmpty()||fields.size()!=references.size()){evidence.uncertain(relation.offset(),"prisma","Implicit or unsupported relation key mapping");continue;}
                    for(int f=0;f<fields.size();f++){
                        String from=source.columns().get(fields.get(f)),to=target.columns().get(references.get(f));
                        if(from==null||to==null){evidence.uncertain(relation.offset(),"prisma","Relation key column could not be statically mapped");continue;}
                        evidence.add(relation.offset(),"prisma",source.schema(),source.table(),from,"maps_foreign_key",.95,
                                Map.of("targetTable",target.table(),"targetSchema",target.schema(),"targetColumn",to,"keyPosition",String.valueOf(f+1)));
                    }
                }
                p=stop-1;
            }
            i=end;
        }
    }
    private static List<String> arrayOption(Annotation annotation,String key){
        var args=annotation.args();var out=new ArrayList<String>();
        for(int i=0;i+2<args.size();i++)if(args.get(i).text().equals(key)&&args.get(i+1).text().equals(":")&&args.get(i+2).text().equals("[")){
            for(int p=i+3;p<args.size();p++){
                String value=args.get(p).text();if(value.equals("]"))return out;
                if(value.equals(","))continue;
                if(args.get(p).string()||!value.matches("[\\p{L}_][\\p{L}\\p{N}_]*")||out.size()>=32)return List.of();out.add(value);
            }
        }
        return List.of();
    }
    static void mybatis(MappingEvidence evidence){
        // Only mapper documents are relevant. Never resolve external DTDs, schemas or entities.
        if(!evidence.source.content().contains("<mapper"))return;
        try{
            var factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
            var reader=factory.newSAXParser().getXMLReader();reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,"");reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            reader.setProperty("http://www.oracle.com/xml/jaxp/properties/entityExpansionLimit","128");
            reader.setProperty("http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit","32768");
            reader.setEntityResolver((publicId,systemId)->new InputSource(new StringReader("")));
            reader.setErrorHandler(new DefaultHandler(){public void error(SAXParseException e)throws SAXException{throw e;}public void fatalError(SAXParseException e)throws SAXException{throw e;}});
            reader.setContentHandler(new DefaultHandler(){
                Locator locator;String statement;StringBuilder sql;boolean dynamic;int offset,count;
                public void setDocumentLocator(Locator value){locator=value;}
                public void startElement(String uri,String local,String qname,Attributes attrs){
                    if(Set.of("select","insert","update","delete").contains(local)&&statement==null){statement=local;sql=new StringBuilder();dynamic=false;offset=lineOffset(evidence.source.content(),locator.getLineNumber());}
                    else if(sql!=null)dynamic=true;
                    if(Set.of("result","id").contains(local)&&attrs.getValue("column")!=null)evidence.add(lineOffset(evidence.source.content(),locator.getLineNumber()),"mybatis","","",attrs.getValue("column"),"unresolved_column",.5,Map.of("field",Objects.toString(attrs.getValue("property"),""),"uncertainty","Result map requires statement/table association"));
                }
                public void characters(char[] chars,int start,int length){if(sql!=null){if(sql.length()+length>32768)dynamic=true;else sql.append(chars,start,length);}}
                public void endElement(String uri,String local,String qname){
                    if(sql==null||!local.equals(statement))return;
                    if(count++>=32||dynamic||sql.indexOf("${")>=0)evidence.uncertain(offset,"mybatis","Dynamic SQL/include or statement bound prevents static resolution");
                    else SqlMappings.extract(evidence,sql.toString().replaceAll("#\\{[^}]{1,256}}","?"),offset,"mybatis");
                    statement=null;sql=null;
                }
            });
            reader.parse(new InputSource(new StringReader(evidence.source.content())));
        }catch(Exception e){evidence.uncertain(0,"mybatis","Mapper XML could not be parsed safely; external entities are disabled");}
    }
    private static int lineOffset(String source,int line){int offset=0;for(int n=1;n<line;n++){int next=source.indexOf('\n',offset);if(next<0)return offset;offset=next+1;}return offset;}
}
