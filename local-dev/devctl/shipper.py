"""Tails local log files and pushes new lines to Loki's HTTP API (no Promtail/Alloy container needed).

Sources (re-globbed every poll so new services/builds are picked up):
  $LOCALDEV_HOME/logs/*.log            one per process-compose process   -> service=<file stem>
  <jboss base>/log/server.log          JBoss EAP                         -> service=jboss-server
  <builds_dir>/runs/*/build.log        build output                      -> service=build, build=<id>
Files present at startup are read from their end (no replay of old history); files that appear later from the start.
"""
from __future__ import annotations

import json
import re
import time
import urllib.request
from pathlib import Path

from . import compose, config

ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
MAX_BUFFER = 5000
BATCH = 500


class Shipper:
    def __init__(self, cfg: dict, url: str | None = None):
        self.cfg = cfg
        self.url = (url or cfg["infra"]["loki_url"]).rstrip("/") + "/loki/api/v1/push"
        self.pos: dict[Path, int] = {}
        self.partial: dict[Path, str] = {}
        self.pending: dict[tuple, list] = {}
        self.first = True

    def sources(self) -> dict:
        out = {}
        for f in compose.log_dir().glob("*.log"):
            out[f] = {"service": f.stem}
        jb = compose.jboss_base(self.cfg) / "log" / "server.log"
        if jb.exists():
            out[jb] = {"service": "jboss-server"}
        for f in (config.builds_root(self.cfg) / "runs").glob("*/build.log"):
            out[f] = {"service": "build", "build": f.parent.name}
        return out

    def poll(self) -> int:
        """Read new lines from all sources into the buffer; returns number of lines read."""
        n = 0
        for f, labels in self.sources().items():
            try:
                size = f.stat().st_size
            except OSError:
                continue
            if f not in self.pos:
                self.pos[f] = size if self.first else 0
            if size < self.pos[f]:  # truncated / rotated
                self.pos[f], self.partial[f] = 0, ""
            if size == self.pos[f]:
                continue
            with open(f, "rb") as fh:
                fh.seek(self.pos[f])
                data = fh.read(1_000_000)
            self.pos[f] += len(data)
            text = self.partial.get(f, "") + data.decode("utf-8", "replace")
            *lines, rest = text.split("\n")
            self.partial[f] = rest
            key = tuple(sorted({"job": "devctl", "env": "local", **labels}.items()))
            for ln in lines:
                ln = ANSI.sub("", ln.rstrip("\r"))
                if ln:
                    self.pending.setdefault(key, []).append((time.time_ns(), ln))
                    n += 1
        self.first = False
        return n

    def flush(self) -> bool:
        streams = []
        for key, vals in self.pending.items():
            for i in range(0, len(vals), BATCH):
                streams.append({"stream": dict(key), "values": [[str(t), ln] for t, ln in vals[i:i + BATCH]]})
        if not streams:
            return True
        req = urllib.request.Request(self.url, data=json.dumps({"streams": streams}).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                ok = 200 <= r.status < 300
        except Exception:
            ok = False
        if ok:
            self.pending.clear()
        else:  # keep for retry, but bound memory
            for key in self.pending:
                self.pending[key] = self.pending[key][-MAX_BUFFER:]
        return ok

    def run(self, interval: float = 1.0) -> None:
        while True:
            self.poll()
            self.flush()
            time.sleep(interval)
