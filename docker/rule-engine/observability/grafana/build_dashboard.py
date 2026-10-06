#!/usr/bin/env python3
"""Generates dashboards/rule-engine-ops.json (the committed output is what Grafana provisions; re-run after editing).

    python3 build_dashboard.py            # writes dashboards/rule-engine-ops.json
    scripts/rule-engine/check-dashboard.sh  # runs every SQL panel against the local database and every PromQL/LogQL against its API

Panels read: dai_re_evaluation / dai_re_evaluation_result / dai_re_audit_log / re_auth_login_event through the read-only role
(seed/grants.sql), Prometheus metrics of both services, and the container logs in Loki. No input values or message texts exist in
any of these sources (LLD-12, LLD-18 §9).
"""
import json
import pathlib

PG = {"type": "grafana-postgresql-datasource", "uid": "rules-db"}
PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}

panels = []
_id = [0]
_y = [0]


def _next():
    _id[0] += 1
    return _id[0]


def row(title):
    _y[0] += 1
    panels.append({"id": _next(), "type": "row", "title": title, "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": _y[0]}})
    _y[0] += 1


def panel(kind, title, ds, targets, x, w, h=8, y=None, options=None, field=None, desc=None):
    p = {"id": _next(), "type": kind, "title": title, "datasource": ds, "targets": targets,
         "gridPos": {"h": h, "w": w, "x": x, "y": _y[0] if y is None else y},
         "fieldConfig": field or {"defaults": {}, "overrides": []}, "options": options or {}}
    if desc:
        p["description"] = desc
    panels.append(p)
    return p


def sql(ref, q, fmt="table"):
    return {"refId": ref, "datasource": PG, "format": fmt, "rawQuery": True, "editorMode": "code", "rawSql": q}


def prom(ref, expr, legend=""):
    return {"refId": ref, "datasource": PROM, "expr": expr, "legendFormat": legend, "range": True}


def loki(ref, expr, kind="range", legend=""):
    return {"refId": ref, "datasource": LOKI, "expr": expr, "queryType": kind, "legendFormat": legend}


def colored(mapping):
    return {"defaults": {"custom": {"drawStyle": "bars", "fillOpacity": 70, "stacking": {"mode": "normal"}}},
            "overrides": [{"matcher": {"id": "byName", "options": k},
                           "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": v}}]}
                          for k, v in mapping.items()]}


DECISION_COLORS = {"ALLOW": "green", "WARN": "orange", "BLOCK": "red"}
STAT = {"reduceOptions": {"calcs": ["lastNotNull"], "values": False}, "colorMode": "value", "graphMode": "none"}
TS = {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}
TBL = {"showHeader": True, "cellHeight": "sm"}

# ── decisions ────────────────────────────────────────────────────────────────
row("Decisions")
y0 = _y[0]
panel("stat", "Evaluations", PG, [sql("A", "SELECT count(*) AS evaluations FROM dynamic_ai.dai_re_evaluation WHERE $__timeFilter(evaluated_at)")],
      0, 4, 4, y0, STAT)
