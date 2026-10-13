"""API discovery for the lab: OpenAPI (running service), Spring actuator mappings, or the repository's Java sources.

Every API is normalised to {id, method, path, summary, source, request: [field], response: [field]}; a field is
{path, name, in, type, format?, required?, enum?, min?, max?, minLength?, maxLength?, pattern?}. Request field paths are
``path.<p>``, ``query.<p>``, ``header.<p>``, ``body.<a>.<b>`` (``[]`` marks array items); response paths are relative
to the JSON body (``id``, ``items[].sku``; ``[].id`` when the body itself is an array).
"""
from __future__ import annotations

import json
import re
import urllib.request
from pathlib import Path

SKIP = re.compile(r"^/(actuator|error|v3/api-docs|swagger-ui|webjars)(/|$)")
MAX_DEPTH = 5


def api_id(method: str, path: str, op_id: str = "") -> str:
    if op_id and re.fullmatch(r"[A-Za-z0-9._-]{1,80}", op_id):
        return op_id
    slug = re.sub(r"[{}]", "", path).strip("/").replace("/", "_")
    return re.sub(r"[^A-Za-z0-9_]+", "_", f"{method.lower()}_{slug or 'root'}")[:80]


# ---------------------------------------------------------------- OpenAPI

def from_openapi(doc: dict) -> list:
    comps = (doc.get("components") or {}).get("schemas") or doc.get("definitions") or {}

    def deref(s, seen=()):
        while isinstance(s, dict) and "$ref" in s:
            name = s["$ref"].rsplit("/", 1)[-1]
            if name in seen:
                return {"type": "object"}
            seen = seen + (name,)
            s = comps.get(name, {"type": "object"})
        if isinstance(s, dict) and ("allOf" in s):
            merged = {"type": "object", "properties": {}, "required": []}
            for part in s["allOf"]:
                p = deref(part, seen)
                merged["properties"].update(p.get("properties", {}))
                merged["required"] += p.get("required", [])
            return merged
        if isinstance(s, dict) and ("oneOf" in s or "anyOf" in s):
            return deref((s.get("oneOf") or s.get("anyOf"))[0], seen)
        return s or {}

    def flatten(s, prefix, loc, required, depth, out):
        s = deref(s)
        t = s.get("type") or ("object" if "properties" in s else "string")
        if t == "object" and s.get("properties") and depth < MAX_DEPTH:
            req = set(s.get("required", []))
            for k, v in s["properties"].items():
                flatten(v, f"{prefix}.{k}" if prefix else k, loc, k in req, depth + 1, out)
            return
        if t == "array" and depth < MAX_DEPTH:
            items = deref(s.get("items", {}))
            it = items.get("type") or ("object" if "properties" in items else "string")
            if it == "object":
                flatten(items, f"{prefix}[]", loc, required, depth + 1, out)
                return
            f = _field(prefix, loc, "array", required, items)
            f["items"] = it
            out.append(f)
            return
        if prefix:
            out.append(_field(prefix, loc, t, required, s))

    out = []
    for path, ops in (doc.get("paths") or {}).items():
        if SKIP.match(path):
            continue
        for method, op in ops.items():
            if method.upper() not in ("GET", "POST", "PUT", "PATCH", "DELETE"):
                continue
            if re.match(r"redirectToSwagger", op.get("operationId", "")) or (path == "/" and not op.get("requestBody")):
                continue  # springdoc lists its own "/" redirect under every method
            req = []
            for p in (op.get("parameters") or []) + (ops.get("parameters") or []):
                p = deref(p)
                if p.get("in") in ("path", "query", "header"):
                    f = _field(f"{p['in']}.{p['name']}", p["in"], deref(p.get("schema", {})).get("type", "string"),
                               p.get("required", p.get("in") == "path"), deref(p.get("schema", {})))
                    req.append(f)
            body = (((op.get("requestBody") or {}).get("content") or {}).get("application/json") or {}).get("schema")
            if body:
                b = []
                flatten(body, "", "body", True, 0, b)
                req += [{**f, "path": "body." + f["path"] if not f["path"].startswith("[]") else "body" + f["path"]} for f in b]
            resp, status = [], None
            for code in ("200", "201", "202", "default"):
                r = (op.get("responses") or {}).get(code)
                if r:
                    sch = ((deref(r).get("content") or {}).get("application/json") or (deref(r).get("content") or {}).get("*/*") or {}).get("schema")
                    if sch:
                        flatten(sch, "", "response", False, 0, resp)
                        status = int(code) if code.isdigit() else None
                        break
            out.append({"id": api_id(method, path, op.get("operationId", "")), "method": method.upper(), "path": path,
                        "summary": op.get("summary") or op.get("operationId") or "", "source": "openapi",
                        "request": req, "response": resp, "status": status})
    return _dedupe(out)


