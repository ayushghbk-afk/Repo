#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${MCP_URL:-http://127.0.0.1:8080/mcp}"
base="${BASE_URL%/}"

printf '%s\n' '--- GET / ---'
curl --fail-with-body -sS "${base%/mcp}/" | python3 -m json.tool
printf '%s\n' '--- initialize ---'
curl --fail-with-body -sS "$base" \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data-raw '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl-smoke","version":"1.0"}}}' | python3 -m json.tool
printf '%s\n' '--- tools/list ---'
curl --fail-with-body -sS "$base" \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data-raw '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | python3 -m json.tool
printf '%s\n' '--- tools/call decode_data ---'
curl --fail-with-body -sS "$base" \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data-raw '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"decode_data","arguments":{"operation":"text_to_hex","input":"hello"}}}' | python3 -m json.tool
