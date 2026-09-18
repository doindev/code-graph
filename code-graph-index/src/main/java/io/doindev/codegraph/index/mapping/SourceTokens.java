package io.doindev.codegraph.index.mapping;

import java.util.*;

/** Bounded lexer for static annotation/decorator/Prisma values; not a language or security parser. */
final class SourceTokens {
    record Token(String text,int offset,boolean string,boolean dynamic){}
    static List<Token> lex(String source){
        var out=new ArrayList<Token>();int n=source.length();
        for(int i=0;i<n;){
            if(out.size()>=65536)throw new IllegalArgumentException("Source mapping token bound exceeded");
            char c=source.charAt(i);if(Character.isWhitespace(c)){i++;continue;}
            if(c=='/'&&i+1<n&&source.charAt(i+1)=='/'){while(i<n&&source.charAt(i)!='\n')i++;continue;}
            if(c=='/'&&i+1<n&&source.charAt(i+1)=='*'){int end=source.indexOf("*/",i+2);i=end<0?n:end+2;continue;}
            int start=i;
            if(c=='\''||c=='"'||c=='`'){
                boolean triple=c=='"'&&source.startsWith("\"\"\"",i);i+=triple?3:1;StringBuilder value=new StringBuilder();boolean dynamic=false,closed=false;
                while(i<n){
                    if(triple?source.startsWith("\"\"\"",i):source.charAt(i)==c){i+=triple?3:1;closed=true;break;}
                    char part=source.charAt(i++);
                    if(part=='\\'&&i<n){char escaped=source.charAt(i++);part=switch(escaped){case 'n'->'\n';case 'r'->'\r';case 't'->'\t';default->escaped;};if(escaped=='u'||escaped=='x')dynamic=true;}
                    if(part=='$'&&i<n&&source.charAt(i)=='{')dynamic=true;
                    if(value.length()<32768)value.append(part);else dynamic=true;
                }
                out.add(new Token(value.toString(),start,true,dynamic||!closed));continue;
            }
            if(Character.isJavaIdentifierPart(c)){while(i<n&&Character.isJavaIdentifierPart(source.charAt(i)))i++;}
            else i++;
            String text=source.substring(start,i);out.add(new Token(text.length()>512?text.substring(0,512):text,start,false,text.length()>512));
        }
        return out;
    }
    static int end(List<Token> tokens,int start,String open,String close){int depth=0;for(int i=start;i<tokens.size();i++){if(tokens.get(i).string())continue;if(tokens.get(i).text().equals(open))depth++;
            if(tokens.get(i).text().equals(close)){depth--;if(depth==0)return i;}}
        return -1;
    }
}
