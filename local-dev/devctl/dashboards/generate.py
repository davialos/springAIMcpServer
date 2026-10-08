import json, sys
out = sys.argv[1]
P = {"type": "prometheus", "uid": "prometheus"}
L = {"type": "loki", "uid": "loki"}

def tgt(expr, legend="", ds=P, **kw):
    return {"datasource": ds, "expr": expr, "legendFormat": legend, "refId": kw.pop("ref", "A"), **kw}

_id = [0]
def panel(kind, title, x, y, w, h, targets, ds=P, unit=None, desc="", opts=None, extra=None):
    _id[0] += 1
    p = {"id": _id[0], "type": kind, "title": title, "description": desc, "datasource": ds,
         "gridPos": {"x": x, "y": y, "w": w, "h": h}, "targets": targets,
         "fieldConfig": {"defaults": ({"unit": unit} if unit else {}), "overrides": []}, "options": opts or {}}
    for i, t in enumerate(p["targets"]):
        t["refId"] = chr(65 + i)
    if extra:
        p.update(extra)
    return p

def dash(uid, title, tags, variables, panels, refresh="5s"):
    return {"uid": uid, "title": title, "tags": tags, "schemaVersion": 39, "version": 1, "editable": True,
            "refresh": refresh, "time": {"from": "now-15m", "to": "now"}, "timezone": "browser",
            "templating": {"list": variables}, "annotations": {"list": []}, "panels": panels}

def qvar(name, query, label, ds=P, multi=True):
    return {"name": name, "label": label, "type": "query", "datasource": ds, "query": {"query": query, "refId": "v"},
            "definition": query, "refresh": 2, "includeAll": True, "multi": multi, "allValue": ".*",
            "current": {"selected": True, "text": "All", "value": "$__all"}, "sort": 1}