def _field(path: str, loc: str, t: str, required: bool, s: dict) -> dict:
    f = {"path": path, "name": re.sub(r"\[\]$", "", path.rsplit(".", 1)[-1]), "in": loc, "type": t or "string", "required": bool(required)}
    for k_src, k_dst in (("format", "format"), ("enum", "enum"), ("minimum", "min"), ("maximum", "max"), ("minLength", "minLength"),
                         ("maxLength", "maxLength"), ("pattern", "pattern"), ("example", "example")):
        if isinstance(s, dict) and s.get(k_src) is not None:
            f[k_dst] = s[k_src]
    return f


def _dedupe(items: list) -> list:
    seen, out = {}, []
    for a in items:
        base, n = a["id"], 2
        while a["id"] in seen:
            a["id"] = f"{base}_{n}"
            n += 1
        seen[a["id"]] = 1
        out.append(a)
    return out


# ---------------------------------------------------------------- actuator mappings

def from_mappings(doc: dict) -> list:
    out = []
    for ctx in doc.get("contexts", {}).values():
        for servlet in ctx.get("mappings", {}).get("dispatcherServlets", {}).values():
            for m in servlet:
                cond = (m.get("details") or {}).get("requestMappingConditions") or {}
                for path in cond.get("patterns", []):
                    if SKIP.match(path):
                        continue
                    for method in cond.get("methods") or ["GET"]:
                        req = [_field(f"path.{p}", "path", "string", True, {}) for p in re.findall(r"\{([^}:]+)", path)]
                        out.append({"id": api_id(method, path), "method": method, "path": path, "summary": m.get("handler", "")[:120],
                                    "source": "actuator-mappings", "request": req, "response": []})
    return _dedupe(out)


# ---------------------------------------------------------------- Java sources (Spring MVC controllers + DTOs)

SCALARS = {"String": "string", "CharSequence": "string", "char": "string", "Character": "string",
           "int": "integer", "Integer": "integer", "long": "integer", "Long": "integer", "short": "integer", "Short": "integer",
           "byte": "integer", "BigInteger": "integer", "double": "number", "Double": "number", "float": "number", "Float": "number",
           "BigDecimal": "number", "boolean": "boolean", "Boolean": "boolean"}
FORMATS = {"UUID": ("string", "uuid"), "LocalDate": ("string", "date"), "LocalDateTime": ("string", "date-time"),
           "OffsetDateTime": ("string", "date-time"), "ZonedDateTime": ("string", "date-time"), "Instant": ("string", "date-time"),
           "Date": ("string", "date-time"), "LocalTime": ("string", "time"), "URI": ("string", "uri"), "URL": ("string", "uri")}
WRAPPERS = {"ResponseEntity", "Optional", "Mono", "Flux", "CompletableFuture", "CompletionStage", "Callable", "DeferredResult",
            "HttpEntity", "EntityModel"}
COLLECTIONS = {"List", "Set", "Collection", "Iterable", "Page", "Slice", "Stream", "CollectionModel", "PagedModel"}
MAPPING = re.compile(r"@(Get|Post|Put|Patch|Delete|Request)Mapping\s*(\(([^)]*)\))?")
ANNO = re.compile(r"@(\w+)(\s*\((?:[^()]|\([^()]*\))*\))?")


