"""Realistic fake values for request fields (stdlib only, seedable).

Value = rule (set by AI / UI / hand in rules/<api>.json) else a guess from name, format, type and the validation
constraints found at discovery (@Email, @Size, @Min/@Max, @Pattern, enums). Rule keys:
  kind      one of KINDS (email, first_name, full_name, phone, company, city, country, iso_country, street, zip, uuid, date,
            datetime, past_date, future_date, url, word, words, sentence, paragraph, int, decimal, money, bool, currency,
            sku, code, ip, color, username, password, iban, lat, lon)
  enum      [values]          const     value              pattern   "ORD-####-??" (# digit, ? letter, * alnum)
  template  "user{{seq}}@{{word}}.test" (seq, rand, uuid, word, int)
  min/max   numbers / length  pool      "<api>.<response field>"  pick a value captured from real responses
  null_rate 0..1              omit      true (leave the field out)
Linked fields (accepted threads) are filled at run time from the producing step's response; in stored data they keep a
marker {"$link": "<api>.<field>"} or, when the pool already holds real values for that source, one of those values.
"""
from __future__ import annotations

import random
import re
import string
import uuid
import zlib
from datetime import date, datetime, timedelta, timezone

FIRST = "Ava Liam Noah Emma Olivia Mia Lucas Amelia Ethan Sofia Mateo Isla Leo Aria Arjun Priya Yuki Hana Omar Layla Jonas Lena Diego Chloe".split()
LAST = "Smith Johnson Garcia Müller Rossi Silva Kim Nguyen Patel Sato Kowalski Novak Dubois Hansen Jensen Costa Ali Haddad Brown Wilson".split()
CITY = "Lisbon Berlin Austin Toronto Melbourne Osaka Pune Lyon Bergen Porto Utrecht Krakow Denver Seattle Valencia Turin".split()
COUNTRY = [("Portugal", "PT"), ("Germany", "DE"), ("United States", "US"), ("Canada", "CA"), ("Australia", "AU"), ("Japan", "JP"),
           ("India", "IN"), ("France", "FR"), ("Norway", "NO"), ("Netherlands", "NL"), ("Poland", "PL"), ("Spain", "ES"), ("Italy", "IT")]
STREET = "Oak Maple Cedar Harbor Mill River Station Market Church Park Lake Hill".split()
COMPANY = "Acme Globex Initech Umbrella Stark Wayne Hooli Vandelay Soylent Wonka Tyrell Cyberdyne".split()
WORDS = ("alpha beta gamma delta orbit vector signal ledger cargo harbor summit prism ember quartz nimbus cobalt "
         "pixel lumen atlas forge relay canvas anchor beacon cipher drift echo fable glyph").split()
CURRENCY = ["EUR", "USD", "GBP", "JPY", "CHF", "CAD", "AUD", "INR"]
KINDS = {"email", "first_name", "last_name", "full_name", "phone", "company", "city", "country", "iso_country", "street", "zip",
         "uuid", "date", "datetime", "past_date", "future_date", "url", "word", "words", "sentence", "paragraph", "int", "decimal",
         "money", "bool", "currency", "sku", "code", "ip", "color", "username", "password", "iban", "lat", "lon", "time"}


