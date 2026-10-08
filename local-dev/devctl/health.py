"""Health probes for services: HTTP (2xx/3xx = up) or bare TCP."""
from __future__ import annotations

import socket
import time
import urllib.error
import urllib.request


def check(health: dict, timeout: float = 2.0) -> dict:
    if not health:
        return {"ok": None, "detail": "no health check configured"}
    t0 = time.time()
    try:
        if "url" in health:
            req = urllib.request.Request(health["url"], headers={"User-Agent": "devctl"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                ok = 200 <= r.status < 400
                detail = f"HTTP {r.status}"
        else:
            with socket.create_connection((health.get("host", "127.0.0.1"), int(health["port"])), timeout=timeout):
                ok, detail = True, "tcp open"
    except urllib.error.HTTPError as e:
        ok, detail = False, f"HTTP {e.code}"
    except Exception as e:  # connection refused, timeout, DNS
        ok, detail = False, type(e).__name__
    return {"ok": ok, "detail": detail, "ms": int((time.time() - t0) * 1000)}