def _strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    return re.sub(r"//[^\n]*", " ", src)


CONSTS: dict = {}  # String constants of the scanned sources (mapping paths are often constants in generated code)


def _resolve(v: str) -> str:
    v = re.sub(r"\$\{[^:}]+:([^}]*)\}", r"\1", v)  # ${property:default} -> default
    return re.sub(r"\$\{[^}]*\}", "", v)


def _anno_value(args: str | None, *keys: str) -> str | None:
    if not args:
        return None
    for k in keys:
        m = re.search(rf"\b{k}\s*=\s*\{{?\s*\"([^\"]*)\"", args)
        if m:
            return _resolve(m.group(1))
        m = re.search(rf"\b{k}\s*=\s*\{{?\s*([A-Za-z_][\w.]*)", args)
        if m and m.group(1).split(".")[-1] in CONSTS:
            return _resolve(CONSTS[m.group(1).split(".")[-1]])
    m = re.match(r"\s*\(?\s*\{?\s*\"([^\"]*)\"", args)
    if m:
        return _resolve(m.group(1))
    m = re.match(r"\s*\(?\s*\{?\s*([A-Za-z_][\w.]*)\s*\}?\s*\)?\s*$", args)
    if m and m.group(1).split(".")[-1] in CONSTS:
        return _resolve(CONSTS[m.group(1).split(".")[-1]])
    return None


def _split_params(s: str) -> list:
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out


def _generic(t: str) -> tuple:
    t = re.sub(r"\s+", "", t).replace("final", "")
    m = re.match(r"([\w.]+)<(.*)>$", t)
    if not m:
        return t.split(".")[-1].replace("[]", ""), [], t.endswith("[]")
    return m.group(1).split(".")[-1], _split_params(m.group(2)), False


