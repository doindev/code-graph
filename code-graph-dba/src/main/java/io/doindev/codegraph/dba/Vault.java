package io.doindev.codegraph.dba;

/** No secret is a profile field. Missing/locked vaults fail closed. */
public interface Vault {
    void put(String id, byte[] secret);
    byte[] get(String id);
    void remove(String id);
    static Vault system() { return NativeVault.create(); }
}
