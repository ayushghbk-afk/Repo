#!/usr/bin/env python3
"""
Minimal MCP (Model Context Protocol) client used to reach a daemon that is not
directly reachable from the sandbox network.

Round 1 goal: discover the server's capabilities (tools / resources / prompts)
and dump everything raw so the caller can decide how to extract the project.

Everything discovered is written under .mcp-relay/out/ and committed back to the
repository, because that is the only channel readable from the sandbox.
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get(
    "MCP_URL", "https://mysimon-undo-congressional-reliability.trycloudflare.com"
).rstrip("/")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)

PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"]

session_id = None
log_lines = []


def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    log_lines.append(line)


def save(name, obj):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as fh:
        if isinstance(obj, str):
            fh.write(obj)
        else:
            json.dump(obj, fh, indent=2, ensure_ascii=False)
    log("saved %s (%d bytes)" % (name, os.path.getsize(path)))


def parse_body(raw, content_type=""):
    """MCP Streamable HTTP may answer with application/json or text/event-stream."""
    raw = raw or ""
    if "text/event-stream" in content_type or raw.lstrip().startswith("event:"):
        events = []
        for block in re.split(r"\r?\n\r?\n", raw):
            data_lines = [
                ln[5:].lstrip() for ln in block.splitlines() if ln.startswith("data:")
            ]
            if not data_lines:
                continue
            chunk = "\n".join(data_lines)
            try:
                events.append(json.loads(chunk))
            except Exception:
                events.append({"_raw": chunk})
        # Return the last JSON-RPC message if present, else the whole list.
        rpc = [e for e in events if isinstance(e, dict) and ("result" in e or "error" in e)]
        return {"_transport": "sse", "_events": events, "value": (rpc[-1] if rpc else None)}
    try:
        return {"_transport": "json", "value": json.loads(raw)}
    except Exception:
        return {"_transport": "text", "value": None, "_raw": raw[:20000]}


def post(path, payload, extra_headers=None, timeout=90, method="POST"):
    global session_id
    url = BASE + path
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "User-Agent": "arena-mcp-relay/1.0",
    }
    if session_id:
        headers["mcp-session-id"] = session_id
        headers["Mcp-Session-Id"] = session_id
    if extra_headers:
        headers.update(extra_headers)
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
            sid = resp.headers.get("mcp-session-id") or resp.headers.get("Mcp-Session-Id")
            if sid:
                session_id = sid
            return {
                "ok": True,
                "status": resp.status,
                "headers": dict(resp.headers),
                "content_type": resp.headers.get("Content-Type", ""),
                "body": body,
                "parsed": parse_body(body, resp.headers.get("Content-Type", "")),
            }
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        return {
            "ok": False,
            "status": exc.code,
            "headers": dict(exc.headers),
            "content_type": exc.headers.get("Content-Type", ""),
            "body": body,
            "parsed": parse_body(body, exc.headers.get("Content-Type", "")),
        }
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "status": None, "error": repr(exc), "parsed": {"value": None}}


def rpc(path, method, params=None, msg_id=1, notify=False):
    payload = {"jsonrpc": "2.0", "method": method}
    if not notify:
        payload["id"] = msg_id
    if params is not None:
        payload["params"] = params
    return post(path, payload)


def short(resp, limit=600):
    txt = resp.get("body") or resp.get("error") or ""
    return txt[:limit].replace("\n", " ")


# ---------------------------------------------------------------------------
# Step 0: what does a plain GET look like from a real HTTP client?
# ---------------------------------------------------------------------------
get_probes = {}
for p in ["/", "/mcp", "/sse", "/messages", "/health"]:
    r = post(p, None, method="GET", timeout=30)
    get_probes[p] = {"status": r.get("status"), "content_type": r.get("content_type"),
                     "body": (r.get("body") or r.get("error") or "")[:2000]}
    log("GET %-10s -> %s %s" % (p, r.get("status"), r.get("content_type")))
save("00-get-probes.json", get_probes)

# ---------------------------------------------------------------------------
# Step 1: find a working endpoint + protocol version via `initialize`
# ---------------------------------------------------------------------------
CANDIDATE_PATHS = ["/mcp", "/mcp/", "/", "/messages", "/rpc", "/jsonrpc", "/api", "/sse"]

init_result = None
chosen_path = None
attempts = []

for path in CANDIDATE_PATHS:
    for version in PROTOCOL_VERSIONS:
        params = {
            "protocolVersion": version,
            "capabilities": {},
            "clientInfo": {"name": "arena-mcp-relay", "version": "1.0"},
        }
        r = rpc(path, "initialize", params, msg_id=1)
        val = (r.get("parsed") or {}).get("value")
        record = {
            "path": path,
            "protocolVersion": version,
            "status": r.get("status"),
            "content_type": r.get("content_type"),
            "session_id": session_id,
            "has_rpc_result": isinstance(val, dict) and "result" in val,
            "error": r.get("error"),
            "body_preview": short(r, 400),
        }
        attempts.append(record)
        if isinstance(val, dict) and "result" in val:
            init_result = val
            chosen_path = path
            log("INITIALIZE OK via %s (protocol %s) session=%s" % (path, version, session_id))
            break
        log("initialize %s v%s -> %s %s" % (path, version, r.get("status"), short(r, 160)))
    if init_result:
        break

save("01-initialize-attempts.json", attempts)
save("02-initialize-result.json", init_result or {"error": "no endpoint accepted initialize"})

if not init_result:
    log("!! No MCP endpoint accepted `initialize`. Dumping state and exiting.")
    save("99-log.txt", "\n".join(log_lines))
    sys.exit(0)

# Tell the server we are ready (required by the spec before other calls).
rpc(chosen_path, "notifications/initialized", {}, notify=True)

# ---------------------------------------------------------------------------
# Step 2: enumerate capabilities
# ---------------------------------------------------------------------------
capabilities = {}
for name, method, params in [
    ("tools", "tools/list", {}),
    ("resources", "resources/list", {}),
    ("resource_templates", "resources/templates/list", {}),
    ("prompts", "prompts/list", {}),
]:
    r = rpc(chosen_path, method, params, msg_id=2)
    val = (r.get("parsed") or {}).get("value")
    capabilities[name] = val if val is not None else {"_raw": short(r, 4000), "_status": r.get("status")}
    log("%s: %s" % (name, json.dumps(val)[:300] if val else "n/a"))
save("03-capabilities.json", capabilities)

# ---------------------------------------------------------------------------
# Step 3: best-effort, strictly read-only tool exploration
# ---------------------------------------------------------------------------
MUTATING = re.compile(
    r"(delete|remove|destroy|write|create|edit|update|patch|exec|execute|run|shell|bash|"
    r"terminal|kill|stop|start|restart|install|deploy|publish|push|commit|send|post|set|"
    r"save|move|rename|mkdir|touch|chmod|eval|apply|migrat)",
    re.I,
)

tools = []
tv = capabilities.get("tools")
if isinstance(tv, dict):
    tools = tv.get("result", {}).get("tools", []) if isinstance(tv.get("result"), dict) else []

calls = []
for tool in tools:
    name = tool.get("name", "")
    schema = tool.get("inputSchema") or {}
    required = schema.get("required") or []
    props = schema.get("properties") or {}
    if MUTATING.search(name):
        log("skip (looks mutating): %s" % name)
        continue
    if required:
        log("skip (needs args %s): %s" % (required, name))
        continue
    args = {k: "" for k in [] }  # no args
    r = rpc(chosen_path, "tools/call", {"name": name, "arguments": args}, msg_id=3, )
    val = (r.get("parsed") or {}).get("value")
    calls.append({"tool": name, "arguments": args, "result": val if val is not None else short(r, 4000)})
    log("called %s -> %s" % (name, json.dumps(val)[:200] if val else "n/a"))

save("04-tool-calls.json", calls)

# ---------------------------------------------------------------------------
# Step 4: read any advertised resources (read-only by definition)
# ---------------------------------------------------------------------------
reads = []
rv = capabilities.get("resources")
if isinstance(rv, dict) and isinstance(rv.get("result"), dict):
    for res in rv["result"].get("resources", [])[:50]:
        uri = res.get("uri")
        if not uri:
            continue
        r = rpc(chosen_path, "resources/read", {"uri": uri}, msg_id=4)
        val = (r.get("parsed") or {}).get("value")
        reads.append({"uri": uri, "name": res.get("name"), "result": val if val is not None else short(r, 4000)})
        log("read %s -> %s" % (uri, json.dumps(val)[:200] if val else "n/a"))
save("05-resource-reads.json", reads)

save("99-log.txt", "\n".join(log_lines))
log("done")
save("99-log.txt", "\n".join(log_lines))
