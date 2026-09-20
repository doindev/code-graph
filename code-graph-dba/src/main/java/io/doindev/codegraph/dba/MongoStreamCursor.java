package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.bson.BsonDocument;

/** Opaque, authenticated positions, not grants. No retained stream inventory or persistent secret. */
final class MongoStreamCursor implements AutoCloseable {
    static final int MAX_TOKEN=6144;
    private final byte[] key=new byte[32];
    private final SecureRandom random=new SecureRandom();
    private final LongSupplier clock;
    private boolean closed;
    MongoStreamCursor(){this(System::currentTimeMillis);}
    MongoStreamCursor(LongSupplier clock){this.clock=clock;random.nextBytes(key);}
    record Position(BsonDocument resume,String collection){}
    synchronized String encode(String scope,String collection,BsonDocument resume){
        try{
            if(closed)throw new IllegalStateException("Stream continuations are closed");
            if(resume==null||resume.isEmpty()||resume.toJson().length()>2048)throw new IllegalArgumentException("Resume position unavailable or too large; no continuation was issued");
            ObjectNode value=Profiles.JSON.createObjectNode().put("scope",scope).put("collection",collection).put("expires",clock.getAsLong()+300000).put("resume",resume.toJson());
            byte[] plain=Profiles.JSON.writeValueAsBytes(value),iv=new byte[12];
            if(plain.length>4096)throw new IllegalArgumentException("Resume position exceeds the continuation allowance");
            random.nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD(new byte[]{1});byte[] encrypted=cipher.doFinal(plain);
            return "mcs1."+Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(iv.length+encrypted.length).put(iv).put(encrypted).array());
        }catch(IllegalArgumentException e){throw e;}catch(Exception failure){throw new IllegalStateException("Cannot encode stream continuation");}
    }
    synchronized Position decode(String token,String scope){
        try{
            if(closed)throw new IllegalArgumentException();
            if(token==null||!token.startsWith("mcs1.")||token.length()>MAX_TOKEN)throw new IllegalArgumentException();
            byte[] data=Base64.getUrlDecoder().decode(token.substring(5));if(data.length<29||data.length>4124)throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(data,12)));cipher.updateAAD(new byte[]{1});
            var value=Profiles.JSON.readTree(cipher.doFinal(data,12,data.length-12));
            if(!value.path("scope").asText().equals(scope)||value.path("expires").asLong()<=clock.getAsLong())throw new IllegalArgumentException();
            return new Position(BsonDocument.parse(value.path("resume").asText()),value.path("collection").asText());
        }catch(Exception invalid){throw new IllegalArgumentException("Stream cursor is invalid, expired, belongs to another owner/target, or predates this server restart; explicitly start a new watch (history may be missed)");}
    }
    public synchronized void close(){closed=true;Arrays.fill(key,(byte)0);}
}
