package io.doindev.codegraph.dba;

import java.sql.*;
import java.util.Set;

/** Observed session semantics, shared by native SQL and metadata workflows. */
final class MysqlDialect {
    static boolean supports(String engine){return Set.of("mysql","mariadb").contains(engine);}
    static String mode(QueryJobs.Job job,Connection connection)throws Exception{
        try(Statement s=connection.createStatement()){
            job.statement=s;s.setQueryTimeout(job.remainingSeconds());s.setMaxRows(1);
            try(ResultSet rows=s.executeQuery("SELECT @@SESSION.sql_mode")){if(!rows.next())throw new SQLException("MySQL session SQL mode is unavailable");return rows.getString(1);}
        }finally{job.statement=null;}
    }
    static String quote(String value){if(value==null||value.isBlank()||value.length()>64||value.indexOf(0)>=0)throw new IllegalArgumentException("MySQL identifiers must contain 1..64 characters");return "`"+value.replace("`","``")+"`";}
    private MysqlDialect(){}
}
