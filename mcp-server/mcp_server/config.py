"""Configuration for the Android Reverse Engineering MCP server.

The server deliberately keeps configuration in environment variables so a local
clone, an Android/Termux install, and a Cloudflare Tunnel can use the same code
without baking a public hostname into the application.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


def _bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int, minimum: int, maximum: int) -> int:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        parsed = int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if parsed < minimum or parsed > maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return parsed


def _path(name: str, default: str) -> Path:
    raw = os.getenv(name, default).strip()
    if not raw:
        raw = default
    return Path(raw).expanduser().resolve()


def _optional_path(name: str) -> Path | None:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return None
    return Path(raw).expanduser().resolve()


def _origins(raw: str) -> tuple[str, ...]:
    values = tuple(item.strip() for item in raw.split(",") if item.strip())
    return values or ("*",)


def _command(name: str, default: str) -> str:
    return os.getenv(name, default).strip() or default


@dataclass(frozen=True)
class Settings:
    """Runtime settings loaded from the process environment."""

    host: str = "0.0.0.0"
    port: int = 8080
    path: str = "/mcp"
    cors_origins: tuple[str, ...] = ("*",)
    cors_allow_credentials: bool = False
    workspace_path: Path = Path("workspace")
    cache_path: Path = Path(".cache/android-reverse-mcp")
    allow_external_paths: bool = False
    default_apk: Path | None = None
    android_sdk_path: Path | None = None
    android_build_tools_path: Path | None = None
    apktool_path: str = "apktool"
    jadx_path: str = "jadx"
    baksmali_path: str = "baksmali"
    aapt2_path: str = "aapt2"
    apkanalyzer_path: str = "apkanalyzer"
    radare2_path: str = "r2"
    rabin2_path: str = "rabin2"
    il2cpp_dumper_path: str | None = None
    cpp2il_path: str | None = None
    unflutter_path: str | None = None
    command_timeout_seconds: int = 60
    max_output_bytes: int = 1_000_000
    max_input_bytes: int = 10_000_000
    max_archive_entry_bytes: int = 64 * 1024 * 1024
    enable_r2_execute: bool = True
    log_level: str = "INFO"

    def __post_init__(self) -> None:
        normalized = self.path.strip() or "/mcp"
        if not normalized.startswith("/"):
            normalized = "/" + normalized
        normalized = normalized.rstrip("/") or "/"
        object.__setattr__(self, "path", normalized)
        if self.port < 1 or self.port > 65535:
            raise ValueError("MCP port must be between 1 and 65535")
        if self.cors_allow_credentials and "*" in self.cors_origins:
            raise ValueError(
                "MCP_CORS_ALLOW_CREDENTIALS cannot be true when MCP_CORS_ORIGINS contains *"
            )

    @classmethod
    def from_env(cls) -> "Settings":
        origins = _origins(os.getenv("MCP_CORS_ORIGINS", "*"))
        sdk_root = _optional_path("ANDROID_SDK_ROOT") or _optional_path("ANDROID_HOME")
        build_tools = _optional_path("MCP_ANDROID_BUILD_TOOLS_PATH")
        if build_tools is None and sdk_root is not None:
            candidates = sorted((sdk_root / "build-tools").glob("*"))
            build_tools = candidates[-1] if candidates else None
        aapt2_default = str(build_tools / "aapt2") if build_tools else "aapt2"
        apkanalyzer_default = "apkanalyzer"
        return cls(
            host=os.getenv("MCP_HOST", "0.0.0.0").strip() or "0.0.0.0",
            port=_int("MCP_PORT", 8080, 1, 65535),
            path=os.getenv("MCP_PATH", "/mcp"),
            cors_origins=origins,
            cors_allow_credentials=_bool("MCP_CORS_ALLOW_CREDENTIALS", False),
            workspace_path=_path("MCP_WORKSPACE_PATH", "./workspace"),
            cache_path=_path("MCP_CACHE_PATH", "./.cache/android-reverse-mcp"),
            allow_external_paths=_bool("MCP_ALLOW_EXTERNAL_PATHS", False),
            default_apk=_optional_path("MCP_DEFAULT_APK"),
            android_sdk_path=sdk_root,
            android_build_tools_path=build_tools,
            apktool_path=_command("MCP_APKTOOL", "apktool"),
            jadx_path=_command("MCP_JADX", "jadx"),
            baksmali_path=_command("MCP_BAKSMALI", "baksmali"),
            aapt2_path=_command("MCP_AAPT2", aapt2_default),
            apkanalyzer_path=_command("MCP_APKANALYZER", apkanalyzer_default),
            radare2_path=_command("MCP_RADARE2", "r2"),
            rabin2_path=_command("MCP_RABIN2", "rabin2"),
            il2cpp_dumper_path=os.getenv("MCP_IL2CPP_DUMPER") or None,
            cpp2il_path=os.getenv("MCP_CPP2IL") or None,
            unflutter_path=os.getenv("MCP_UNFLUTTER") or None,
            command_timeout_seconds=_int("MCP_COMMAND_TIMEOUT", 60, 1, 900),
            max_output_bytes=_int("MCP_MAX_OUTPUT_BYTES", 1_000_000, 1_024, 50_000_000),
            max_input_bytes=_int("MCP_MAX_INPUT_BYTES", 10_000_000, 1_024, 500_000_000),
            max_archive_entry_bytes=_int(
                "MCP_MAX_ARCHIVE_ENTRY_BYTES", 64 * 1024 * 1024, 1_024, 2_000_000_000
            ),
            enable_r2_execute=_bool("MCP_ENABLE_R2_EXECUTE", True),
            log_level=os.getenv("MCP_LOG_LEVEL", "INFO").upper(),
        )

    def ensure_directories(self) -> None:
        self.workspace_path.mkdir(parents=True, exist_ok=True)
        self.cache_path.mkdir(parents=True, exist_ok=True)
        (self.cache_path / "native").mkdir(parents=True, exist_ok=True)
        (self.cache_path / "dex").mkdir(parents=True, exist_ok=True)
        (self.cache_path / "resources").mkdir(parents=True, exist_ok=True)
        (self.cache_path / "notes").mkdir(parents=True, exist_ok=True)

    def allowed_roots(self) -> tuple[Path, ...]:
        roots: list[Path] = [self.workspace_path, self.cache_path]
        if self.default_apk is not None:
            roots.append(self.default_apk.parent)
        if self.android_sdk_path is not None:
            roots.append(self.android_sdk_path)
        return tuple(dict.fromkeys(path.resolve() for path in roots))

    def is_allowed_path(self, path: Path) -> bool:
        resolved = path.expanduser().resolve()
        if self.allow_external_paths:
            return True
        return any(resolved == root or root in resolved.parents for root in self.allowed_roots())

    def describe(self) -> dict[str, object]:
        """Return safe, non-secret settings for diagnostics."""
        return {
            "host": self.host,
            "port": self.port,
            "path": self.path,
            "cors_origins": list(self.cors_origins),
            "workspace_path": str(self.workspace_path),
            "cache_path": str(self.cache_path),
            "allow_external_paths": self.allow_external_paths,
            "default_apk": str(self.default_apk) if self.default_apk else None,
            "android_sdk_path": str(self.android_sdk_path) if self.android_sdk_path else None,
            "tools": {
                "apktool": self.apktool_path,
                "jadx": self.jadx_path,
                "baksmali": self.baksmali_path,
                "aapt2": self.aapt2_path,
                "apkanalyzer": self.apkanalyzer_path,
                "radare2": self.radare2_path,
                "rabin2": self.rabin2_path,
                "il2cpp_dumper_configured": bool(self.il2cpp_dumper_path),
                "cpp2il_configured": bool(self.cpp2il_path),
                "unflutter_configured": bool(self.unflutter_path),
            },
        }


def under_any(path: Path, roots: Iterable[Path]) -> bool:
    resolved = path.expanduser().resolve()
    return any(resolved == root or root in resolved.parents for root in roots)
