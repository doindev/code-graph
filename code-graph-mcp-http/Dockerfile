# code-graph streamable-HTTP MCP server, strictly loopback-bound.
#
# Build the module layout first (produces target/classes and target/lib):
#   mvn -B -pl code-graph-mcp-http -am package
# Then, from the repo root:
#   docker build -t code-graph-mcp-http -f code-graph-mcp-http/Dockerfile code-graph-mcp-http
#
# Clients must share the container's network namespace to reach its loopback listener.
# Ordinary -p port publishing does not expose the application. For desktop clients,
# run Java locally (or use the stdio entry point); do not proxy the trusted local endpoint.

FROM eclipse-temurin:25-jre

RUN useradd --system --home-dir /app --shell /usr/sbin/nologin codegraph \
    && mkdir -p /app /workspace \
    && chown -R codegraph:codegraph /app

COPY --chown=codegraph:codegraph target/classes /app/classes
COPY --chown=codegraph:codegraph target/lib /app/lib

USER codegraph
WORKDIR /app

EXPOSE 3000

# --enable-native-access: the tree-sitter grammars load native libraries via System::load
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-cp", "/app/classes:/app/lib/*", \
            "io.doindev.codegraph.mcp.http.HttpMain", "--root", "/workspace", "--port", "3000"]
