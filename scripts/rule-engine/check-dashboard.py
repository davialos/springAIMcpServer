#!/usr/bin/env python3
"""Runs every query of the provisioned Grafana dashboard "rule-engine-ops" through Grafana itself (/api/ds/query), so SQL macros,
datasource permissions (the read-only role) and PromQL/LogQL are checked exactly as the panels run them.

    scripts/rule-engine/dev.sh dashboards          (stack must be up)

Exit code 1 if any query errors. A panel with zero rows is reported but is not an error (a fresh stack has no traffic yet)."""
import base64
import json
import os
import sys
import time
import urllib.request

base = os.environ.get("GRAFANA_URL", "http://127.0.0.1:3000")
auth = base64.b64encode(f"{os.environ.get('GRAFANA_USER', 'admin')}:{os.environ.get('GRAFANA_PASSWORD', 'admin')}".encode()).decode()


def call(path, body=None):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": f"Basic {auth}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def interpolate(text):
    """The dashboard variables, set to 'everything' / 'empty' like the dashboard's own defaults."""
    return (text.replace("$app", ".+").replace("$service", ".+").replace("$search", "").replace("${app}", ".+")
            .replace("${service}", ".+").replace("${search}", ""))


dash = call("/api/dashboards/uid/rule-engine-ops")["dashboard"]
now = int(time.time() * 1000)
failures, checked = 0, 0
for panel in dash["panels"]:
    for target in panel.get("targets", []):
        q = {k: v for k, v in target.items()}
        for key in ("rawSql", "expr"):
            if key in q:
                q[key] = interpolate(q[key])
        q.update({"intervalMs": 60000, "maxDataPoints": 500})
        checked += 1
        try:
            res = call("/api/ds/query", {"queries": [q], "from": str(now - 6 * 3600 * 1000), "to": str(now)})
            result = res["results"][target["refId"]]
            if result.get("error"):
                raise RuntimeError(result["error"])
            rows = sum(len(f["data"]["values"][0]) if f["data"]["values"] else 0 for f in result.get("frames", []))
            print(f"  ok    {panel['title']} [{target['refId']}]: {rows} rows")
        except Exception as e:  # noqa: BLE001 — report every failing panel, not just the first
            failures += 1
            detail = e.read().decode()[:300] if hasattr(e, "read") else str(e)
            print(f"  FAIL  {panel['title']} [{target['refId']}]: {detail}")
print(f"{checked} queries, {failures} failed")
sys.exit(1 if failures else 0)