T = 'testid=~"$testid"'
legend_table = {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull", "max"]}}

# ---------------- k6 ----------------
k6 = dash("devctl-k6", "devctl · k6 load test", ["devctl", "k6"], [qvar("testid", "label_values(k6_vus, testid)", "Test")], [
    panel("stat", "Virtual users", 0, 0, 4, 4, [tgt(f"sum(k6_vus{{{T}}})")], opts={"colorMode": "value"}),
    panel("stat", "Requests / s", 4, 0, 5, 4, [tgt(f"sum(rate(k6_http_reqs_total{{{T}}}[30s]))")], unit="reqps"),
    panel("stat", "p95 latency", 9, 0, 5, 4, [tgt(f"max(k6_http_req_duration_p95{{{T}}})")], unit="s",
          extra={"fieldConfig": {"defaults": {"unit": "s", "thresholds": {"mode": "absolute", "steps": [
              {"color": "green", "value": None}, {"color": "orange", "value": 0.5}, {"color": "red", "value": 1}]}}, "overrides": []}}),
    panel("stat", "Error rate", 14, 0, 5, 4, [tgt(f"avg(k6_http_req_failed_rate{{{T}}})")], unit="percentunit",
          extra={"fieldConfig": {"defaults": {"unit": "percentunit", "thresholds": {"mode": "absolute", "steps": [
              {"color": "green", "value": None}, {"color": "orange", "value": 0.01}, {"color": "red", "value": 0.05}]}}, "overrides": []}}),
    panel("stat", "Checks passed", 19, 0, 5, 4, [tgt(f"avg(k6_checks_rate{{{T}}})")], unit="percentunit"),
    panel("timeseries", "Virtual users", 0, 4, 12, 8, [tgt(f"sum by (testid) (k6_vus{{{T}}})", "{{testid}} VUs")], opts=legend_table),
    panel("timeseries", "Throughput (requests/s)", 12, 4, 12, 8,
          [tgt(f"sum by (testid) (rate(k6_http_reqs_total{{{T}}}[30s]))", "{{testid}} req/s")], unit="reqps", opts=legend_table),
    panel("timeseries", "Response time", 0, 12, 12, 8, [
        tgt(f"max by (testid) (k6_http_req_duration_avg{{{T}}})", "{{testid}} avg"),
        tgt(f"max by (testid) (k6_http_req_duration_p95{{{T}}})", "{{testid}} p95"),
        tgt(f"max by (testid) (k6_http_req_duration_p99{{{T}}})", "{{testid}} p99"),
        tgt(f"max by (testid) (k6_http_req_duration_max{{{T}}})", "{{testid}} max")], unit="s", opts=legend_table),
    panel("timeseries", "Failed requests & checks", 12, 12, 12, 8, [
        tgt(f"avg by (testid) (k6_http_req_failed_rate{{{T}}})", "{{testid}} failed"),
        tgt(f"1 - avg by (testid) (k6_checks_rate{{{T}}})", "{{testid}} checks failing")], unit="percentunit", opts=legend_table),
    panel("timeseries", "Time breakdown (avg)", 0, 20, 12, 8, [
        tgt(f"max(k6_http_req_waiting_avg{{{T}}})", "waiting (TTFB)"),
        tgt(f"max(k6_http_req_connecting_avg{{{T}}})", "connecting"),
        tgt(f"max(k6_http_req_blocked_avg{{{T}}})", "blocked"),
        tgt(f"max(k6_http_req_receiving_avg{{{T}}})", "receiving")], unit="s", opts=legend_table),
    panel("timeseries", "Network", 12, 20, 12, 8, [
        tgt(f"sum(rate(k6_data_received_total{{{T}}}[30s]))", "received"),
        tgt(f"sum(rate(k6_data_sent_total{{{T}}}[30s]))", "sent")], unit="Bps", opts=legend_table),
    panel("timeseries", "Application logs during the test (errors / min per service)", 0, 28, 24, 7,
          [tgt('sum by (service) (count_over_time({job="devctl", service!~"build|log-shipper"} |~ "(?i)(error|exception|fatal)" [1m]))',
               "{{service}}", ds=L)], ds=L, desc="Loki: correlate latency spikes with errors", opts=legend_table),
])

# ---------------- logs ----------------
SV = 'job="devctl", service=~"$service"'
logs = dash("devctl-logs", "devctl · logs", ["devctl", "loki"], [
    qvar("service", 'label_values({job="devctl"}, service)', "Service", ds=L),
    {"name": "search", "label": "Contains", "type": "textbox", "query": "", "current": {"text": "", "value": ""}}],
    [
    panel("timeseries", "Log volume by service (lines/min)", 0, 0, 12, 7,
          [tgt(f'sum by (service) (count_over_time({{{SV}}} [1m]))', "{{service}}", ds=L)], ds=L, opts=legend_table,
          extra={"fieldConfig": {"defaults": {"custom": {"drawStyle": "bars", "stacking": {"mode": "normal"}, "fillOpacity": 60}}, "overrides": []}}),
    panel("timeseries", "Errors & warnings (lines/min)", 12, 0, 12, 7, [
        tgt(f'sum by (service) (count_over_time({{{SV}}} |~ "(?i)(error|exception|fatal|severe)" [1m]))', "{{service}} error", ds=L),
        tgt(f'sum by (service) (count_over_time({{{SV}}} |~ "(?i)(warn)" !~ "(?i)(error|exception)" [1m]))', "{{service}} warn", ds=L)],
        ds=L, opts=legend_table),
    panel("stat", "Error lines (range)", 0, 7, 6, 4,
          [tgt(f'sum(count_over_time({{{SV}}} |~ "(?i)(error|exception|fatal|severe)" [$__range]))', ds=L)], ds=L),
    panel("stat", "Failed builds (range)", 6, 7, 6, 4,
          [tgt('sum(count_over_time({job="devctl", service="build"} |= "BUILD FAILED" [$__range]))', ds=L)], ds=L,
          extra={"fieldConfig": {"defaults": {"thresholds": {"mode": "absolute", "steps": [
              {"color": "green", "value": None}, {"color": "red", "value": 1}]}}, "overrides": []}}),
    panel("stat", "JBoss deployment events (range)", 12, 7, 6, 4,
          [tgt('sum(count_over_time({job="devctl", service="jboss-server"} |~ "WFLYSRV0010|WFLYSRV0027|WFLYSRV0028" [$__range]))', ds=L)], ds=L,
          desc="WFLYSRV0010 deployed, WFLYSRV0028 stopped/undeployed, WFLYSRV0027 starting"),
    panel("stat", "Services logging (range)", 18, 7, 6, 4,
          [tgt(f'count(sum by (service) (count_over_time({{{SV}}} [$__range])))', ds=L)], ds=L),
    panel("logs", "Logs", 0, 11, 24, 14, [tgt(f'{{{SV}}} |~ "(?i)$search"', ds=L)], ds=L,
          opts={"showTime": True, "wrapLogMessage": True, "enableLogDetails": True, "sortOrder": "Descending", "dedupStrategy": "none"}),
    panel("logs", "Errors only", 0, 25, 24, 10, [tgt(f'{{{SV}}} |~ "(?i)(error|exception|fatal|severe)" |~ "(?i)$search"', ds=L)], ds=L,
          opts={"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending"}),
    panel("logs", "Builds", 0, 35, 24, 10, [tgt('{job="devctl", service="build"}', ds=L)], ds=L,
          opts={"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending"}, desc="Build output; label `build` = build id"),
], refresh="10s")

for name, d in (("k6.json", k6), ("logs.json", logs)):
    json.dump(d, open(f"{out}/{name}", "w"), indent=1)
    open(f"{out}/{name}", "a").write("\n")
