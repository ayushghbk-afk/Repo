# Lenix and Android Reverse Engineering MCP

This repository contains two maintained components:

1. **Android Reverse Engineering MCP** in [`mcp-server/`](mcp-server/). It is a
   standalone, configurable Streamable HTTP MCP server that exposes the complete
   `androidReverse Cognitive Core` tool contract (29 tools) for APK, DEX/Smali,
   ELF/Radare2, IL2CPP, and Flutter analysis.
2. **Lenix Runtime** in [`app/`](app/), the existing Android PRoot/Linux runtime
   project. It is kept intact while the MCP server is published alongside it;
   its original architecture and build notes remain in [`docs/`](docs/),
   [`docs/LENIX-README.md`](docs/LENIX-README.md), and
   [`PORTING_REPORT.md`](PORTING_REPORT.md).

The MCP server has no required Python package dependencies. It uses bounded
Python fallbacks and automatically uses optional Android/native analyzers when
they are configured. Runtime workspaces, extracted APKs, native caches, notes,
and credentials are intentionally excluded from Git.

> Reverse engineer only software and devices that you own or are authorized to
> test. Analyzing an APK does not grant permission to redistribute its code or
> assets.

## MCP project overview

The MCP implementation is a small control plane around a stateful analysis
engine:

```text
MCP client (Claude, Cursor, an agent, curl)
                 |
                 | Streamable HTTP / JSON-RPC 2.0
                 v
       mcp_server.transport
       GET / health, POST /mcp
                 |
                 v
       mcp_server.engine.AnalysisEngine
       +-- APK/ZIP and Android binary XML readers
       +-- DEX structural index and Smali fallback
       +-- resources.arsc and APK signature inspection
       +-- ELF symbols, sections, hex and optional Radare2 bridge
       +-- IL2CPP and Flutter artifact discovery
       +-- persistent bookmarks, notes, and native analysis state
                 |
                 +-- workspace/       input artifacts (not committed)
                 +-- .cache/...       bounded derived data (not committed)
                 +-- optional apktool, JADX, baksmali, SDK, r2, IL2CPP tools
```

### Transport and compatibility

- JSON-RPC 2.0 over HTTP POST at a configurable path, `/mcp` by default.
- Supports `initialize`, `notifications/initialized`, `tools/list`, `tools/call`,
  and `ping`.
- `initialize` returns protocol version `2024-11-05`, server name
  `androidReverse Cognitive Core`, version `5.8.0`, and an MCP session id.
- `tools/list` preserves the public tool names, descriptions, property names,
  required fields, and optional-field schemas.
- Tool results use the normal MCP `content: [{"type":"text", ...}]` shape.
- `GET /` and `GET /mcp` return a health document; tool calls are POST requests.
- CORS, host, port, and endpoint path are environment-configurable. The source
  contains no temporary tunnel hostname.

## Complete MCP tool list

The following 29 tools are registered by `mcp-server/mcp_server/schemas.py` and
implemented by `AnalysisEngine`:

### APK, Android XML, and resources

| Tool | Purpose |
| --- | --- |
| `list_apk_files` | List APK/ZIP members with path, extension, and result-count filters. |
| `read_axml_file` | Decode binary Android XML such as `AndroidManifest.xml`; supports line paging. |
| `read_raw_file` | Read bounded text entries from an APK. |
| `query_arsc_resources` | Query `resources.arsc`; uses `aapt2`/`apkanalyzer` when available and a bounded string-pool fallback otherwise. |
| `get_apk_signature` | Identify META-INF certificates and return SHA fingerprints plus optional OpenSSL certificate details. |

### DEX, classes, and Smali

| Tool | Purpose |
| --- | --- |
| `list_loaded_classes` | Search the currently loaded/default DEX class index. |
| `decompile_class` | Return JADX output when configured or deterministic structural Smali fallback. |
| `search_bytecode` | Search classes, methods, strings, or fallback Smali with optional regular expressions. |
| `explain_smali` | Summarize opcodes, registers, branches, labels, and invokes in supplied Smali. |
| `analyze_control_flow` | Build syntactic basic-block and branch information for an indexed method. |
| `analyze_call_graph` | Extract visible invoke edges from an indexed method up to the requested depth. |

