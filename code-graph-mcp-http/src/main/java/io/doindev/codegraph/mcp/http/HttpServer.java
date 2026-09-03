package io.doindev.codegraph.mcp.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.config.loader.ConfigLoader;
import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.Workspace;
import io.doindev.codegraph.lifecycle.ProjectLifecycle;
import io.doindev.codegraph.mcp.CodeGraphMcpServer;
import io.doindev.codegraph.mcp.ProjectOnboarding;
import io.doindev.codegraph.mcp.WorkspaceVizControl;
import io.doindev.codegraph.tools.CodeGraphTools;
import io.doindev.codegraph.tools.GraphTool;
import io.doindev.codegraph.tools.WorkspaceTools;
import io.doindev.codegraph.viz.VizServer;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.time.Duration;

/**
 * Assembles the shared/team deployment: index a repo root, watch it for changes, and serve the
 * code-graph tool set over the MCP streamable-HTTP transport at {@code /mcp} on embedded Jetty 12.
 * {@link HttpMain} is the thin CLI wrapper; tests start it on port 0 and read {@link #port()}.
 * Progress goes to stderr, matching the stdio server's convention.
 */
public final class HttpServer implements AutoCloseable {

    /** Path the MCP streamable-HTTP transport is mounted at. */
    public static final String MCP_ENDPOINT = "/mcp";

    private final Server jetty;
    private final ServerConnector connector;
    private final CodeGraphMcpServer mcp;
    private final Workspace workspace;
    private final VizServer viz;
    private final WorkspaceTools registry;

    private HttpServer(Server jetty, ServerConnector connector, CodeGraphMcpServer mcp,
                       Workspace workspace, VizServer viz, WorkspaceTools registry) {
        this.registry = registry;
        this.jetty = jetty;
        this.connector = connector;
        this.mcp = mcp;
        this.workspace = workspace;
        this.viz = viz;
    }

    /**
     * Indexes {@code root}, starts the file watcher and serves MCP over streamable HTTP.
     *
     * @param root repo root to index and watch
     * @param port TCP port to bind; 0 picks an ephemeral port (read it back via {@link #port()})
     * @return the running server; {@link #close()} stops Jetty, the MCP server and the watcher
     */
    public static HttpServer start(Path root, int port) throws Exception {
        return start(List.of(root), port, -1);
    }

    /**
     * Multi-project variant: every root indexes as its own project (tools route on the
     * {@code project} parameter); {@code vizPort >= 0} also serves the 3D visualization UI,
     * bound to all interfaces like the MCP endpoint (this is the team deployment).
     */
    public static HttpServer start(List<Path> roots, int port, int vizPort) throws Exception {
        Analyzers analyzers = Analyzers.discover();
        if (analyzers.isEmpty()) {
            throw new IllegalStateException("code-graph-http: no language analyzers on classpath");
        }
        Workspace workspace = Workspace.open(roots, analyzers, ConfigLoader::load);
        return start(workspace, port, vizPort);
    }

    public static HttpServer start(Workspace workspace, int port, int vizPort) throws Exception {
        return start(workspace, port, vizPort, false);
    }

    /**
     * @param vizAdmin enable the viz action endpoints (reindex/add/remove/browse). Off by
     *                 default because this server binds to all interfaces — only enable behind
     *                 trusted network controls.
     */
    public static HttpServer start(Workspace workspace, int port, int vizPort, boolean vizAdmin)
            throws Exception {
        return start(workspace, port, vizPort, vizAdmin, ProjectLifecycle.DEFAULT_TTL);
    }

    public static HttpServer start(Workspace workspace, int port, int vizPort, boolean vizAdmin,
                                   Duration projectTtl) throws Exception {
        ProjectLifecycle.validateTtl(projectTtl);
        long start = System.nanoTime();
        workspace.fullIndexAll().forEach((name, result) -> System.err.printf(
                "code-graph-http: [%s] indexed %d files, %d symbols, %d edges%n",
                name, result.filesIndexed(), result.symbolCount(), result.edgeCount()));
        System.err.printf("code-graph-http: %d project(s) ready in %d ms%n",
                workspace.projects().size(), (System.nanoTime() - start) / 1_000_000);
        workspace.watchAll();

        WorkspaceTools registry = CodeGraphTools.workspace(List.of(), workspace::remove);
        registry.lifecycle().setTtl(projectTtl);
        ProjectOnboarding onboarding = new ProjectOnboarding(workspace, registry, Analyzers.discover());
        workspace.projects().forEach(onboarding::register);
        List<GraphTool> tools = registry.tools(onboarding::add);

        VizServer viz = null;
        CodeGraphMcpServer mcp = null;
        Server jetty = null;
        try {
            HttpServletStreamableServerTransportProvider transport =
                    HttpServletStreamableServerTransportProvider.builder()
                            .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
                            .mcpEndpoint(MCP_ENDPOINT)
                            .build();
            mcp = CodeGraphMcpServer.serve("code-graph", "0.0.1", tools, transport);

            // dispatch requests onto virtual threads — tool calls block on graph queries
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName("code-graph-http");
            threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
            jetty = new Server(threadPool);
            ServerConnector connector = new ServerConnector(jetty);
            connector.setPort(port);
            jetty.addConnector(connector);

            ServletContextHandler context = new ServletContextHandler("/");
            ServletHolder holder = new ServletHolder("mcp", transport);
            holder.setAsyncSupported(true); // the transport streams SSE responses via startAsync()
            context.addServlet(holder, MCP_ENDPOINT);
            jetty.setHandler(context);
            jetty.start();

            int boundPort = connector.getLocalPort();
            System.err.printf("code-graph-http: serving MCP (streamable HTTP) on http://0.0.0.0:%d%s%n",
                    boundPort, MCP_ENDPOINT);

            if (vizPort >= 0) {
                String endpoint = "http://<host>:" + boundPort + MCP_ENDPOINT;
                io.doindev.codegraph.viz.VizControl control = new WorkspaceVizControl(
                        workspace, registry, Analyzers.discover(), endpoint, vizAdmin);
                viz = VizServer.start(control, java.net.InetAddress.getByName("0.0.0.0"), vizPort);
                System.err.println("code-graph-http: viz on http://0.0.0.0:" + viz.port() + "/"
                        + (vizAdmin ? " (actions enabled)" : " (read-only)"));
            }
            registry.lifecycle().start();
            System.err.println("code-graph-http: project idle TTL " + projectTtl);
            return new HttpServer(jetty, connector, mcp, workspace, viz, registry);
        } catch (Exception e) {
            if (jetty != null) {
                try {
                    jetty.stop();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
            if (mcp != null) {
                mcp.close();
            }
            if (viz != null) {
                viz.close();
            }
            registry.close();
            workspace.close();
            throw e;
        }
    }

    /** The port Jetty actually bound (useful when started with port 0). */
    public int port() {
        return connector.getLocalPort();
    }

    /** Blocks until the server is stopped. */
    public void join() throws InterruptedException {
        jetty.join();
    }

    /** Port the viz UI bound, or -1 when viz is off. */
    public int vizPort() {
        return viz == null ? -1 : viz.port();
    }

    @Override
    public void close() {
        try {
            jetty.stop();
        } catch (Exception e) {
            System.err.println("code-graph-http: error stopping Jetty: " + e.getMessage());
        }
        mcp.close();
        if (viz != null) {
            viz.close();
        }
        registry.close();
        workspace.close();
    }
}
