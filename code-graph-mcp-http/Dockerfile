# code-graph streamable-HTTP MCP server for shared/team deployment.
#
# Build the module layout first (produces target/classes and target/lib):
#   mvn -B -pl code-graph-mcp-http -am package
# Then, from the repo root:
#   docker build -t code-graph-mcp-http -f code-graph-mcp-http/Dockerfile code-graph-mcp-http
#
# Run against the repo you want indexed (mounted read-only at /workspace):
#   docker run --rm -p 3000:3000 -v $PWD:/workspace:ro code-graph-mcp-http
# MCP clients then connect to http://localhost:3000/mcp (streamable HTTP transport).

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
