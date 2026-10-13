"""Threads between APIs: which response attribute of one API feeds which request attribute of another.

Heuristics only *suggest* (status "suggested", with a confidence and a reason); the AI (through MCP) or the developer
accepts, rejects or adds links - the AI is meant to be the deciding mind, the heuristics are its starting point.
"""
from __future__ import annotations

import hashlib
import re

GENERIC = {"name", "description", "status", "type", "value", "createdat", "updatedat", "created", "updated", "price", "amount",
           "quantity", "title", "note", "notes", "comment", "enabled", "active", "version", "page", "size", "sort", "limit",
           "offset", "text", "message", "label", "language", "locale", "count", "total", "from", "to"}


def link_id(from_api, from_field, to_api, to_field) -> str:
    return hashlib.sha1(f"{from_api}|{from_field}|{to_api}|{to_field}".encode()).hexdigest()[:12]


def singular(w: str) -> str:
    w = w.lower()
    for suf, rep in (("ies", "y"), ("sses", "ss"), ("ses", "s"), ("xes", "x"), ("s", "")):
        if w.endswith(suf) and len(w) > len(suf) + 1:
            return w[: -len(suf)] + rep
    return w


def resource(path: str) -> str:
    """The resource an endpoint is about: last static segment, singular (/api/v1/customers/{id}/orders -> order)."""
    segs = [s for s in path.strip("/").split("/") if s and not s.startswith("{") and not re.fullmatch(r"v\d+|api", s)]
    return singular(segs[-1]) if segs else ""


def _norm(n: str) -> str:
    return re.sub(r"[^a-z0-9]", "", n.lower())


def _consumer_noun(api: dict, f: dict) -> str | None:
    """customerId -> customer; {id} in /orders/{id} -> order; {orderId} -> order; body.id -> resource of the API."""
    n = f["name"]
    m = re.fullmatch(r"(\w+?)_?(Id|ID|id|Uuid|UUID|uuid|Code|code|Key|key)", n)
    if m and m.group(1).lower() not in ("", "parent"):
        return singular(m.group(1))
    if _norm(n) in ("id", "uuid", "code", "key") and f["in"] == "body":
        segs = [x for x in f["path"].split(".")[1:] if x not in ("", "[]")]
        if len(segs) > 1:  # body.type.id -> type
            return singular(segs[-2].replace("[]", ""))
        return None
    if _norm(n) in ("id", "uuid", "code", "key") and f["in"] == "path":
        segs = api["path"].split("/")
        for i, s in enumerate(segs):
            if s == "{" + n + "}" and i > 0 and not segs[i - 1].startswith("{"):
                return singular(segs[i - 1])
    return None


