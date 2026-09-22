# Android Reverse Engineering MCP server

This directory is the standalone Python implementation of the repository's
Android reverse-engineering MCP service. It exposes the 29-tool public contract
through configurable Streamable HTTP JSON-RPC. See the repository root
[`README.md`](../README.md) for the complete tool catalog, environment
reference, client JSON, curl examples, Cloudflare Tunnel deployment, and
troubleshooting.

Quick start:

```bash
cp .env.example .env
set -a; . .env; set +a
./scripts/run-server.sh
```

Run checks:

```bash
./scripts/test.sh
```

The runtime dependencies are Python 3.10+ and the standard library. JADX,
apktool, Android SDK tools, radare2, and framework-specific analyzers are
optional and configured with environment variables. Workspaces and caches are
kept outside Git.
