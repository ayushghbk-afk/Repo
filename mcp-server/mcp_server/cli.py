"""Command-line entry point."""

from __future__ import annotations

import argparse
import logging
import os

from .config import Settings
from .transport import SERVER_NAME, SERVER_VERSION, serve


def main() -> None:
    parser = argparse.ArgumentParser(description="Android Reverse Engineering MCP server")
    parser.add_argument("--host", help="override MCP_HOST")
    parser.add_argument("--port", type=int, help="override MCP_PORT")
    parser.add_argument("--path", help="override MCP_PATH")
    parser.add_argument("--print-config", action="store_true", help="print safe configuration and exit")
    args = parser.parse_args()

    if args.host is not None:
        os.environ["MCP_HOST"] = args.host
    if args.port is not None:
        os.environ["MCP_PORT"] = str(args.port)
    if args.path is not None:
        os.environ["MCP_PATH"] = args.path

    settings = Settings.from_env()
    logging.basicConfig(
        level=getattr(logging, settings.log_level, logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    if args.print_config:
        import json

        print(json.dumps({"server": SERVER_NAME, "version": SERVER_VERSION, **settings.describe()}, indent=2))
        return
    serve(settings)


if __name__ == "__main__":
    main()
