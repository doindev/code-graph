package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/** Uses the installed JDBC driver's authenticated HTTP transport, including its TLS and AWS signing. */
final class OpenSearchExplain {
    interface Request { Response post(String path, String body, int timeout) throws Exception; }
    record Response(int status, Reader body, AutoCloseable cleanup) implements AutoCloseable {
        @Override public void close() throws Exception { cleanup.close(); }
    }
    static ObjectNode collect(QueryJobs.Job job, Connection connection, String sql, JsonNode parameters, ClassLoader loader) throws Exception {
        try {
            Class<?> nativeType=Class.forName("org.opensearch.jdbc.ConnectionImpl",false,loader);
            Object nativeConnection=connection.unwrap(nativeType);
            Object transport=nativeType.getMethod("getTransport").invoke(nativeConnection);
            Object protocol=nativeType.getMethod("getProtocol").invoke(nativeConnection);
            Class<?> protocolType=Class.forName("org.opensearch.jdbc.protocol.http.JsonHttpProtocol",false,loader);
            String path=(String)protocolType.getMethod("getSqlContextPath").invoke(protocol);
            if(!path.endsWith("/_sql"))throw new SQLFeatureNotSupportedException("Driver does not expose a compatible SQL endpoint");
            Class<?> transportType=Class.forName("org.opensearch.jdbc.transport.http.HttpTransport",false,loader);
            Method post=java.util.Arrays.stream(transportType.getMethods()).filter(method->method.getName().equals("doPost")&&method.getParameterCount()==5&&method.getParameterTypes()[0]==String.class&&method.getParameterTypes()[1].isArray()&&method.getParameterTypes()[2].isArray()).findFirst().orElseThrow(NoSuchMethodException::new);
            Object headers=java.lang.reflect.Array.newInstance(post.getParameterTypes()[1].getComponentType(),0), params=java.lang.reflect.Array.newInstance(post.getParameterTypes()[2].getComponentType(),0);
            Method status=post.getReturnType().getMethod("getStatusLine"), entityMethod=post.getReturnType().getMethod("getEntity");
            Method statusCode=status.getReturnType().getMethod("getStatusCode"), content=entityMethod.getReturnType().getMethod("getContent");
            job.statement=(Statement)Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(proxy,method,args)->{
                if(method.getName().equals("cancel"))((Connection)nativeConnection).abort(Runnable::run);
                return method.getReturnType()==boolean.class?false:method.getReturnType()==int.class?0:null;
            });
            try {
                return request(job,path+"/_explain",sql,parameters,(endpoint,body,timeout)->{
                    Object response=post.invoke(transport,endpoint,headers,params,body,timeout);
                    try {
                        int code=(Integer)statusCode.invoke(status.invoke(response));
                        Object entity=entityMethod.invoke(response);
                        InputStream stream=(InputStream)content.invoke(entity);
                        return new Response(code,new InputStreamReader(stream,StandardCharsets.UTF_8),(AutoCloseable)response);
                    } catch(Exception failure) { ((AutoCloseable)response).close();throw failure; }
                });
            } finally {
                job.statement=null;
                // The driver resets this per request; zero is its ordinary SQL protocol default.
                if(!job.cancelled)Class.forName("org.opensearch.jdbc.transport.Transport",false,loader).getMethod("setReadTimeout",int.class).invoke(transport,0);
            }
        } catch(ClassNotFoundException|NoSuchMethodException|ClassCastException e) {
            throw new SQLFeatureNotSupportedException("Installed OpenSearch driver lacks its native HTTP Explain facilities",e);
        } catch(InvocationTargetException e) {
            if(e.getCause() instanceof SQLException sqlError)throw sqlError;
            throw new SQLException("Native OpenSearch Explain transport failed","HY000",e.getCause());
        }
    }
    static ObjectNode request(QueryJobs.Job job,String path,String sql,JsonNode values,Request transport)throws Exception {
        if(job.cancelled)throw new java.util.concurrent.CancellationException();
        ObjectNode body=Profiles.JSON.createObjectNode().put("query",ExplainPlans.literalParameters(sql,values));
        try(Response response=transport.post(path,body.toString(),job.remainingSeconds()*1000)) {
            if(response.status==401||response.status==403)throw new SQLException("Explain access denied","42501");
            if(response.status==404)throw new SQLException("SQL Explain plugin endpoint is unavailable","42P01");
            if(response.status<200||response.status>=300)throw new SQLException("Explain request rejected","HY000",response.status);
            int limit=job.byteLimit/12;char[] chars=new char[limit+1];int used=0,read;
            while(used<chars.length&&(read=response.body.read(chars,used,chars.length-used))>0){used+=read;if(job.cancelled)throw new java.util.concurrent.CancellationException();}
            return ExplainPlans.textRaw(new String(chars,0,used),job.byteLimit/3);
        }
    }
}
