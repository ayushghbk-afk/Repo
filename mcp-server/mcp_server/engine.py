"""Android, DEX, ELF, and framework analysis implementations.

The engine is intentionally usable without a mandatory native toolchain. When
JADX, apktool, Android SDK tools, radare2, Il2CppDumper, or UnFlutter are
configured, the corresponding tools are used for richer output; otherwise the
bounded Python fallbacks remain available.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import json
import os
import re
import shutil
import struct
import tempfile
import time
import urllib.parse
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable

from .axml import decode_axml, looks_like_axml
from .config import Settings
from .dex import DexClass, DexFile, DexMethod
from .elf import ElfFile, ElfSymbol
from .resources import query_resources
from .utils import (
    ToolError,
    atomic_write,
    clamp_int,
    json_text,
    line_slice,
    normalize_archive_member,
    parse_address,
    printable_text,
    require_string,
    resolve_user_path,
    run_command,
    safe_join,
    sha256_bytes,
    sha256_file,
)


class AnalysisEngine:
    """Stateful tool dispatcher shared by all MCP requests in one process."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.settings.ensure_directories()
        self.current_apk: Path | None = settings.default_apk
        self.current_binary_path: Path | None = None
        self.current_elf: ElfFile | None = None
        self.current_dexes: list[DexFile] = []
        self.smali_cache: dict[str, str] = {}
        self.bookmarks_path = settings.cache_path / "bookmarks.json"
        self.notes_path = settings.cache_path / "notes"
        self.state_path = settings.cache_path / "analysis-state.json"
        self._load_state()

    def _load_state(self) -> None:
        try:
            state = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError, OSError):
            state = {}
        current_apk = state.get("current_apk")
        current_binary = state.get("current_binary")
        if current_apk and Path(current_apk).exists() and self.settings.is_allowed_path(Path(current_apk)):
            self.current_apk = Path(current_apk)
        if current_binary and Path(current_binary).exists() and self.settings.is_allowed_path(Path(current_binary)):
            self._load_elf(Path(current_binary))

    def _save_state(self) -> None:
        atomic_write(
            self.state_path,
            json_text(
                {
                    "current_apk": str(self.current_apk) if self.current_apk else None,
                    "current_binary": str(self.current_binary_path) if self.current_binary_path else None,
                    "updated_at": datetime.now(timezone.utc).isoformat(),
                }
            ),
        )

    # ---------- path and archive helpers ----------

    def _apk(self, raw: str | None = None) -> Path:
        if raw:
            path = resolve_user_path(self.settings, raw, must_exist=True)
            if not zipfile.is_zipfile(path):
                raise ToolError(f"APK/XAPK path is not a readable ZIP archive: {path}")
            self.current_apk = path
            self._save_state()
            return path
        if self.current_apk is None:
            raise ToolError("No APK is loaded. Pass apkPath or set MCP_DEFAULT_APK.")
        if not self.current_apk.exists():
            raise ToolError(f"Configured APK no longer exists: {self.current_apk}")
        return self.current_apk

    def _archive_names(self, apk: Path) -> list[str]:
        try:
            with zipfile.ZipFile(apk) as archive:
                return [normalize_archive_member(item.filename) for item in archive.infolist() if item.filename]
        except zipfile.BadZipFile as exc:
            raise ToolError(f"Invalid APK/ZIP archive: {apk}") from exc

    def _archive_read(self, apk: Path, member: str) -> bytes:
        member = normalize_archive_member(member)
        try:
            with zipfile.ZipFile(apk) as archive:
                info = archive.getinfo(member)
                if info.file_size > self.settings.max_archive_entry_bytes:
                    raise ToolError(
                        f"Archive entry {member} is {info.file_size} bytes, above "
                        "MCP_MAX_ARCHIVE_ENTRY_BYTES"
                    )
                return archive.read(info)
        except KeyError as exc:
            raise ToolError(f"APK entry not found: {member}") from exc
        except zipfile.BadZipFile as exc:
            raise ToolError(f"Invalid APK/ZIP archive: {apk}") from exc

    def _available_apk_entries(self, apk: Path) -> list[str]:
        return self._archive_names(apk)

    def _ensure_dexes(self, apk: Path | None = None) -> list[DexFile]:
        apk = apk or self._apk()
        if self.current_dexes and self.current_apk == apk:
            return self.current_dexes
        dexes: list[DexFile] = []
        for name in self._archive_names(apk):
            if re.fullmatch(r"classes(?:\d+)?\.dex", name):
                try:
                    dexes.append(DexFile(self._archive_read(apk, name), name))
                except ToolError:
                    continue
        self.current_apk = apk
        self.current_dexes = dexes
        self.smali_cache.clear()
        self._save_state()
        return dexes

    def _find_class(self, descriptor: str) -> tuple[DexFile, DexClass] | None:
        for dex in self._ensure_dexes():
            klass = dex.find_class(descriptor)
            if klass:
                return dex, klass
        return None

    def _smali_for(self, descriptor: str) -> str:
        normalized = descriptor.strip()
        if normalized in self.smali_cache:
            return self.smali_cache[normalized]
        found = self._find_class(normalized)
        if found is None:
            raise ToolError(f"Class not found: {descriptor}")
        dex, _ = found
        # A full external disassembly gives real opcode mnemonics. The fallback remains
        # deterministic and contains all structural method signatures.
        if self.settings.baksmali_path:
            result = run_command(
                [self.settings.baksmali_path, "d", "--output", str(self.settings.cache_path / "dex"), str(self.current_apk)],
                timeout=self.settings.command_timeout_seconds,
                max_output=self.settings.max_output_bytes,
            )
            if result.get("available") and result.get("returncode") == 0:
                candidate = self.settings.cache_path / "dex" / (normalized.strip("L;").replace("/", os.sep) + ".smali")
                if candidate.is_file():
                    text = candidate.read_text(encoding="utf-8", errors="replace")
                    self.smali_cache[normalized] = text
                    return text
        text = dex.smali_for(normalized)
        self.smali_cache[normalized] = text
        return text

    # ---------- APK and resources ----------

    def list_apk_files(self, args: dict[str, Any]) -> dict[str, Any]:
        apk = self._apk(require_string(args, "apkPath"))
        path_filter = str(args.get("pathFilter", "") or "").replace("\\", "/").lstrip("/")
        extension_filter = str(args.get("extensionFilter", "") or "").lower()
        limit = clamp_int(args.get("maxResults"), 200, 1, 1000)
        files = []
        for name in self._archive_names(apk):
            if path_filter and not name.startswith(path_filter):
                continue
            if extension_filter and not name.lower().endswith(extension_filter):
                continue
            files.append(name)
            if len(files) >= limit:
                break
        return {
            "apkPath": str(apk),
            "count": len(files),
            "truncated": len(files) == limit,
            "files": files,
        }

    def _read_archive_text(self, args: dict[str, Any], decode_xml: bool) -> dict[str, Any]:
        apk = self._apk(require_string(args, "apkPath"))
        member = require_string(args, "filePath")
        data = self._archive_read(apk, member)
        if decode_xml:
            if looks_like_axml(data):
                text = decode_axml(data)
                encoding = "android-binary-xml"
            else:
                text = printable_text(data)
                encoding = "text-fallback"
        else:
            if b"\0" in data[:4096] and not data.startswith((b"PK", b"dex\n")):
                raise ToolError(f"Archive entry does not appear to be text: {member}")
            text = printable_text(data)
            encoding = "utf-8-lossy"
        result = line_slice(text, args.get("startLine"), args.get("endLine"))
        result.update({"apkPath": str(apk), "filePath": normalize_archive_member(member), "encoding": encoding})
        return result

    def read_axml_file(self, args: dict[str, Any]) -> dict[str, Any]:
        return self._read_archive_text(args, True)

    def read_raw_file(self, args: dict[str, Any]) -> dict[str, Any]:
        return self._read_archive_text(args, False)

    def query_arsc_resources(self, args: dict[str, Any]) -> dict[str, Any]:
        apk = self._apk(require_string(args, "apkPath"))
        return query_resources(self.settings, apk, args.get("resourceType"), args.get("query"), args.get("limit"))

    def get_apk_signature(self, args: dict[str, Any]) -> dict[str, Any]:
        apk = self._apk(require_string(args, "apkPath"))
        entries = []
        for name in self._archive_names(apk):
            upper = name.upper()
            if not upper.startswith("META-INF/") or not upper.endswith((".RSA", ".DSA", ".EC", ".DER")):
                continue
            data = self._archive_read(apk, name)
            item: dict[str, Any] = {
                "entry": name,
                "size": len(data),
                "sha1": hashlib.sha1(data).hexdigest().upper(),
                "sha256": hashlib.sha256(data).hexdigest().upper(),
            }
            with tempfile.NamedTemporaryFile(suffix=Path(name).suffix, delete=False) as handle:
                handle.write(data)
                temporary = Path(handle.name)
            try:
                openssl = run_command(
                    ["openssl", "x509", "-inform", "DER", "-in", str(temporary), "-noout", "-subject", "-issuer", "-serial", "-fingerprint", "-sha256"],
                    timeout=self.settings.command_timeout_seconds,
                    max_output=20_000,
                )
                if openssl.get("available") and openssl.get("returncode") == 0:
                    item["certificate"] = openssl.get("stdout", "").strip()
            finally:
                temporary.unlink(missing_ok=True)
            entries.append(item)
        return {"apkPath": str(apk), "certificates": entries, "count": len(entries)}

    # ---------- DEX and smali ----------

    def list_loaded_classes(self, args: dict[str, Any]) -> dict[str, Any]:
        raw_query = args.get("filterQuery", "")
        if not isinstance(raw_query, str):
            raise ToolError("filterQuery must be a string")
        query = raw_query.lower()
        limit = clamp_int(args.get("limit"), 100, 1, 2000)
        if self.current_apk is None or not self.current_apk.exists():
            return {"classes": [], "count": 0, "warning": "No APK is loaded; set MCP_DEFAULT_APK or call an APK tool first."}
        classes = [name for dex in self._ensure_dexes() for name in dex.class_names()]
        if query:
            normalized = query.replace(".", "/")
            classes = [name for name in classes if normalized in name.lower() or query in name.lower()]
        selected = classes[:limit]
        return {"count": len(selected), "total": len(classes), "truncated": len(classes) > limit, "classes": selected}

    def decompile_class(self, args: dict[str, Any]) -> dict[str, Any]:
        descriptor = require_string(args, "classDescriptor")
        engine = str(args.get("engine") or "Smali")
        if engine.lower() != "smali":
            # Prefer JADX when explicitly requested; other names are preserved in the
            # response and fall back to structural smali if their executable is absent.
            if engine.lower() in {"jadx", "jadx_fallback", "jadx_ir"}:
                result = run_command(
                    [self.settings.jadx_path, "-d", str(self.settings.cache_path / "jadx"), str(self._apk())],
                    timeout=self.settings.command_timeout_seconds,
                    max_output=self.settings.max_output_bytes,
                )
                if result.get("available") and result.get("returncode") == 0:
                    # JADX output is indexed below if it matches the requested class.
                    java_path = self.settings.cache_path / "jadx" / "sources" / (descriptor.strip("L;").replace("/", os.sep) + ".java")
                    if java_path.is_file():
                        text = java_path.read_text(encoding="utf-8", errors="replace")
                        return {"classDescriptor": descriptor, "engine": engine, **line_slice(text, args.get("startLine"), args.get("endLine"))}
        text = self._smali_for(descriptor)
        if args.get("methodSignature"):
            method = str(args["methodSignature"])
            chunks = text.split("\n.method")
            matches = [chunk for chunk in chunks if method in chunk]
            text = ("\n.method" + "\n.method".join(matches)) if matches else ""
        result = line_slice(text, args.get("startLine"), args.get("endLine"))
        result.update({"classDescriptor": descriptor, "engine": "Smali" if engine.lower() == "smali" else f"{engine} (fallback)"})
        return result

    def search_bytecode(self, args: dict[str, Any]) -> dict[str, Any]:
        query = require_string(args, "query")
        search_type = str(args.get("searchType") or "SMALI").upper()
        is_regex = bool(args.get("isRegex", False))
        limit = clamp_int(args.get("maxResults"), 50, 1, 500)
        pattern = re.compile(query, re.IGNORECASE) if is_regex else None
        results: list[dict[str, Any]] = []
        if self.current_apk is not None and self.current_apk.exists():
            dexes = self._ensure_dexes()
        else:
            dexes = []
        for dex_index, dex in enumerate(dexes):
            if search_type in {"CLASS_NAME", "SMALI"}:
                for klass in dex.classes:
                    haystack = klass.descriptor if search_type == "CLASS_NAME" else dex.smali_for(klass.descriptor)
                    matched = bool(pattern.search(haystack)) if pattern else query.lower() in haystack.lower()
                    if matched:
                        results.append({"dex": dex.source, "classDescriptor": klass.descriptor, "matchType": search_type})
                        if len(results) >= limit:
                            break
            if len(results) >= limit:
                break
            if search_type in {"METHOD_NAME", "SMALI"}:
                for klass in dex.classes:
                    for method in klass.methods:
                        haystack = method.name if search_type == "METHOD_NAME" else method.short_signature
                        matched = bool(pattern.search(haystack)) if pattern else query.lower() in haystack.lower()
                        if matched:
                            results.append({"dex": dex.source, "classDescriptor": klass.descriptor, "method": method.short_signature, "matchType": search_type})
                            if len(results) >= limit:
                                break
                    if len(results) >= limit:
                        break
            if len(results) >= limit:
                break
            if search_type in {"STRING", "SMALI"}:
                for index, value in enumerate(dex.strings):
                    matched = bool(pattern.search(value)) if pattern else query.lower() in value.lower()
                    if matched:
                        results.append({"dex": dex.source, "stringIndex": index, "value": value, "matchType": "STRING"})
                        if len(results) >= limit:
                            break
            if len(results) >= limit:
                break
        return {"query": query, "searchType": search_type, "count": len(results), "truncated": len(results) >= limit, "matches": results}

    def explain_smali(self, args: dict[str, Any]) -> dict[str, Any]:
        code = require_string(args, "smaliCode")
        instruction_counts: dict[str, int] = {}
        branches: list[str] = []
        invokes: list[str] = []
        registers: set[str] = set()
        labels: list[str] = []
        for raw in code.splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith(":"):
                labels.append(line)
                continue
            opcode = line.split(None, 1)[0]
            instruction_counts[opcode] = instruction_counts.get(opcode, 0) + 1
            registers.update(re.findall(r"\b[vp]\d+\b", line))
            if opcode.startswith("if-") or opcode in {"goto", "goto/16", "goto/32", "packed-switch", "sparse-switch"}:
                branches.append(line)
            if opcode.startswith("invoke-"):
                invokes.append(line)
        explanation = []
        if instruction_counts:
            explanation.append("The block contains " + ", ".join(f"{key} × {value}" for key, value in sorted(instruction_counts.items())) + ".")
        if branches:
            explanation.append(f"It has {len(branches)} branch or jump instruction(s), including {len(labels)} label(s).")
        if invokes:
            explanation.append(f"It invokes {len(invokes)} method call(s); inspect their descriptors for external effects.")
        if not explanation:
            explanation.append("No executable Smali instructions were found in the supplied block.")
        return {
            "instructionCount": sum(instruction_counts.values()),
            "opcodes": instruction_counts,
            "registers": sorted(registers),
            "labels": labels,
            "branches": branches,
            "invokes": invokes,
            "explanation": " ".join(explanation),
        }

    def _method_text(self, descriptor: str, signature: str) -> tuple[DexClass, str]:
        found = self._find_class(descriptor)
        if found is None:
            raise ToolError(f"Class not found: {descriptor}")
        _, klass = found
        text = self._smali_for(descriptor)
        if signature not in text:
            available = [item.short_signature for item in klass.methods]
            raise ToolError(f"Method not found: {signature}; available methods: {available[:30]}")
        return klass, text

    def analyze_control_flow(self, args: dict[str, Any]) -> dict[str, Any]:
        descriptor = require_string(args, "classDescriptor")
        signature = require_string(args, "methodSignature")
        _, text = self._method_text(descriptor, signature)
        method_lines = self._extract_method_lines(text, signature)
        labels = [line.strip() for line in method_lines if line.strip().startswith(":")]
        edges: list[dict[str, str]] = []
        for index, line in enumerate(method_lines):
            stripped = line.strip()
            if stripped.startswith("if-") or stripped.startswith("goto"):
                target = stripped.split()[-1]
                source = next((item.strip() for item in reversed(method_lines[:index]) if item.strip().startswith(":") or item.strip()), "entry")
                edges.append({"from": source, "to": target, "kind": "branch" if stripped.startswith("if-") else "jump"})
        blocks = []
        current: list[str] = []
        for line in method_lines:
            if line.strip().startswith(":") and current:
                blocks.append(current)
                current = []
            current.append(line)
        if current:
            blocks.append(current)
        return {
            "classDescriptor": descriptor,
            "methodSignature": signature,
            "blockCount": len(blocks),
            "blocks": [{"index": index, "label": next((line.strip() for line in block if line.strip().startswith(":")), "entry"), "lineCount": len(block)} for index, block in enumerate(blocks)],
            "labels": labels,
            "edges": edges,
            "note": "Edges are syntactic Smali control-flow edges; exception handlers may require a full disassembler.",
        }

    def _extract_method_lines(self, text: str, signature: str) -> list[str]:
        lines = text.splitlines()
        start = next((index for index, line in enumerate(lines) if signature in line and line.strip().startswith(".method")), None)
        if start is None:
            return []
        end = next((index for index in range(start + 1, len(lines)) if lines[index].strip() == ".end method"), len(lines))
        return lines[start : end + 1]

    def analyze_call_graph(self, args: dict[str, Any]) -> dict[str, Any]:
        descriptor = require_string(args, "classDescriptor")
        signature = require_string(args, "methodSignature")
        depth = clamp_int(args.get("depth"), 1, 1, 3)
        _, text = self._method_text(descriptor, signature)
        lines = self._extract_method_lines(text, signature)
        calls = []
        for line in lines:
            match = re.search(r"invoke-[^ ]+\s+\{[^}]*\},\s*(L[^;]+;)->([^\s]+)", line.strip())
            if match:
                calls.append({"classDescriptor": match.group(1) + ";", "methodSignature": match.group(2), "depth": 1})
        return {"root": {"classDescriptor": descriptor, "methodSignature": signature}, "depth": depth, "calls": calls[:500], "count": len(calls), "note": "The fallback reports calls visible in indexed Smali; use a full DEX analyzer for inherited and reflective calls."}

    # ---------- session state ----------

    def _read_json(self, path: Path, default: Any) -> Any:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError, OSError):
            return default

    def manage_bookmarks(self, args: dict[str, Any]) -> dict[str, Any]:
        action = require_string(args, "action").lower()
        bookmarks = self._read_json(self.bookmarks_path, [])
        if not isinstance(bookmarks, list):
            bookmarks = []
        descriptor = str(args.get("descriptor") or "").strip()
        if action == "list":
            return {"bookmarks": bookmarks, "count": len(bookmarks)}
        if not descriptor:
            raise ToolError("descriptor is required for add/remove")
        if action == "add":
            if descriptor not in bookmarks:
                bookmarks.append(descriptor)
            atomic_write(self.bookmarks_path, json_text(bookmarks))
            return {"action": "add", "descriptor": descriptor, "bookmarks": bookmarks}
        if action == "remove":
            bookmarks = [item for item in bookmarks if item != descriptor]
            atomic_write(self.bookmarks_path, json_text(bookmarks))
            return {"action": "remove", "descriptor": descriptor, "bookmarks": bookmarks}
        raise ToolError("action must be add, remove, or list")

    def decode_data(self, args: dict[str, Any]) -> dict[str, Any]:
        operation = require_string(args, "operation").lower()
        value = require_string(args, "input")
        key = str(args.get("key") or "")
        if operation == "base64_decode":
            try:
                raw = base64.b64decode(value, validate=True)
            except (ValueError, binascii.Error) as exc:
                raise ToolError("input is not valid base64") from exc
            return {"operation": operation, "text": printable_text(raw), "hex": raw.hex(), "bytes": len(raw)}
        if operation == "base64_encode":
            raw = value.encode()
            return {"operation": operation, "base64": base64.b64encode(raw).decode(), "bytes": len(raw)}
        if operation == "hex_to_text":
            try:
                raw = bytes.fromhex(value.replace("0x", "").replace(" ", ""))
            except ValueError as exc:
                raise ToolError("input is not valid hexadecimal") from exc
            return {"operation": operation, "text": printable_text(raw), "bytes": len(raw)}
        if operation == "text_to_hex":
            raw = value.encode()
            return {"operation": operation, "hex": raw.hex(), "bytes": len(raw)}
        if operation == "url_decode":
            return {"operation": operation, "text": urllib.parse.unquote(value)}
        if operation == "url_encode":
            return {"operation": operation, "text": urllib.parse.quote(value, safe="")}
        if operation == "decimal_to_hex":
            try:
                number = int(value, 0)
            except ValueError as exc:
                raise ToolError("input is not a decimal or integer literal") from exc
            return {"operation": operation, "decimal": number, "hex": hex(number)}
        if operation == "hex_to_decimal":
            try:
                number = int(value.strip(), 16)
            except ValueError as exc:
                raise ToolError("input is not hexadecimal") from exc
            return {"operation": operation, "hex": value, "decimal": number}
        if operation == "xor_cipher":
            if not key:
                raise ToolError("key is required for xor_cipher")
            try:
                raw = bytes.fromhex(value.replace("0x", "").replace(" ", ""))
            except ValueError:
                raw = value.encode()
            try:
                key_bytes = bytes.fromhex(key.replace("0x", "").replace(" ", ""))
                if not key_bytes:
                    raise ValueError
            except ValueError:
                key_bytes = key.encode()
            output = bytes(item ^ key_bytes[index % len(key_bytes)] for index, item in enumerate(raw))
            return {"operation": operation, "text": printable_text(output), "hex": output.hex(), "bytes": len(output)}
        if operation == "color_preview":
            color = value.strip().lstrip("#")
            if len(color) == 3:
                color = "ff" + "".join(char * 2 for char in color)
            elif len(color) == 6:
                color = "ff" + color
            elif len(color) != 8:
                raise ToolError("color_preview accepts RGB, RRGGBB, or AARRGGBB")
            try:
                channels = [int(color[index : index + 2], 16) for index in range(0, 8, 2)]
            except ValueError as exc:
                raise ToolError("color_preview contains non-hex characters") from exc
            return {"operation": operation, "input": value, "argb": "#" + color, "rgba": {"r": channels[1], "g": channels[2], "b": channels[3], "a": channels[0]}}
        raise ToolError("Unsupported decode operation")

    def manage_global_notes(self, args: dict[str, Any]) -> dict[str, Any]:
        action = require_string(args, "action").lower()
        title = str(args.get("title") or "").strip()
        if action == "read":
            if title:
                path = self.notes_path / (re.sub(r"[^A-Za-z0-9_.-]+", "_", title) + ".md")
                if not path.exists():
                    raise ToolError(f"Note not found: {title}")
                return {"title": title, "content": path.read_text(encoding="utf-8")}
            notes = []
            for path in sorted(self.notes_path.glob("*.md")):
                notes.append({"title": path.stem, "content": path.read_text(encoding="utf-8")})
            return {"notes": notes, "count": len(notes)}
        if action == "write":
            if not title:
                raise ToolError("title is required for write")
            content = str(args.get("content") or "")
            if len(content.encode()) > self.settings.max_input_bytes:
                raise ToolError("note exceeds MCP_MAX_INPUT_BYTES")
            safe_title = re.sub(r"[^A-Za-z0-9_.-]+", "_", title)
            path = self.notes_path / f"{safe_title}.md"
            atomic_write(path, content)
            return {"action": "write", "title": title, "path": str(path), "bytes": len(content.encode())}
        raise ToolError("action must be write or read")

    def list_workspace_files(self, args: dict[str, Any]) -> dict[str, Any]:
        _ = args
        files = []
        roots = (("workspace", self.settings.workspace_path), ("cache", self.settings.cache_path))
        for root_name, root in roots:
            if not root.exists():
                continue
            for path in sorted(root.rglob("*")):
                if not path.is_file():
                    continue
                try:
                    size = path.stat().st_size
                except OSError:
                    continue
                files.append({"filename": path.name, "path": str(path), "size_bytes": size, "root": root_name})
                if len(files) >= 5000:
                    return {"files": files, "count": len(files), "truncated": True}
        return {"files": files, "count": len(files), "truncated": False}

    # ---------- native / radare2 ----------

    def _load_elf(self, path: Path) -> ElfFile:
        data = path.read_bytes()
        if len(data) > self.settings.max_archive_entry_bytes * 8:
            raise ToolError("Native binary is larger than the configured analysis bound")
        elf = ElfFile(data, str(path))
        self.current_binary_path = path
        self.current_elf = elf
        self._save_state()
        return elf

    def load_binary(self, args: dict[str, Any]) -> dict[str, Any]:
        apk = self._apk(require_string(args, "apkPath"))
        entry = normalize_archive_member(require_string(args, "entryPath"))
        if not entry.lower().endswith((".so", ".elf", ".bin")):
            raise ToolError("entryPath must point to a native binary")
        data = self._archive_read(apk, entry)
        digest = sha256_bytes(data)[:16]
        target = self.settings.cache_path / "native" / f"{digest}-{Path(entry).name}"
        if not target.exists() or target.stat().st_size != len(data):
            atomic_write(target, data)
        try:
            elf = self._load_elf(target)
            metadata = elf.metadata()
        except ToolError as exc:
            self.current_binary_path = target
            self.current_elf = None
            self._save_state()
            metadata = {"path": str(target), "error": str(exc), "format": "unknown"}
        return {"apkPath": str(apk), "entryPath": entry, "binaryPath": str(target), "sha256": sha256_file(target), "metadata": metadata}

    def _require_elf(self) -> ElfFile:
        if self.current_elf is None:
            if self.current_binary_path and self.current_binary_path.exists():
                return self._load_elf(self.current_binary_path)
            raise ToolError("No native binary is loaded. Call load_binary first.")
        return self.current_elf

    def _r2(self, command: str, max_output: int | None = None) -> dict[str, Any]:
        if self.current_binary_path is None:
            raise ToolError("No native binary is loaded")
        return run_command(
            [self.settings.radare2_path, "-q0", "-e", "scr.color=false", "-c", command, str(self.current_binary_path)],
            timeout=self.settings.command_timeout_seconds,
            max_output=max_output or self.settings.max_output_bytes,
        )

    def list_binary_functions(self, args: dict[str, Any]) -> dict[str, Any]:
        query = str(args.get("query") or "").lower()
        skip = clamp_int(args.get("skip"), 0, 0, 10_000_000)
        limit = clamp_int(args.get("limit"), 100, 1, 1000)
        result = self._r2("aaa;aflj", self.settings.max_output_bytes)
        functions: list[dict[str, Any]] = []
        if result.get("available") and result.get("returncode") == 0:
            try:
                parsed = json.loads(result.get("stdout", "[]").strip() or "[]")
                if isinstance(parsed, list):
                    functions = parsed
            except json.JSONDecodeError:
                pass
        if not functions:
            functions = [symbol.as_dict() for symbol in self._require_elf().functions()]
        if query:
            functions = [item for item in functions if query in str(item.get("name", "")).lower()]
        selected = functions[skip : skip + limit]
        return {"count": len(selected), "total": len(functions), "skip": skip, "limit": limit, "truncated": skip + limit < len(functions), "functions": selected}

    def decompile_binary_function(self, args: dict[str, Any]) -> dict[str, Any]:
        address = require_string(args, "address")
        mode = require_string(args, "viewMode").lower()
        if mode not in {"pseudocode", "assembly", "hex", "cfg"}:
            raise ToolError("viewMode must be pseudocode, assembly, hex, or cfg")
        if mode == "pseudocode":
            command = f"aaa;pdg @ {address}"  # r2ghidra/decompiler plugin when installed.
        elif mode == "assembly":
            command = f"aaa;pdf @ {address}"
        elif mode == "cfg":
            command = f"aaa;agf @ {address}"
        else:
            command = f"px 256 @ {address}"
        result = self._r2(command)
        if result.get("available") and result.get("returncode") == 0 and result.get("stdout"):
            text = str(result["stdout"])
            source = "radare2"
        else:
            elf = self._require_elf()
            numeric = parse_address(address)
            if numeric is None:
                symbol = elf.find_symbol(address)
                numeric = symbol.address if symbol else None
            if numeric is None:
                raise ToolError(f"Unable to resolve native address or symbol: {address}")
            raw = elf.bytes_at(numeric, 256)
            text = " ".join(f"{raw[index:index + 16].hex():<32}" for index in range(0, len(raw), 16))
            source = "ELF byte fallback"
            mode = "hex" if mode != "hex" else mode
        sliced = line_slice(text, args.get("startLine"), args.get("endLine"))
        sliced.update({"address": address, "viewMode": mode, "source": source})
        return sliced

    def binary_search(self, args: dict[str, Any]) -> dict[str, Any]:
        query = require_string(args, "query")
        mode = require_string(args, "mode").lower()
        limit = clamp_int(args.get("limit"), 50, 1, 500)
        is_regex = bool(args.get("isRegex", False))
        if mode == "assembly":
            result = self._r2(f"aaa;/{query}")
            return {"query": query, "mode": mode, "source": "radare2", "output": result.get("stdout", "")[: self.settings.max_output_bytes]}
        if mode == "hex":
            try:
                needle = bytes.fromhex(query.replace("0x", "").replace(" ", ""))
            except ValueError as exc:
                raise ToolError("hex search query is not valid hexadecimal") from exc
            data = self._require_elf().data
            offsets = []
            start = 0
            while len(offsets) < limit:
                index = data.find(needle, start)
                if index < 0:
                    break
                offsets.append({"fileOffset": f"0x{index:x}", "decimalOffset": index})
                start = index + 1
            return {"query": query, "mode": mode, "count": len(offsets), "matches": offsets}
        data = self._require_elf().data
        results = []
        if mode == "magic":
            needle = query.encode().decode("latin1", errors="ignore")
            indexes = [match.start() for match in re.finditer(re.escape(needle), data.decode("latin1", errors="ignore"))]
        else:
            if is_regex:
                try:
                    regex = re.compile(query.encode() if False else query.encode("latin1"))
                    indexes = [match.start() for match in regex.finditer(data)]
                except re.error as exc:
                    raise ToolError(f"invalid byte regex: {exc}") from exc
            else:
                indexes = [match.start() for match in re.finditer(re.escape(query.encode("utf-8")), data)]
        for index in indexes[:limit]:
            results.append({"fileOffset": f"0x{index:x}", "decimalOffset": index, "preview": printable_text(data[max(0, index - 16) : index + len(query) + 32])})
        return {"query": query, "mode": mode, "count": len(results), "truncated": len(indexes) > limit, "matches": results}

    def get_binary_metadata(self, args: dict[str, Any]) -> dict[str, Any]:
        _ = args
        return self._require_elf().metadata()

    def get_function_properties(self, args: dict[str, Any]) -> dict[str, Any]:
        address = require_string(args, "address")
        elf = self._require_elf()
        symbol = elf.find_symbol(address)
        if symbol is None:
            raise ToolError(f"Function not found: {address}")
        result = self._r2(f"aaa;afij @ {address}")
        if result.get("available") and result.get("returncode") == 0 and result.get("stdout"):
            try:
                parsed = json.loads(result["stdout"].strip())
                if parsed:
                    return parsed[0] if isinstance(parsed, list) else parsed
            except json.JSONDecodeError:
                pass
        return {"name": symbol.name, "address": f"0x{symbol.address:x}", "size": symbol.size, "stackFrame": None, "variables": [], "source": "ELF symbol fallback"}

    def get_xrefs(self, args: dict[str, Any]) -> dict[str, Any]:
        address = require_string(args, "address")
        limit = clamp_int(args.get("limit"), 50, 1, 500)
        result = self._r2(f"aaa;axtj @ {address}")
        if result.get("available") and result.get("returncode") == 0:
            try:
                refs = json.loads(result.get("stdout", "[]").strip() or "[]")
                return {"address": address, "count": len(refs[:limit]), "truncated": len(refs) > limit, "xrefs": refs[:limit], "source": "radare2"}
            except json.JSONDecodeError:
                pass
        return {"address": address, "count": 0, "xrefs": [], "source": "ELF fallback", "warning": "Install radare2 for cross-reference analysis."}

    def rename_function(self, args: dict[str, Any]) -> dict[str, Any]:
        address = require_string(args, "address")
        name = require_string(args, "newName")
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.$@-]*", name):
            raise ToolError("newName must be a valid analysis flag name")
        result = self._r2(f"afn {name} @ {address}")
        path = self.settings.cache_path / "native" / "renames.json"
        values = self._read_json(path, {})
        values[address] = name
        atomic_write(path, json_text(values))
        return {"address": address, "newName": name, "applied": bool(result.get("available") and result.get("returncode") == 0), "persisted": str(path), "radare2": result.get("stdout", "")}

    def add_comment(self, args: dict[str, Any]) -> dict[str, Any]:
        address = require_string(args, "address")
        comment = require_string(args, "comment")
        result = self._r2(f"CC {comment} @ {address}")
        path = self.settings.cache_path / "native" / "comments.jsonl"
        line = json_text({"address": address, "comment": comment, "timestamp": datetime.now(timezone.utc).isoformat()}) + "\n"
        with path.open("a", encoding="utf-8") as handle:
            handle.write(line)
        return {"address": address, "comment": comment, "applied": bool(result.get("available") and result.get("returncode") == 0), "persisted": str(path)}

    def inspect_data(self, args: dict[str, Any]) -> dict[str, Any]:
        address = require_string(args, "address")
        numeric = parse_address(address)
        if numeric is None:
            symbol = self._require_elf().find_symbol(address)
            numeric = symbol.address if symbol else None
        if numeric is None:
            raise ToolError(f"Unable to resolve address: {address}")
        data = self._require_elf().bytes_at(numeric, 256)
        rows = []
        for offset in range(0, len(data), 16):
            chunk = data[offset : offset + 16]
            rows.append({"address": f"0x{numeric + offset:x}", "hex": chunk.hex(" "), "ascii": "".join(chr(byte) if 32 <= byte < 127 else "." for byte in chunk)})
        return {"address": address, "resolvedAddress": f"0x{numeric:x}", "bytes": len(data), "rows": rows}

    def r2_execute(self, args: dict[str, Any]) -> dict[str, Any]:
        if not self.settings.enable_r2_execute:
            raise ToolError("r2_execute is disabled by MCP_ENABLE_R2_EXECUTE=false")
        binary = resolve_user_path(self.settings, require_string(args, "binaryPath"), must_exist=True)
        if self.current_binary_path is None or binary != self.current_binary_path:
            # Do not let an arbitrary remote request make r2 read outside the active
            # workspace. The configured path policy still permits workspace/cache files.
            self._load_elf(binary)
        command = require_string(args, "command")
        result = self._r2(command)
        return {"binaryPath": str(binary), "command": command, **result}

    # ---------- framework-specific analysis ----------

    def _framework_entries(self, apk: Path) -> list[str]:
        return self._archive_names(apk)

    def analyze_il2cpp(self, args: dict[str, Any]) -> dict[str, Any]:
        apk = self._apk(require_string(args, "apkPath"))
        entries = self._framework_entries(apk)
        native = [item for item in entries if re.search(r"(^|/)libil2cpp\.so$", item, re.I)]
        metadata = [item for item in entries if item.lower().endswith("global-metadata.dat")]
        result: dict[str, Any] = {"apkPath": str(apk), "detected": bool(native or metadata), "nativeLibraries": native, "metadataFiles": metadata}
        if metadata:
            data = self._archive_read(apk, metadata[0])
            info: dict[str, Any] = {"path": metadata[0], "size": len(data), "sha256": sha256_bytes(data)}
            if len(data) >= 8:
                magic, version = struct.unpack_from("<II", data, 0)
                info.update({"magic": f"0x{magic:08x}", "version": version, "validMagic": magic == 0xFAB11BAF})
            result["metadata"] = info
        if self.settings.il2cpp_dumper_path:
            result["configuredTool"] = self.settings.il2cpp_dumper_path
            result["toolNote"] = "Run Il2CppDumper/CPP2IL in a controlled worker and inspect its generated artifacts from the cache."
        return result

    def analyze_unflutter(self, args: dict[str, Any]) -> dict[str, Any]:
        apk = self._apk(require_string(args, "apkPath"))
        entries = self._framework_entries(apk)
        lower = {item.lower(): item for item in entries}
        native = [item for item in entries if Path(item).name.lower() in {"libapp.so", "libflutter.so"}]
        flutter_assets = [item for item in entries if "/flutter_assets/" in item.lower() or item.lower().startswith("flutter_assets/")]
        snapshots = [item for item in entries if Path(item).name.lower() in {"isolate_snapshot_data", "isolate_snapshot_instr", "vm_snapshot_data", "vm_snapshot_instr", "kernel_blob.bin"}]
        result = {
            "apkPath": str(apk),
            "detected": bool(native or flutter_assets or snapshots),
            "nativeLibraries": native,
            "snapshotFiles": snapshots,
            "flutterAssetCount": len(flutter_assets),
            "flutterAssets": flutter_assets[:500],
            "indicators": ["libapp.so" if native else None, "flutter_assets" if flutter_assets else None, "Dart snapshots" if snapshots else None],
        }
        result["indicators"] = [item for item in result["indicators"] if item]
        if self.settings.unflutter_path:
            result["configuredTool"] = self.settings.unflutter_path
        return result

    def clear_native_cache(self, args: dict[str, Any]) -> dict[str, Any]:
        _ = args
        native = self.settings.cache_path / "native"
        removed = 0
        if native.exists():
            for path in native.iterdir():
                if path.is_file() or path.is_symlink():
                    path.unlink(missing_ok=True)
                    removed += 1
                elif path.is_dir():
                    shutil.rmtree(path)
                    removed += 1
        self.current_binary_path = None
        self.current_elf = None
        self._save_state()
        return {"cleared": True, "removedEntries": removed, "cachePath": str(native), "dexIndexPreserved": bool(self.current_dexes)}

    # ---------- dispatcher ----------

    def dispatch(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        method: Callable[[dict[str, Any]], dict[str, Any]] | None = getattr(self, name, None)
        if method is None or name.startswith("_"):
            raise ToolError(f"Unknown MCP tool: {name}")
        return method(arguments)