### Session state and data utilities

| Tool | Purpose |
| --- | --- |
| `manage_bookmarks` | Add, remove, or list persistent analysis bookmarks. |
| `decode_data` | Base64, hex, URL, decimal, XOR, and color conversions. |
| `manage_global_notes` | Read or write Markdown scratchpad notes in the cache. |
| `list_workspace_files` | List workspace and derived-cache files with sizes. |

### Native and Radare2

| Tool | Purpose |
| --- | --- |
| `load_binary` | Extract an APK `.so`/ELF entry into the native cache and make it current. |
| `list_binary_functions` | List Radare2 functions or ELF fallback symbols with pagination. |
| `decompile_binary_function` | Request Radare2 pseudocode, assembly, CFG, or hex with line paging. |
| `binary_search` | Search native strings, bytes, assembly, or magic values. |
| `get_binary_metadata` | Return ELF architecture, entry point, sections, and function counts. |
| `get_function_properties` | Return Radare2 function JSON or ELF symbol fallback properties. |
| `get_xrefs` | Return Radare2 cross-references, or an explicit fallback warning. |
| `rename_function` | Apply a Radare2 rename when available and persist the analysis rename. |
| `add_comment` | Add a Radare2 comment when available and persist a comment record. |
| `inspect_data` | Produce bounded address-relative hex/ASCII rows. |
| `r2_execute` | Run a caller-supplied Radare2 command against a configured workspace/cache binary. |

### Unity IL2CPP and Flutter

| Tool | Purpose |
| --- | --- |
| `analyze_il2cpp` | Locate `libil2cpp.so` and `global-metadata.dat`, inspect metadata headers, and report configured external tools. |
| `analyze_unflutter` | Detect Flutter native libraries, snapshots, and `flutter_assets` contents. |
| `clear_native_cache` | Remove derived native files and reset the current native target without deleting input APKs. |

The schemas are deliberately kept separate from the implementations so adding
an internal optimization does not silently change the MCP contract.

## Repository layout

```text
.
├── mcp-server/
│   ├── mcp_server/
│   │   ├── axml.py          # Android binary XML decoder
│   │   ├── config.py         # environment-backed Settings
│   │   ├── dex.py            # bounded DEX structural index
│   │   ├── elf.py            # ELF sections and symbols
│   │   ├── engine.py         # all 29 tool implementations
│   │   ├── resources.py      # resources.arsc/SDK integration
│   │   ├── schemas.py        # stable MCP tool declarations
│   │   ├── transport.py      # HTTP JSON-RPC/MCP transport
│   │   └── utils.py
│   ├── tests/                # protocol and engine tests
│   ├── scripts/run-server.sh
│   ├── scripts/test.sh
│   ├── scripts/curl-smoke.sh
│   ├── .env.example
│   ├── pyproject.toml
│   └── requirements-dev.txt
├── app/                      # existing Lenix Android application
├── native/                   # Lenix native sources
├── stryker/                  # existing vendored Android terminal sources
├── docs/                     # existing Lenix architecture/build decisions
├── gradlew, build.gradle.kts # existing Android build
└── .gitignore
```

## Installation

### MCP server (recommended quick start)

Requirements:

- Python 3.10 or newer.
- `zipfile`/DEX/ELF fallback analysis works with the Python standard library.
- Optional: `venv` for isolation.

```bash
git clone https://github.com/ayushghbk-afk/lenix.git
cd lenix
python3 -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
# The runtime has no mandatory dependencies. Install test tooling only if desired:
python -m pip install -r mcp-server/requirements-dev.txt
cp mcp-server/.env.example mcp-server/.env  # optional; export it with your shell
```

The server does not read `.env` automatically. Either export variables in the
shell, use a dotenv-capable process manager, or source a shell-compatible file:

```bash
set -a
. mcp-server/.env
set +a
```

### Optional Android/native analyzers

