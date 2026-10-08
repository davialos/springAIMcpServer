#!/usr/bin/env python3
"""Stand-in for k6 in tests: drives DEMO_URL for ~4 s and writes a k6-style --summary-export file."""
import json, os, signal, sys, time, urllib.request, urllib.error
args = sys.argv[1:]
out = args[args.index("--summary-export") + 1]
stop = []
signal.signal(signal.SIGINT, lambda *a: stop.append(1))
base, n, fail, lat, t_start = os.environ["DEMO_URL"], 0, 0, [], time.time()
end = time.time() + float(os.environ.get("FAKE_K6_SECONDS", "4"))
while time.time() < end and not stop:
    t0 = time.time()
    try:
        urllib.request.urlopen(f"{base}/api/orders/{n % 7}", timeout=5).read()
    except urllib.error.HTTPError:
        fail += 1
    lat.append((time.time() - t0) * 1000)
    n += 1
lat.sort()
el = max(0.001, time.time() - t_start)
p = lambda q: lat[min(len(lat) - 1, int(q * len(lat)))]
print(f"fake k6: {n} requests", flush=True)
json.dump({"metrics": {
    "http_reqs": {"count": n, "rate": n / el},
    "http_req_duration": {"avg": sum(lat) / len(lat), "min": lat[0], "med": p(.5), "max": lat[-1], "p(90)": p(.9), "p(95)": p(.95), "p(99)": p(.99),
                          "thresholds": {"p(95)<500": False}},
    "http_req_failed": {"passes": fail, "fails": n - fail, "value": fail / n, "thresholds": {"rate<0.01": True}},
    "checks": {"passes": n - fail, "fails": fail, "value": (n - fail) / n},
    "vus_max": {"value": 1, "min": 1, "max": 1}, "iterations": {"count": n, "rate": n / el}}}, open(out, "w"))
sys.exit(99 if fail / n >= 0.01 else 0)
