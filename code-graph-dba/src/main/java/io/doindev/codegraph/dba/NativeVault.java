package io.doindev.codegraph.dba;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Native APIs on Windows/macOS; Secret Service's secret-tool client on Linux. */
final class NativeVault {
    private NativeVault() {}
    static Vault create() {
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new Windows();
        if (os.contains("mac")) return new Mac();
        if (os.contains("linux")) return new Linux();
        throw new IllegalStateException("No supported OS credential vault");
    }
    static IllegalStateException failure() { return new IllegalStateException("OS credential vault unavailable, locked, or entry missing"); }
    interface CredApi extends StdCallLibrary {
        boolean CredWriteW(Credential credential,int flags);
        boolean CredReadW(WString target,int type,int flags,PointerByReference value);
        boolean CredDeleteW(WString target,int type,int flags);
        void CredFree(Pointer pointer);
    }
    @Structure.FieldOrder({"flags","type","target","comment","timeLow","timeHigh","size","blob","persist","count","attributes","alias","user"})
    public static class Credential extends Structure {
        public int flags,type; public WString target,comment; public int timeLow,timeHigh,size;
        public Pointer blob; public int persist,count; public Pointer attributes; public WString alias,user;
        public Credential() {} public Credential(Pointer p) { super(p); read(); }
    }
    static final class Windows implements Vault {
        private CredApi api() { return Native.load("Advapi32",CredApi.class); }
        public void put(String id,byte[] secret) {
            if(secret.length>2560) throw new IllegalArgumentException("Credential exceeds Windows vault limit");
            try(Memory memory=new Memory(Math.max(1,secret.length))) {
                memory.write(0,secret,0,secret.length);
                Credential c=new Credential(); c.type=1;c.target=new WString(id);c.size=secret.length;c.blob=memory;c.persist=2;c.user=new WString("code-graph-dba");
                if(!api().CredWriteW(c,0)) throw failure();
                memory.clear();
            }
        }
        public byte[] get(String id) {
            CredApi a=api();PointerByReference ref=new PointerByReference();
            if(!a.CredReadW(new WString(id),1,0,ref)) throw failure();
            try { Credential c=new Credential(ref.getValue()); return c.blob.getByteArray(0,c.size); }
            finally { a.CredFree(ref.getValue()); }
        }
        public void remove(String id) { if(!api().CredDeleteW(new WString(id),1,0) && Native.getLastError()!=1168) throw failure(); }
    }
    interface SecurityApi extends Library {
        int SecKeychainFindGenericPassword(Pointer keychain,int serviceLength,byte[] service,int accountLength,byte[] account,IntByReference length,PointerByReference data,PointerByReference item);
        int SecKeychainAddGenericPassword(Pointer keychain,int serviceLength,byte[] service,int accountLength,byte[] account,int length,byte[] data,PointerByReference item);
        int SecKeychainItemModifyAttributesAndData(Pointer item,Pointer attributes,int length,byte[] data);
        int SecKeychainItemFreeContent(Pointer attributes,Pointer data);
        int SecKeychainItemDelete(Pointer item);
    }
    interface CoreApi extends Library { void CFRelease(Pointer ref); }
    static final class Mac implements Vault {
        final byte[] service="code-graph-dba".getBytes(StandardCharsets.UTF_8);
        private SecurityApi api() { return Native.load("Security",SecurityApi.class); }
        private void release(Pointer p) { if(p!=null) Native.load("CoreFoundation",CoreApi.class).CFRelease(p); }
        public byte[] get(String id) {
            byte[] account=id.getBytes(StandardCharsets.UTF_8);IntByReference length=new IntByReference();PointerByReference data=new PointerByReference();
            SecurityApi a=api();if(a.SecKeychainFindGenericPassword(null,service.length,service,account.length,account,length,data,null)!=0) throw failure();
            try { return data.getValue().getByteArray(0,length.getValue()); } finally { a.SecKeychainItemFreeContent(null,data.getValue()); }
        }
        public void put(String id,byte[] secret) {
            SecurityApi a=api();byte[] account=id.getBytes(StandardCharsets.UTF_8);PointerByReference item=new PointerByReference();
            int found=a.SecKeychainFindGenericPassword(null,service.length,service,account.length,account,null,null,item);
            if(found==0) { try { if(a.SecKeychainItemModifyAttributesAndData(item.getValue(),null,secret.length,secret)!=0) throw failure(); } finally { release(item.getValue()); } }
            else if(found==-25300) { if(a.SecKeychainAddGenericPassword(null,service.length,service,account.length,account,secret.length,secret,null)!=0) throw failure(); }
            else throw failure();
        }
        public void remove(String id) {
            SecurityApi a=api();byte[] account=id.getBytes(StandardCharsets.UTF_8);PointerByReference item=new PointerByReference();
            int status=a.SecKeychainFindGenericPassword(null,service.length,service,account.length,account,null,null,item);
            if(status==-25300)return; if(status!=0)throw failure();
            try { if(a.SecKeychainItemDelete(item.getValue())!=0)throw failure(); } finally { release(item.getValue()); }
        }
    }
    static final class Linux implements Vault {
        byte[] run(List<String> args,byte[] input,boolean missingOkay) {
            Process p=null;
            try {
                p=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                Process child=p;
                try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                    Future<byte[]> output=executor.submit(()->child.getInputStream().readNBytes(8193));
                    if(input!=null)child.getOutputStream().write(input);
                    child.getOutputStream().close();
                    if(!child.waitFor(Duration.ofSeconds(15))) { child.destroyForcibly();throw failure(); }
                    byte[] result=output.get(2,TimeUnit.SECONDS);
                    if(result.length>8192 || (child.exitValue()!=0&&!missingOkay))throw failure();
                    return result;
                }
            } catch(Exception e) { throw failure(); } finally { if(p!=null&&p.isAlive())p.destroyForcibly(); }
        }
        public void put(String id,byte[] secret) { run(List.of("secret-tool","store","--label=Code Graph DBA","application","code-graph-dba","id",id),Base64.getEncoder().encode(secret),false); }
        public byte[] get(String id) { return Base64.getDecoder().decode(new String(run(List.of("secret-tool","lookup","application","code-graph-dba","id",id),null,false),StandardCharsets.UTF_8).strip()); }
        public void remove(String id) { run(List.of("secret-tool","clear","application","code-graph-dba","id",id),null,false); }
    }
}