The server still starts if none of these tools are installed. Configure their
executable names or absolute paths with the variables below.

- Android SDK `aapt2` and `apkanalyzer` for precise resource output.
- `apktool` for resource/decode workflows.
- `jadx` or `baksmali` for full Java/Smali output.
- `radare2` (`r2`) and optionally `rabin2` for native analysis.
- An authorized Il2CppDumper/CPP2IL or UnFlutter installation for framework-
  specific workflows.

Do not put proprietary tools, APKs, native libraries, keystores, or their
credentials in this repository. Point the server at them through a workspace or
an environment variable on the host that runs it.

## Configuration reference

All settings are optional. Defaults are safe for a local clone.

| Variable | Default | Meaning |
| --- | --- | --- |
| `MCP_HOST` | `0.0.0.0` | Bind address. Use `127.0.0.1` for local-only service. |
| `MCP_PORT` | `8080` | TCP port. |
| `MCP_PATH` | `/mcp` | JSON-RPC endpoint path. |
| `MCP_CORS_ORIGINS` | `*` | Comma-separated allowed origins. |
| `MCP_CORS_ALLOW_CREDENTIALS` | `false` | Allow browser credentials; never combine with `*`. |
| `MCP_WORKSPACE_PATH` | `./workspace` | Input APK/ZIP and user workspace root. |
| `MCP_CACHE_PATH` | `./.cache/android-reverse-mcp` | Derived DEX/native/resource/state cache. |
| `MCP_DEFAULT_APK` | unset | APK used by tools whose public schema has no `apkPath`, such as class searches. |
| `MCP_ALLOW_EXTERNAL_PATHS` | `false` | Permit absolute paths outside workspace/cache. Keep false for a network-exposed server. |
| `ANDROID_SDK_ROOT` / `ANDROID_HOME` | unset | Android SDK root. |
| `MCP_ANDROID_BUILD_TOOLS_PATH` | unset | Optional build-tools directory. |
| `MCP_APKTOOL` | `apktool` | Apktool executable. |
| `MCP_JADX` | `jadx` | JADX executable. |
| `MCP_BAKSMALI` | `baksmali` | Baksmali executable. |
| `MCP_AAPT2` | `aapt2` | AAPT2 executable. |
| `MCP_APKANALYZER` | `apkanalyzer` | Android SDK APK analyzer. |
| `MCP_RADARE2` | `r2` | Radare2 executable. |
| `MCP_RABIN2` | `rabin2` | Radare2 symbol helper. |
| `MCP_IL2CPP_DUMPER` | unset | Optional Il2CppDumper path/configuration marker. |
| `MCP_CPP2IL` | unset | Optional CPP2IL path/configuration marker. |
| `MCP_UNFLUTTER` | unset | Optional UnFlutter path/configuration marker. |
| `MCP_COMMAND_TIMEOUT` | `60` | Timeout for optional external commands, in seconds. |
| `MCP_MAX_OUTPUT_BYTES` | `1000000` | Bound for command/tool output. |
| `MCP_MAX_INPUT_BYTES` | `10000000` | Bound for HTTP bodies and notes. |
| `MCP_MAX_ARCHIVE_ENTRY_BYTES` | `67108864` | Bound for one extracted APK entry. |
| `MCP_ENABLE_R2_EXECUTE` | `true` | Enable the explicit low-level `r2_execute` tool. |
| `MCP_LOG_LEVEL` | `INFO` | Python logging level. |

The complete template is [`mcp-server/.env.example`](mcp-server/.env.example).

## Build and run

### Run the MCP server

From the repository root:

```bash
export MCP_HOST=0.0.0.0
export MCP_PORT=8080
export MCP_PATH=/mcp
export MCP_WORKSPACE_PATH="$PWD/workspace"
export MCP_CACHE_PATH="$PWD/.cache/android-reverse-mcp"
./mcp-server/scripts/run-server.sh
```

Or install the package in editable mode and run its console script:

```bash
python -m pip install -e ./mcp-server
android-reverse-mcp --host 0.0.0.0 --port 8080 --path /mcp
```

