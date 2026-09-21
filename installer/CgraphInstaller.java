import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarFile;

/** JDK 25 source launcher: shared, dependency-free build/install engine for install.ps1 and install.sh. */
public final class CgraphInstaller {
    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    static final boolean MAC = System.getProperty("os.name").startsWith("Mac");
    static final String MARKER = "code-graph-native-install-v1";
    static final String SHIM_MARKER = "cgraph-managed-launcher-v1";
    final Map<String,String> options;
    final Path source, base, jdk;
    final Map<String,String> environment = new HashMap<>(System.getenv());

    CgraphInstaller(String[] args) {
        options = parse(args);
        source = Path.of(required("--source")).toAbsolutePath().normalize();
        base = Path.of(required("--install-dir")).toAbsolutePath().normalize();
        jdk = Path.of(System.getProperty("java.home"));
    }

    public static void main(String[] args) {
        try { new CgraphInstaller(args).install(); }
        catch (OptionalSetupFailure e) { System.err.println(e.getMessage()); System.exit(2); }
        catch (Exception e) { System.err.println("Installation failed: " + e.getMessage()); System.err.println("For network failures, inspect the Git/Maven diagnostics and check proxy authentication, repository access and --maven-settings. Installer TLS validation is disabled unless --cert-pem is supplied; runtime TLS is unchanged."); System.exit(1); }
    }
    static final class OptionalSetupFailure extends IOException {
        OptionalSetupFailure() { super("Application installed, but optional MCP/skill setup was incomplete. Inspect per-client results and any backup paths; resolve reported errors and rerun the helpers."); }
    }

    static Map<String,String> parse(String[] args) {
        Map<String,String> out = new LinkedHashMap<>();
        Set<String> flags = Set.of("--run-tests", "--skip-tests", "--no-path", "--non-interactive", "--keep-build", "--build-only");
        Set<String> values = Set.of("--source", "--install-dir", "--maven", "--maven-settings", "--cert-pem", "--proxy", "--no-proxy", "--skills", "--mcp-clients", "--mcp-url");
        for (int i=0; i<args.length; i++) {
            String key = args[i];
            if (out.containsKey(key)) throw new IllegalArgumentException("Duplicate installer option: " + key);
            if (flags.contains(key)) out.put(key, "true");
            else if (values.contains(key) && i+1<args.length && !args[i+1].isBlank() && !args[i+1].startsWith("--")) out.put(key, args[++i]);
            else throw new IllegalArgumentException("Unknown or incomplete installer option: " + key);
        }
        if(out.containsKey("--run-tests")&&out.containsKey("--skip-tests"))throw new IllegalArgumentException("Choose --run-tests or --skip-tests, not both");
        return out;
    }
    String required(String key) { String value=options.get(key); if(value==null||value.isBlank())throw new IllegalArgumentException("Required: "+key); return value; }

