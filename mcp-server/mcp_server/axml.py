"""Minimal, dependency-free Android binary XML decoder.

It implements the chunk types needed for manifests and common resource XML.
Unknown chunks are preserved as comments rather than making the whole file
unreadable. The parser is intentionally bounded by the caller's ZIP entry
limit.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Any

from .utils import ToolError

NO_INDEX = 0xFFFFFFFF


def _u16(data: bytes, offset: int) -> int:
    if offset + 2 > len(data):
        raise ToolError("Truncated Android XML (u16)")
    return struct.unpack_from("<H", data, offset)[0]


def _u32(data: bytes, offset: int) -> int:
    if offset + 4 > len(data):
        raise ToolError("Truncated Android XML (u32)")
    return struct.unpack_from("<I", data, offset)[0]


@dataclass
class StringPool:
    values: list[str]

    def get(self, index: int) -> str:
        if index == NO_INDEX:
            return ""
        if 0 <= index < len(self.values):
            return self.values[index]
        return f"@string[{index}]"


def _read_utf8_string(data: bytes, offset: int, end: int) -> str:
    if offset >= end:
        return ""
    first = data[offset]
    offset += 1
    if first & 0x80:
        if offset >= end:
            return ""
        second = data[offset]
        offset += 1
        length = ((first & 0x7F) << 7) | (second & 0x7F)
    else:
        length = first
    if offset >= end:
        return ""
    second = data[offset]
    offset += 1
    if second & 0x80:
        if offset >= end:
            return ""
        third = data[offset]
        offset += 1
        length = ((second & 0x7F) << 7) | (third & 0x7F)
    else:
        length = second
    raw = data[offset : offset + length]
    return raw.decode("utf-8", errors="replace")


def _read_utf16_string(data: bytes, offset: int, end: int) -> str:
    if offset + 2 > end:
        return ""
    first = _u16(data, offset)
    offset += 2
    if first & 0x8000:
        if offset + 2 > end:
            return ""
        second = _u16(data, offset)
        offset += 2
        length = ((first & 0x7FFF) << 16) | second
    else:
        length = first
    raw = data[offset : min(end, offset + length * 2)]
    return raw.decode("utf-16-le", errors="replace")


def parse_string_pool(data: bytes, offset: int) -> tuple[StringPool, int]:
    chunk_type = _u16(data, offset)
    header_size = _u16(data, offset + 2)
    chunk_size = _u32(data, offset + 4)
    if chunk_type != 0x0001 or header_size < 28:
        raise ToolError("Android XML does not begin with a string pool")
    count = _u32(data, offset + 8)
    style_count = _u32(data, offset + 12)
    flags = _u32(data, offset + 16)
    strings_start = _u32(data, offset + 20)
    # styles_start is not needed for string decoding.
    _ = _u32(data, offset + 24)
    offsets_start = offset + header_size
    utf8 = bool(flags & 0x100)
    values: list[str] = []
    chunk_end = min(len(data), offset + chunk_size)
    for index in range(count):
        string_offset = _u32(data, offsets_start + index * 4)
        absolute = offset + strings_start + string_offset
        if utf8:
            values.append(_read_utf8_string(data, absolute, chunk_end))
        else:
            values.append(_read_utf16_string(data, absolute, chunk_end))
    # style_count is included to make malformed header diagnostics easier to inspect.
    _ = style_count
    return StringPool(values), offset + chunk_size


def _typed_value(data_type: int, value: int, strings: StringPool) -> str:
    if data_type == 0x03:  # TYPE_STRING
        return strings.get(value)
    if data_type == 0x01:  # TYPE_REFERENCE
        return f"@0x{value:08x}"
    if data_type == 0x02:  # TYPE_ATTRIBUTE
        return f"?0x{value:08x}"
    if data_type == 0x10:
        return str(value)
    if data_type == 0x11:
        return f"0x{value:x}"
    if data_type == 0x12:
        return "true" if value else "false"
    if data_type == 0x1C:
        return f"#{value:08x}"
    if data_type == 0x1D:
        return f"@0x{value:08x}"
    if data_type == 0x1E:
        return f"@null"
    # Dimension and fraction values are complex encoded fixed point values. Keeping the
    # raw value is more useful than silently inventing a unit.
    return f"0x{value:08x} (type=0x{data_type:02x})"


def _xml_escape(value: str, attribute: bool = False) -> str:
    value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if attribute:
        value = value.replace('"', "&quot;")
    return value


def decode_axml(data: bytes) -> str:
    if len(data) < 8:
        raise ToolError("File is too small to be Android binary XML")
    xml_header = _u16(data, 0)
    if xml_header == 0x0003:
        strings, offset = parse_string_pool(data, 8)
    else:
        strings, offset = parse_string_pool(data, 0)
    namespaces: dict[int, str] = {}
    prefix_for_uri: dict[str, str] = {}
    lines: list[str] = []
    stack: list[str] = []
    pending_namespaces: list[tuple[str, str]] = []

    while offset + 8 <= len(data):
        chunk_type = _u16(data, offset)
        header_size = _u16(data, offset + 2)
        chunk_size = _u32(data, offset + 4)
        if chunk_size < header_size or offset + chunk_size > len(data):
            raise ToolError("Truncated or invalid Android XML chunk")
        if chunk_type == 0x0180:  # resource map
            offset += chunk_size
            continue
        if chunk_type in {0x0100, 0x0101}:  # namespace start/end
            if header_size >= 24:
                prefix_idx = _u32(data, offset + 16)
                uri_idx = _u32(data, offset + 20)
                prefix = strings.get(prefix_idx)
                uri = strings.get(uri_idx)
                if chunk_type == 0x0100:
                    namespaces[uri_idx] = prefix
                    prefix_for_uri[uri] = prefix
                    pending_namespaces.append((prefix, uri))
                else:
                    # The mapping remains useful for element attributes after the end
                    # marker in malformed files; no removal is intentional.
                    _ = prefix, uri
            offset += chunk_size
            continue
        if chunk_type == 0x0102:  # start element
            if header_size < 36:
                raise ToolError("Invalid Android start-element chunk")
            name_idx = _u32(data, offset + 20)
            attr_start = _u16(data, offset + 24)
            attr_size = _u16(data, offset + 26)
            attr_count = _u16(data, offset + 28)
            name = strings.get(name_idx)
            ns_idx = _u32(data, offset + 16)
            namespace = strings.get(ns_idx) if ns_idx != NO_INDEX else ""
            qualified = name
            if namespace and namespace in prefix_for_uri:
                qualified = f"{prefix_for_uri[namespace]}:{name}"
            indent = "  " * len(stack)
            attrs: list[str] = []
            attrs_offset = offset + attr_start
            for index in range(attr_count):
                item = attrs_offset + index * attr_size
                if item + 20 > offset + chunk_size:
                    break
                attr_ns_idx = _u32(data, item)
                attr_name_idx = _u32(data, item + 4)
                raw_idx = _u32(data, item + 8)
                value_size = _u16(data, item + 12)
                data_type = data[item + 15]
                value_data = _u32(data, item + 16)
                attr_name = strings.get(attr_name_idx)
                attr_ns = strings.get(attr_ns_idx) if attr_ns_idx != NO_INDEX else ""
                if attr_ns and attr_ns in prefix_for_uri:
                    attr_name = f"{prefix_for_uri[attr_ns]}:{attr_name}"
                value = strings.get(raw_idx) if raw_idx != NO_INDEX else _typed_value(data_type, value_data, strings)
                attrs.append(f' {attr_name}="{_xml_escape(value, attribute=True)}"')
                _ = value_size
            for prefix, uri in pending_namespaces:
                attrs.append(f' xmlns:{prefix}="{_xml_escape(uri, attribute=True)}"')
            pending_namespaces.clear()
            if attrs:
                lines.append(f"{indent}<{qualified}{''.join(attrs)}>")
            else:
                lines.append(f"{indent}<{qualified}>")
            stack.append(qualified)
        elif chunk_type == 0x0103:  # end element
            name_idx = _u32(data, offset + 20) if header_size >= 24 else NO_INDEX
            name = strings.get(name_idx)
            if stack:
                qualified = stack.pop()
            else:
                qualified = name
            lines.append(f"{'  ' * len(stack)}</{qualified}>")
        elif chunk_type == 0x0104:  # CDATA
            if header_size >= 28:
                text_idx = _u32(data, offset + 16)
                lines.append(f"{'  ' * len(stack)}{_xml_escape(strings.get(text_idx))}")
        else:
            lines.append(f"{'  ' * len(stack)}<!-- unknown chunk 0x{chunk_type:04x} -->")
        offset += chunk_size

    return "\n".join(lines) + ("\n" if lines else "")


def looks_like_axml(data: bytes) -> bool:
    return len(data) >= 8 and _u16(data, 0) == 0x0003 and _u16(data, 2) >= 8