def guess_kind(f: dict) -> str:
    n, fmt, t = re.sub(r"[^a-z0-9]", "", f.get("name", "").lower()), f.get("format", ""), f.get("type", "string")
    if fmt in ("email",):
        return "email"
    if fmt == "uuid":
        return "uuid"
    if fmt == "date":
        return "past_date" if f.get("past") else "future_date" if f.get("future") else "date"
    if fmt == "date-time":
        return "datetime"
    if fmt == "time":
        return "time"
    if fmt in ("uri", "url"):
        return "url"
    if t == "boolean":
        return "bool"
    table = [("email", "email"), ("firstname", "first_name"), ("givenname", "first_name"), ("lastname", "last_name"),
             ("surname", "last_name"), ("familyname", "last_name"), ("fullname", "full_name"), ("username", "username"),
             ("login", "username"), ("password", "password"), ("phone", "phone"), ("mobile", "phone"), ("company", "company"),
             ("organization", "company"), ("city", "city"), ("countrycode", "iso_country"), ("country", "country"),
             ("street", "street"), ("address", "street"), ("zip", "zip"), ("postal", "zip"), ("currency", "currency"),
             ("sku", "sku"), ("iban", "iban"), ("ip", "ip"), ("color", "color"), ("colour", "color"), ("url", "url"),
             ("website", "url"), ("latitude", "lat"), ("longitude", "lon"), ("birth", "past_date"), ("dob", "past_date"),
             ("description", "sentence"), ("comment", "sentence"), ("note", "sentence"), ("message", "sentence"),
             ("text", "sentence"), ("body", "paragraph"), ("title", "words"), ("name", "full_name" if n == "name" else "words"),
             ("code", "code"), ("uuid", "uuid")]
    for key, kind in table:
        if key in n and not (key == "ip" and n not in ("ip", "ipaddress", "clientip")) and not (key == "name" and t != "string"):
            return kind
    if t == "integer":
        return "int"
    if t == "number":
        return "money" if any(k in n for k in ("price", "amount", "total", "cost", "balance", "fee")) else "decimal"
    if n.endswith("at") or n.endswith("date") or n.endswith("time"):
        return "datetime"
    if n.endswith("id"):
        return "uuid"
    return "word"


def _pattern(p: str, rng: random.Random) -> str:
    out = []
    for ch in p:
        out.append(rng.choice(string.digits) if ch == "#" else rng.choice(string.ascii_uppercase) if ch == "?"
                   else rng.choice(string.ascii_uppercase + string.digits) if ch == "*" else ch)
    return "".join(out)


def _from_regex(rx: str, rng: random.Random) -> str | None:
    """A tiny regex sampler for common validation patterns (^[A-Z]{3}-\\d{4}$ style); None when too complex."""
    rx = rx.strip("^$")
    out, i = [], 0
    classes = {r"\d": string.digits, r"\w": string.ascii_letters + string.digits, "[A-Z]": string.ascii_uppercase,
               "[a-z]": string.ascii_lowercase, "[0-9]": string.digits, "[A-Za-z]": string.ascii_letters,
               "[A-Z0-9]": string.ascii_uppercase + string.digits, "[a-z0-9]": string.ascii_lowercase + string.digits,
               "[A-Za-z0-9]": string.ascii_letters + string.digits}
    while i < len(rx):
        tok = next((c for c in classes if rx.startswith(c, i)), None)
        if tok:
            chars, i = classes[tok], i + len(tok)
        elif rx[i] in "()|*+?[]{}^$" or rx[i] == "\\":
            if rx[i] == "\\" and i + 1 < len(rx) and rx[i + 1] in "-._/ ":
                chars, i = rx[i + 1], i + 2
            else:
                return None
        else:
            chars, i = rx[i], i + 1
        n = 1
        m = re.match(r"\{(\d+)(?:,(\d+))?\}|(\+)", rx[i:])
        if m:
            n = rng.randint(int(m.group(1)), int(m.group(2) or m.group(1))) if m.group(1) else rng.randint(1, 6)
            i += len(m.group(0))
        out.append("".join(rng.choice(chars) for _ in range(n)))
    return "".join(out)


