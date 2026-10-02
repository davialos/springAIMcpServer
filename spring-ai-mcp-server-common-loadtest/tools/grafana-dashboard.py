# Builds src/main/resources/loadtest/grafana/dashboards/k6-load-test.json (the dashboard template GrafanaStack
# writes into every suite). Edit this script, not the JSON:
#   python3 tools/grafana-dashboard.py src/main/resources/loadtest/grafana/dashboards/k6-load-test.json
import json, sys
DS = {"type": "prometheus", "uid": "loadtest-prometheus"}
K = 'testid=~"$testid"'
ids = iter(range(1, 1000))
panels = []
y = 0

def target(expr, legend, ref="A", instant=False):
    t = {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": ref, "range": not instant}
    if instant:
        t["instant"] = True
    return t

def ts(title, targets, unit, x, w, h=8, desc=None, stack=False, max_=None):
    global y
    p = {"id": next(ids), "type": "timeseries", "title": title, "datasource": DS,
         "gridPos": {"x": x, "y": y, "w": w, "h": h}, "targets": targets,
         "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 1, "fillOpacity": 10, "showPoints": "never",
                                                               "stacking": {"mode": "normal" if stack else "none"}}},
                         "overrides": []},
         "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["mean", "max", "lastNotNull"]},
                     "tooltip": {"mode": "multi", "sort": "desc"}}}
    if max_ is not None:
        p["fieldConfig"]["defaults"]["max"] = max_
        p["fieldConfig"]["defaults"]["min"] = 0
    if desc:
        p["description"] = desc
    return p

