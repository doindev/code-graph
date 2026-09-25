package io.doindev.codegraph.dba;

import java.util.*;

/** Lossless SHOW CREATE TABLE decomposition. Unedited clauses remain byte-for-byte intact. */
final class MysqlTableDefinition {
    record Token(String text,int start,int end,int depth,boolean quoted) {String word(){return quoted?"":text.toUpperCase(Locale.ROOT);}}
    record Table(List<String> clauses,String options) {}
    static List<Token> tokens(String sql,boolean noBackslash){return tokens(sql,new MysqlScript.Mode(noBackslash,false));}
    static List<Token> tokens(String sql,MysqlScript.Mode mode){
        List<Token> tokens=new ArrayList<>();int depth=0;
        for(int i=0;i<sql.length();){int start=i;char c=sql.charAt(i);
            if(Character.isWhitespace(c)){i++;continue;}
            if(c=='#'||c=='-'&&i+2<sql.length()&&sql.charAt(i+1)=='-'&&Character.isWhitespace(sql.charAt(i+2))){int end=sql.indexOf('\n',i);if(end<0)end=sql.length();tokens.add(new Token(sql.substring(i,end),i,end,depth,true));i=end;continue;}
            if(c=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'){int end=sql.indexOf("*/",i+2);if(end<0)throw new IllegalArgumentException("Unterminated native table comment");tokens.add(new Token(sql.substring(i,end+2),i,end+2,depth,true));i=end+2;continue;}
            if(c=='\''||c=='"'||c=='`'){char quote=c;boolean closed=false;for(i++;i<sql.length();i++){char q=sql.charAt(i);if(!mode.noBackslashEscapes()&&(quote=='\''||quote=='"'&&!mode.ansiQuotes())&&q=='\\'){i++;continue;}if(q==quote){if(i+1<sql.length()&&sql.charAt(i+1)==quote){i++;continue;}i++;closed=true;break;}}if(!closed)throw new IllegalArgumentException("Unterminated native table literal");tokens.add(new Token(sql.substring(start,i),start,i,depth,true));continue;}
            if(c==')'){if(--depth<0)throw new IllegalArgumentException("Unbalanced native table definition");}
            if(Character.isLetterOrDigit(c)||c=='_'||c=='$'){i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||sql.charAt(i)=='_'||sql.charAt(i)=='$'))i++;}else i++;
            tokens.add(new Token(sql.substring(start,i),start,i,depth,false));if(c=='(')depth++;
        }
        if(depth!=0)throw new IllegalArgumentException("Unbalanced native table definition");return tokens;
    }
    static Table parse(String ddl,boolean noBackslash){
        var tokens=tokens(ddl,noBackslash);int start=-1,end=-1,at=-1;List<String> clauses=new ArrayList<>();
        for(Token token:tokens){if(start<0&&token.text.equals("(")&&token.depth==0){start=token.end;at=start;continue;}if(start>=0&&token.text.equals(",")&&token.depth==1){clauses.add(ddl.substring(at,token.start).strip());at=token.end;}if(start>=0&&token.text.equals(")")&&token.depth==0){end=token.end;clauses.add(ddl.substring(at,token.start).strip());break;}}
        if(start<0||end<0||clauses.isEmpty())throw new IllegalArgumentException("Unsupported SHOW CREATE TABLE definition");return new Table(List.copyOf(clauses),ddl.substring(end).strip().replaceFirst(";\\s*$",""));
    }
    static String columnName(String clause){
        if(clause.isEmpty()||clause.charAt(0)!='`'&&clause.charAt(0)!='"')return null;char quote=clause.charAt(0);StringBuilder name=new StringBuilder();
        for(int i=1;i<clause.length();i++){char c=clause.charAt(i);if(c==quote){if(i+1<clause.length()&&clause.charAt(i+1)==quote){name.append(c);i++;}else return name.toString();}else name.append(c);}throw new IllegalArgumentException("Unterminated native column identifier");
    }
    private static final Set<String> ATTRIBUTES=Set.of("NOT","NULL","DEFAULT","CHARACTER","COLLATE","GENERATED","AS","VIRTUAL","STORED","PERSISTENT","AUTO_INCREMENT","COMMENT","INVISIBLE","VISIBLE","ON","PRIMARY","UNIQUE","REFERENCES","CHECK","COLUMN_FORMAT","STORAGE","SRID");
    private static LinkedHashMap<String,int[]> attributes(String clause,boolean mode){
        var tokens=tokens(clause,mode);var out=new LinkedHashMap<String,int[]>();String previous="type";int start=tokens.getFirst().end;out.put(previous,new int[]{start,clause.length()});
        for(int i=1;i<tokens.size();i++){Token t=tokens.get(i);if(t.depth==0&&t.text.startsWith("/*")){out.get(previous)[1]=t.start;previous="nativeComment"+t.start;out.put(previous,new int[]{t.start,clause.length()});continue;}if(t.depth!=0||!ATTRIBUTES.contains(t.word()))continue;
            // These words belong to the preceding multi-token attribute.
            if(t.word().equals("NULL")&&i>0&&tokens.get(i-1).word().equals("NOT")||t.word().equals("NULL")&&previous.equals("DEFAULT")&&tokens.get(i-1).word().equals("DEFAULT")||t.word().equals("AS")&&previous.equals("GENERATED"))continue;
            String key=t.word().equals("NOT")?"NULL":t.word();if(out.containsKey(key))throw new IllegalArgumentException("Ambiguous native column attribute: "+key);
            out.get(previous)[1]=t.start;out.put(key,new int[]{t.start,clause.length()});previous=key;
        }return out;
    }
    static String attribute(String clause,String key,boolean mode){int[] range=attributes(clause,mode).get(key);return range==null?"":clause.substring(range[0],range[1]).strip();}
    static String replace(String clause,String key,String value,boolean mode){int[] range=attributes(clause,mode).get(key);if(range==null)return clause+(value.isBlank()?"":" "+value);return (clause.substring(0,range[0])+" "+value+" "+clause.substring(range[1])).strip();}
    static Map<String,String> columns(Table table){var out=new LinkedHashMap<String,String>();for(String clause:table.clauses){String name=columnName(clause);if(name!=null&&out.put(name,clause)!=null)throw new IllegalArgumentException("Duplicate native column");}return out;}
    private MysqlTableDefinition(){}
}