def scalar(f: dict, rule: dict, rng: random.Random, seq: int):
    if "const" in rule:
        return rule["const"]
    if rule.get("enum") or f.get("enum"):
        return rng.choice(rule.get("enum") or f["enum"])
    if rule.get("template"):
        return re.sub(r"\{\{(\w+)\}\}", lambda m: {"seq": str(seq), "rand": str(rng.randint(1000, 9999)), "uuid": str(uuid.UUID(int=rng.getrandbits(128))),
                                                    "word": rng.choice(WORDS), "int": str(rng.randint(1, 999))}.get(m.group(1), m.group(0)), rule["template"])
    if rule.get("pattern"):
        return _pattern(rule["pattern"], rng)
    kind = rule.get("kind") or guess_kind(f)
    lo, hi = rule.get("min", f.get("min")), rule.get("max", f.get("max"))
    if kind == "int":
        lo = int(lo) if lo is not None else 1
        hi = int(hi) if hi is not None else max(lo, 1) * 100 if lo and lo > 0 else 100
        return rng.randint(lo, max(lo, hi))
    if kind in ("decimal", "money"):
        lo = float(lo) if lo is not None else (1.0 if kind == "money" else 0.0)
        hi = float(hi) if hi is not None else (999.0 if kind == "money" else 100.0)
        return round(rng.uniform(lo, max(lo, hi)), 2)
    if kind == "bool":
        return rng.random() < 0.5
    if f.get("pattern") and kind in ("word", "code", "words"):
        s = _from_regex(f["pattern"], rng)
        if s is not None:
            return s
    first, last = rng.choice(FIRST), rng.choice(LAST)
    country = rng.choice(COUNTRY)
    today = date(2026, 6, 1)
    v = {
        "email": lambda: f"{first.lower()}.{last.lower()}{seq}@example.test",
        "first_name": lambda: first, "last_name": lambda: last, "full_name": lambda: f"{first} {last}",
        "username": lambda: f"{first.lower()}{seq}", "password": lambda: "Pw-" + "".join(rng.choice(string.ascii_letters + string.digits) for _ in range(12)),
        "phone": lambda: f"+1-555-{rng.randint(100, 999)}-{rng.randint(1000, 9999)}",
        "company": lambda: f"{rng.choice(COMPANY)} {rng.choice(['Ltd', 'GmbH', 'Inc', 'SA', 'BV'])}",
        "city": lambda: rng.choice(CITY), "country": lambda: country[0], "iso_country": lambda: country[1],
        "street": lambda: f"{rng.randint(1, 250)} {rng.choice(STREET)} Street", "zip": lambda: f"{rng.randint(10000, 99999)}",
        "uuid": lambda: str(uuid.UUID(int=rng.getrandbits(128), version=4)),
        "date": lambda: (today + timedelta(days=rng.randint(-365, 365))).isoformat(),
        "past_date": lambda: (today - timedelta(days=rng.randint(365 * 18, 365 * 70))).isoformat(),
        "future_date": lambda: (today + timedelta(days=rng.randint(1, 365))).isoformat(),
        "datetime": lambda: (datetime(2026, 6, 1, tzinfo=timezone.utc) + timedelta(minutes=rng.randint(-500000, 500000))).isoformat().replace("+00:00", "Z"),
        "time": lambda: f"{rng.randint(0, 23):02d}:{rng.choice([0, 15, 30, 45]):02d}:00",
        "url": lambda: f"https://{rng.choice(WORDS)}.example.test/{rng.choice(WORDS)}",
        "word": lambda: rng.choice(WORDS), "words": lambda: " ".join(rng.choice(WORDS).capitalize() for _ in range(rng.randint(2, 3))),
        "sentence": lambda: " ".join(rng.choice(WORDS) for _ in range(rng.randint(6, 12))).capitalize() + ".",
        "paragraph": lambda: " ".join((" ".join(rng.choice(WORDS) for _ in range(9))).capitalize() + "." for _ in range(3)),
        "currency": lambda: rng.choice(CURRENCY), "sku": lambda: _pattern("???-#####", rng), "code": lambda: _pattern("????-###", rng),
        "ip": lambda: f"10.{rng.randint(0, 255)}.{rng.randint(0, 255)}.{rng.randint(1, 254)}",
        "color": lambda: "#%06x" % rng.randint(0, 0xFFFFFF), "iban": lambda: "DE" + "".join(rng.choice(string.digits) for _ in range(20)),
        "lat": lambda: round(rng.uniform(-60, 70), 6), "lon": lambda: round(rng.uniform(-180, 180), 6),
    }.get(kind, lambda: rng.choice(WORDS))()
    if isinstance(v, str):
        mx, mn = rule.get("maxLength", f.get("maxLength")), rule.get("minLength", f.get("minLength"))
        if mx:
            v = v[: int(mx)]
        if mn and len(v) < int(mn):
            v = v + "x" * (int(mn) - len(v))
        if f.get("type") == "integer":
            return rng.randint(1, 1000)
    return v