class JavaIndex:
    """Records, classes and enums of a source tree (by simple name), with field annotations."""

    def __init__(self, roots):
        self.types: dict = {}
        self.enums: dict = {}
        self.files: list = []
        roots = [roots] if isinstance(roots, Path) else roots
        files = []
        for root in roots:
            gen = "generated" in str(root)
            files += [f for f in root.rglob("*.java") if gen or not re.search(r"/(test|build|target)/", str(f))]
        for f in files:
            try:
                src = _strip_comments(f.read_text(errors="replace"))
            except OSError:
                continue
            self.files.append((f, src))
            for c in re.finditer(r"\bString\s+([A-Z][A-Z0-9_]*)\s*=\s*\"([^\"]*)\"\s*;", src):
                CONSTS.setdefault(c.group(1), c.group(2))
            for m in re.finditer(r"\benum\s+(\w+)[^{]*\{([^;}]*)", src):
                self.enums[m.group(1)] = [v.strip().split("(")[0] for v in m.group(2).split(",") if re.match(r"\s*[A-Z_][A-Z0-9_]*", v)]
            for m in re.finditer(r"\brecord\s+(\w+)\s*(<[^>]*>)?\s*\(((?:[^()]|\([^()]*\))*)\)", src):
                fields = []
                for p in _split_params(m.group(3)):
                    annos = " ".join(a.group(0) for a in ANNO.finditer(p))
                    rest = ANNO.sub("", p).strip().split()
                    if len(rest) >= 2:
                        fields.append((rest[-1], " ".join(rest[:-1]), annos))
                self.types[m.group(1)] = fields
            for m in re.finditer(r"\bclass\s+(\w+)[^{]*\{", src):
                body = self._body(src, m.end() - 1)
                fields = []
                for fm in re.finditer(r"((?:@\w+(?:\s*\((?:[^()]|\([^()]*\))*\))?\s*)*)(?:private|protected|public)\s+(?!static)(?:final\s+)?((?:@\w+(?:\([^)]*\))?\s+)*)([\w.<>,\s\[\]?]+?)\s+(\w+)\s*(?:=[^;]*)?;", body):
                    if "(" in fm.group(3):
                        continue
                    fields.append((fm.group(4), fm.group(3).strip(), fm.group(1) + " " + fm.group(2)))  # type-use annos too
                # validation annotations on getters (OpenAPI-generator / Lombok-free DTOs put them there)
                getters = {}
                for gm in re.finditer(r"((?:@\w+(?:\s*\((?:[^()]|\([^()]*\))*\))?\s*)+)public\s+[\w.<>,\s\[\]?]+?\s+(?:get|is)(\w+)\s*\(\s*\)", body):
                    getters[gm.group(2)[:1].lower() + gm.group(2)[1:]] = gm.group(1)
                fields = [(n, t, a + " " + getters.get(n, "")) for n, t, a in fields]
                if fields and m.group(1) not in self.types:
                    self.types[m.group(1)] = fields

    @staticmethod
    def _body(src: str, start: int) -> str:
        depth, i = 0, start
        while i < len(src):
            if src[i] == "{":
                depth += 1
            elif src[i] == "}":
                depth -= 1
                if depth == 0:
                    return src[start + 1:i]
            i += 1
        return src[start + 1:]

    def fields_of(self, jtype: str, prefix: str, loc: str, depth: int = 0, out=None, seen=()) -> list:
        out = [] if out is None else out
        name, args, is_arr = _generic(jtype)
        if name in WRAPPERS and args:
            return self.fields_of(args[0], prefix, loc, depth, out, seen)
        if name in COLLECTIONS and args or is_arr:
            inner = args[0] if args else name
            iname = _generic(inner)[0]
            if iname in SCALARS or iname in FORMATS or iname in self.enums:
                f = self._scalar_field(prefix, loc, inner, "")
                out.append({**f, "type": "array", "items": f["type"]})
                return out
            return self.fields_of(inner, f"{prefix}[]", loc, depth + 1, out, seen)
        if name in ("Map", "HashMap", "Object", "JsonNode", "Void", "void") or depth >= MAX_DEPTH or name in seen:
            if prefix and name not in ("Void", "void"):
                out.append({"path": prefix, "name": prefix.rsplit(".", 1)[-1].replace("[]", ""), "in": loc, "type": "object", "required": False})
            return out
        if name in SCALARS or name in FORMATS or name in self.enums or name not in self.types:
            if prefix:
                out.append(self._scalar_field(prefix, loc, jtype, ""))
            return out
        for fname, ftype, annos in self.types[name]:
            p = f"{prefix}.{fname}" if prefix else fname
            fname_t, fargs, farr = _generic(ftype)
            if fname_t in SCALARS or fname_t in FORMATS or fname_t in self.enums or (fname_t not in self.types and not fargs and not farr):
                out.append(self._scalar_field(p, loc, ftype, annos))
            else:
                self.fields_of(ftype, p, loc, depth + 1, out, seen + (name,))
        return out

    def _scalar_field(self, path: str, loc: str, jtype: str, annos: str) -> dict:
        name = _generic(jtype)[0]
        t, fmt = FORMATS.get(name, (SCALARS.get(name, "string"), None))
        f = {"path": path, "name": path.rsplit(".", 1)[-1].replace("[]", ""), "in": loc, "type": t,
             "required": bool(re.search(r"@(NotNull|NotBlank|NotEmpty)\b", annos)) or name in ("int", "long", "boolean", "double")}
        if fmt:
            f["format"] = fmt
        if name in self.enums:
            f["enum"] = self.enums[name]
        if "@Email" in annos:
            f["format"] = "email"
        for a, keys in (("Size", ("min", "max")), ("Length", ("min", "max"))):
            m = re.search(rf"@{a}\s*\(([^)]*)\)", annos)
            if m:
                for k in keys:
                    v = re.search(rf"\b{k}\s*=\s*(\d+)", m.group(1))
                    if v:
                        f["minLength" if k == "min" else "maxLength"] = int(v.group(1))
        for a, k in (("Min", "min"), ("DecimalMin", "min"), ("Max", "max"), ("DecimalMax", "max")):
            m = re.search(rf"@{a}\s*\(\s*(?:value\s*=\s*)?\"?(-?[\d.]+)", annos)
            if m:
                f[k] = float(m.group(1)) if "." in m.group(1) else int(m.group(1))
        if re.search(r"@Positive\b", annos):
            f.setdefault("min", 1)
        if re.search(r"@PositiveOrZero\b", annos):
            f.setdefault("min", 0)
        m = re.search(r"@Pattern\s*\([^)]*regexp\s*=\s*\"((?:[^\"\\]|\\.)*)\"", annos)
        if m:
            f["pattern"] = m.group(1).encode().decode("unicode_escape")
        if re.search(r"@Past\b", annos):
            f["past"] = True
        if re.search(r"@Future\b", annos):
            f["future"] = True
        return f


