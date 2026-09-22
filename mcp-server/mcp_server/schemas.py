"""Stable MCP tool declarations.

These declarations intentionally mirror the public androidReverse Cognitive
Core contract. Keep names and property spellings stable: MCP clients cache
these schemas and use them to generate calls.
"""

from __future__ import annotations

from copy import deepcopy
from typing import Any


def _schema(
    properties: dict[str, Any], required: list[str] | None = None
) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": properties,
        "required": required or [],
    }


TOOL_DEFINITIONS: list[dict[str, Any]] = [
    {
        "name": "list_apk_files",
        "description": "Maps the internal directory structure of the APK archive.",
        "inputSchema": _schema(
            {
                "apkPath": {"type": "string"},
                "pathFilter": {
                    "type": "string",
                    "description": "Optional directory prefix (e.g., 'lib/arm64-v8a/').",
                },
                "extensionFilter": {
                    "type": "string",
                    "description": "Optional extension to filter by (e.g., '.so' or '.dex').",
                },
                "maxResults": {
                    "type": "integer",
                    "description": "Max files to return. Default 200, max 1000.",
                },
            },
            ["apkPath"],
        ),
    },
    {
        "name": "read_axml_file",
        "description": "Decodes binary Android XML files (like AndroidManifest.xml) into readable text.",
        "inputSchema": _schema(
            {
                "apkPath": {"type": "string"},
                "filePath": {"type": "string"},
                "startLine": {
                    "type": "integer",
                    "description": "Line to start reading from. Use for large files.",
                },
                "endLine": {"type": "integer", "description": "Line to stop reading."},
            },
            ["apkPath", "filePath"],
        ),
    },
    {
        "name": "read_raw_file",
        "description": "Extracts raw text data from standard files within the APK.",
        "inputSchema": _schema(
            {
                "apkPath": {"type": "string"},
                "filePath": {"type": "string"},
                "startLine": {"type": "integer", "description": "Line to start reading from."},
                "endLine": {"type": "integer", "description": "Line to stop reading."},
            },
            ["apkPath", "filePath"],
        ),
    },
    {
        "name": "query_arsc_resources",
        "description": "Searches the resources.arsc file using a memory-cached tree to find strings, colors, and layout mappings.",
        "inputSchema": _schema(
            {
                "apkPath": {"type": "string"},
                "resourceType": {
                    "type": "string",
                    "description": "Optional. Filter by type (e.g., 'string', 'color').",
                },
                "query": {
                    "type": "string",
                    "description": "Optional. Specific resource name or value to search for.",
                },
                "limit": {"type": "integer", "description": "Max results to return. Default 150."},
            },
            ["apkPath"],
        ),
    },
    {
        "name": "get_apk_signature",
        "description": "Extracts X.509 Certificates and SHA fingerprints from the APK's META-INF folder.",
        "inputSchema": _schema({"apkPath": {"type": "string"}}, ["apkPath"]),
    },
    {
        "name": "list_loaded_classes",
        "description": "Searches the structural tree for loaded Dalvik/Smali classes.",
        "inputSchema": _schema(
            {
                "filterQuery": {
                    "type": "string",
                    "description": "Text to filter class paths by (case-insensitive; dots or slashes accepted). Empty returns all.",
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results to return. Default 100, max 2000.",
                },
            },
            ["filterQuery"],
        ),
    },
    {
        "name": "decompile_class",
        "description": "Decompiles a structural Smali class into high-level Java or paginated Smali.",
        "inputSchema": _schema(
            {
                "classDescriptor": {
                    "type": "string",
                    "description": "e.g., 'Ljava/lang/String;' or 'java.lang.String' (both accepted).",
                },
                "engine": {
                    "type": "string",
                    "description": "One of (case-insensitive): Jadx, Vineflower, CFR, Procyon, JD-Core, Jadx_Fallback, Krakatau, Jadx_IR, Smali. Default is Smali.",
                },
                "startLine": {"type": "integer"},
                "endLine": {"type": "integer"},
                "methodSignature": {"type": "string", "description": "Optional. Extract only a specific method."},
            },
            ["classDescriptor"],
        ),
    },
    {
        "name": "search_bytecode",
        "description": "Executes a deep scan across the decompiled Dalvik execution blocks.",
        "inputSchema": _schema(
            {
                "query": {"type": "string"},
                "searchType": {
                    "type": "string",
                    "description": "One of (case-insensitive): SMALI, CLASS_NAME, METHOD_NAME, FIELD_NAME, STRING. Default SMALI.",
                },
                "isRegex": {"type": "boolean"},
                "maxResults": {"type": "integer", "description": "Hard cap on search results to save tokens. Default 50."},
            },
            ["query"],
        ),
    },
    {
        "name": "explain_smali",
        "description": "Explains a block of Smali instructions in plain language.",
        "inputSchema": _schema({"smaliCode": {"type": "string"}}, ["smaliCode"]),
    },
    {
        "name": "analyze_control_flow",
        "description": "Analyzes the basic blocks and branches of a Dalvik method.",
        "inputSchema": _schema(
            {"classDescriptor": {"type": "string"}, "methodSignature": {"type": "string"}},
            ["classDescriptor", "methodSignature"],
        ),
    },
    {
        "name": "analyze_call_graph",
        "description": "Extracts the execution call hierarchy tracing incoming or outgoing calls.",
        "inputSchema": _schema(
            {
                "classDescriptor": {"type": "string"},
                "methodSignature": {"type": "string"},
                "depth": {"type": "integer", "description": "Analysis depth level (1-3)."},
            },
            ["classDescriptor", "methodSignature"],
        ),
    },
    {
        "name": "manage_bookmarks",
        "description": "Handles app session bookmark flags.",
        "inputSchema": _schema(
            {
                "action": {"type": "string", "description": "One of (case-insensitive): add, remove, list."},
                "descriptor": {"type": "string"},
            },
            ["action"],
        ),
    },
    {
        "name": "decode_data",
        "description": "Utility engine for decoding data, hex, ciphers, colors, or decimals.",
        "inputSchema": _schema(
            {
                "operation": {
                    "type": "string",
                    "description": "One of (case-insensitive): base64_decode, base64_encode, hex_to_text, text_to_hex, url_decode, url_encode, decimal_to_hex, hex_to_decimal, xor_cipher, color_preview.",
                },
                "input": {"type": "string"},
                "key": {"type": "string"},
            },
            ["operation", "input"],
        ),
    },
    {
        "name": "manage_global_notes",
        "description": "Stores text or logs into global workspace scratchpad files.",
        "inputSchema": _schema(
            {
                "action": {"type": "string", "description": "One of (case-insensitive): write, read."},
                "title": {"type": "string"},
                "content": {"type": "string"},
            },
            ["action"],
        ),
    },
    {
        "name": "list_workspace_files",
        "description": "Lists working directory assets inside native cache layers.",
        "inputSchema": _schema({}, []),
    },
    {
        "name": "load_binary",
        "description": "Initializes Radare2 workspace targets for an extracted shared library (.so file).",
        "inputSchema": _schema(
            {
                "apkPath": {"type": "string"},
                "entryPath": {
                    "type": "string",
                    "description": "Relative internal archive path (e.g. 'lib/arm64-v8a/libnative.so')",
                },
            },
            ["apkPath", "entryPath"],
        ),
    },
    {
        "name": "list_binary_functions",
        "description": "Queries identified native functions and entrypoints with robust string filters and paginated parsing boundaries.",
        "inputSchema": _schema(
            {
                "query": {"type": "string", "description": "Sub-string filter matching function name patterns (e.g. 'Java_' or 'crypto')."},
                "skip": {"type": "integer", "description": "The row count offset index to skip forward for pagination. Default 0."},
                "limit": {"type": "integer", "description": "Maximum number of native symbols to return. Default 100, max 1000."},
            },
            [],
        ),
    },
    {
        "name": "decompile_binary_function",
        "description": "Extracts C pseudocode or assembly listings for a specific native address block, supported by line bounds.",
        "inputSchema": _schema(
            {
                "address": {"type": "string", "description": "Hex address location or flag name (e.g. '0x0041a0b0' or 'sym.main')."},
                "viewMode": {"type": "string", "description": "One of (case-insensitive): 'pseudocode', 'assembly', 'hex', or 'cfg'."},
                "startLine": {"type": "integer", "description": "For large functions, line to start reading."},
                "endLine": {"type": "integer"},
            },
            ["address", "viewMode"],
        ),
    },
    {
        "name": "binary_search",
        "description": "Searches for raw data parameters or strings inside loaded native execution sections.",
        "inputSchema": _schema(
            {
                "query": {"type": "string"},
                "mode": {"type": "string", "description": "Search target (case-insensitive): 'string', 'hex', 'assembly', or 'magic'."},
                "isRegex": {"type": "boolean"},
                "limit": {"type": "integer", "description": "Cap on maximum returned search locations. Default 50."},
            },
            ["query", "mode"],
        ),
    },
    {
        "name": "get_binary_metadata",
        "description": "Extracts headers, architecture, entry points, and section layout data from the current ELF/native context.",
        "inputSchema": _schema({}, []),
    },
    {
        "name": "get_function_properties",
        "description": "Extracts specialized analysis parameters (size, stack frame, variables) for a specific native symbol.",
        "inputSchema": _schema({"address": {"type": "string"}}, ["address"]),
    },
    {
        "name": "get_xrefs",
        "description": "Extracts data/code cross-references (XREFs) for specified address segments, bound by volume limits.",
        "inputSchema": _schema(
            {
                "address": {"type": "string"},
                "limit": {"type": "integer", "description": "Maximum references to trace before capping output. Default 50."},
            },
            ["address"],
        ),
    },
    {
        "name": "rename_function",
        "description": "Applies a descriptive rename flag to an analyzed address location inside the working session.",
        "inputSchema": _schema({"address": {"type": "string"}, "newName": {"type": "string"}}, ["address", "newName"]),
    },
    {
        "name": "add_comment",
        "description": "Appends an inline comment log directly to a targeted instruction offset inside native files.",
        "inputSchema": _schema({"address": {"type": "string"}, "comment": {"type": "string"}}, ["address", "comment"]),
    },
    {
        "name": "inspect_data",
        "description": "Reads standard hex-dump views from custom targeted memory locations.",
        "inputSchema": _schema({"address": {"type": "string"}}, ["address"]),
    },
    {
        "name": "r2_execute",
        "description": "Executes low-level custom scripting parameters against backend Radare2 core pipelines.",
        "inputSchema": _schema({"binaryPath": {"type": "string"}, "command": {"type": "string"}}, ["binaryPath", "command"]),
    },
    {
        "name": "analyze_il2cpp",
        "description": "Triggers Unity metadata inspections mapping metadata strings back to structural assemblies.",
        "inputSchema": _schema({"apkPath": {"type": "string"}}, ["apkPath"]),
    },
    {
        "name": "analyze_unflutter",
        "description": "Extracts Dart application layout properties out of isolated execution segments.",
        "inputSchema": _schema({"apkPath": {"type": "string"}}, ["apkPath"]),
    },
    {
        "name": "clear_native_cache",
        "description": "Wipes global memory registries, dynamic structures, and ARSC parsing matrices to reset workspace parameters.",
        "inputSchema": _schema({}, []),
    },
]

TOOL_NAMES = tuple(tool["name"] for tool in TOOL_DEFINITIONS)


def tool_definitions() -> list[dict[str, Any]]:
    """Return a defensive copy so callers cannot mutate the public contract."""
    return deepcopy(TOOL_DEFINITIONS)
