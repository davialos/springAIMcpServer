"""Observability / support services run as docker containers under process-compose.

Config files are generated into ``$LOCALDEV_HOME/infra``. Containers reach host apps via host.docker.internal
(Docker Desktop / OrbStack / Colima on macOS all provide it).
"""
from __future__ import annotations

import json
from pathlib import Path

from . import config

LOKI_YAML = """auth_enabled: false
server: {http_listen_port: 3100}
common:
  path_prefix: /loki
  storage: {filesystem: {chunks_directory: /loki/chunks, rules_directory: /loki/rules}}
  replication_factor: 1
  ring: {kvstore: {store: inmemory}}
schema_config:
  configs:
    - {from: "2024-01-01", store: tsdb, object_store: filesystem, schema: v13, index: {prefix: index_, period: 24h}}
limits_config: {allow_structured_metadata: true}
"""


def _docker(name, image, ports, mounts=(), env=(), args="", health=None):
    parts = ["docker run --rm", f"--name devctl-{name}", "--add-host=host.docker.internal:host-gateway"]
    parts += [f"-p {p}" for p in ports] + [f"-v {m}" for m in mounts] + [f"-e {e}" for e in env] + [image]
    if args:
        parts.append(args)
    return {"command": " ".join(parts), "shutdown": {"command": f"docker stop devctl-{name}", "timeout_seconds": 20},
            "health": health}


def write_configs(cfg: dict) -> Path:
    d = config.HOME / "infra"
    (d / "grafana").mkdir(parents=True, exist_ok=True)
    jobs = []
    for name, s in cfg["services"].items():
        port = s.get("port") or s.get("metrics_port")
        if port and s.get("kind", "jar") == "jar":
            jobs.append({"job_name": name, "metrics_path": cfg["infra"]["prometheus_scrape_path"],
                         "static_configs": [{"targets": [f"host.docker.internal:{port}"]}]})
        if s.get("kind") == "war" and s.get("metrics_path"):
            off = cfg["jboss"].get("port_offset", 0)
            jobs.append({"job_name": name, "metrics_path": s["metrics_path"],
                         "static_configs": [{"targets": [f"host.docker.internal:{8080 + off}"]}]})
    prom = {"global": {"scrape_interval": "15s"},
            "scrape_configs": [{"job_name": "prometheus", "static_configs": [{"targets": ["localhost:9090"]}]}] + jobs}
    (d / "prometheus.yml").write_text(json.dumps(prom, indent=2))  # JSON is valid YAML
    (d / "loki.yaml").write_text(LOKI_YAML)
    ds = {"apiVersion": 1, "datasources": [
        {"name": "Prometheus", "type": "prometheus", "url": "http://host.docker.internal:9090", "isDefault": True},
        {"name": "Loki", "type": "loki", "url": "http://host.docker.internal:3100"}]}
    (d / "grafana" / "datasources.yaml").write_text(json.dumps(ds, indent=2))
    return d


def catalog(cfg: dict) -> dict:
    d = write_configs(cfg)
    return {
        "prometheus": _docker("prometheus", "prom/prometheus:latest", ["9090:9090"],
                              [f"{d}/prometheus.yml:/etc/prometheus/prometheus.yml:ro"],
                              args="--config.file=/etc/prometheus/prometheus.yml --web.enable-remote-write-receiver",
                              health={"url": "http://localhost:9090/-/ready"}),
        "loki": _docker("loki", "grafana/loki:latest", ["3100:3100"], [f"{d}/loki.yaml:/etc/loki/local-config.yaml:ro"],
                        args="-config.file=/etc/loki/local-config.yaml", health={"url": "http://localhost:3100/ready"}),
        "grafana": _docker("grafana", "grafana/grafana:latest", ["3000:3000"],
                           [f"{d}/grafana/datasources.yaml:/etc/grafana/provisioning/datasources/ds.yaml:ro"],
                           env=["GF_AUTH_ANONYMOUS_ENABLED=true", "GF_AUTH_ANONYMOUS_ORG_ROLE=Admin"],
                           health={"url": "http://localhost:3000/api/health"}),
        "postgres": _docker("postgres", "postgres:16", ["5432:5432"], env=["POSTGRES_PASSWORD=postgres"],
                            health={"port": 5432}),
    }
