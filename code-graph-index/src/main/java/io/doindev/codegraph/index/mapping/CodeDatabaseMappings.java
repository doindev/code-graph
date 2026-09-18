package io.doindev.codegraph.index.mapping;

import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import java.util.*;

/** Adapter composition at file extraction: atomic replacement/deletion uses existing graph generations. */
public final class CodeDatabaseMappings implements LanguageAnalyzer {
    private final LanguageAnalyzer delegate;
    private final String extension;
    private CodeDatabaseMappings(LanguageAnalyzer delegate,String extension){this.delegate=delegate;this.extension=extension;}
    public static LanguageAnalyzer wrap(LanguageAnalyzer analyzer){return Set.of("java","js","ts","tsx","javascript","typescript","sql").contains(analyzer.languageId())?new CodeDatabaseMappings(analyzer,null):analyzer;}
    public static LanguageAnalyzer standalone(String extension){return new CodeDatabaseMappings(null,extension);}
    public String languageId(){return delegate==null?extension:delegate.languageId();}
    public Set<String> fileExtensions(){return delegate==null?Set.of(extension):delegate.fileExtensions();}
    public FileFragment extract(SourceFile source){
        FileFragment fragment=delegate==null?empty(source):delegate.extract(source);
        var evidence=new MappingEvidence(source);
        try{
            switch(languageId()){
                case "sql" -> SqlMappings.extract(evidence,source.content(),0,"sql");
                case "xml" -> OrmMappings.mybatis(evidence);
                case "prisma" -> OrmMappings.prisma(evidence,SourceTokens.lex(source.content()));
                case "java","js","ts","tsx","javascript","typescript" -> {
                    var tokens=SourceTokens.lex(source.content());
                    OrmMappings.annotations(evidence,tokens,languageId().equals("java"));
                    int count=0;long deadline=System.nanoTime()+1_000_000_000L;
                    for(int i=0;i<tokens.size();i++){
                        var token=tokens.get(i);if(!token.string()||!token.text().stripLeading().matches("(?is)^(select|with|insert|update|delete|create|alter)\\b.*"))continue;
                        if(count++>=32||System.nanoTime()>deadline){evidence.uncertain(token.offset(),"sql_literal","Static SQL extraction budget exceeded");break;}
                        boolean dynamic=token.dynamic()||i>0&&tokens.get(i-1).text().equals("+");
                        StringBuilder sql=new StringBuilder(token.text());int last=i;
                        while(last+1<tokens.size()&&tokens.get(last+1).text().equals("+")){
                            if(last+2>=tokens.size()||!tokens.get(last+2).string()){dynamic=true;break;}
                            var next=tokens.get(last+2);dynamic|=next.dynamic();
                            if(sql.length()+next.text().length()>32768){dynamic=true;break;}
                            sql.append(next.text());last+=2;
                        }
                        if(dynamic)evidence.uncertain(token.offset(),"sql_literal","Dynamic SQL or computed concatenation is not statically resolved");
                        else SqlMappings.extract(evidence,sql.toString(),token.offset(),"sql_literal");
                        i=last;
                    }
                }
                default -> {}
            }
        }catch(RuntimeException bounded){evidence.uncertain(0,languageId(),"Unsupported source mapping syntax or file-local limits exceeded");}
        evidence.finish();
        if(evidence.nodes.isEmpty())return fragment;
        var nodes=new ArrayList<>(fragment.declarations());nodes.addAll(evidence.nodes);
        var edges=new ArrayList<>(fragment.localEdges());edges.addAll(evidence.edges);
        return new FileFragment(fragment.file(),fragment.lang(),fragment.contentHash(),nodes,edges,fragment.rawRefs(),fragment.imports());
    }
    private FileFragment empty(SourceFile source){
        var file=new FileId(source.relPath());var node=new Node(file,NodeKind.FILE,source.relPath(),source.relPath(),new SourceSpan(source.relPath(),1,1,1,1),Metrics.NONE,Map.of("lang",languageId()));
        return new FileFragment(file,languageId(),MappingEvidence.hash(source.content()),List.of(node),List.of(),List.of(),List.of());
    }
}
