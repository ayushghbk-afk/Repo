"""Small shared helpers with no third-party runtime dependency."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Iterable, Sequence

from .config import Settings


class ToolError(RuntimeError):
    """A safe, user-facing error raised by a tool implementation."""


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8")


def json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True)


def clamp_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    if value is None:
        return default
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise ToolError(f"Expected an integer, got {value!r}") from exc
    return max(minimum, min(maximum, parsed))


def require_string(arguments: dict[str, Any], name: str) -> str:
    value = arguments.get(name)
    if not isinstance(value, str) or not value.strip():
        raise ToolError(f"{name} is required and must be a non-empty string")
    return value


def normalize_archive_member(member: str) -> str:
    """Normalize an archive member while rejecting traversal."""
    member = member.replace("\\", "/").lstrip("/")
    parts = [part for part in member.split("/") if part not in {"", "."}]
    if any(part == ".." for part in parts):
        raise ToolError(f"Archive path escapes the archive root: {member!r}")
    return "/".join(parts)


def safe_join(root: Path, relative: str) -> Path:
    member = normalize_archive_member(relative)
    result = (root / member).resolve()
    root = root.resolve()
    if result != root and root not in result.parents:
        raise ToolError(f"Path escapes configured root: {relative!r}")
    return result


def resolve_user_path(settings: Settings, raw: str, *, must_exist: bool = False) -> Path:
    """Resolve an input path against the workspace and enforce path policy."""
    candidate = Path(raw).expanduser()
    if not candidate.is_absolute():
        candidate = settings.workspace_path / candidate
    candidate = candidate.resolve()
    if not settings.is_allowed_path(candidate):
        raise ToolError(
            f"Path is outside the configured workspace/cache roots: {candidate}. "
            """Set MCP_ALLOW_EXTERNAL_PATHS=true only for a trusted local server."""
        )
    if must_exist and not candidate.exists():
        raise ToolError(f"Path does not exist: {candidate}")
    return candidate


def atomic_write(path: Path, data: bytes | str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = data.encode("utf-8") if isinstance(data, str) else data
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def run_command(
    command: Sequence[str],
    *,
    timeout: int,
    max_output: int,
    cwd: Path | None = None,
    input_text: str | None = None,
) -> dict[str, Any]:
    """Run an optional native tool without invoking a shell."""
    try:
        completed = subprocess.run(
            list(command),
            cwd=str(cwd) if cwd else None,
            input=input_text,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
            env=os.environ.copy(),
        )
    except FileNotFoundError:
        return {"available": False, "returncode": None, "stdout": "", "stderr": "command not found"}
    except subprocess.TimeoutExpired as exc:
        stdout = (exc.stdout or "") if isinstance(exc.stdout, str) else ""
        stderr = (exc.stderr or "") if isinstance(exc.stderr, str) else ""
        return {
            "available": True,
            "returncode": None,
            "timed_out": True,
            "stdout": stdout[:max_output],
            "stderr": stderr[:max_output],
        }
    return {
        "available": True,
        "returncode": completed.returncode,
        "stdout": completed.stdout[:max_output],
        "stderr": completed.stderr[:max_output],
        "timed_out": False,
    }


def first_executable(candidates: Iterable[str | Path]) -> str | None:
    for candidate in candidates:
        value = str(candidate)
        if Path(value).is_file() and os.access(value, os.X_OK):
            return value
        if shutil.which(value):
            return value
    return None


def line_slice(text: str, start: Any = None, end: Any = None) -> dict[str, Any]:
    lines = text.splitlines()
    start_number = clamp_int(start, 1, 1, max(1, len(lines) + 1))
    end_number = clamp_int(end, len(lines), start_number, max(start_number, len(lines)))
    selected = lines[start_number - 1 : end_number]
    return {
        "startLine": start_number,
        "endLine": min(end_number, len(lines)),
        "totalLines": len(lines),
        "text": "\n".join(selected),
    }


def printable_text(data: bytes) -> str:
    return data.decode("utf-8", errors="replace")


def parse_address(value: str) -> int | None:
    raw = value.strip().lower()
    if raw.startswith("0x"):
        raw = raw[2:]
    if re.fullmatch(r"[0-9a-f]+", raw):
        try:
            return int(raw, 16)
        except ValueError:
            return None
    if raw.isdigit():
        return int(raw)
    return None
