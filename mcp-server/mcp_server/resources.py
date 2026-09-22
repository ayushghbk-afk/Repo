"""Best-effort resources.arsc inspection with optional Android SDK tools."""

from __future__ import annotations

import re
import struct
import zipfile
from pathlib import Path
from typing import Any

from .axml import StringPool, parse_string_pool
from .config import Settings
from .utils import ToolError, clamp_int, run_command


def _u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _read_utf16(data: bytes, offset: int, end: int) -> str:
    if offset + 2 > end:
        return ""
    length = _u16(data, offset)
    offset += 2
    if length & 0x8000:
        if offset + 2 > end:
            return ""
        length = ((length & 0x7FFF) << 16) | _u16(data, offset)
        offset += 2
    return data[offset : min(end, offset + length * 2)].decode("utf-16-le", errors="replace")


def _read_utf8(data: bytes, offset: int, end: int) -> str:
    if offset >= end:
        return ""
    first = data[offset]
    offset += 1
    if first & 0x80:
        if offset >= end:
            return ""
        length = ((first & 0x7F) << 7) | (data[offset] & 0x7F)
        offset += 1
    else:
        length = first
    if offset >= end:
        return ""
    second = data[offset]
    offset += 1
    if second & 0x80:
        if offset >= end:
            return ""
        length = ((second & 0x7F) << 7) | (data[offset] & 0x7F)
        offset += 1
    else:
        length = second
    return data[offset : min(end, offset + length)].decode("utf-8", errors="replace")


def _scan_string_pool(data: bytes, offset: int) -> list[str]:
    """Parse a resources.arsc string-pool chunk without depending on AXML."""
    try:
        if offset + 28 > len(data) or _u16(data, offset) != 1:
            return []
        header_size = _u16(data, offset + 2)
        chunk_size = _u32(data, offset + 4)
        count = _u32(data, offset + 8)
        flags = _u32(data, offset + 16)
        strings_start = _u32(data, offset + 20)
        if header_size < 28 or chunk_size < header_size or count > 2_000_000:
            return []
        end = min(len(data), offset + chunk_size)
        values: list[str] = []
        for index in range(count):
            pointer = offset + header_size + index * 4
            if pointer + 4 > end:
                return []
            relative = _u32(data, pointer)
            absolute = offset + strings_start + relative
            if absolute >= end:
                return []
            values.append(_read_utf8(data, absolute, end) if flags & 0x100 else _read_utf16(data, absolute, end))
        return values
    except (struct.error, ValueError):
        return []


def _external_dump(settings: Settings, apk: Path) -> str | None:
    tool = settings.aapt2_path
    result = run_command(
        [tool, "dump", "resources", str(apk)],
        timeout=settings.command_timeout_seconds,
        max_output=settings.max_output_bytes,
    )
    if result.get("available") and result.get("returncode") == 0 and result.get("stdout"):
        return str(result["stdout"])
    tool = settings.apkanalyzer_path
    result = run_command(
        [tool, "resources", "packages", str(apk)],
        timeout=settings.command_timeout_seconds,
        max_output=settings.max_output_bytes,
    )
    if result.get("available") and result.get("returncode") == 0:
        return str(result.get("stdout", ""))
    return None


def _parse_aapt_lines(text: str, resource_type: str | None, query: str | None, limit: int) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    current_type = None
    current_name = None
    for line in text.splitlines():
        type_match = re.search(r"\btype\s+\d+[^\n]*?\s([A-Za-z0-9_.-]+):", line)
        if type_match:
            current_type = type_match.group(1)
        resource_match = re.search(r"\b(?:spec resource|resource)\s+(0x[0-9a-fA-F]+)\s+([^\s]+)", line)
        if resource_match:
            current_name = resource_match.group(2)
            if ":" in current_name:
                current_type = current_name.split(":", 2)[1]
            result = {
                "id": resource_match.group(1),
                "name": current_name,
                "resourceType": current_type,
                "raw": line.strip(),
            }
            if resource_type and str(current_type).lower() != resource_type.lower():
                continue
            if query and query.lower() not in jsonish(result).lower():
                continue
            results.append(result)
        elif current_name and line.strip().startswith("("):
            results[-1]["value"] = line.strip() if results else line.strip()
        if len(results) >= limit:
            break
    return results[:limit]


def jsonish(value: Any) -> str:
    return " ".join(str(item) for item in value.values()) if isinstance(value, dict) else str(value)


def query_resources(
    settings: Settings,
    apk: Path,
    resource_type: str | None,
    query: str | None,
    limit_value: Any,
) -> dict[str, Any]:
    limit = clamp_int(limit_value, 150, 1, 2_000)
    dump = _external_dump(settings, apk)
    if dump:
        results = _parse_aapt_lines(dump, resource_type, query, limit)
        return {"apkPath": str(apk), "source": "android-sdk", "count": len(results), "resources": results}

    try:
        with zipfile.ZipFile(apk) as archive:
            data = archive.read("resources.arsc")
    except KeyError as exc:
        raise ToolError("APK does not contain resources.arsc") from exc
    if len(data) > settings.max_archive_entry_bytes:
        raise ToolError("resources.arsc exceeds MCP_MAX_ARCHIVE_ENTRY_BYTES")
    strings: list[str] = []
    seen: set[tuple[str, ...]] = set()
    offset = 0
    while offset + 28 <= len(data):
        if _u16(data, offset) == 1:
            values = _scan_string_pool(data, offset)
            key = tuple(values[:100])
            if values and key not in seen:
                seen.add(key)
                strings.extend(values)
            try:
                size = _u32(data, offset + 4)
            except struct.error:
                size = 4
            offset += max(4, size)
        else:
            offset += 4
    results = []
    for index, value in enumerate(strings):
        if not value or (query and query.lower() not in value.lower()):
            continue
        # The fallback does not know the package/type mapping; make that limitation explicit.
        result = {"index": index, "name": value, "value": value, "resourceType": "unknown"}
        if resource_type and resource_type.lower() not in {"unknown", "string"}:
            continue
        results.append(result)
        if len(results) >= limit:
            break
    return {
        "apkPath": str(apk),
        "source": "resources.arsc string-pool fallback",
        "count": len(results),
        "resources": results,
        "warning": "Install aapt2 or apkanalyzer for package/type/resource-id resolution.",
    }
