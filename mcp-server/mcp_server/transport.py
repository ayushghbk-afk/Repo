"""Minimal Streamable HTTP JSON-RPC transport for MCP."""

from __future__ import annotations

import json
import logging
import threading
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from .config import Settings
from .engine import AnalysisEngine
from .schemas import TOOL_NAMES, tool_definitions
from .utils import ToolError

LOGGER = logging.getLogger("android_reverse_mcp")
SERVER_NAME = "androidReverse Cognitive Core"
SERVER_VERSION = "5.8.0"
SUPPORTED_PROTOCOL = "2024-11-05"


class McpApplication:
    """JSON-RPC request handling independent of the HTTP server."""

    def __init__(self, settings: Settings, engine: AnalysisEngine | None = None) -> None:
        self.settings = settings
        self.engine = engine or AnalysisEngine(settings)
        self._sessions: set[str] = set()
        self._lock = threading.RLock()

    def initialize(self, params: dict[str, Any]) -> tuple[dict[str, Any], str]:
        requested = params.get("protocolVersion", SUPPORTED_PROTOCOL)
        # 2024-11-05 is the contract exposed by the current Android client. Returning
        # it for older/unknown clients is more interoperable than rejecting a client
        # that can still speak the JSON-RPC subset used here.
        protocol = SUPPORTED_PROTOCOL if requested else SUPPORTED_PROTOCOL
        session_id = uuid.uuid4().hex
        with self._lock:
            self._sessions.add(session_id)
        return (
            {
                "protocolVersion": protocol,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            },
            session_id,
        )

    def handle(self, request: Any, session_id: str | None = None) -> tuple[dict[str, Any] | None, str | None, int]:
        if not isinstance(request, dict):
            return self._error(None, -32600, "Request must be a JSON object"), None, HTTPStatus.BAD_REQUEST
        request_id = request.get("id")
        method = request.get("method")
        if not isinstance(method, str):
            return self._error(request_id, -32600, "JSON-RPC method is required"), None, HTTPStatus.BAD_REQUEST
        params = request.get("params")
        if params is None:
            params = {}
        if not isinstance(params, dict):
            return self._error(request_id, -32602, "params must be an object"), None, HTTPStatus.OK

        if method == "initialize":
            result, new_session = self.initialize(params)
            return {"jsonrpc": "2.0", "id": request_id, "result": result}, new_session, HTTPStatus.OK
        if method in {"notifications/initialized", "initialized"}:
            # Notifications have no JSON-RPC response. Streamable HTTP permits a 202
            # response for this request, while clients that incorrectly assign an id
            # still receive a harmless acknowledgement.
            if "id" not in request:
                return None, session_id, HTTPStatus.ACCEPTED
            return {"jsonrpc": "2.0", "id": request_id, "result": {}}, session_id, HTTPStatus.OK
        if method == "ping":
            return {"jsonrpc": "2.0", "id": request_id, "result": {}}, session_id, HTTPStatus.OK
        if method == "tools/list":
            return {"jsonrpc": "2.0", "id": request_id, "result": {"tools": tool_definitions()}}, session_id, HTTPStatus.OK
        if method == "tools/call":
            name = params.get("name")
            arguments = params.get("arguments", {})
            if not isinstance(name, str) or name not in TOOL_NAMES:
                return self._error(request_id, -32602, f"Unknown tool: {name}"), session_id, HTTPStatus.OK
            if not isinstance(arguments, dict):
                return self._error(request_id, -32602, "tools/call arguments must be an object"), session_id, HTTPStatus.OK
            try:
                value = self.engine.dispatch(name, arguments)
                result: dict[str, Any] = {
                    "content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False, indent=2)}]
                }
            except ToolError as exc:
                result = {
                    "isError": True,
                    "content": [{"type": "text", "text": json.dumps({"error": str(exc)}, ensure_ascii=False)}],
                }
            except Exception as exc:  # Keep server alive and do not leak a traceback to clients.
                LOGGER.exception("Unhandled error in MCP tool %s", name)
                result = {
                    "isError": True,
                    "content": [{"type": "text", "text": json.dumps({"error": f"Tool failed: {exc}"}, ensure_ascii=False)}],
                }
            return {"jsonrpc": "2.0", "id": request_id, "result": result}, session_id, HTTPStatus.OK
        if method.startswith("notifications/"):
            return None, session_id, HTTPStatus.ACCEPTED
        return self._error(request_id, -32601, f"Method not found: {method}"), session_id, HTTPStatus.OK

    @staticmethod
    def _error(request_id: Any, code: int, message: str) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


class McpHttpServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, settings: Settings, app: McpApplication | None = None) -> None:
        self.settings = settings
        self.app = app or McpApplication(settings)
        super().__init__((settings.host, settings.port), McpRequestHandler)


class McpRequestHandler(BaseHTTPRequestHandler):
    server: McpHttpServer
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: Any) -> None:
        LOGGER.info("%s - %s", self.address_string(), format % args)

    @property
    def settings(self) -> Settings:
        return self.server.settings

    def _origin(self) -> str | None:
        return self.headers.get("Origin")

    def _cors_headers(self) -> dict[str, str]:
        origin = self._origin()
        allowed = self.settings.cors_origins
        if "*" in allowed:
            value = "*"
        elif origin and origin in allowed:
            value = origin
        else:
            value = allowed[0] if allowed else "*"
        headers = {
            "Access-Control-Allow-Origin": value,
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type, Accept, MCP-Protocol-Version, MCP-Session-Id, Last-Event-ID",
            "Access-Control-Expose-Headers": "MCP-Session-Id, MCP-Protocol-Version",
            "Vary": "Origin",
        }
        if self.settings.cors_allow_credentials:
            headers["Access-Control-Allow-Credentials"] = "true"
        return headers

    def _send_bytes(self, payload: bytes, status: int = HTTPStatus.OK, content_type: str = "application/json", extra: dict[str, str] | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        for key, value in self._cors_headers().items():
            self.send_header(key, value)
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def _send_json(self, value: Any, status: int = HTTPStatus.OK, extra: dict[str, str] | None = None) -> None:
        self._send_bytes(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), status, extra=extra)

    def _is_endpoint(self) -> bool:
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        endpoint = self.settings.path.rstrip("/") or "/"
        return path in {endpoint, "/"} if endpoint == "/" else path in {endpoint, "/"}

    def do_OPTIONS(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if not self._is_endpoint():
            self._send_json({"error": "not found"}, HTTPStatus.NOT_FOUND)
            return
        self._send_bytes(b"", HTTPStatus.NO_CONTENT)

    def do_GET(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        endpoint = self.settings.path.rstrip("/") or "/"
        if path not in {"/", endpoint}:
            self._send_json({"error": "not found", "path": path}, HTTPStatus.NOT_FOUND)
            return
        self._send_json(
            {
                "status": "Cognitive MCP Daemon Online",
                "name": SERVER_NAME,
                "version": SERVER_VERSION,
                "mcpPath": self.settings.path,
                "transport": "Streamable HTTP JSON-RPC",
            }
        )

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        endpoint = self.settings.path.rstrip("/") or "/"
        if path != endpoint:
            self._send_json({"error": "not found", "path": path}, HTTPStatus.NOT_FOUND)
            return
        raw_length = self.headers.get("Content-Length")
        try:
            length = int(raw_length) if raw_length else 0
        except ValueError:
            length = -1
        if length < 0 or length > self.settings.max_input_bytes:
            self._send_json({"error": "request body is too large or invalid"}, HTTPStatus.REQUEST_ENTITY_TOO_LARGE)
            return
        body = self.rfile.read(length)
        try:
            request = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._send_json({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}, HTTPStatus.BAD_REQUEST)
            return
        session_id = self.headers.get("MCP-Session-Id")
        response, new_session, status = self.server.app.handle(request, session_id)
        extra = {"MCP-Protocol-Version": SUPPORTED_PROTOCOL}
        if new_session:
            extra["MCP-Session-Id"] = new_session
        if response is None:
            self._send_bytes(b"", status, extra=extra)
        else:
            self._send_json(response, status, extra=extra)


def create_server(settings: Settings | None = None, app: McpApplication | None = None) -> McpHttpServer:
    settings = settings or Settings.from_env()
    settings.ensure_directories()
    return McpHttpServer(settings, app)


def serve(settings: Settings | None = None) -> None:
    server = create_server(settings)
    LOGGER.info("MCP server listening on http://%s:%s%s", server.settings.host, server.settings.port, server.settings.path)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        LOGGER.info("Stopping MCP server")
    finally:
        server.server_close()