METHOD_RX = re.compile(r"((?:@\w+(?:\s*\((?:[^()]|\([^()]*\))*\))?\s*)+)(?:public\s+|protected\s+)?(?:static\s+|default\s+)?"
                       r"([\w.<>,?\s\[\]]+?)\s+(\w+)\s*\(((?:[^()]|\([^()]*\))*)\)\s*(?:throws[^{;]*)?[{;]")
TYPE_RX = re.compile(r"((?:@\w+(?:\s*\((?:[^()]|\([^()]*\))*\))?\s*)*)\b(?:public\s+)?(?:abstract\s+)?(?:final\s+)?(class|interface)\s+(\w+)([^{]*)\{")


def _base(annos: str) -> str:
    rm = re.search(r"@RequestMapping\s*\(((?:[^()]|\([^()]*\))*)\)", annos)
    return (_anno_value(rm.group(1), "value", "path") or "") if rm else ""


def _endpoints(idx: "JavaIndex", body: str) -> list:
    """(method, sub-path, java method name, annotations, return type, params) of every mapped method in a type body."""
    out = []
    for mm in METHOD_RX.finditer(body):
        annos = mm.group(1)
        mp = MAPPING.search(annos)
        if not mp:
            continue
        kind, args = mp.group(1), mp.group(3)
        if kind == "Request":
            mt = re.search(r"RequestMethod\.(\w+)", args or "")
            method = mt.group(1) if mt else "GET"
        else:
            method = kind.upper()
        out.append((method, _anno_value(args, "value", "path") or "", mm.group(3), annos, mm.group(2).strip(), mm.group(4)))
    return out


def _api(idx, repo, f, ctrl_name, base, ep) -> dict | None:
    method, sub, mname, annos, rtype, params = ep
    path = "/" + "/".join(x.strip("/") for x in (base, sub) if x.strip("/"))
    path = re.sub(r"\{(\w+):[^}]*\}", r"{\1}", path)
    if SKIP.match(path):
        return None
    req = []
    for p in _split_params(params):
        pann = " ".join(a.group(0) for a in ANNO.finditer(p))
        rest = ANNO.sub("", p).replace("final ", "").strip().split()
        if len(rest) < 2:
            continue
        ptype, pname = " ".join(rest[:-1]), rest[-1]
        for a, loc in (("PathVariable", "path"), ("RequestParam", "query"), ("RequestHeader", "header")):
            am = re.search(rf"@{a}\s*(\(((?:[^()]|\([^()]*\))*)\))?", pann)
            if am:
                n = _anno_value(am.group(2), "value", "name") or pname
                fld = idx._scalar_field(f"{loc}.{n}", loc, ptype, pann)
                fld["required"] = loc == "path" or not re.search(r"required\s*=\s*false|defaultValue", am.group(2) or "")
                req.append(fld)
        if "@RequestBody" in pann:
            req += [{**x, "path": "body." + x["path"] if not x["path"].startswith("[]") else "body" + x["path"]}
                    for x in idx.fields_of(ptype, "", "body")]
    resp = [x for x in idx.fields_of(rtype, "", "response") if x["path"] not in ("", "[]")]
    req = [x for x in req if x["path"] not in ("body.", "body", "body[]")]
    if path == "/" and not req and not resp:
        return None  # root redirects (e.g. to swagger-ui) are not APIs
    status = 201 if re.search(r"HttpStatus\.CREATED", annos) else None
    ctrl = re.sub(r"(Rest)?(Controller|Resource|Api|Endpoint)(V\d+)?$", "", ctrl_name) or ctrl_name
    op = ctrl[:1].lower() + ctrl[1:] + mname[:1].upper() + mname[1:]
    return {"id": api_id(method, path, op), "method": method, "path": path, "summary": f"{ctrl_name}.{mname}",
            "source": f"java:{f.relative_to(repo) if repo in f.parents else f.name}", "request": req, "response": resp, "status": status}


