"""HTTP stub of the fixture shop API (same contract as tests/fixtures/shop sources), strict about relationships:
an order needs an existing customer, a payment an existing order - so only correctly threaded workflows pass."""
import json
import re
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CUSTOMERS, ORDERS = {}, {}


class H(BaseHTTPRequestHandler):
    def _send(self, code, body=None):
        out = b"" if body is None else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_POST(self):
        p = self.path.split("?")[0]
        b = self._body()
        if p == "/api/customers":
            if not b.get("email") or "@" not in b["email"] or not b.get("firstName"):
                return self._send(400, {"error": "invalid customer"})
            c = {"id": str(uuid.uuid4()), **{k: b.get(k) for k in ("firstName", "lastName", "email", "countryCode", "tier")}}
            CUSTOMERS[c["id"]] = c
            return self._send(201, c)
        if p == "/api/orders":
            if b.get("customerId") not in CUSTOMERS:
                return self._send(422, {"error": f"unknown customer {b.get('customerId')}"})
            if not b.get("lines"):
                return self._send(400, {"error": "no lines"})
            o = {"id": str(uuid.uuid4()), "customerId": b["customerId"], "status": "NEW", "lines": b["lines"],
                 "total": sum(int(l.get("quantity", 1)) * 9.5 for l in b["lines"])}
            ORDERS[o["id"]] = o
            return self._send(200, o)
        m = re.fullmatch(r"/api/orders/([^/]+)/payments", p)
        if m:
            if m.group(1) not in ORDERS:
                return self._send(404, {"error": "no such order"})
            return self._send(200, {"id": str(uuid.uuid4()), "orderId": m.group(1), "status": "CAPTURED"})
        self._send(404, {"error": "not found"})

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/api/customers":
            return self._send(200, list(CUSTOMERS.values()))
        m = re.fullmatch(r"/api/(customers|orders)/([^/]+)", p)
        if m:
            store = CUSTOMERS if m.group(1) == "customers" else ORDERS
            return self._send(200, store[m.group(2)]) if m.group(2) in store else self._send(404, {"error": "not found"})
        self._send(404, {"error": "not found"})

    def do_DELETE(self):
        m = re.fullmatch(r"/api/orders/([^/]+)", self.path)
        if m and m.group(1) in ORDERS:
            del ORDERS[m.group(1)]
            return self._send(204)
        self._send(404, {"error": "not found"})

    def log_message(self, *a):
        pass


def serve(port=0):
    srv = ThreadingHTTPServer(("127.0.0.1", port), H)
    return srv


if __name__ == "__main__":
    serve(int(sys.argv[1])).serve_forever()
