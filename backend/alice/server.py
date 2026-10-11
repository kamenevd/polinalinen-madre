#!/usr/bin/env python3
"""Entrypoint for the isolated Madre Alice voice bridge. Does not deploy itself."""

from __future__ import annotations

import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app import App, make_server  # noqa: E402
from config import settings_from_env  # noqa: E402


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    settings = settings_from_env()
    app = App(settings)
    httpd = make_server(settings.host, settings.port, app)
    logging.getLogger("madre-alice").info("listening")
    try:
        httpd.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
