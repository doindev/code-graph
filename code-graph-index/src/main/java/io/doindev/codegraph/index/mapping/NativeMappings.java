package io.doindev.codegraph.index.mapping;

import java.util.*;

/** Bounded literal mappings only. Never evaluates framework code or retains Redis values/documents. */
final class NativeMappings {
    static void extract(MappingEvidence out,List<SourceTokens.Token> tokens,boolean java){
        if(java)spring(out,tokens);else javascript(out,tokens);
    }
    private static void spring(MappingEvidence out,List<SourceTokens.Token> t){
        Set<String> imports=new HashSet<>();
        for(int i=0;i<t.size();i++)if(text(t,i).equals("import")){StringBuilder name=new StringBuilder();for(int j=i+1;j<t.size()&&!text(t,j).equals(";");j++){if(j-i>64)break;name.append(text(t,j));}imports.add(name.toString());}
        boolean mongo=imports.contains("org.springframework.data.mongodb.core.mapping.Document"),redis=imports.contains("org.springframework.data.redis.core.RedisHash");
        if(!mongo&&!redis)return;int prior=0;
        for(int i=0;i+2<t.size();i++){
            if(t.get(i).string()||!Set.of("class","record").contains(text(t,i)))continue;int body=i+2;
            while(body<t.size()&&!text(t,body).equals("{"))body++;if(body==t.size())break;int end=SourceTokens.end(t,body,"{","}");if(end<0)break;
            OrmMappings.Annotation config=null;for(var annotation:OrmMappings.annotations(t,prior,i))if(mongo&&annotation.name().equals("Document")||redis&&annotation.name().equals("RedisHash"))config=annotation;
            if(config==null){prior=end+1;i=end;continue;}boolean document=config.name().equals("Document");String framework=document?"spring-data-mongodb":"spring-data-redis";
            String name=OrmMappings.option(config,document?"collection":"value");if(name==null)name=OrmMappings.option(config,"value");if(name==null)name=OrmMappings.firstString(config);
            if(name==null||name.isBlank()||name.contains("#{")||name.contains("${")){out.uncertain(config.offset(),framework,"Computed or implicit collection/keyspace naming is not resolved");prior=end+1;i=end;continue;}
            Map<String,String> base=new HashMap<>();base.put("transport",document?"mongodb":"redis");base.put("model",text(t,i+1));base.put("nativeEvidence","declared_mapping");base.put("nameResolution","explicit_mapping");base.put("uncertainty","Runtime custom converters and naming strategies are not evaluated");
            if(!document){base.put("databaseType","hash");String ttl=integerOption(config.args(),"timeToLive");if(ttl!=null)base.put("ttlPolicy",Long.parseLong(ttl)>0?"expiring":"persistent");}
            out.add(config.offset(),framework,"",name,"",document?"maps_collection":"maps_key_namespace",.9,base);
            if(document){
                for(var annotation:OrmMappings.annotations(t,body+1,end)){
                    if(!annotation.name().equals("Field")||!imports.contains("org.springframework.data.mongodb.core.mapping.Field"))continue;
                    int next=annotation.end();List<String> declaration=new ArrayList<>();while(next<end&&declaration.size()<16&&!Set.of(";","=","(","{","@","}").contains(text(t,next)))declaration.add(text(t,next++));
                    if(declaration.size()<2||Set.of("(","{","@").contains(text(t,next))||declaration.contains("static"))continue;
                    String field=declaration.getLast(),type=declaration.get(declaration.size()-2),column=OrmMappings.option(annotation,"name");if(column==null)column=OrmMappings.option(annotation,"value");if(column==null)column=OrmMappings.firstString(annotation);if(column==null)column=field;
                    if(!field.matches("[A-Za-z_$][\\w$]*")||column.contains("#{")||column.contains("${"))continue;
                    var attrs=new HashMap<>(base);attrs.put("field",field);attrs.put("sourceType",type);String bson=bson(type,true);if(bson!=null&&!annotation.args().stream().anyMatch(token->token.text().equals("targetType")))attrs.put("databaseType",bson);
                    out.add(annotation.offset(),framework,"",name,column,"maps_document_field",.8,attrs);
                }
            }
            prior=end+1;i=end;
        }
    }
    private static String integerOption(List<SourceTokens.Token> t,String key){for(int i=0;i+2<t.size();i++)if(text(t,i).equals(key)&&text(t,i+1).equals("=")&&text(t,i+2).matches("[0-9]{1,9}"))return text(t,i+2);return null;}
    private static void javascript(MappingEvidence out,List<SourceTokens.Token> t){
        Set<String> mongoose=modules(t,"mongoose"),redis=modules(t,"redis");
        for(int i=0;i+5<t.size();i++){
            if(text(t,i).equals("new")&&mongoose.contains(text(t,i+1))&&text(t,i+2).equals(".")&&text(t,i+3).equals("Schema")&&text(t,i+4).equals("(")&&text(t,i+5).equals("{")){
                int fieldsEnd=SourceTokens.end(t,i+5,"{","}"),callEnd=SourceTokens.end(t,i+4,"(",")");if(fieldsEnd<0||callEnd<0)continue;
                String collection=literalProperty(t,fieldsEnd+1,callEnd,"collection");if(collection==null){out.uncertain(t.get(i).offset(),"mongoose","Schema collection must be an explicit literal option; model pluralization and runtime options are not resolved");i=callEnd;continue;}
                Map<String,String> base=Map.of("transport","mongodb","nameResolution","explicit_mapping","nativeEvidence","declared_mapping","uncertainty","Plugins, converters, schema methods and runtime changes are not evaluated");
                out.add(t.get(i).offset(),"mongoose","",collection,"","maps_collection",.9,base);
                for(int j=i+6;j<fieldsEnd;){
                    String name=text(t,j);if(j+1>=fieldsEnd||!text(t,j+1).equals(":")){out.uncertain(t.get(j).offset(),"mongoose","Computed or spread schema fields are not statically resolved");break;}
                    int value=j+2,finish=value;String type=text(t,value);boolean required=false;
                    if(type.equals("{")){finish=SourceTokens.end(t,value,"{","}");if(finish<0)break;type=identifierProperty(t,value+1,finish,"type");required="true".equals(identifierProperty(t,value+1,finish,"required"));}
                    else if(type.equals("[")){finish=SourceTokens.end(t,value,"[","]");if(finish<0)break;type="Array";}
                    // Accept one literal type or a structured definition, not computed constructors/calls.
                    if(finish+1<fieldsEnd&&!text(t,finish+1).equals(",")){out.uncertain(t.get(j).offset(),"mongoose","Computed schema field type is not resolved");break;}
                    var attrs=new HashMap<>(base);String bson=bson(type,false);if(bson!=null)attrs.put("databaseType",bson);attrs.put("required",String.valueOf(required));
                    if(!t.get(j).dynamic())out.add(t.get(j).offset(),"mongoose","",collection,name,"maps_document_field",.8,attrs);
                    j=finish+2;
                }
                i=callEnd;
            }
        }
        // Only a client assigned directly from an imported node-redis factory is recognized.
        Set<String> clients=new HashSet<>();
        for(int i=0;i+6<t.size();i++)if(Set.of("const","let").contains(text(t,i))&&text(t,i+2).equals("=")&&redis.contains(text(t,i+3))&&text(t,i+4).equals(".")&&text(t,i+5).equals("createClient")&&text(t,i+6).equals("("))clients.add(text(t,i+1));
        for(int i=0;i+5<t.size();i++)if(clients.contains(text(t,i))&&text(t,i+1).equals(".")&&text(t,i+3).equals("(")&&t.get(i+4).string()){
            String method=text(t,i+2),type=switch(method){case "get","set"->"string";case "hGet","hSet","hGetAll"->"hash";case "lPush","rPush","lRange"->"list";case "sAdd","sMembers"->"set";case "zAdd","zRange"->"zset";case "xAdd","xRange"->"stream";default->null;};if(type==null)continue;
            var key=t.get(i+4);int colon=key.text().indexOf(':');if(key.dynamic()||colon<=0){out.uncertain(key.offset(),"node-redis","Only explicit colon-prefixed literal key namespaces are recovered; dynamic prefixes remain unresolved");continue;}
            String namespace=key.text().substring(0,colon);out.add(key.offset(),"node-redis","",namespace,"","maps_key_namespace",.75,Map.of("transport","redis","databaseType",type,"nativeEvidence","literal_key_namespace","uncertainty","Lexical client binding only; reassignment, shadowing and runtime naming may differ"));
        }
    }
    private static Set<String> modules(List<SourceTokens.Token> t,String module){
        Set<String> names=new HashSet<>();for(int i=0;i<t.size();i++){
            if(text(t,i).equals("import")&&i+3<t.size()&&text(t,i+2).equals("from")&&t.get(i+3).string()&&text(t,i+3).equals(module))names.add(text(t,i+1));
            if(Set.of("const","let").contains(text(t,i))&&i+6<t.size()&&text(t,i+2).equals("=")&&text(t,i+3).equals("require")&&text(t,i+4).equals("(")&&t.get(i+5).string()&&text(t,i+5).equals(module)&&text(t,i+6).equals(")"))names.add(text(t,i+1));
        }return names;
    }
    private static String literalProperty(List<SourceTokens.Token> t,int start,int end,String key){for(int i=start;i+2<end;i++)if(text(t,i).equals(key)&&text(t,i+1).equals(":")&&t.get(i+2).string()&&!t.get(i+2).dynamic()&&(i+3==end||Set.of(",","}").contains(text(t,i+3))))return text(t,i+2);return null;}
    private static String identifierProperty(List<SourceTokens.Token> t,int start,int end,String key){for(int i=start;i+2<end;i++)if(text(t,i).equals(key)&&text(t,i+1).equals(":")&&!t.get(i+2).string()&&Set.of(",","}").contains(text(t,i+3)))return text(t,i+2);return null;}
    private static String bson(String type,boolean java){return switch(Objects.toString(type,"")){case "String","string"->"string";case "Boolean","boolean"->"bool";case "int","Integer"->"int";case "long","Long"->"long";case "Number","Double","double","float","Float"->"double";case "Date","Instant"->"date";case "Array"->"array";case "Object","Map"->"object";default->null;};}
    private static String text(List<SourceTokens.Token> t,int index){return index<0||index>=t.size()?"":t.get(index).text();}
    private NativeMappings(){}
}
