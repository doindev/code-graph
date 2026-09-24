package io.doindev.codegraph.dba;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded lexical extraction for local-human JDBC scripts; preserves exact source text. */
final class SqlScript {
    record Unit(int index,String sql,int parameters) {}
    private static final Pattern GO=Pattern.compile("(?i)go"),GO_COUNT=Pattern.compile("(?i)go\\s+\\d+"),DELIMITER=Pattern.compile("(?i)delimiter(?:\\s+.*)?");
    private enum Mode {NORMAL,LINE,SINGLE,DOUBLE,BACKTICK,BRACKET,BLOCK,DOLLAR,ALTERNATIVE}
    private static final class State {Mode mode=Mode.NORMAL;int blockDepth;String dollar;char alternative;}

    static List<Unit> extract(String source) {
        if(source==null||source.isBlank()||source.length()>16_384)throw new IllegalArgumentException("SQL must contain 1..16384 characters");
        List<int[]> markers=new ArrayList<>();State state=new State();int lineStart=0;
        for(int i=0;i<=source.length();i++)if(i==source.length()||source.charAt(i)=='\n'){
            int contentEnd=i>lineStart&&source.charAt(i-1)=='\r'?i-1:i;String trimmed=source.substring(lineStart,contentEnd).strip();
            if(state.mode==Mode.NORMAL&&DELIMITER.matcher(trimmed).matches())throw new IllegalArgumentException("MySQL DELIMITER directives are not supported; submit one JDBC statement at a time");
            if(state.mode==Mode.NORMAL&&GO_COUNT.matcher(trimmed).matches())throw new IllegalArgumentException("SQL Server GO repeat counts are not supported");
            if(state.mode==Mode.NORMAL&&(GO.matcher(trimmed).matches()||trimmed.equals("/")))markers.add(new int[]{lineStart,i<source.length()?i+1:i});
            else scan(source,lineStart,i<source.length()?i+1:i,state,null);
            lineStart=i+1;
        }
        if(state.mode!=Mode.NORMAL&&state.mode!=Mode.LINE)throw new IllegalArgumentException("Unterminated SQL quote, identifier, or comment; nothing was executed");
        List<String> pieces=new ArrayList<>();
        if(!markers.isEmpty()){
            int start=0;for(int[] marker:markers){pieces.add(source.substring(start,marker[0]));start=marker[1];}pieces.add(source.substring(start));
        }else{
            state=new State();int[] start={0};
            scan(source,0,source.length(),state,(at,c)->{if(c==';'){pieces.add(source.substring(start[0],at));start[0]=at+1;}});
            pieces.add(source.substring(start[0]));
        }
        List<Unit> units=new ArrayList<>();
        for(String piece:pieces){String sql=piece.strip();if(sql.isEmpty()||!executable(sql))continue;if(units.size()>=32)throw new IllegalArgumentException("A script may contain at most 32 execution units");units.add(new Unit(units.size()+1,sql,parameters(sql)));}
        if(units.isEmpty())throw new IllegalArgumentException("SQL must contain an executable statement");
        return List.copyOf(units);
    }

    /** Oracle slash delimiters terminate PL/SQL units, while ordinary SQL still splits at semicolons. */
    static List<Unit> extract(String source,String engine){return extract(source,engine,16384);}
    static List<Unit> extract(String source,String engine,int maximum){
        if(!"oracle".equals(engine))return extract(source);
        if(source==null||source.isBlank()||source.length()>maximum)throw new IllegalArgumentException("SQL must contain 1.."+maximum+" characters");
        List<Unit> units=new ArrayList<>();State state=new State();int lineStart=0,segmentStart=0;
        for(int i=0;i<=source.length();i++)if(i==source.length()||source.charAt(i)=='\n'){
            String line=source.substring(lineStart,i).strip();
            if(state.mode==Mode.NORMAL&&line.equals("/")){
                oracleSegment(source.substring(segmentStart,lineStart),units);segmentStart=i<source.length()?i+1:i;
            }else scan(source,lineStart,i<source.length()?i+1:i,state,null);
            lineStart=i+1;
        }
        if(state.mode!=Mode.NORMAL&&state.mode!=Mode.LINE)throw new IllegalArgumentException("Unterminated SQL quote, identifier, or comment; nothing was executed");
        oracleSegment(source.substring(segmentStart),units);
        if(units.isEmpty())throw new IllegalArgumentException("SQL must contain an executable statement");return List.copyOf(units);
    }
    private static void oracleSegment(String source,List<Unit> units){
        State state=new State();int[] start={0};boolean[] block={false};
        scan(source,0,source.length(),state,(at,c)->{
            if(c!=';'||block[0])return;
            String piece=source.substring(start[0],at);
            if(oracleBlock(piece)){block[0]=true;return;}
            addOracleUnit(piece,units);start[0]=at+1;
        });
        addOracleUnit(source.substring(start[0]),units);
    }
    static boolean oracleBlock(String sql){
        StringBuilder prefix=new StringBuilder();int[] previous={-1};
        scan(sql,0,sql.length(),new State(),(at,c)->{if(prefix.length()<256){if(at>previous[0]+1)prefix.append(' ');prefix.append(c);previous[0]=at;}});
        return prefix.toString().stripLeading().matches("(?is)(?:BEGIN|DECLARE)\\b.*|CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?(?:PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE|JAVA)\\b.*|WITH\\s+(?:FUNCTION|PROCEDURE)\\b.*");
    }
    private static void addOracleUnit(String text,List<Unit> units){
        String sql=text.strip();if(sql.isEmpty()||!executable(sql))return;
        if(units.size()>=32)throw new IllegalArgumentException("A script may contain at most 32 execution units");
        units.add(new Unit(units.size()+1,sql,parameters(sql)));
    }