def stat(title, expr, unit, x, w=4, thresholds=None, desc=None):
    p = {"id": next(ids), "type": "stat", "title": title, "datasource": DS,
         "gridPos": {"x": x, "y": y, "w": w, "h": 4}, "targets": [target(expr, title)],
         "fieldConfig": {"defaults": {"unit": unit, "decimals": 2,
                                      "thresholds": {"mode": "absolute", "steps": thresholds or [{"color": "green", "value": None}]}},
                         "overrides": []},
         "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                     "colorMode": "value", "graphMode": "area", "textMode": "auto"}}
    if desc:
        p["description"] = desc
    return p

def row(title, repeat=None, collapsed=False):
    global y
    r = {"id": next(ids), "type": "row", "title": title, "collapsed": collapsed, "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []}
    if repeat:
        r["repeat"] = repeat
    y += 1
    return r

# ── k6 overview ──
panels.append(row("Load test (k6) — $testid"))
panels += [
    stat("Requests/s", f'sum(irate(k6_http_reqs_total{{{K}}}[$__rate_interval]))', "reqps", 0),
    stat("Failed requests", f'avg(k6_http_req_failed_rate{{{K}}})', "percentunit", 4,
         [{"color": "green", "value": None}, {"color": "orange", "value": 0.01}, {"color": "red", "value": 0.05}]),
    stat("p95 (all APIs)", f'max(k6_http_req_duration_p95{{{K}}})', "s", 8,
         [{"color": "green", "value": None}, {"color": "orange", "value": 0.5}, {"color": "red", "value": 1}]),
    stat("Virtual users", f'sum(k6_vus{{{K}}})', "short", 12),
    stat("Checks passing", f'avg(k6_checks_rate{{{K}}})', "percentunit", 16,
         [{"color": "red", "value": None}, {"color": "orange", "value": 0.95}, {"color": "green", "value": 0.99}]),
    stat("Data received/s", f'sum(irate(k6_data_received_total{{{K}}}[$__rate_interval]))', "Bps", 20),
]
y += 4
panels += [
    ts("Requests/s by API", [target(f'sum by (api) (irate(k6_http_reqs_total{{{K}, api=~"$api"}}[$__rate_interval]))', "{{api}}")], "reqps", 0, 12, stack=True),
    ts("p95 latency by API", [target(f'max by (api) (k6_http_req_duration_p95{{{K}, api=~"$api"}})', "{{api}}")], "s", 12, 12,
       desc="k6 trend statistic p(95) per API (K6_PROMETHEUS_RW_TREND_STATS)."),
]
y += 8
panels += [
    ts("Failed requests by API", [target(f'avg by (api) (k6_http_req_failed_rate{{{K}, api=~"$api"}})', "{{api}}")], "percentunit", 0, 12, max_=1),
    ts("Virtual users and iterations/s", [target(f'sum(k6_vus{{{K}}})', "VUs"),
                                          target(f'sum(irate(k6_iterations_total{{{K}}}[$__rate_interval]))', "iterations/s", "B")], "short", 12, 12),
]
y += 8
# ── per API, repeated ──
panels.append(row("API $api", repeat="api", collapsed=False))
panels += [
    ts("$api — requests/s", [target(f'sum(irate(k6_http_reqs_total{{{K}, api=~"$api"}}[$__rate_interval]))', "requests/s")], "reqps", 0, 8, 6),
    ts("$api — latency", [target(f'max(k6_http_req_duration_p95{{{K}, api=~"$api"}})', "p95"),
                          target(f'max(k6_http_req_duration_p99{{{K}, api=~"$api"}})', "p99", "B"),
                          target(f'max(k6_http_req_duration_avg{{{K}, api=~"$api"}})', "avg", "C")], "s", 8, 8, 6),
    ts("$api — failed", [target(f'avg(k6_http_req_failed_rate{{{K}, api=~"$api"}})', "failed")], "percentunit", 16, 8, 6, max_=1),
]
y += 6
# ── application ──
A = 'job="spring-app"'
panels.append(row("Spring Boot application (/actuator/prometheus)"))
panels += [
    ts("Server requests/s by URI", [target(f'sum by (uri) (rate(http_server_requests_seconds_count{{{A}}}[$__rate_interval]))', "{{uri}}")], "reqps", 0, 12),
    ts("Server latency by URI (avg / max)", [
        target(f'sum by (uri) (rate(http_server_requests_seconds_sum{{{A}}}[$__rate_interval])) / sum by (uri) (rate(http_server_requests_seconds_count{{{A}}}[$__rate_interval]))', "avg {{uri}}"),
        target(f'max by (uri) (http_server_requests_seconds_max{{{A}}})', "max {{uri}}", "B")], "s", 12, 12,
       desc="For percentiles enable management.metrics.distribution.percentiles-histogram.http.server.requests=true and use histogram_quantile on http_server_requests_seconds_bucket."),
]
y += 8
panels += [
    ts("Server errors (4xx/5xx) by URI", [target(f'sum by (uri, status) (rate(http_server_requests_seconds_count{{{A}, status=~"[45].."}}[$__rate_interval]))', "{{status}} {{uri}}")], "reqps", 0, 12),
    ts("Database connection pool (HikariCP)", [target(f'sum by (pool) (hikaricp_connections_active{{{A}}})', "active {{pool}}"),
                                               target(f'sum by (pool) (hikaricp_connections_pending{{{A}}})', "pending {{pool}}", "B"),
                                               target(f'max by (pool) (hikaricp_connections_max{{{A}}})', "max {{pool}}", "C")], "short", 12, 12,
       desc="Pending connections under load mean the pool is the bottleneck."),
]
y += 8
panels += [
    ts("JVM heap", [target(f'sum(jvm_memory_used_bytes{{{A}, area="heap"}})', "used"),
                    target(f'sum(jvm_memory_max_bytes{{{A}, area="heap"}})', "max", "B")], "bytes", 0, 8),
    ts("GC pause time per second", [target(f'sum by (action, cause) (rate(jvm_gc_pause_seconds_sum{{{A}}}[$__rate_interval]))', "{{action}} ({{cause}})")], "s", 8, 8),
    ts("CPU and threads", [target(f'max(process_cpu_usage{{{A}}})', "process CPU"),
                           target(f'max(system_cpu_usage{{{A}}})', "system CPU", "B")], "percentunit", 16, 8, max_=1),
]
y += 8
panels += [
    ts("Threads", [target(f'max(jvm_threads_live_threads{{{A}}})', "live"),
                   target(f'sum(tomcat_threads_busy_threads{{{A}}})', "tomcat busy", "B"),
                   target(f'sum(executor_active_threads{{{A}}})', "executor active", "C")], "short", 0, 12),
    ts("Scrape health", [target(f'up{{{A}}}', "application up")], "short", 12, 12,
       desc="0 means Prometheus cannot reach the application's metrics endpoint (see grafana/prometheus.yml)."),
]

def var(name, query, label, multi):
    return {"name": name, "label": label, "type": "query", "datasource": DS, "refresh": 2, "sort": 2 if name == "testid" else 1,
            "query": {"query": query, "refId": "PrometheusVariableQueryEditor-VariableQuery"}, "definition": query,
            "multi": multi, "includeAll": True, "allValue": ".*",
            "current": {"selected": True, "text": ["All"], "value": ["$__all"]}, "options": [], "hide": 0}

dash = {
    "uid": "${UID}", "title": "${TITLE}", "tags": ["k6", "load-test", "spring-boot"],
    "description": "Generated by spring-ai-mcp-server-common-loadtest: k6 metrics per API (remote write) next to the application's Micrometer metrics. Regenerated with the suite; save changes under another name.",
    "timezone": "browser", "editable": True, "graphTooltip": 1, "refresh": "5s", "schemaVersion": 41, "version": 1,
    "time": {"from": "now-30m", "to": "now"},
    "annotations": {"list": [
        {"builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True, "hide": True,
         "iconColor": "rgba(0, 211, 255, 1)", "name": "Annotations & Alerts", "type": "dashboard"},
        {"datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True, "iconColor": "orange",
         "name": "Load test runs", "target": {"type": "tags", "tags": ["k6"], "limit": 100, "matchAny": True}}]},
    "templating": {"list": [
        var("testid", "label_values(k6_http_reqs_total, testid)", "Test run", True),
        var("api", 'label_values(k6_http_reqs_total{testid=~"$testid"}, api)', "API", True)]},
    "panels": panels,
}
json.dump(dash, open(sys.argv[1], "w"), indent=2)
print(len(panels), "panels")
