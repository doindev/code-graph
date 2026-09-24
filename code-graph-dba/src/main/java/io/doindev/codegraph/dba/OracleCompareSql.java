package io.doindev.codegraph.dba;

import java.util.*;

/** Oracle identifier tokens are remapped without changing comments or any literal syntax. */
final class OracleCompareSql {
    private OracleCompareSql(){}
    record Token(String text,String name,boolean identifier){}
    static String remap(String sql,Map<String,String> owners){
        if(owners.isEmpty()||owners.entrySet().stream().allMatch(entry->entry.getKey().equals(entry.getValue())))return sql;
        List<Token> tokens=tokens(sql);StringBuilder output=new StringBuilder();
        for(int i=0;i<tokens.size();i++){
            Token token=tokens.get(i);String replacement=token.identifier()?owners.get(token.name()):null;
            int next=i+1;while(next<tokens.size()&&ignorable(tokens.get(next).text()))next++;
            int previous=i-1;while(previous>=0&&ignorable(tokens.get(previous).text()))previous--;
            if(replacement!=null&&!replacement.equals(token.name())&&(next>=tokens.size()||!tokens.get(next).text().equals("."))&&(previous<0||!tokens.get(previous).text().equals(".")))throw new IllegalArgumentException("Oracle owner name is also used as an unqualified identifier or alias; review its remapping manually: "+token.name());
            if(replacement!=null&&next<tokens.size()&&tokens.get(next).text().equals("."))output.append(OracleDialect.identifier(replacement));else output.append(token.text());
        }return output.toString();
    }
    static boolean dynamic(String sql){
        List<String> names=tokens(sql).stream().filter(t->!ignorable(t.text())).map(t->t.identifier()?t.name():t.text()).toList();
        for(int i=0;i<names.size();i++){
            String word=names.get(i);if(word.equals("DBMS_SQL")||word.equals("DBMS_UTILITY")&&i+2<names.size()&&names.get(i+2).equals("EXEC_DDL_STATEMENT"))return true;
            if(word.equals("EXECUTE")&&i+1<names.size()&&names.get(i+1).equals("IMMEDIATE"))return true;
            if(word.equals("OPEN"))for(int j=i+1;j<names.size()&&!names.get(j).equals(";");j++)if(names.get(j).equals("FOR")){
                if(j+1>=names.size()||!Set.of("SELECT","WITH").contains(names.get(j+1)))return true;break;
            }
        }return false;
    }
    static String canonical(String sql){
        StringBuilder out=new StringBuilder();for(Token token:tokens(sql))if(!ignorable(token.text())){
            if(token.identifier())out.append('I').append(token.name().length()).append(':').append(token.name());else out.append('T').append(token.text().length()).append(':').append(token.text());
        }return out.toString();
    }
    private static boolean ignorable(String text){return text.isBlank()||text.startsWith("--")||text.startsWith("/*");}
    private static List<Token> tokens(String sql){
        var tokens=new ArrayList<Token>();for(int i=0;i<sql.length();){int start=i;char ch=sql.charAt(i);
            if(Character.isWhitespace(ch)){while(i<sql.length()&&Character.isWhitespace(sql.charAt(i)))i++;tokens.add(new Token(sql.substring(start,i),"",false));continue;}
            if(sql.startsWith("--",i)){int end=sql.indexOf('\n',i);i=end<0?sql.length():end;tokens.add(new Token(sql.substring(start,i),"",false));continue;}
            if(sql.startsWith("/*",i)){int end=sql.indexOf("*/",i+2);if(end<0)throw new IllegalArgumentException("Unclosed Oracle comment");i=end+2;tokens.add(new Token(sql.substring(start,i),"",false));continue;}
            int prefix=(ch=='q'||ch=='Q')&&i+1<sql.length()&&sql.charAt(i+1)=='\''?1:(ch=='n'||ch=='N')&&i+2<sql.length()&&(sql.charAt(i+1)=='q'||sql.charAt(i+1)=='Q')&&sql.charAt(i+2)=='\''?2:0;
            if(prefix>0){int opening=i+prefix+1;if(opening>=sql.length())throw new IllegalArgumentException("Unclosed Oracle literal");char close=switch(sql.charAt(opening)){case '['->']';case '{'->'}';case '('->')';case '<'->'>';default->sql.charAt(opening);};int end=sql.indexOf(""+close+'\'',opening+1);if(end<0)throw new IllegalArgumentException("Unclosed Oracle literal");i=end+2;tokens.add(new Token(sql.substring(start,i),"",false));continue;}
            if(ch=='\''||ch=='"'){i++;boolean closed=false;while(i<sql.length()){if(sql.charAt(i++)==ch){if(i<sql.length()&&sql.charAt(i)==ch)i++;else{closed=true;break;}}}if(!closed)throw new IllegalArgumentException("Unclosed Oracle token");String text=sql.substring(start,i);tokens.add(new Token(text,ch=='"'?text.substring(1,text.length()-1).replace("\"\"","\""):"",ch=='"'));continue;}
            if(Character.isLetter(ch)||ch=='_'){i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||"_$#".indexOf(sql.charAt(i))>=0))i++;String text=sql.substring(start,i);tokens.add(new Token(text,text.toUpperCase(Locale.ROOT),true));continue;}
            i++;tokens.add(new Token(sql.substring(start,i),"",false));
        }return tokens;
    }
}