    void install() throws Exception {
        SkillInstaller skills = new SkillInstaller(source);
        // Select MCP first. Skills are offered only after actual configuration is rechecked.
        String mcpUrl = options.getOrDefault("--mcp-url", McpInstaller.DEFAULT_URL);
        List<String> selectedMcp = McpInstaller.choose(options.get("--mcp-clients"), options.containsKey("--non-interactive"),
                options.containsKey("--build-only"), System.console(), mcpUrl);
        skills.choose(options.get("--skills"), true, options.containsKey("--build-only"), null); // Early syntax validation only.
        if (Runtime.version().feature()!=25 || !Files.isRegularFile(tool("jpackage")) || !Files.isRegularFile(tool("javac")))
            throw new IOException("A full JDK 25 with javac, jlink and jpackage is required for the build.");
        validateDestination(source, base, Path.of(System.getProperty("user.home")));
        if (!Files.isRegularFile(source.resolve("pom.xml"))) throw new IOException("Source directory does not contain pom.xml");
        Path settings = options.containsKey("--maven-settings") ? Path.of(options.get("--maven-settings")).toAbsolutePath() : null;
        if (settings!=null && !Files.isRegularFile(settings)) throw new IOException("The supplied Maven settings file does not exist");
        String proxy=options.getOrDefault("--proxy",firstNonBlank(environment.get("HTTPS_PROXY"),environment.get("https_proxy"),environment.get("HTTP_PROXY"),environment.get("http_proxy")));
        String generatedSettings=null;
        if(settings==null&&proxy!=null) {
            // -s replaces user settings. Never silently discard corporate mirrors, servers or repository policy.
            requireExplicitExistingSettings(Path.of(System.getProperty("user.home"),".m2","settings.xml"));
            generatedSettings=proxySettings(proxy,options.getOrDefault("--no-proxy",firstNonBlank(environment.get("NO_PROXY"),environment.get("no_proxy"))));
        }
        Path maven = Path.of(required("--maven")).toAbsolutePath();
        environment.put("JAVA_HOME", jdk.toString());
        environment.remove("DBA_DESKTOP_TEST"); // Build tests must not display approval dialogs.
        Files.createDirectories(base);
        Path marker=base.resolve(".cgraph-install");
        if (!Files.exists(marker)) Files.writeString(marker, MARKER, StandardOpenOption.CREATE_NEW);
        Path work = Files.createTempDirectory("cgraph-build-");
        String owner=UUID.randomUUID().toString(); Files.writeString(work.resolve(".cgraph-build-owner"),owner);
        boolean complete=false;
        try {
            Path snapshot=work.resolve("source");
            System.out.println("Building in an isolated copy; existing checkouts and running installations are not modified.");
            copyTree(source,snapshot,true);
            if (generatedSettings!=null) {
                settings=work.resolve("proxy-settings.xml");
                Files.writeString(settings,generatedSettings);
                System.out.println("Using temporary Maven proxy settings (credentials, if supplied, remain in process environment variables).");
            } else if(settings!=null) System.out.println("Using the supplied Maven settings; its proxies and mirrors take precedence for Maven.");
            Path certificatePem=options.containsKey("--cert-pem")?Path.of(options.get("--cert-pem")).toAbsolutePath():null;
            List<String> tlsArguments;
            if(certificatePem!=null){
                tlsArguments=installationTrustArguments(work,certificatePem);
                System.out.println("Maven certificate verification enabled using the supplied PEM plus default JVM trust roots; the temporary installer trust store is not installed.");
            }else{
                tlsArguments=installationTlsArguments();
                System.err.println("WARNING — INSTALLATION ONLY: Maven TLS certificate/hostname/date verification is disabled. Downloads may be intercepted or tampered with. Runtime TLS and global configuration are unchanged.");
            }
            System.out.println(options.containsKey("--run-tests")?"Maven tests explicitly enabled.":"Maven tests skipped (use -RunTests / --run-tests to enable).");
            try{run(mavenBuildCommand(maven,settings,options.containsKey("--run-tests"),tlsArguments),snapshot);}
            finally{if(certificatePem!=null)Files.deleteIfExists(work.resolve("installer-trust.p12"));}
            Path target=snapshot.resolve("code-graph-mcp-http/target");
            Path jar=target.resolve("code-graph-server.jar");
            verifyRuntime(jar,target.resolve("lib"));
            Path input=work.resolve("input");Files.createDirectories(input);
            Files.copy(jar,input.resolve("code-graph-server.jar"));copyTree(target.resolve("lib"),input.resolve("lib"),false);
            String version;
            try(JarFile built=new JarFile(jar.toFile())) { version=built.getManifest().getMainAttributes().getValue("Implementation-Version"); }
            // jpackage requires a numeric application version; Maven snapshot suffixes are not valid on every OS.
            version=version==null?"0.0.1":version.replaceFirst("-.*$","");
            Path output=work.resolve("native");
            List<String> packaging=new ArrayList<>(List.of(tool("jpackage").toString(),"--type","app-image","--name","cgraph",
                    "--input",input.toString(),"--main-jar","code-graph-server.jar","--main-class","io.doindev.codegraph.mcp.http.CgraphMain",
                    "--app-version",version,"--vendor","doindev","--description","Local code graph and JDBC administration",
                    "--dest",output.toString(),"--java-options","--enable-native-access=ALL-UNNAMED",
                    "--add-modules","ALL-MODULE-PATH","--jlink-options","--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services"));
            if(WINDOWS)packaging.add("--win-console");
            System.out.println("Creating the native launcher with a private Java 25 runtime...");run(packaging,work);
            Path image=output.resolve(MAC?"cgraph.app":"cgraph");
            Path executable=image.resolve(executableRelative(WINDOWS,MAC));
            run(List.of(executable.toString(),"--version"),work);
            String release="release-"+Instant.now().toEpochMilli()+"-"+UUID.randomUUID().toString().substring(0,8);
            Path releaseDir=base.resolve("releases").resolve(release);Files.createDirectories(releaseDir);
            Path installed=releaseDir.resolve(image.getFileName());copyTree(image,installed,false);
            Path installedExe=installed.resolve(executableRelative(WINDOWS,MAC));
            run(List.of(installedExe.toString(),"--help"),base);
            Files.writeString(releaseDir.resolve("INSTALLATION.txt"),"Built: "+Instant.now()+"\nJava: "+Runtime.version()+"\nOS: "+System.getProperty("os.name")+" "+System.getProperty("os.arch")+"\nNo credentials, proxy settings, or user data are part of this application bundle.\n");
            if(!options.containsKey("--build-only")) {
                installUninstallers(snapshot, base);
                installShim(base,installedExe,WINDOWS);
                if(!options.containsKey("--no-path"))try { registerPath(base.resolve("bin")); }
                catch(Exception e) { System.err.println("Application installed, but PATH could not be updated. Add this directory manually: "+base.resolve("bin")); }
            }
            complete=true;
            System.out.println("Installed native application: "+installedExe);
            System.out.println("Start with cgraph (open a new terminal if PATH changed). Ctrl+C stops it. No server was started or stopped by installation.");
            System.out.println("Defaults: MCP 3000, admin UI/DBA 8137, desktop approvals, hybrid storage, 1.5 GiB graph/cache budget (1536m).");
            System.out.println("JDK, Git and Maven were build prerequisites only. Node.js/npm are not required.");
            if (!options.containsKey("--build-only")) finishOptionalSetup(snapshot, selectedMcp, mcpUrl);
        } finally {
            if(complete&&!options.containsKey("--keep-build")) {
                try { removeOwnedWork(work,owner); } catch(IOException e) { System.err.println("Temporary build cleanup could not complete; remove this owned build directory after tools close: "+work); }
            } else System.err.println("Build directory retained for diagnostics: "+work);
        }
    }

