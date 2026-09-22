from __future__ import annotations

import json
import tempfile
import threading
import unittest
import urllib.request
import zipfile
from pathlib import Path

from mcp_server.config import Settings
from mcp_server.schemas import TOOL_NAMES
from mcp_server.transport import McpApplication, McpHttpServer


class McpContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        # Settings validates normal ports; bind the test server to an available port.
        import socket

        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            settings = Settings(
                host="127.0.0.1",
                port=sock.getsockname()[1],
                workspace_path=root / "workspace",
                cache_path=root / "cache",
            )
        self.app = McpApplication(settings)
        self.server = McpHttpServer(settings, self.app)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}/mcp"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def post(self, payload: dict) -> tuple[dict, dict[str, str]]:
        request = urllib.request.Request(
            self.base,
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read()), dict(response.headers)

    def test_initialize_tools_list_and_tool_call(self) -> None:
        initialize, headers = self.post(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "unit-test", "version": "1"},
                },
            }
        )
        self.assertEqual(initialize["result"]["serverInfo"]["name"], "androidReverse Cognitive Core")
        self.assertIn("MCP-Session-Id", headers)

        listing, _ = self.post({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        names = [tool["name"] for tool in listing["result"]["tools"]]
        self.assertEqual(tuple(names), TOOL_NAMES)
        self.assertEqual(len(names), 29)

        called, _ = self.post(
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "decode_data",
                    "arguments": {"operation": "text_to_hex", "input": "hello"},
                },
            }
        )
        self.assertNotIn("isError", called["result"])
        content = json.loads(called["result"]["content"][0]["text"])
        self.assertEqual(content["hex"], "68656c6c6f")

    def test_apk_file_listing_and_workspace(self) -> None:
        apk = Path(self.temp.name) / "workspace" / "sample.apk"
        apk.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"plain test")
            archive.writestr("lib/arm64-v8a/libdemo.so", b"ELF placeholder")
            archive.writestr("classes.dex", b"not a dex")
        listing = self.app.engine.dispatch("list_apk_files", {"apkPath": str(apk), "extensionFilter": ".so"})
        self.assertEqual(listing["files"], ["lib/arm64-v8a/libdemo.so"])
        workspace = self.app.engine.dispatch("list_workspace_files", {})
        self.assertTrue(any(item["filename"] == "sample.apk" for item in workspace["files"]))

    def test_stateful_bookmarks_and_notes(self) -> None:
        added = self.app.engine.dispatch("manage_bookmarks", {"action": "add", "descriptor": "Ldemo/Main;"})
        self.assertIn("Ldemo/Main;", added["bookmarks"])
        note = self.app.engine.dispatch(
            "manage_global_notes", {"action": "write", "title": "finding one", "content": "hello"}
        )
        self.assertEqual(note["bytes"], 5)
        read = self.app.engine.dispatch("manage_global_notes", {"action": "read", "title": "finding one"})
        self.assertEqual(read["content"], "hello")


if __name__ == "__main__":
    unittest.main()
