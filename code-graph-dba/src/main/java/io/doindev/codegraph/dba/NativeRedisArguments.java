package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Binary keys/values are explicit; command names and control syntax are always text. */
final class NativeRedisArguments {
    private static final Set<String> KEY_COMMANDS=Set.of(
            "GET","GETRANGE","STRLEN","TYPE","EXISTS","TTL","PTTL","EXPIRETIME","PEXPIRETIME",
            "HGET","HLEN","HEXISTS","HSCAN","LRANGE","LINDEX","LLEN","SSCAN","SCARD","SISMEMBER",
            "ZRANGE","ZREVRANGE","ZCARD","ZSCORE","ZSCAN","XRANGE","XREVRANGE","XLEN","GETBIT",
            "SET","DEL","UNLINK","RENAME","RENAMENX","EXPIRE","PEXPIRE","PERSIST","HSET","HDEL",
            "LPUSH","RPUSH","SADD","SREM","ZADD","ZREM");

    static void validate(JsonNode command){
        String name=text(command,0).toUpperCase(Locale.ROOT);
        for(int i=1;i<command.size();i++){
            JsonNode value=command.get(i);
            if(!value.isTextual()){
                if(!binaryPosition(name,i))throw new IllegalArgumentException("Binary values are allowed only in supported key/value positions; commands, options, numbers, cursors and patterns must be text");
                byte[] decoded=bytes(command,i);
                if(keyOrField(name,i)&&decoded.length>8192)throw new IllegalArgumentException("Key/field exceeds 8192-byte interactive allowance");
            }else if(keyOrField(name,i)&&value.asText().getBytes(StandardCharsets.UTF_8).length>8192){
                throw new IllegalArgumentException("Key/field exceeds 8192-byte interactive allowance");
            }
        }
    }
    static String text(JsonNode command,int index){
        JsonNode value=command.get(index);
        if(value==null||!value.isTextual())throw new IllegalArgumentException("Redis control arguments must be text");
        return value.asText();
    }
    static byte[] bytes(JsonNode command,int index){
        JsonNode value=command.get(index);
        if(value==null)throw new IllegalArgumentException("Missing Redis argument");
        if(value.isTextual())return value.asText().getBytes(StandardCharsets.UTF_8);
        if(!value.isObject()||value.size()!=1||!value.path("base64").isTextual())
            throw new IllegalArgumentException("Redis values must be text or an object containing only base64");
        String encoded=value.path("base64").asText();
        if(encoded.length()>87384)throw new IllegalArgumentException("Binary values exceed the 64 KiB per-value input allowance");
        try{
            byte[] decoded=Base64.getDecoder().decode(encoded);
            if(decoded.length>65536||!Base64.getEncoder().encodeToString(decoded).equals(encoded))
                throw new IllegalArgumentException();
            return decoded;
        }catch(IllegalArgumentException invalid){
            throw new IllegalArgumentException("Binary arguments require canonical padded base64, at most 64 KiB decoded");
        }
    }
    private static boolean binaryPosition(String name,int index){
        if(keyOrField(name,index))return true;
        return switch(name){
            case "SET" -> index==2;
            case "HSET" -> index>=2;
            case "LPUSH","RPUSH","SADD","SREM","ZREM" -> index>=2;
            case "ZADD" -> index>=3&&index%2==1;
            case "HGET","HEXISTS","SISMEMBER","ZSCORE" -> index==2;
            default -> false;
        };
    }
    private static boolean keyOrField(String name,int index){
        if(index==1&&KEY_COMMANDS.contains(name))return true;
        return switch(name){
            case "DEL","UNLINK" -> index>=1;
            case "RENAME","RENAMENX","HGET","HEXISTS" -> index==2;
            case "HSET" -> index>=2&&index%2==0;
            case "HDEL" -> index>=2;
            default -> false;
        };
    }
    private NativeRedisArguments(){}
}
