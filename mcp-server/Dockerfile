FROM python:3.12-slim

WORKDIR /opt/android-reverse-mcp
COPY . /opt/android-reverse-mcp

ENV MCP_HOST=0.0.0.0 \
    MCP_PORT=8080 \
    MCP_PATH=/mcp \
    MCP_WORKSPACE_PATH=/data/workspace \
    MCP_CACHE_PATH=/data/cache

RUN mkdir -p /data/workspace /data/cache
EXPOSE 8080

CMD ["python", "-m", "mcp_server"]
