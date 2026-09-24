package io.doindev.codegraph.dba;

import java.nio.file.*;
import java.util.Map;

/** Administrator configuration, never accepted from connection drafts or agents. */
public record DriverDownloadConfig(String mode, String command, Path settings, Path certPem, boolean explicit, boolean insecureTls) {
    public DriverDownloadConfig(String mode,String command,Path settings,Path certPem,boolean explicit){this(mode,command,settings,certPem,explicit,false);}
    public static DriverDownloadConfig embedded() { return new DriverDownloadConfig("embedded", "", null, null, false); }
    public DriverDownloadConfig {
        if (!mode.equals("embedded") && !mode.equals("maven"))
            throw new IllegalArgumentException("--dba-driver-download must be embedded or maven");
        if (mode.equals("embedded") && (!command.isEmpty() || settings != null))
            throw new IllegalArgumentException("Maven command and settings require the installed Maven download mode");
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.indexOf('"') >= 0)
            throw new IllegalArgumentException("--dba-maven-command must be an executable path, not a command with arguments");
        if (settings != null) {
            settings = settings.toAbsolutePath().normalize();
            if (explicit && (!Files.isRegularFile(settings) || !Files.isReadable(settings)))
                throw new IllegalArgumentException("--dba-maven-settings must name a readable settings.xml file");
        }
        if (certPem != null) {
            certPem = certPem.toAbsolutePath().normalize();
            if (explicit && (!Files.isRegularFile(certPem) || !Files.isReadable(certPem)))
                throw new IllegalArgumentException("CA certificate must name a readable PEM file");
        }
    }
    static DriverDownloadConfig parse(Map<String,String> values) {
        boolean explicit=values.keySet().stream().anyMatch(k->k.equals("--dba-driver-download")||k.startsWith("--dba-maven-"));
        return new DriverDownloadConfig(values.getOrDefault("--dba-driver-download", "embedded"),
            values.getOrDefault("--dba-maven-command", ""),
            values.containsKey("--dba-maven-settings") ? Path.of(values.get("--dba-maven-settings")) : null,
            values.containsKey("--dba-maven-cert") ? Path.of(values.get("--dba-maven-cert")) : null, explicit,
            booleanValue(values.getOrDefault("--dba-maven-insecure-tls","false")));
    }
    private static boolean booleanValue(String value){if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("--dba-maven-insecure-tls must be true or false");return Boolean.parseBoolean(value);}
}
