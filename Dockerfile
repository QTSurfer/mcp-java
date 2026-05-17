# QTSurfer MCP — fat JAR image (JRE 21, ~230 MB).
# For a smaller image with ~17 ms startup, use Dockerfile.native to produce
# a GraalVM native binary and package it in distroless/cc (~65 MB).
#
# Build:
#   docker build -t qtsurfer/mcp-java:0.2.0 .
#
# Run (MCP over stdio — pipe stdin/stdout):
#   docker run -i -e QTS_TOKEN=<token> qtsurfer/mcp-java:0.2.0
#
# Claude Code config (~/.claude.json):
#   "command": "docker",
#   "args": ["run", "-i", "--rm", "-e", "QTS_TOKEN", "qtsurfer/mcp-java:0.2.0"]

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/mcp-*.jar app.jar
ENV QTS_URL=https://api.qtsurfer.com/v1
ENTRYPOINT ["java", "-jar", "app.jar"]
