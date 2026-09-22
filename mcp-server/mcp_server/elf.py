"""Dependency-free ELF headers, sections, and symbol table inspection."""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Any

from .utils import ToolError, parse_address


@dataclass
class ElfSection:
    index: int
    name: str
    section_type: int
    address: int
    offset: int
    size: int
    flags: int
    link: int
    info: int
    entry_size: int

    def contains_address(self, address: int) -> bool:
        return self.address <= address < self.address + self.size


@dataclass
class ElfSymbol:
    index: int
    name: str
    address: int
    size: int
    info: int
    section_index: int
    table: str

    @property
    def symbol_type(self) -> int:
        return self.info & 0x0F

    @property
    def binding(self) -> int:
        return self.info >> 4

    @property
    def is_function(self) -> bool:
        return self.symbol_type in {2, 10}

    def as_dict(self) -> dict[str, Any]:
        return {
            "name": self.name or f"sub_{self.address:x}",
            "address": f"0x{self.address:x}",
            "offset": self.address,
            "size": self.size,
            "type": self.symbol_type,
            "binding": self.binding,
            "sectionIndex": self.section_index,
            "table": self.table,
        }


class ElfFile:
    def __init__(self, data: bytes, source: str = "binary") -> None:
        if len(data) < 64 or data[:4] != b"\x7fELF":
            raise ToolError(f"Not an ELF binary: {source}")
        self.data = data
        self.source = source
        self.is_64 = data[4] == 2
        self.little_endian = data[5] == 1
        if not self.little_endian:
            raise ToolError("Big-endian ELF files are not supported by the fallback parser")
        self.sections = self._sections()
        self.symbols = self._symbols()

    def _header(self) -> dict[str, int]:
        if self.is_64:
            values = struct.unpack_from("<HHIQQQIHHHHHH", self.data, 16)
            keys = ("type", "machine", "version", "entry", "phoff", "shoff", "flags", "ehsize", "phentsize", "phnum", "shentsize", "shnum", "shstrndx")
        else:
            values = struct.unpack_from("<HHIIIIIHHHHHH", self.data, 16)
            keys = ("type", "machine", "version", "entry", "phoff", "shoff", "flags", "ehsize", "phentsize", "phnum", "shentsize", "shnum", "shstrndx")
        return dict(zip(keys, values))

    def _str(self, table: bytes, index: int) -> str:
        if index < 0 or index >= len(table):
            return ""
        end = table.find(b"\0", index)
        return table[index : end if end >= 0 else len(table)].decode("utf-8", errors="replace")

    def _sections(self) -> list[ElfSection]:
        header = self._header()
        shoff, shentsize, shnum = header["shoff"], header["shentsize"], header["shnum"]
        raw: list[tuple[int, int, int, int, int, int, int, int, int]] = []
        for index in range(shnum):
            offset = shoff + index * shentsize
            if self.is_64:
                name, section_type, flags, address, file_offset, size, link, info, _, entry_size = struct.unpack_from("<IIQQQQIIQQ", self.data, offset)
            else:
                name, section_type, flags, address, file_offset, size, link, info, _, entry_size = struct.unpack_from("<IIIIIIIIII", self.data, offset)
            raw.append((name, section_type, flags, address, file_offset, size, link, info, entry_size))
        shstrndx = header["shstrndx"]
        if shstrndx >= len(raw):
            names = b""
        else:
            shstr = raw[shstrndx]
            names = self.data[shstr[4] : shstr[4] + shstr[5]]
        return [
            ElfSection(index, self._str(names, item[0]), item[1], item[3], item[4], item[5], item[2], item[6], item[7], item[8])
            for index, item in enumerate(raw)
        ]

    def _symbols(self) -> list[ElfSymbol]:
        result: list[ElfSymbol] = []
        for section in self.sections:
            if section.section_type not in {2, 11}:  # SYMTAB, DYNSYM
                continue
            if not section.entry_size:
                continue
            linked = self.sections[section.link] if 0 <= section.link < len(self.sections) else None
            strings = self.data[linked.offset : linked.offset + linked.size] if linked else b""
            count = section.size // section.entry_size
            for index in range(count):
                offset = section.offset + index * section.entry_size
                try:
                    if self.is_64:
                        name, info, _, shndx, address, size = struct.unpack_from("<IBBHQQ", self.data, offset)
                    else:
                        name, address, size, info, _, shndx = struct.unpack_from("<IIIBBH", self.data, offset)
                except struct.error:
                    continue
                result.append(ElfSymbol(index, self._str(strings, name), address, size, info, shndx, section.name))
        return result

    @property
    def machine_name(self) -> str:
        machines = {3: "x86", 8: "MIPS", 40: "ARM", 62: "x86_64", 183: "AArch64", 243: "RISC-V"}
        return machines.get(self._header()["machine"], f"machine-{self._header()['machine']}")

    def metadata(self) -> dict[str, Any]:
        header = self._header()
        return {
            "path": self.source,
            "class": "ELF64" if self.is_64 else "ELF32",
            "endianness": "little" if self.little_endian else "big",
            "machine": self.machine_name,
            "machineId": header["machine"],
            "type": header["type"],
            "entryPoint": f"0x{header['entry']:x}",
            "sectionCount": len(self.sections),
            "functionSymbolCount": len([item for item in self.symbols if item.is_function]),
            "sections": [
                {
                    "index": item.index,
                    "name": item.name,
                    "address": f"0x{item.address:x}",
                    "offset": item.offset,
                    "size": item.size,
                    "flags": item.flags,
                }
                for item in self.sections
                if item.name or item.size
            ],
        }

    def functions(self) -> list[ElfSymbol]:
        return sorted(
            [item for item in self.symbols if item.is_function and item.address],
            key=lambda item: (item.address, item.name),
        )

    def find_symbol(self, value: str) -> ElfSymbol | None:
        address = parse_address(value)
        if address is not None:
            candidates = [item for item in self.functions() if item.address == address]
            if candidates:
                return candidates[0]
            candidates = [item for item in self.functions() if item.address <= address < item.address + max(item.size, 1)]
            if candidates:
                return candidates[0]
        cleaned = value.strip()
        return next((item for item in self.symbols if item.name == cleaned), None)

    def file_offset_for_address(self, address: int) -> int | None:
        for section in self.sections:
            if section.contains_address(address):
                return section.offset + (address - section.address)
        return None

    def bytes_at(self, address: int, length: int = 256) -> bytes:
        offset = self.file_offset_for_address(address)
        if offset is None:
            raise ToolError(f"Address 0x{address:x} does not map to an ELF section")
        return self.data[offset : min(len(self.data), offset + length)]
