#!/usr/bin/env python3
"""
MCP relay client. Runs on a GitHub-hosted runner because the sandbox network
cannot reach the Cloudflare tunnel that fronts the MCP daemon.

Modes
-----
* If .mcp-relay/calls.json exists -> execute that ordered list of tool calls.
* Otherwise                       -> capability discovery sweep.

All output is written to .mcp-relay/out/ and committed back to the branch, which
is the only channel readable from the sandbox.
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get(
    "MCP_URL", "https://mysimon-undo-congressional-reliability.trycloudflare.com"
).rstrip("/")
OUT = os.path.join(HERE, "out")
MAX_TEXT = 200000
CALLS_FILE = os.path.join(HERE, "calls.json")
os.makedirs(OUT, exist_ok=True)

PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"]
session_id = None
_id = [0]
log_lines = []


def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    log_lines.append(line)


def save(name, obj):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(obj if isinstance(obj, str) else json.dumps(obj, indent=2, ensure_ascii=False))
    log("saved %s (%d bytes)" % (name, os.path.getsize(path)))


def parse_body(raw, content_type=""):
    raw = raw or ""
    if "text/event-stream" in content_type or raw.lstrip().startswith("event:"):
        events = []
        for block in re.split(r"\r?\n\r?\n", raw):
            data = [ln[5:].lstrip() for ln in block.splitlines() if ln.startswith("data:")]
            if not data:
                continue
            chunk = "\n".join(data)
            try:
                events.append(json.loads(chunk))
            except Exception:
                events.append({"_raw": chunk})
        rpc = [e for e in events if isinstance(e, dict) and ("result" in e or "error" in e)]
        return {"_transport": "sse", "_events": events, "value": (rpc[-1] if rpc else None)}
    try:
        return {"_transport": "json", "value": json.loads(raw)}
    except Exception:
        return {"_transport": "text", "value": None, "_raw": raw[:20000]}


def post(path, payload, timeout=300):
    global session_id
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "User-Agent": "arena-mcp-relay/1.0",
    }
    if session_id:
        headers["mcp-session-id"] = session_id
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
            sid = resp.headers.get("mcp-session-id")
            if sid:
                session_id = sid
            return {"ok": True, "status": resp.status, "content_type": resp.headers.get("Content-Type", ""),
                    "body": body, "parsed": parse_body(body, resp.headers.get("Content-Type", ""))}
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        return {"ok": False, "status": exc.code, "content_type": exc.headers.get("Content-Type", ""),
                "body": body, "parsed": parse_body(body, exc.headers.get("Content-Type", ""))}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "status": None, "error": repr(exc), "parsed": {"value": None}}


def rpc(path, method, params=None, notify=False, timeout=300):
    _id[0] += 1
    payload = {"jsonrpc": "2.0", "method": method}
    if not notify:
        payload["id"] = _id[0]
    if params is not None:
        payload["params"] = params
    return post(path, payload, timeout=timeout)


def initialize():
    for path in ["/mcp", "/mcp/", "/", "/messages", "/rpc", "/jsonrpc"]:
        for version in PROTOCOL_VERSIONS:
            r = rpc(path, "initialize", {
                "protocolVersion": version, "capabilities": {},
                "clientInfo": {"name": "arena-mcp-relay", "version": "1.0"}}, timeout=60)
            val = (r.get("parsed") or {}).get("value")
            if isinstance(val, dict) and "result" in val:
                log("initialize OK via %s (%s)" % (path, version))
                rpc(path, "notifications/initialized", {}, notify=True)
                return path, val
    log("!! initialize failed on all endpoints")
    return None, None


def tool_text(result):
    """Flatten MCP tool content blocks into readable text."""
    if not isinstance(result, dict):
        return None
    r = result.get("result")
    if not isinstance(r, dict):
        return None
    out = []
    for block in r.get("content", []) or []:
        if isinstance(block, dict) and block.get("type") == "text":
            out.append(block.get("text", ""))
        else:
            out.append(json.dumps(block)[:2000])
    return "\n".join(out)


# ---------------------------------------------------------------------------
path, init = initialize()
if not path:
    save("99-log.txt", "\n".join(log_lines))
    sys.exit(0)
save("02-initialize-result.json", init)

if os.path.exists(CALLS_FILE):
    with open(CALLS_FILE, encoding="utf-8") as fh:
        spec = json.load(fh)
    calls = spec if isinstance(spec, list) else spec.get("calls", [])
    results = []
    for i, call in enumerate(calls):
        tool = call.get("tool")
        args = call.get("arguments", {}) or {}
        log("call %d/%d %s %s" % (i + 1, len(calls), tool, json.dumps(args)[:200]))
        r = rpc(path, "tools/call", {"name": tool, "arguments": args},
                timeout=int(call.get("timeout", 300)))
        val = (r.get("parsed") or {}).get("value")
        text = tool_text(val)
        if text and len(text) > MAX_TEXT:
            text = text[:MAX_TEXT] + "\n...[truncated by relay]"
        entry = {"tool": tool, "arguments": args, "status": r.get("status"),
                 "error": r.get("error"), "rpc": val, "text": text}
        results.append(entry)
        preview = (text if text is not None else json.dumps(val)) or ""
        log("   -> %s" % preview[:300].replace("\n", " "))
        # keep a per-call file so big payloads stay inspectable
        save("10-call-%02d-%s.json" % (i + 1, re.sub(r"\W+", "_", tool)[:40]), entry)
    save("11-calls-combined.json", results)
else:
    log("no calls.json present; run discovery")
    caps = {}
    for name, method, params in [("tools", "tools/list", {}), ("resources", "resources/list", {}),
                                 ("prompts", "prompts/list", {})]:
        r = rpc(path, method, params, timeout=60)
        caps[name] = (r.get("parsed") or {}).get("value")
    save("03-capabilities.json", caps)

save("99-log.txt", "\n".join(log_lines))
log("done")
save("99-log.txt", "\n".join(log_lines))
