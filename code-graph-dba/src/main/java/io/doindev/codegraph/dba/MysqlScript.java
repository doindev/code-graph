package io.doindev.codegraph.dba;

import java.util.*;
import java.util.regex.Pattern;

/** MySQL client delimiters are consumed here, never sent to JDBC. */
final class MysqlScript {
    private static final Pattern DIRECTIVE=Pattern.compile("(?i)DELIMITER\\s+(\\S+)");
    record Mode(boolean noBackslashEscapes,boolean ansiQuotes) {
        static Mode parse(String value){Set<String> modes=new HashSet<>(Arrays.asList(value.toUpperCase(Locale.ROOT).split(",")));return new Mode(modes.contains("NO_BACKSLASH_ESCAPES"),modes.contains("ANSI_QUOTES")||modes.contains("ANSI"));}
    }
    static List<SqlScript.Unit> extract(String source,int maximum,Mode mode){
        if(source==null||source.isBlank()||source.length()>maximum)throw new IllegalArgumentException("SQL must contain 1.."+maximum+" characters");
        List<SqlScript.Unit> units=new ArrayList<>();String delimiter=";";int start=0,parameters=0;boolean code=false,compound=false;StringBuilder words=new StringBuilder();
        for(int i=0;i<source.length();){
            char c=source.charAt(i),next=i+1<source.length()?source.charAt(i+1):0;
            if(i==0||source.charAt(i-1)=='\n'){
                int end=source.indexOf('\n',i);if(end<0)end=source.length();String line=source.substring(i,end).strip();
                if(line.toUpperCase(Locale.ROOT).startsWith("DELIMITER")&&(line.length()==9||Character.isWhitespace(line.charAt(9)))){
                    var match=DIRECTIVE.matcher(line);if(code||!match.matches())throw new IllegalArgumentException("DELIMITER must appear on its own line between complete statements");
                    delimiter=match.group(1);if(delimiter.length()>16||delimiter.matches(".*[\\\\'\"`#].*")||delimiter.contains("--")||delimiter.contains("/*")||delimiter.contains("*/")||delimiter.indexOf(0)>=0)throw new IllegalArgumentException("Use a delimiter of 1..16 non-quote, non-comment characters without backslashes");
                    i=end<source.length()?end+1:end;start=i;continue;
                }
            }
            if(source.startsWith(delimiter,i)){
                if(compound&&delimiter.equals(";"))throw new IllegalArgumentException("Use DELIMITER around compound stored programs so their complete bodies reach JDBC");
                if(code){String unit=source.substring(start,i);add(units,unit,parameters);mode=nextMode(unit,mode,source.substring(i+delimiter.length()));}
                i+=delimiter.length();start=i;parameters=0;code=false;compound=false;words.setLength(0);continue;
            }
            if(c=='#'||c=='-'&&next=='-'&&(i+2==source.length()||Character.isWhitespace(source.charAt(i+2)))){int end=source.indexOf('\n',i);i=end<0?source.length():end;continue;}
            if(c=='/'&&next=='*'){
                int end=source.indexOf("*/",i+2);if(end<0)throw invalid();
                // Executable comments may contain statements, parameter markers, or SQL-mode changes.
                // Reject these rather than miscount or silently execute hidden SQL.
                if(i+2<source.length()&&(source.charAt(i+2)=='!'||source.startsWith("M!",i+2))){
                    String body=source.substring(i+3,end).strip();if(!body.matches("(?i)[0-9]{5,6}\\s+(?:INVISIBLE|VISIBLE|NOT\\s+ENFORCED|ENFORCED)"))throw new IllegalArgumentException("Expand executable SQL comments before review; only native visibility/enforcement annotations are supported");code=true;
                }
                i=end+2;continue;
            }
            if(c=='\''||c=='"'||c=='`'){
                code=true;char quote=c;boolean escape=!mode.noBackslashEscapes()&&(quote=='\''||quote=='"'&&!mode.ansiQuotes());boolean closed=false;
                for(i++;i<source.length();i++){
                    char q=source.charAt(i);if(escape&&q=='\\'){i++;continue;}
                    if(q==quote){if(i+1<source.length()&&source.charAt(i+1)==quote){i++;continue;}i++;closed=true;break;}
                }
                if(!closed)throw invalid();continue;
            }
            if(Character.isLetter(c)||c=='_'){
                int end=i+1;while(end<source.length()&&(Character.isLetterOrDigit(source.charAt(end))||source.charAt(end)=='_'))end++;
                String word=source.substring(i,end).toUpperCase(Locale.ROOT);if(words.length()<1024)words.append(word).append(' ');
                if(word.equals("BEGIN")&&words.toString().matches("(?s)^CREATE .*\\b(PROCEDURE|FUNCTION|TRIGGER|EVENT)\\b.*"))compound=true;
                code=true;i=end;continue;
            }
            if(c=='?')parameters++;if(!Character.isWhitespace(c))code=true;i++;
        }
        if(code)add(units,source.substring(start),parameters);
        if(units.isEmpty())throw new IllegalArgumentException("SQL must contain an executable statement");
        return List.copyOf(units);
    }
    private static Mode nextMode(String sql,Mode current,String remaining){
        String clean=sql.replaceAll("(?s)/\\*.*?\\*/|(?m)--\\s[^\\r\\n]*|#[^\\r\\n]*"," ").strip();
        var assignment=Pattern.compile("(?is)^SET\\s+(?:(?:SESSION|LOCAL)\\s+|@@(?:SESSION\\.)?)?sql_mode\\s*=\\s*(.*?)\\s*$").matcher(clean);if(!assignment.matches())return current;
        String value=assignment.group(1);if(value.matches("'[A-Za-z0-9_,]*'"))return Mode.parse(value.substring(1,value.length()-1));
        if(!remaining.replaceAll("(?s)/\\*.*?\\*/|(?m)--\\s[^\\r\\n]*|#[^\\r\\n]*"," ").isBlank())throw new IllegalArgumentException("A dynamic SQL-mode assignment must be the last statement; submit subsequent SQL after observing the new mode");return current;
    }
    /** The definition editor explicitly submits one complete native stored-program definition. */
    static List<SqlScript.Unit> single(String sql,int maximum,Mode mode){
        if(sql==null||sql.isBlank()||sql.length()>maximum)throw new IllegalArgumentException("SQL must contain 1.."+maximum+" characters");
        String delimiter="__cg_end__";while(sql.contains(delimiter))delimiter+="x";
        if(delimiter.length()>16)throw new IllegalArgumentException("Use a script with an explicit DELIMITER");
        return extract("DELIMITER "+delimiter+"\n"+sql+"\n"+delimiter,maximum+64,mode);
    }
    private static void add(List<SqlScript.Unit> units,String text,int parameters){if(units.size()>=32)throw new IllegalArgumentException("A script may contain at most 32 execution units");units.add(new SqlScript.Unit(units.size()+1,text.strip(),parameters));}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Unterminated MySQL quote, identifier, or comment; nothing was executed");}
    private MysqlScript(){}
}