def from_java(repo: Path) -> list:
    """Spring MVC endpoints from sources: annotated controllers, and API-first controllers that implement interfaces
    (OpenAPI Generator style; generated sources under target/ or build/ are scanned when present)."""
    CONSTS.clear()
    src_roots = [p for p in repo.rglob("src/main/java") if p.is_dir() and "/target/" not in str(p) and "/build/" not in str(p)] or [repo]
    gen_roots = [p for p in (list(repo.glob("target/generated-sources/*")) + list(repo.glob("build/generated/**/java"))
                             + list(repo.glob("*/target/generated-sources/*"))) if p.is_dir()]
    idx = JavaIndex(src_roots + gen_roots)
    interfaces = {}
    for f, src in idx.files:
        for tm in TYPE_RX.finditer(src):
            if tm.group(2) == "interface":
                eps = _endpoints(idx, JavaIndex._body(src, tm.end() - 1))
                if eps:
                    interfaces[tm.group(3)] = (f, _base(tm.group(1)), eps)
    out, seen = [], set()
    for f, src in idx.files:
        if not re.search(r"@(RestController|Controller)\b", src):
            continue
        for tm in TYPE_RX.finditer(src):
            annos, kind, name, header = tm.groups()
            if kind != "class" or not re.search(r"@(RestController|Controller)\b", annos):
                continue
            base = _base(annos)
            eps = [(base, e, f) for e in _endpoints(idx, JavaIndex._body(src, tm.end() - 1))]
            im = re.search(r"\bimplements\s+([\w.,\s<>]+)", header)
            for iname in ([x.strip().split("<")[0].split(".")[-1] for x in im.group(1).split(",")] if im else []):
                if iname in interfaces:
                    ifile, ibase, ieps = interfaces[iname]
                    joined = "/".join(x.strip("/") for x in (base, ibase) if x.strip("/"))
                    eps += [(joined, e, ifile) for e in ieps]
            for b, e, src_file in eps:
                a = _api(idx, repo, src_file, name, b, e)
                if a and (a["method"], a["path"]) not in seen:
                    seen.add((a["method"], a["path"]))
                    out.append(a)
    return _dedupe(out)


# ---------------------------------------------------------------- entry point

def _get_json(url: str):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=5) as r:
        return json.loads(r.read(20_000_000))


def discover(proj: dict, source: str = "auto") -> tuple:
    """Returns (apis, source used). auto: OpenAPI of the running service, then Java sources, then actuator mappings."""
    base, repo = proj.get("base_url", ""), proj.get("repo", "")
    errors = []
    order = {"auto": ["openapi", "source", "mappings"], "openapi": ["openapi"], "source": ["source"], "mappings": ["mappings"]}.get(source)
    if not order:
        raise ValueError("source must be auto, openapi, source or mappings")
    for s in order:
        try:
            if s == "openapi" and base:
                return from_openapi(_get_json(base + "/v3/api-docs")), "openapi"
            if s == "source" and repo:
                found = from_java(Path(repo))
                if found:
                    return found, "source"
                errors.append("source: no Spring controllers found")
            if s == "mappings" and base:
                return from_mappings(_get_json(base + "/actuator/mappings")), "actuator-mappings"
        except Exception as e:
            errors.append(f"{s}: {e}")
    raise ValueError("nothing discovered - " + ("; ".join(errors) or "the project needs a repo or a base_url"))