panel("stat", "BLOCK share", PG, [sql("A", "SELECT COALESCE(round(100.0 * count(*) FILTER (WHERE decision = 'BLOCK') / NULLIF(count(*), 0), 1), 0) AS block_pct FROM dynamic_ai.dai_re_evaluation WHERE $__timeFilter(evaluated_at)")],
      4, 4, 4, y0, STAT | {"colorMode": "value"},
      {"defaults": {"unit": "percent", "thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "orange", "value": 25}, {"color": "red", "value": 50}]}}, "overrides": []})
panel("stat", "p95 evaluation time", PG, [sql("A", "SELECT COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_micros) / 1000.0, 0) AS p95_ms FROM dynamic_ai.dai_re_evaluation WHERE $__timeFilter(evaluated_at)")],
      8, 4, 4, y0, STAT, {"defaults": {"unit": "ms", "decimals": 2}, "overrides": []})
panel("stat", "Rule errors", PG, [sql("A", "SELECT count(*) AS errors FROM dynamic_ai.dai_re_evaluation_result r JOIN dynamic_ai.dai_re_evaluation e ON e.id = r.evaluation_id WHERE r.outcome = 'ERROR' AND $__timeFilter(e.evaluated_at)")],
      12, 4, 4, y0, STAT,
      {"defaults": {"thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "red", "value": 1}]}}, "overrides": []})
panel("stat", "Successful sign-ins", PG, [sql("A", "SELECT count(*) AS logins FROM re_auth.re_auth_login_event WHERE outcome = 'SUCCESS' AND $__timeFilter(occurred_at)")],
      16, 4, 4, y0, STAT)
panel("stat", "Failed / locked logins", PG, [sql("A", "SELECT count(*) AS failed FROM re_auth.re_auth_login_event WHERE outcome <> 'SUCCESS' AND $__timeFilter(occurred_at)")],
      20, 4, 4, y0, STAT,
      {"defaults": {"thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "orange", "value": 5}]}}, "overrides": []})
_y[0] += 4
panel("timeseries", "Decisions over time", PG, [sql("A",
      "SELECT $__timeGroupAlias(evaluated_at, $__interval), decision AS metric, count(*) AS value FROM dynamic_ai.dai_re_evaluation WHERE $__timeFilter(evaluated_at) GROUP BY 1, 2 ORDER BY 1", "time_series")],
      0, 14, 8, options=TS, field=colored(DECISION_COLORS))
panel("barchart", "Decisions by rule group", PG, [sql("A",
      "SELECT g.code AS rule_group, count(*) FILTER (WHERE e.decision = 'ALLOW') AS \"ALLOW\", count(*) FILTER (WHERE e.decision = 'WARN') AS \"WARN\", count(*) FILTER (WHERE e.decision = 'BLOCK') AS \"BLOCK\" FROM dynamic_ai.dai_re_evaluation e JOIN dynamic_ai.dai_re_rule_group g ON g.id = e.rule_group_id WHERE $__timeFilter(e.evaluated_at) GROUP BY 1 ORDER BY 1")],
      14, 10, 8, options={"stacking": "normal", "xField": "rule_group", "xTickLabelRotation": -45, "xTickLabelMaxLength": 14,
                          "legend": {"displayMode": "list", "placement": "bottom"}},
      field={"defaults": {}, "overrides": [{"matcher": {"id": "byName", "options": k},
                                            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": v}}]}
                                           for k, v in DECISION_COLORS.items()]})
_y[0] += 8
panel("table", "Rules that fail or error the most", PG, [sql("A",
      "SELECT ru.code AS rule, count(*) FILTER (WHERE r.outcome = 'FALSE') AS false_results, count(*) FILTER (WHERE r.outcome = 'ERROR') AS errors, count(*) AS evaluated FROM dynamic_ai.dai_re_evaluation_result r JOIN dynamic_ai.dai_re_evaluation e ON e.id = r.evaluation_id JOIN dynamic_ai.dai_re_rule ru ON ru.id = r.rule_id WHERE $__timeFilter(e.evaluated_at) GROUP BY 1 ORDER BY errors DESC, false_results DESC LIMIT 15")],
      0, 12, 8, options=TBL)
panel("table", "Error codes", PG, [sql("A",
      "SELECT r.error_code, count(*) AS n FROM dynamic_ai.dai_re_evaluation_result r JOIN dynamic_ai.dai_re_evaluation e ON e.id = r.evaluation_id WHERE r.outcome = 'ERROR' AND $__timeFilter(e.evaluated_at) GROUP BY 1 ORDER BY 2 DESC")],
      12, 12, 8, options=TBL, desc="COMPILE_ERROR, MISSING_PARAMETER, INVALID_PARAMETER, NOT_BOOLEAN, EVALUATION_ERROR (LLD-18 §3). Codes only: no values are stored.")
_y[0] += 8

# ── audit and logins ─────────────────────────────────────────────────────────
row("Who changed what")
panel("table", "Authoring audit trail", PG, [sql("A",
      "SELECT occurred_at AS time, actor_name AS actor, actor_role AS role, action, entity_type AS type, entity_code AS entity, summary FROM dynamic_ai.dai_re_audit_log WHERE $__timeFilter(occurred_at) ORDER BY occurred_at DESC LIMIT 200")],
      0, 16, 9, options=TBL)
panel("piechart", "Actions", PG, [sql("A",
      "SELECT action, count(*) AS n FROM dynamic_ai.dai_re_audit_log WHERE $__timeFilter(occurred_at) GROUP BY 1 ORDER BY 2 DESC")],
      16, 8, 9, options={"legend": {"displayMode": "table", "placement": "right", "values": ["value"]}, "pieType": "donut",
              "reduceOptions": {"values": True, "calcs": [], "fields": "/^n$/"}})
_y[0] += 9
panel("timeseries", "Sign-in attempts", PG, [sql("A",
      "SELECT $__timeGroupAlias(occurred_at, $__interval), outcome AS metric, count(*) AS value FROM re_auth.re_auth_login_event WHERE $__timeFilter(occurred_at) GROUP BY 1, 2 ORDER BY 1", "time_series")],
      0, 12, 8, options=TS, field=colored({"SUCCESS": "green", "BAD_CREDENTIALS": "orange", "LOCKED": "red", "DISABLED": "purple"}))
panel("table", "Recent sign-ins", PG, [sql("A",
      "SELECT occurred_at AS time, username, outcome, remote_addr FROM re_auth.re_auth_login_event WHERE $__timeFilter(occurred_at) ORDER BY occurred_at DESC LIMIT 50")],
      12, 12, 8, options=TBL)
_y[0] += 8

# ── service metrics ──────────────────────────────────────────────────────────
row("Services")
R = 'application=~"$app", uri!~"/actuator.*"'
panel("timeseries", "Requests per second", PROM, [prom("A", f'sum by (application, status) (rate(http_server_requests_seconds_count{{{R}}}[1m]))', "{{application}} {{status}}")],
      0, 8, 8, options=TS, field={"defaults": {"unit": "reqps"}, "overrides": []})
panel("timeseries", "Latency p95 / p99", PROM, [
      prom("A", f'histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket{{{R}}}[2m])))', "p95 {{application}}"),
      prom("B", f'histogram_quantile(0.99, sum by (le, application) (rate(http_server_requests_seconds_bucket{{{R}}}[2m])))', "p99 {{application}}")],
      8, 8, 8, options=TS, field={"defaults": {"unit": "s"}, "overrides": []})
panel("timeseries", "5xx responses", PROM, [prom("A", f'sum by (application) (rate(http_server_requests_seconds_count{{{R}, status=~"5.."}}[1m]))', "{{application}}")],
      16, 8, 8, options=TS, field={"defaults": {"unit": "reqps", "color": {"mode": "fixed", "fixedColor": "red"}}, "overrides": []})
_y[0] += 8
panel("timeseries", "JVM heap used", PROM, [prom("A", 'sum by (application) (jvm_memory_used_bytes{application=~"$app", area="heap"})', "{{application}}")],
      0, 12, 7, options=TS, field={"defaults": {"unit": "bytes"}, "overrides": []})
panel("timeseries", "Database connections (active)", PROM, [prom("A", 'hikaricp_connections_active{application=~"$app"}', "{{application}}")],
      12, 12, 7, options=TS)
_y[0] += 7

# ── logs ─────────────────────────────────────────────────────────────────────
row("Logs")
panel("timeseries", "Log volume by level", LOKI, [loki("A", 'sum by (level) (count_over_time({service=~"$service"}[$__interval]))', legend="{{level}}")],
      0, 24, 6, options=TS,
      field=colored({"ERROR": "red", "WARN": "orange", "INFO": "blue", "DEBUG": "purple"}))
_y[0] += 6
panel("logs", "Container logs", LOKI, [loki("A", '{service=~"$service"} |~ "$search"', "range")],
      0, 24, 12, options={"showTime": True, "wrapLogMessage": True, "enableLogDetails": True, "sortOrder": "Descending", "dedupStrategy": "none"},
      desc="Structured JSON log lines of the two services and the web server's access log. Request bodies, fact values and message texts are never logged.")
_y[0] += 12

dash = {
    "uid": "rule-engine-ops",
    "title": "Rule engine — operations",
    "tags": ["rule-engine", "logs"],
    "timezone": "browser",
    "schemaVersion": 39,
    "version": 1,
    "refresh": "30s",
    "time": {"from": "now-7d", "to": "now"},
    "templating": {"list": [
        {"name": "app", "label": "Application", "type": "query", "datasource": PROM, "query": "label_values(http_server_requests_seconds_count, application)",
         "refresh": 2, "includeAll": True, "allValue": ".+", "multi": True, "current": {"text": "All", "value": "$__all"}},
        {"name": "service", "label": "Log source", "type": "query", "datasource": LOKI, "query": "label_values(service)",
         "refresh": 2, "includeAll": True, "allValue": ".+", "multi": True, "current": {"text": "All", "value": "$__all"}},
        {"name": "search", "label": "Log filter", "type": "textbox", "query": "", "current": {"text": "", "value": ""}},
    ]},
    "annotations": {"list": []},
    "panels": panels,
}

out = pathlib.Path(__file__).parent / "dashboards" / "rule-engine-ops.json"
out.write_text(json.dumps(dash, indent=2) + "\n")
print(f"wrote {out} ({len(panels)} panels)")
