"""Small DEX indexer used when jadx/baksmali is not installed.

It reads the public DEX tables and class-data records. This is not intended to
replace a full decompiler; it gives MCP clients a useful structural index and
stable fallback output on a clean machine.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterator

from .utils import ToolError


def uleb(data: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    for _ in range(5):
        if offset >= len(data):
            raise ToolError("Truncated DEX ULEB128 value")
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7
    raise ToolError("Invalid DEX ULEB128 value")


def _u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _descriptor_to_java(descriptor: str) -> str:
    mapping = {"V": "void", "Z": "boolean", "B": "byte", "S": "short", "C": "char", "I": "int", "J": "long", "F": "float", "D": "double"}
    if descriptor.startswith("["):
        return _descriptor_to_java(descriptor[1:]) + "[]"
    if descriptor.startswith("L") and descriptor.endswith(";"):
        return descriptor[1:-1].replace("/", ".")
    return mapping.get(descriptor, descriptor)


@dataclass
class DexMethod:
    index: int
    name: str
    descriptor: str
    access_flags: int
    code_off: int = 0
    class_descriptor: str = ""
    registers_size: int | None = None
    ins_size: int | None = None
    outs_size: int | None = None
    insns: tuple[int, ...] = ()

    @property
    def short_signature(self) -> str:
        return f"{self.name}{self.descriptor}"

    def smali_header(self) -> str:
        flags = []
        flag_names = ((0x1, "public"), (0x2, "private"), (0x4, "protected"), (0x8, "static"), (0x10, "final"), (0x100, "native"), (0x400, "abstract"), (0x10000, "constructor"))
        for bit, name in flag_names:
            if self.access_flags & bit:
                flags.append(name)
        modifier = (" " + " ".join(flags)) if flags else ""
        return f".method{modifier} {self.name}{self.descriptor}"


@dataclass
class DexClass:
    descriptor: str
    access_flags: int
    superclass: str | None
    source_file: str | None
    methods: list[DexMethod] = field(default_factory=list)

    @property
    def java_name(self) -> str:
        return _descriptor_to_java(self.descriptor)


class DexFile:
    def __init__(self, data: bytes, source: str = "classes.dex") -> None:
        if len(data) < 112 or data[:3] != b"dex" or data[3:4] not in {b"\n", b"\r"}:
            raise ToolError(f"Not a supported DEX file: {source}")
        self.data = data
        self.source = source
        self.version = data[4:7].decode("ascii", errors="replace")
        self.strings = self._strings()
        self.types = [self.strings[self._u32_at(68 + i * 4)] for i in range(self._u32_at(64))]
        self.protos = self._protos()
        self.methods = self._method_ids()
        self.classes = self._classes()

    def _u32_at(self, offset: int) -> int:
        if offset + 4 > len(self.data):
            raise ToolError("Truncated DEX header")
        return _u32(self.data, offset)

    def _strings(self) -> list[str]:
        count = self._u32_at(56)
        offset = self._u32_at(60)
        values = []
        for index in range(count):
            string_off = _u32(self.data, offset + index * 4)
            _, cursor = uleb(self.data, string_off)
            end = self.data.find(b"\0", cursor)
            if end < 0:
                end = len(self.data)
            values.append(self.data[cursor:end].decode("utf-8", errors="replace"))
        return values

    def _type_at(self, index: int) -> str:
        return self.types[index] if 0 <= index < len(self.types) else f"?type{index}"

    def _protos(self) -> list[str]:
        count = self._u32_at(72)
        offset = self._u32_at(76)
        result = []
        for index in range(count):
            item = offset + index * 12
            return_type = self._type_at(_u32(self.data, item + 4))
            parameters_off = _u32(self.data, item + 8)
            args = []
            if parameters_off:
                amount = _u32(self.data, parameters_off)
                for argument in range(amount):
                    args.append(self._type_at(_u16(self.data, parameters_off + 4 + argument * 2)))
            result.append(f"({''.join(args)}){return_type}")
        return result

    def _method_ids(self) -> list[tuple[str, str, str]]:
        count = self._u32_at(88)
        offset = self._u32_at(92)
        result = []
        for index in range(count):
            item = offset + index * 8
            class_idx = _u16(self.data, item)
            proto_idx = _u16(self.data, item + 2)
            name_idx = _u32(self.data, item + 4)
            result.append((self._type_at(class_idx), self.protos[proto_idx], self.strings[name_idx]))
        return result

    def _classes(self) -> list[DexClass]:
        count = self._u32_at(96)
        offset = self._u32_at(100)
        result: list[DexClass] = []
        for index in range(count):
            item = offset + index * 32
            class_idx = _u32(self.data, item)
            access_flags = _u32(self.data, item + 4)
            superclass_idx = _u32(self.data, item + 8)
            source_idx = _u32(self.data, item + 16)
            class_data_off = _u32(self.data, item + 24)
            klass = DexClass(
                descriptor=self._type_at(class_idx),
                access_flags=access_flags,
                superclass=None if superclass_idx == 0xFFFFFFFF else self._type_at(superclass_idx),
                source_file=None if source_idx == 0xFFFFFFFF else self.strings[source_idx],
            )
            if class_data_off:
                self._class_data(klass, class_data_off)
            result.append(klass)
        return result

    def _class_data(self, klass: DexClass, offset: int) -> None:
        static_count, offset = uleb(self.data, offset)
        instance_count, offset = uleb(self.data, offset)
        direct_count, offset = uleb(self.data, offset)
        virtual_count, offset = uleb(self.data, offset)
        # Skip encoded fields (field_idx_diff, access_flags).
        for _ in range(static_count + instance_count):
            _, offset = uleb(self.data, offset)
            _, offset = uleb(self.data, offset)
        method_index = 0
        for amount in (direct_count, virtual_count):
            method_index = 0
            for _ in range(amount):
                diff, offset = uleb(self.data, offset)
                flags, offset = uleb(self.data, offset)
                code_off, offset = uleb(self.data, offset)
                method_index += diff
                if method_index >= len(self.methods):
                    continue
                _, descriptor, name = self.methods[method_index]
                method = DexMethod(method_index, name, descriptor, flags, code_off, klass.descriptor)
                if code_off:
                    self._code_item(method)
                klass.methods.append(method)

    def _code_item(self, method: DexMethod) -> None:
        try:
            offset = method.code_off
            method.registers_size = _u16(self.data, offset)
            method.ins_size = _u16(self.data, offset + 2)
            method.outs_size = _u16(self.data, offset + 4)
            size = _u32(self.data, offset + 12)
            method.insns = tuple(_u16(self.data, offset + 16 + i * 2) for i in range(min(size, 100_000)))
        except (struct.error, IndexError):
            method.insns = ()

    def class_names(self) -> list[str]:
        return [item.descriptor for item in self.classes]

    def find_class(self, descriptor: str) -> DexClass | None:
        cleaned = descriptor.strip()
        if not cleaned.startswith("L"):
            cleaned = "L" + cleaned.replace(".", "/").strip("/") + ";"
        if not cleaned.endswith(";"):
            cleaned += ";"
        return next((item for item in self.classes if item.descriptor == cleaned), None)

    def methods_for(self, descriptor: str) -> list[DexMethod]:
        klass = self.find_class(descriptor)
        return klass.methods if klass else []

    def smali_for(self, descriptor: str) -> str:
        klass = self.find_class(descriptor)
        if klass is None:
            raise ToolError(f"Class not found in indexed DEX: {descriptor}")
        lines = [f".class 0x{klass.access_flags:x} {klass.descriptor}"]
        if klass.superclass:
            lines.append(f".super {klass.superclass}")
        if klass.source_file:
            lines.append(f'.source "{klass.source_file}"')
        for method in klass.methods:
            lines.append("")
            lines.append(method.smali_header())
            if method.code_off == 0:
                lines.append("    .code_off 0x0")
            else:
                lines.append(f"    # code_item 0x{method.code_off:x}; registers={method.registers_size}")
                # Keep the raw code units available for analysis without pretending to
                # have decoded every opcode.
                if method.insns:
                    units = " ".join(f"{unit:04x}" for unit in method.insns[:512])
                    lines.append(f"    # insns {units}")
            lines.append(".end method")
        return "\n".join(lines) + "\n"


def iter_dexes(data: bytes, source: str) -> Iterator[DexFile]:
    yield DexFile(data, source)