def _set(tree: dict, path: str, value) -> None:
    parts = path.split(".")
    cur = tree
    for p in parts[:-1]:
        cur = cur.setdefault(p, {})
    cur[parts[-1]] = value


def record(api: dict, rules: dict, links_in: list, pool: dict, rng: random.Random, seq: int) -> dict:
    """One request: {"path": {}, "query": {}, "header": {}, "body": ...}."""
    linked = {l["to"]["field"]: l for l in links_in}
    out = {"path": {}, "query": {}, "header": {}, "body": None}
    body_fields = [f for f in api.get("request", []) if f["in"] == "body"]
    for f in api.get("request", []):
        if f["in"] == "body" or (f["in"] == "header" and not f.get("required") and f["path"] not in rules and f["path"] not in linked):
            continue
        out[f["in"]][f["name"]] = _value(f, rules.get(f["path"], {}), linked.get(f["path"]), pool, rng, seq)
    if body_fields:
        out["body"] = _body(body_fields, rules, linked, pool, rng, seq)
    return out


def _value(f, rule, link, pool, rng, seq):
    if link:
        vals = pool.get(f"{link['from']['api']}.{link['from']['field']}") or []
        return rng.choice(vals) if vals else {"$link": f"{link['from']['api']}.{link['from']['field']}"}
    if rule.get("pool"):
        vals = pool.get(rule["pool"]) or []
        if vals:
            return rng.choice(vals)
    if rule.get("null_rate") and rng.random() < float(rule["null_rate"]):
        return None
    if f.get("type") == "array":
        n = rng.randint(1, 3)
        return [scalar({**f, "type": f.get("items", "string")}, rule, rng, seq + i) for i in range(n)]
    if f.get("type") == "object":
        return {}
    return scalar(f, rule, rng, seq)


def _body(fields, rules, linked, pool, rng, seq):
    """Build the JSON body from flat field paths (body.a.b, body.items[].sku, body[].x)."""
    top_array = all(f["path"].startswith("body[]") for f in fields)
    rel = [(f["path"][len("body[]."):] if top_array else f["path"][len("body."):], f) for f in fields]

    def build(items, depth):
        obj, groups = {}, {}
        for p, f in items:
            head, _, rest = p.partition(".")
            if head.endswith("[]") and rest:
                groups.setdefault(head[:-2], []).append((rest, f))
            elif not rest:
                rule = rules.get(f["path"], {})
                if rule.get("omit") or (not f.get("required") and rule.get("null_rate") == 1):
                    continue
                obj[head.replace("[]", "")] = _value(f, rule, linked.get(f["path"]), pool, rng, seq)
            else:
                groups.setdefault("." + head, []).append((rest, f))
        for k, sub in groups.items():
            if k.startswith("."):
                obj[k[1:]] = build(sub, depth + 1)
            else:
                obj[k] = [build(sub, depth + 1) for _ in range(rng.randint(1, 3))]
        return obj
    if top_array:
        return [build(rel, 0) for _ in range(rng.randint(1, 3))]
    return build(rel, 0)


def generate(api: dict, rules: dict, links_in: list, pool: dict, count: int, seed: int | None = None) -> list:
    rng = random.Random(seed if seed is not None else zlib.crc32(api["id"].encode()))
    return [record(api, rules, links_in, pool, rng, i + 1) for i in range(max(1, min(int(count), 10000)))]