A dependency-free container image is also included:

```bash
docker build -t android-reverse-mcp ./mcp-server
docker run --rm -p 8080:8080 \
  -v "$PWD/workspace:/data/workspace" \
  -v "$PWD/.cache/android-reverse-mcp:/data/cache" \
  android-reverse-mcp
```

A local process should answer `http://127.0.0.1:8080/` and accept MCP POSTs at
`http://127.0.0.1:8080/mcp`. Bind `MCP_HOST=0.0.0.0` when using a container,
VM, Android/Termux, or a reverse proxy; clients should still use the proxy's
public hostname rather than `localhost`.

### Test and smoke-check the MCP server

```bash
./mcp-server/scripts/test.sh
# In another terminal, after the server is running:
MCP_URL=http://127.0.0.1:8080/mcp ./mcp-server/scripts/curl-smoke.sh
```

The test suite verifies the live HTTP path for `initialize`, `tools/list`, and
`tools/call`, checks that all 29 names remain registered, and exercises APK
listing, notes, and bookmarks.

### Existing Lenix Android project

The pre-existing Android application remains buildable independently:

```bash
# JDK 17 and Android SDK API 37 are required by the existing app.
./gradlew test
./gradlew assembleDebug
# See docs/BUILDING.md and README history for engine-payload and device details.
```

A clean Android clone may need the project’s documented PRoot engine fetch step:

```bash
./scripts/fetch-engine.sh arm64-v8a
```

That payload is intentionally not committed; follow the existing Lenix docs for
release/CI builds.

## MCP client configuration

For a client that supports remote HTTP MCP servers, use the public URL of your
reverse proxy or tunnel and append the configured path:

```json
{
  "mcpServers": {
    "androidReverse": {
      "url": "https://YOUR-MCP-DOMAIN/mcp"
    }
  }
}
```

For a local client, use:

```json
{
  "mcpServers": {
    "androidReverse": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

If the client uses a legacy `command` transport instead of HTTP, launch the
server separately and select its HTTP MCP configuration; this project is an
HTTP MCP server, not a stdio-only server.

## Example curl requests

Health check:

```bash
curl -sS http://127.0.0.1:8080/ | python3 -m json.tool
curl -sS http://127.0.0.1:8080/mcp | python3 -m json.tool
```

Initialize:

```bash
curl -sS http://127.0.0.1:8080/mcp \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data-raw '{
    "jsonrpc":"2.0",
    "id":1,
    "method":"initialize",
    "params":{
      "protocolVersion":"2024-11-05",
      "capabilities":{},
      "clientInfo":{"name":"curl","version":"1.0"}
    }
  }'
```

List every schema:

```bash
curl -sS http://127.0.0.1:8080/mcp \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data-raw '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

Call a tool without an APK (a deterministic utility example):

```bash
curl -sS http://127.0.0.1:8080/mcp \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data-raw '{
    "jsonrpc":"2.0",
    "id":3,
    "method":"tools/call",
    "params":{
      "name":"decode_data",
      "arguments":{"operation":"text_to_hex","input":"hello"}
    }
  }'
```

Call an APK tool after placing `sample.apk` under the configured workspace:

```bash
curl -sS http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  --data-raw '{
    "jsonrpc":"2.0",
    "id":4,
    "method":"tools/call",
    "params":{
      "name":"list_apk_files",
      "arguments":{"apkPath":"sample.apk","extensionFilter":".so"}
    }
  }'
```

A production MCP client should retain the `MCP-Session-Id` response header from
`initialize` and send it on subsequent requests. The server also accepts calls
without a session for simple curl and compatibility clients.

## Cloudflare Tunnel / reverse proxy

The tunnel is an optional deployment layer; it is not part of the MCP server.
Start the server on a loopback listener or a private local interface, then run
Cloudflare Tunnel separately:

```bash
# terminal 1
MCP_HOST=127.0.0.1 MCP_PORT=8080 ./mcp-server/scripts/run-server.sh

# terminal 2; cloudflared prints a temporary HTTPS hostname
cloudflared tunnel --url http://127.0.0.1:8080
```