    void finishOptionalSetup(Path snapshot, List<String> selectedMcp, String url) throws IOException {
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        var mcpResults = McpInstaller.install(selectedMcp, home, environment, System.getProperty("os.name"), url);
        if (selectedMcp.isEmpty()) System.out.println("MCP setup skipped; checking for existing code-graph connections before offering skills.");
        for (var result : mcpResults) System.out.println("MCP " + result.client() + ": " + result.status() + " — " + result.destination() + " — " + result.message());
        boolean failed = mcpResults.stream().anyMatch(r -> !Set.of("configured", "already-configured", "scoped-existing").contains(r.status()));
        if ("none".equalsIgnoreCase(options.getOrDefault("--skills", ""))
                || (!options.containsKey("--skills") && options.containsKey("--non-interactive"))) {
            System.out.println("Optional skills skipped by explicit/unattended choice. No additional client configuration scanned.");
            if (failed) throw new OptionalSetupFailure();
            return;
        }
        List<String> eligible = McpInstaller.skillClients(home, environment, System.getProperty("os.name"), url, System.out);
        SkillInstaller skills = new SkillInstaller(snapshot);
        try {
            List<String> chosen = skills.chooseConfigured(options.get("--skills"), options.containsKey("--non-interactive"), false, System.console(), eligible);
            if (chosen.isEmpty()) System.out.println("Optional skills skipped (no selection, or no detected code-graph MCP connections).");
            for (var result : skills.install(chosen, home, environment,
                    SkillInstaller.confirmation(options.containsKey("--non-interactive"), System.console()))) {
                System.out.println("Skill " + result.client() + ": " + result.status() + " — " + result.destination()
                        + (result.backup() == null ? "" : " — Previous skill backup: " + result.backup())
                        + (result.status().equals("skipped-existing") ? " — Existing skill kept. Rerun interactively to approve replacement." : "")
                        + (result.error() == null ? "" : " (" + result.error() + ")"));
                failed |= result.status().equals("failed");
            }
        } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); failed = true; }
        System.out.println("Start cgraph separately. Clients may need reload/restart and their normal MCP trust approval. Cloud clients cannot reach this machine's loopback endpoint.");
        if (failed) throw new OptionalSetupFailure();
    }

    static String firstNonBlank(String... values) { for(String value:values)if(value!=null&&!value.isBlank())return value;return null; }
    static void installUninstallers(Path source, Path base) throws IOException {
        for (String name : List.of("uninstall.ps1", "uninstall.sh")) {
            Path from = source.resolve(name), to = base.resolve(name);
            if (!Files.isRegularFile(from) || Files.isSymbolicLink(from)
                    || !Files.readString(from).contains("# cgraph-managed-uninstaller-v1"))
                throw new IOException("Missing verified uninstaller: " + name);
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(to) || !Files.isRegularFile(to) || Files.mismatch(from, to) != -1)
                    System.err.println("Existing uninstaller preserved: " + to + ". Use the current script from the source checkout for updated behavior.");
            } else Files.copy(from, to);
        }
    }
    Path tool(String name) { return jdk.resolve("bin").resolve(name+(WINDOWS?".exe":"")); }
    static String executableRelative(boolean windows,boolean mac) { return windows?"cgraph.exe":mac?"Contents/MacOS/cgraph":"bin/cgraph"; }

    static void validateDestination(Path source,Path base,Path home)throws IOException {
        source=source.toRealPath();base=base.toAbsolutePath().normalize();home=home.toAbsolutePath().normalize();
        if(base.getParent()==null||base.equals(home)||source.startsWith(base)||base.startsWith(source))
            throw new IOException("Install directory must be a dedicated directory outside the source checkout, not a drive root or home directory");
        for(Path p=base;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Install path cannot contain symbolic links: "+p);
        if(Files.exists(base)) {
            Path marker=base.resolve(".cgraph-install");
            try(var entries=Files.list(base)) {
                if(entries.findAny().isPresent()&&(!Files.isRegularFile(marker)||!Files.readString(marker).equals(MARKER)))
                    throw new IOException("Install directory is not empty and is not owned by this installer; choose another directory");
            }
        }
        if(base.toString().chars().anyMatch(c->c=='\n'||c=='\r'||c=='\0'))throw new IOException("Unsupported control character in install path");
    }

    static void copyTree(Path from,Path to,boolean sourceOnly)throws IOException {
        Files.walkFileTree(from,new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {
                String name=dir.getFileName().toString();
                if(sourceOnly&&!dir.equals(from)&&Set.of("target",".git","node_modules",".idea",".codex").contains(name))return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(dir)));return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                Path dest=to.resolve(from.relativize(file));
                if(attrs.isSymbolicLink()) {
                    if(sourceOnly)throw new IOException("Source symlinks are not supported by the isolated builder: "+from.relativize(file));
                    Path link=Files.readSymbolicLink(file);
                    if(link.isAbsolute()||!file.getParent().resolve(link).normalize().startsWith(from.toAbsolutePath().normalize()))throw new IOException("Application bundle contains an external symlink");
                    Files.createSymbolicLink(dest,link);
                } else Files.copy(file,dest,StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void verifyRuntime(Path jar,Path lib)throws IOException {
        try(JarFile built=new JarFile(jar.toFile())) {
            var manifest=built.getManifest();
            if(manifest==null||!"io.doindev.codegraph.mcp.http.CgraphMain".equals(manifest.getMainAttributes().getValue("Main-Class")))
                throw new IOException("The checkout does not contain the native cgraph launcher; select a ref that includes this installer");
            String classpath=manifest.getMainAttributes().getValue("Class-Path");
            if(classpath==null||classpath.isBlank())throw new IOException("Runtime dependency manifest is missing");
            for(String entry:classpath.split("\\s+"))if(!entry.startsWith("lib/")||entry.contains("..")||!Files.isRegularFile(lib.resolve(entry.substring(4))))
                throw new IOException("Missing or invalid bundled dependency: "+entry);
        }
    }

    static String proxySettings(String address,String bypass) {
        return proxySettings(address,bypass,System.getenv());
    }
    static String proxySettings(String address,String bypass,Map<String,String> env) {
        java.net.URI proxy;
        try { proxy=java.net.URI.create(address); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException("Invalid proxy URL; use http(s)://host:port without credentials"); }
        if(proxy.getScheme()==null||!Set.of("http","https").contains(proxy.getScheme())||proxy.getHost()==null||proxy.getRawUserInfo()!=null||
                proxy.getRawQuery()!=null||proxy.getRawFragment()!=null||(!proxy.getPath().isEmpty()&&!proxy.getPath().equals("/"))||proxy.getPort()>65535||proxy.getPort()==0)
            throw new IllegalArgumentException("Proxy must be an http(s)://host:port URL without credentials; use CGRAPH_PROXY_USER/PASSWORD or existing Maven settings");
        int port=proxy.getPort()<0?(proxy.getScheme().equals("https")?443:80):proxy.getPort();
        return "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"><proxies><proxy><id>cgraph-install</id><active>true</active><protocol>"+
                xml(proxy.getScheme())+"</protocol><host>"+xml(proxy.getHost())+"</host><port>"+port+"</port>"+
                (env.get("CGRAPH_PROXY_USER")!=null?"<username>${env.CGRAPH_PROXY_USER}</username>":"")+
                (env.get("CGRAPH_PROXY_PASSWORD")!=null?"<password>${env.CGRAPH_PROXY_PASSWORD}</password>":"")+
                "<nonProxyHosts>"+xml(nonProxyHosts(bypass))+"</nonProxyHosts></proxy></proxies></settings>";
    }
    static void requireExplicitExistingSettings(Path existing)throws IOException {
        if(Files.exists(existing))throw new IOException("Existing Maven user settings detected. Configure their proxy and pass --maven-settings (PowerShell: -MavenSettings) to preserve mirrors, credentials and repository policy. No existing settings were changed.");
    }
    static String nonProxyHosts(String bypass) {
        if(bypass==null||bypass.isBlank())return "localhost|127.*|[::1]";
        List<String> hosts=new ArrayList<>();
        for(String item:bypass.split(",")) { String host=item.trim();if(host.isEmpty())continue;
            if(host.contains("/"))throw new IllegalArgumentException("CIDR NO_PROXY entries need explicit Maven nonProxyHosts in --maven-settings");
            if(host.startsWith(".")){hosts.add("*"+host);hosts.add(host.substring(1));}else hosts.add(host);
        }
        return String.join("|",hosts);
    }
    static String xml(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}

    static void installShim(Path base,Path executable,boolean windows)throws IOException {
        Path bin=base.resolve("bin");Files.createDirectories(bin);
        Path shim=bin.resolve(windows?"cgraph.cmd":"cgraph");
        if(Files.exists(shim,LinkOption.NOFOLLOW_LINKS)) {
            boolean ours=windows?Files.isRegularFile(shim)&&Files.readString(shim).contains(SHIM_MARKER):Files.isSymbolicLink(shim)&&shim.getParent().resolve(Files.readSymbolicLink(shim)).normalize().startsWith(base.resolve("releases"));
            if(!ours)throw new IOException("Refusing to overwrite an unrelated cgraph command: "+shim);
        }
        Path pending=bin.resolve(".cgraph-"+UUID.randomUUID());
        if(windows) {
            String relative=bin.relativize(executable).toString();
            if(relative.contains("%")||relative.contains("\""))throw new IOException("Install path cannot contain percent signs or quotes on Windows");
            Files.writeString(pending,"@echo off\r\nrem "+SHIM_MARKER+"\r\n\"%~dp0"+relative+"\" %*\r\nexit /b %errorlevel%\r\n");
        } else Files.createSymbolicLink(pending,executable);
        try { Files.move(pending,shim,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e){Files.move(pending,shim,StandardCopyOption.REPLACE_EXISTING);}
    }

    void registerPath(Path bin)throws Exception {
        if(Arrays.asList(environment.getOrDefault("PATH","").split(java.util.regex.Pattern.quote(File.pathSeparator))).contains(bin.toString()))return;
        if(WINDOWS) {
            environment.put("CGRAPH_INSTALL_BIN",bin.toString());
            run(powershell("$p=[string][Environment]::GetEnvironmentVariable('Path','User'); $b=$env:CGRAPH_INSTALL_BIN; if(@($p -split ';') -notcontains $b){[Environment]::SetEnvironmentVariable('Path',(($p.TrimEnd(';')+';'+$b).TrimStart(';')),'User')}"),base);
            System.out.println("Added cgraph to your user PATH (not the system PATH). Open a new terminal.");
        } else {
            String shell=environment.getOrDefault("SHELL","");
            if(!(shell.endsWith("bash")||shell.endsWith("zsh")||shell.endsWith("sh"))) { System.out.println("Add this directory to your shell PATH: "+bin);return; }
            Path home=Path.of(System.getProperty("user.home"));
            Path profile=home.resolve(shell.endsWith("zsh")?".zshrc":shell.endsWith("bash")?".bashrc":".profile");
            if(options.containsKey("--non-interactive")||!confirm("Add cgraph to PATH in "+profile+"? [y/N] ")) {
                System.out.println("To enable the command: export PATH="+shellQuote(bin.toString())+":\"$PATH\"");return;
            }
            if(Files.isSymbolicLink(profile))throw new IOException("Shell profile is a symlink; add PATH manually: "+bin);
            String line="export PATH="+shellQuote(bin.toString())+":\"$PATH\"";
            if(!Files.exists(profile)||!Files.readString(profile).contains(line))Files.writeString(profile,"\n# cgraph native launcher\n"+line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            System.out.println("PATH entry added. Open a new terminal.");
        }
    }
    static String shellQuote(String s){return "'"+s.replace("'","'\"'\"'")+"'";}
    static boolean confirm(String prompt)throws IOException {System.out.print(prompt);String answer=new BufferedReader(new InputStreamReader(System.in)).readLine();return answer!=null&&Set.of("y","yes").contains(answer.trim().toLowerCase(Locale.ROOT));}
    static List<String> powershell(String code){return List.of("powershell.exe","-NoProfile","-NonInteractive","-OutputFormat","Text","-EncodedCommand",Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_16LE)));}

    void run(List<String> command,Path cwd)throws Exception {
        ProcessBuilder builder;
        if(WINDOWS&&command.getFirst().toLowerCase(Locale.ROOT).endsWith(".cmd")) {
            builder=new ProcessBuilder(powershell("$a=@($env:CGRAPH_TOOL_ARGS|ConvertFrom-Json); & $env:CGRAPH_TOOL @a; exit $LASTEXITCODE"));
        } else builder=new ProcessBuilder(command);
        builder.environment().clear();builder.environment().putAll(environment);
        if(WINDOWS&&command.getFirst().toLowerCase(Locale.ROOT).endsWith(".cmd")) {
            builder.environment().put("CGRAPH_TOOL",command.getFirst());
            builder.environment().put("CGRAPH_TOOL_ARGS","["+String.join(",",command.subList(1,command.size()).stream().map(CgraphInstaller::json).toList())+"]");
        }
        builder.directory(cwd.toFile());builder.inheritIO();
        Process child=builder.start();
        int result=child.waitFor();if(result!=0)throw new IOException(Path.of(command.getFirst()).getFileName()+" exited with code "+result+"; installation was not activated before a successful build");
    }

    // Command-scoped flags only: never put these in MAVEN_OPTS, settings.xml or the packaged JVM.
    // Force Wagon because early supported Maven 3.9 releases lack Resolver's native insecure mode.
    static List<String> installationTlsArguments() {
        return List.of("-Dmaven.resolver.transport=wagon", "-Dmaven.wagon.http.ssl.insecure=true",
                "-Dmaven.wagon.http.ssl.allowall=true", "-Dmaven.wagon.http.ssl.ignore.validity.dates=true");
    }
    static List<String> mavenBuildCommand(Path maven,Path settings,boolean runTests) {
        return mavenBuildCommand(maven,settings,runTests,installationTlsArguments());
    }
    static List<String> mavenBuildCommand(Path maven,Path settings,boolean runTests,List<String> tlsArguments) {
        List<String> command=new ArrayList<>(List.of(maven.toString(),"-B","-ntp","-Djava.awt.headless=true"));
        command.addAll(tlsArguments);
        command.addAll(List.of("-pl","code-graph-mcp-http","-am"));
        if(settings!=null)command.addAll(List.of("--settings",settings.toString()));
        command.add(runTests?"-DskipTests=false":"-DskipTests=true");
        if(runTests)command.add("-Dmaven.test.skip=false");
        command.add("package");
        return command;
    }

    static List<String> installationTrustArguments(Path work,Path pem)throws Exception {
        if(!Files.isRegularFile(pem)||Files.size(pem)>1024*1024)throw new IOException("--cert-pem must name an existing PEM certificate bundle of at most 1 MiB");
        String content=Files.readString(pem,StandardCharsets.US_ASCII);
        if(content.contains("PRIVATE KEY")||!content.contains("-----BEGIN CERTIFICATE-----"))throw new IOException("--cert-pem requires public X.509 PEM certificates, never private keys");
        Collection<? extends java.security.cert.Certificate> certificates;
        try(var input=new ByteArrayInputStream(content.getBytes(StandardCharsets.US_ASCII))){
            certificates=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(input);
        }catch(java.security.cert.CertificateException e){throw new IOException("--cert-pem contains invalid X.509 certificates");}
        if(certificates.isEmpty())throw new IOException("--cert-pem contains no certificates");
        var store=java.security.KeyStore.getInstance("PKCS12");store.load(null,null);
        var managers=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        managers.init((java.security.KeyStore)null);int index=0;
        for(var manager:managers.getTrustManagers())if(manager instanceof javax.net.ssl.X509TrustManager trust)
            for(var certificate:trust.getAcceptedIssuers())store.setCertificateEntry("default-"+(index++),certificate);
        index=0;
        for(var certificate:certificates){
            try{((java.security.cert.X509Certificate)certificate).checkValidity();}
            catch(java.security.cert.CertificateException e){throw new IOException("--cert-pem contains an expired or not-yet-valid certificate; obtain a current PEM bundle");}
            store.setCertificateEntry("installer-"+(index++),certificate);
        }
        Path trust=work.resolve("installer-trust.p12");
        // Public certificates only; the store password is an integrity check, not a credential.
        try(var output=Files.newOutputStream(trust,StandardOpenOption.CREATE_NEW)){store.store(output,"installer-public-certs".toCharArray());}
        return List.of("-Dmaven.resolver.transport=wagon","-Dmaven.wagon.http.ssl.insecure=false",
                "-Dmaven.wagon.http.ssl.allowall=false","-Dmaven.wagon.http.ssl.ignore.validity.dates=false",
                "-Djavax.net.ssl.trustStore="+trust,"-Djavax.net.ssl.trustStoreType=PKCS12","-Djavax.net.ssl.trustStorePassword=installer-public-certs");
    }
    static String json(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}

    static void removeOwnedWork(Path work,String owner)throws IOException {
        Path temp=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path actual=work.toRealPath();
        if(!actual.getParent().equals(temp)||!actual.getFileName().toString().startsWith("cgraph-build-")||Files.isSymbolicLink(work)||
                !Files.readString(actual.resolve(".cgraph-build-owner")).equals(owner))throw new IOException("Build directory ownership verification failed");
        Files.walkFileTree(actual,new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{
                // Keep ownership evidence if cleanup is interrupted, and handle jpackage's
                // read-only Windows launcher in this verified disposable copy only.
                if(file.equals(actual.resolve(".cgraph-build-owner")))return FileVisitResult.CONTINUE;
                if(WINDOWS&&!attrs.isSymbolicLink()){
                    var dos=Files.getFileAttributeView(file,java.nio.file.attribute.DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                    if(dos!=null&&dos.readAttributes().isReadOnly())dos.setReadOnly(false);
                }
                Files.delete(file);return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{
                if(error!=null)throw error;
                if(dir.equals(actual))Files.delete(actual.resolve(".cgraph-build-owner"));
                Files.delete(dir);return FileVisitResult.CONTINUE;
            }
        });
    }
}