def suggest(apis: list) -> list:
    out = {}
    producers = []  # (api, response field, noun, score base)
    for a in apis:
        noun = resource(a["path"])
        for f in a.get("response", []):
            leaf = _norm(f["name"])
            segs = [x for x in f["path"].split(".") if x not in ("", "[]")]
            if leaf in ("id", "uuid", "code", "key") and len(segs) > 1:
                # nested x.id belongs to x (pet.type.id is a pet *type* id, not a pet id)
                producers.append((a, f, singular(segs[-2].replace("[]", "")), 0.55, leaf))
            elif leaf in ("id", "uuid", "code", "key") and noun:
                is_list = f["path"].startswith("[]") or "[]" in f["path"].split(".")[0]
                base = 0.92 if a["method"] == "POST" and not is_list else 0.7 if is_list else 0.6
                producers.append((a, f, noun, base, leaf))
            m = re.fullmatch(r"(\w+?)_?(id|uuid|code|key)", leaf)
            if m and m.group(1):
                producers.append((a, f, singular(m.group(1)), 0.6, m.group(2)))
    for b in apis:
        for f in b.get("request", []):
            if f["in"] == "header":
                continue
            noun = _consumer_noun(b, f)
            if noun:
                kind = "uuid" if "uuid" in _norm(f["name"]) else "code" if "code" in _norm(f["name"]) else "key" if _norm(f["name"]).endswith("key") else "id"
                for a, rf, pnoun, base, pkind in producers:
                    if a["id"] == b["id"]:
                        continue
                    if pnoun == noun:
                        fuzzy = 0
                    elif len(noun) > 2 and len(pnoun) > 2 and (pnoun.endswith(noun) or noun.endswith(pnoun)):
                        fuzzy = 0.15  # pettype <-> type, customeraccount <-> account
                    else:
                        continue
                    score = base - fuzzy - (0.25 if pkind != kind else 0) - (0.1 if a["method"] == b["method"] == "GET" else 0)
                    if score < 0.35:
                        continue
                    _add(out, a, rf, b, f, round(score, 2), f"{b['id']} needs a {noun} {kind}; {a['method']} {a['path']} returns {rf['path']}")
            elif _norm(f["name"]) not in GENERIC and len(f["name"]) > 2:
                for a in apis:
                    if a["id"] == b["id"]:
                        continue
                    for rf in a.get("response", []):
                        if _norm(rf["name"]) == _norm(f["name"]) and rf.get("type") == f.get("type"):
                            _add(out, a, rf, b, f, 0.45, f"same attribute name '{f['name']}' in {a['id']} response and {b['id']} request")
    # keep the best few producers per consumer field
    by_target = {}
    for l in out.values():
        by_target.setdefault((l["to"]["api"], l["to"]["field"]), []).append(l)
    keep = []
    for ls in by_target.values():
        keep += sorted(ls, key=lambda l: -l["confidence"])[:3]
    return keep


def _add(out, a, rf, b, f, score, reason):
    lid = link_id(a["id"], rf["path"], b["id"], f["path"])
    if lid not in out or out[lid]["confidence"] < score:
        out[lid] = {"id": lid, "from": {"api": a["id"], "field": rf["path"]}, "to": {"api": b["id"], "field": f["path"]},
                    "confidence": score, "status": "suggested", "source": "heuristic", "reason": reason}


def merge(existing: list, suggested: list) -> list:
    """Keep every decided link (accepted/rejected) and every link the AI or a person made; replace the heuristic's own
    undecided suggestions with the new run's (so an improved heuristic does not leave stale suggestions behind)."""
    by = {l["id"]: l for l in existing if not (l.get("status") == "suggested" and l.get("source") == "heuristic")}
    for s in suggested:
        cur = by.get(s["id"])
        if cur is None or (cur.get("status") == "suggested" and cur.get("source") == "heuristic"):
            by[s["id"]] = s
    return list(by.values())


def upsert(existing: list, apis_by_id: dict, from_api: str, from_field: str, to_api: str, to_field: str,
           status: str = "accepted", reason: str = "", source: str = "ai", confidence: float = 1.0, transform: str = "") -> dict:
    if status not in ("accepted", "suggested", "rejected"):
        raise ValueError("status must be accepted, suggested or rejected")
    for api_id, field, side in ((from_api, from_field, "response"), (to_api, to_field, "request")):
        a = apis_by_id.get(api_id)
        if not a:
            raise ValueError(f"unknown API {api_id}")
        if field not in {f["path"] for f in a.get(side, [])}:
            known = ", ".join(f["path"] for f in a.get(side, []))[:600]
            raise ValueError(f"{api_id} has no {side} field '{field}' (known: {known})")
    lid = link_id(from_api, from_field, to_api, to_field)
    link = {"id": lid, "from": {"api": from_api, "field": from_field}, "to": {"api": to_api, "field": to_field},
            "confidence": confidence, "status": status, "source": source, "reason": reason}
    if transform:
        link["transform"] = transform
    existing[:] = [l for l in existing if l["id"] != lid] + [link]
    return link


def accepted_for(links: list, to_api: str) -> list:
    return [l for l in links if l["to"]["api"] == to_api and l.get("status") == "accepted"]