The correct remote MCP URL format is:

```text
https://YOUR-TUNNEL-HOSTNAME/mcp
```

For a named tunnel, configure the hostname and origin in Cloudflare rather than
putting that hostname in source code:

```yaml
# cloudflared config.yml, kept outside this repository if it contains account details
tunnel: YOUR-TUNNEL-NAME
 credentials-file: /secure/path/YOUR-TUNNEL-CREDENTIALS.json
 ingress:
   - hostname: YOUR-MCP-DOMAIN
     service: http://127.0.0.1:8080
   - service: http_status:404
```

Run `cloudflared tunnel run YOUR-TUNNEL-NAME`, then configure the MCP client with
`https://YOUR-MCP-DOMAIN/mcp`. Restrict `MCP_CORS_ORIGINS` to the actual browser
origin when a web client is used, and do not expose `MCP_ALLOW_EXTERNAL_PATHS`
or `r2_execute` to an untrusted public audience without an authentication and
network policy layer.

## Troubleshooting

### `404` at `/mcp`

Check `MCP_PATH`, including its leading slash, and ensure the proxy forwards
`/mcp` without stripping or duplicating the path. A trailing slash is accepted
for health GETs; JSON-RPC POSTs should use the configured path.

### `405`, empty response, or a client that only performs GET

MCP calls are JSON-RPC POST requests. `GET /` and `GET /mcp` are health checks,
not tool calls. Configure the client as an HTTP MCP server and ensure the proxy
allows POST, `Content-Type`, `Accept`, and the MCP session headers.

### CORS errors in a browser

Set `MCP_CORS_ORIGINS` to a comma-separated list of exact origins, for example
`https://client.example`, and restart the server. Do not use credentials with a
wildcard origin.

### `No APK is loaded` or path-policy errors

Place the APK in `MCP_WORKSPACE_PATH`, pass a workspace-relative `apkPath`, or
set `MCP_DEFAULT_APK` to an allowed path. `MCP_ALLOW_EXTERNAL_PATHS=true` is an
explicit trusted-local override, not a general deployment setting.

### Missing JADX, aapt2, or radare2

The server starts without optional tools. Configure the relevant executable
variables or install the tool on `PATH`. The response identifies fallback
outputs and warnings so a client does not mistake a structural fallback for a
full decompilation.

### `r2_execute` is unavailable

Set `MCP_ENABLE_R2_EXECUTE=true`, configure `MCP_RADARE2`, load a binary first,
and pass a path inside the allowed workspace/cache roots. Keep this tool behind
a trusted network boundary.

### Large or corrupt archives

The server enforces `MCP_MAX_ARCHIVE_ENTRY_BYTES`, request/output limits, and
ZIP path normalization. Increase bounds only for a trusted local workload.
Corrupt or encrypted APKs need to be repaired or supplied to an external
analyzer that supports their format.

### Cloudflare Tunnel connects but MCP fails

Verify that the origin is reachable locally, the tunnel service points to the
same host/port, the public URL ends in `/mcp`, and the proxy does not rewrite
JSON or reject POST bodies. Test the public URL with the same initialize curl
request used locally.

## Security and repository hygiene

- No API keys, passwords, tunnel credentials, private certificates, or personal
  tokens are required by the source tree.
- `.gitignore` excludes MCP workspaces/caches, APK/AAB/DEX/native build outputs,
  Gradle state, keystores, and runtime environment files.
- Input paths are restricted to configured workspace/cache roots by default.
- Archive members, external command timeouts, request bodies, and tool output are
  bounded.
- The project does not commit temporary APKs or generated MCP analysis caches.
- Review `git diff --check` and run a secret scan before publishing.

## License and existing project notices

The MCP server additions are distributed under the repository’s existing
[`LICENSE`](LICENSE). Existing Lenix/Stryker components retain their upstream
notices; see [`stryker/THIRD-PARTY-NOTICES.md`](stryker/THIRD-PARTY-NOTICES.md)
and the files under `docs/` before redistributing those components.
