package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.*;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.*;
import org.bouncycastle.openssl.jcajce.*;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

/** Reads user-owned keys in place. Never writes, imports, converts or returns private material. */
final class SnowflakeKeys {
    static ObjectNode validate(String file,char[] passphrase){
        byte[] bytes=null;
        try{
            Path path=Path.of(file).toRealPath();if(!Files.isRegularFile(path)||Files.size(path)>65_536)throw new IllegalArgumentException("Choose a readable PKCS#8 PEM key file (maximum 64 KiB)");
            bytes=Files.readAllBytes(path);Object parsed;
            try(var reader=new PEMParser(new java.io.InputStreamReader(new java.io.ByteArrayInputStream(bytes),StandardCharsets.US_ASCII))){parsed=reader.readObject();if(reader.readObject()!=null)throw new IllegalArgumentException("Choose a file containing exactly one private key");}
            boolean encrypted=parsed instanceof PKCS8EncryptedPrivateKeyInfo;
            PrivateKeyInfo info;
            if(encrypted){if(passphrase.length==0)throw new IllegalArgumentException("An encrypted key requires its passphrase");info=((PKCS8EncryptedPrivateKeyInfo)parsed).decryptPrivateKeyInfo(new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(new BouncyCastleProvider()).build(passphrase));}
            else if(parsed instanceof PrivateKeyInfo p)info=p;
            else throw new IllegalArgumentException("Use a PKCS#8 PEM RSA private key; OpenSSH, PKCS#1 and public-key-only files are not supported. Convert a copy externally, not the original.");
            PrivateKey key=new JcaPEMKeyConverter().setProvider(new BouncyCastleProvider()).getPrivateKey(info);
            if(!(key instanceof RSAPrivateCrtKey rsa)||rsa.getModulus().bitLength()<2048)throw new IllegalArgumentException("Snowflake requires an RSA private key of at least 2048 bits");
            byte[] publicKey=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(),rsa.getPublicExponent())).getEncoded();
            return Profiles.JSON.createObjectNode().put("format","PKCS#8 PEM").put("encrypted",encrypted).put("bits",rsa.getModulus().bitLength()).put("fingerprint","SHA256:"+Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey))).put("contentHash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }catch(IllegalArgumentException e){throw e;}catch(java.nio.file.NoSuchFileException e){throw new IllegalArgumentException("Private key file was not found");}
        catch(Exception e){throw new IllegalArgumentException("Cannot read/decrypt this key; check file permissions, PKCS#8 format and passphrase");}
        finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);Arrays.fill(passphrase,'\0');}
    }
}