    private interface Normal {void accept(int index,char c);}
    private static void scan(String s,int from,int to,State st,Normal normal){
        int i=from;while(i<to){char c=s.charAt(i),n=i+1<s.length()?s.charAt(i+1):0;
            switch(st.mode){
                case NORMAL -> {
                    if(c=='-'&&n=='-'){st.mode=Mode.LINE;i+=2;continue;}
                    if(c=='/'&&n=='*'){st.mode=Mode.BLOCK;st.blockDepth=1;i+=2;continue;}
                    int quote=(c=='q'||c=='Q')&&n=='\''?i+1:(c=='n'||c=='N')&&(n=='q'||n=='Q')&&i+2<s.length()&&s.charAt(i+2)=='\''?i+2:-1;
                    if(quote>=0&&quote+1<s.length()&&(i==0||!Character.isJavaIdentifierPart(s.charAt(i-1)))){
                        char opening=s.charAt(quote+1);if(!Character.isWhitespace(opening)&&opening!='\''){
                            st.alternative=switch(opening){case '['->']';case '('->')';case '{'->'}';case '<'->'>';default->opening;};st.mode=Mode.ALTERNATIVE;i=quote+2;continue;
                        }
                    }
                    if(c=='\''){st.mode=Mode.SINGLE;i++;continue;}if(c=='"'){st.mode=Mode.DOUBLE;i++;continue;}if(c=='`'){st.mode=Mode.BACKTICK;i++;continue;}if(c=='['){st.mode=Mode.BRACKET;i++;continue;}
                    if(c=='$'){String tag=dollarTag(s,i);if(tag!=null){st.mode=Mode.DOLLAR;st.dollar=tag;i+=tag.length();continue;}}
                    if(normal!=null)normal.accept(i,c);i++;
                }
                case LINE -> {if(c=='\n')st.mode=Mode.NORMAL;i++;}
                case SINGLE -> {if(c=='\''&&n=='\'')i+=2;else{if(c=='\'')st.mode=Mode.NORMAL;i++;}}
                case DOUBLE -> {if(c=='"'&&n=='"')i+=2;else{if(c=='"')st.mode=Mode.NORMAL;i++;}}
                case BACKTICK -> {if(c=='`'&&n=='`')i+=2;else{if(c=='`')st.mode=Mode.NORMAL;i++;}}
                case BRACKET -> {if(c==']'&&n==']')i+=2;else{if(c==']')st.mode=Mode.NORMAL;i++;}}
                case BLOCK -> {if(c=='/'&&n=='*'){st.blockDepth++;i+=2;}else if(c=='*'&&n=='/'){if(--st.blockDepth==0)st.mode=Mode.NORMAL;i+=2;}else i++;}
                case ALTERNATIVE -> {if(c==st.alternative&&n=='\''){st.mode=Mode.NORMAL;i+=2;}else i++;}
                case DOLLAR -> {if(s.startsWith(st.dollar,i)){i+=st.dollar.length();st.mode=Mode.NORMAL;st.dollar=null;}else i++;}
            }
        }
    }
    private static String dollarTag(String s,int at){int end=s.indexOf('$',at+1);if(end<0)return null;String name=s.substring(at+1,end);if(!name.matches("[A-Za-z_][A-Za-z0-9_]*|"))return null;return s.substring(at,end+1);}
    private static int parameters(String sql){int[] count={0};State state=new State();scan(sql,0,sql.length(),state,(i,c)->{if(c=='?')count[0]++;});return count[0];}
    /** Rebind the parser's temporary numbered markers without touching strings/comments/identifiers. */
    static GridSql.Edit bindIndexed(String sql,com.fasterxml.jackson.databind.JsonNode values){
        StringBuilder text=new StringBuilder();var ordered=Profiles.JSON.createArrayNode();int[] end={0};State state=new State();
        scan(sql,0,sql.length(),state,(at,c)->{if(c!='?')return;int stop=at+1;while(stop<sql.length()&&Character.isDigit(sql.charAt(stop)))stop++;if(stop==at+1)throw new IllegalArgumentException("Unresolved JDBC marker");int index=Integer.parseInt(sql.substring(at+1,stop))-1;if(index<0||index>=values.size())throw new IllegalArgumentException("Unresolved JDBC value");text.append(sql,end[0],at).append('?');end[0]=stop;ordered.add(values.get(index));});
        text.append(sql,end[0],sql.length());return new GridSql.Edit(text.toString(),ordered);
    }
    static String displayIndexed(String sql,com.fasterxml.jackson.databind.JsonNode values){
        StringBuilder text=new StringBuilder();int[] end={0};State state=new State();
        scan(sql,0,sql.length(),state,(at,c)->{if(c!='?')return;int stop=at+1;while(stop<sql.length()&&Character.isDigit(sql.charAt(stop)))stop++;int index=Integer.parseInt(sql.substring(at+1,stop))-1;var value=values.get(index);String literal=value.isNull()?"NULL":value.isBoolean()?value.asText().toUpperCase(Locale.ROOT):value.isNumber()?value.asText():"'"+value.asText().replace("'","''")+"'";text.append(sql,end[0],at).append(literal);end[0]=stop;});
        return text.append(sql,end[0],sql.length()).toString();
    }
    private static boolean executable(String sql){boolean[] code={false};State state=new State();scan(sql,0,sql.length(),state,(i,c)->{if(!Character.isWhitespace(c)&&c!=';')code[0]=true;});return code[0];}
    private SqlScript(){}
}
