"""AI assistant inside the dashboard: Claude with the same tools the MCP server exposes (tools.py).

Manual tool-use loop so the UI can gate side effects: read tools run at once; when Claude asks for a write/destroy
tool the turn pauses and the developer approves or declines in the dashboard (``ai.confirm_writes``, default on).
Conversations live in memory in the dashboard process (local, single user); history is only ever appended to.

Needs the official SDK (``pip install anthropic``, Python >= 3.10 - ``devctl setup --with-ai`` creates local-dev/.venv)
and credentials: ANTHROPIC_API_KEY in the dashboard's environment, or an ``ant auth login`` profile.
"""
from __future__ import annotations

import os
import threading
import time
import uuid
from pathlib import Path

from . import config, mcp, tools

FALLBACK_MODELS = {"claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5"}
MAX_RESULT_CHARS = 20_000
_SESSIONS: dict = {}
_LOCK = threading.Lock()


def settings(cfg: dict) -> dict:
    s = {"enabled": True, "model": "claude-opus-5-5", "effort": "medium", "confirm_writes": True, "max_steps": 15,
         "fallbacks": True, "max_tokens": 16000}
    s.update(cfg.get("ai", {}))
    return s


def status(cfg: dict) -> dict:
    s = settings(cfg)
    try:
        import anthropic  # noqa: F401
        sdk = True
    except ImportError:
        sdk = False
    creds = bool(os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN")
                 or os.environ.get("ANTHROPIC_PROFILE") or (Path.home() / ".config" / "anthropic").exists())
    hint = None
    if not sdk:
        hint = "Install the SDK: local-dev/bin/devctl setup --with-ai (or pip install anthropic, Python >= 3.10), then restart the dashboard (devctl dashboard stop && devctl dashboard start)."
    elif not creds:
        hint = "No Anthropic credentials found: export ANTHROPIC_API_KEY=... (or run `ant auth login`) and restart the dashboard (devctl dashboard stop && devctl dashboard start)."
    return {"enabled": s["enabled"], "sdk": sdk, "credentials": creds, "model": s["model"], "effort": s["effort"],
            "confirm_writes": s["confirm_writes"], "ready": s["enabled"] and sdk and creds, "hint": hint}


def _system(cfg: dict) -> str:
    svc = ", ".join(f"{n} ({s.get('kind', 'jar')})" for n, s in cfg["services"].items()) or "none configured yet"
    return (mcp.INSTRUCTIONS + "\n\nYou are the assistant inside the devctl web dashboard. When you call a write or destroy tool, "
            "the dashboard asks the developer to approve it - do not ask for permission in text, just make the call and explain "
            "why in one sentence. Be concise; use short markdown (lists, `code`). Quote log lines and file:line hot spots exactly."
            f"\n\nConfigured services: {svc}. Stacks: {', '.join(cfg['stacks']) or 'none'}. "
            f"Load tests: {', '.join(cfg.get('loadtests', {})) or 'none'}.")


def _tool_defs() -> list:
    return [{"name": n, "description": t["description"], "input_schema": tools.schema(n)} for n, t in tools.TOOLS.items()]


def _client():
    import anthropic  # optional dependency, imported only when the assistant is used
    return anthropic.Anthropic()


def _session(sid: str | None) -> tuple:
    with _LOCK:
        now = time.time()
        for k in [k for k, v in _SESSIONS.items() if now - v["last"] > 7200]:
            del _SESSIONS[k]
        if not sid or sid not in _SESSIONS:
            sid = uuid.uuid4().hex
            _SESSIONS[sid] = {"messages": [], "pending": None, "last": now, "busy": False}
        _SESSIONS[sid]["last"] = now
        return sid, _SESSIONS[sid]


def chat(cfg: dict, sid: str | None, text: str, client=None) -> dict:
    sid, sess = _session(sid)
    if sess["pending"]:
        return {"session": sid, "events": [{"type": "error", "text": "Approve or decline the pending action first."}], "done": False}
    if not text.strip():
        raise ValueError("empty message")
    msgs = sess["messages"]
    if msgs and msgs[-1]["role"] == "user" and isinstance(msgs[-1]["content"], list):
        msgs[-1]["content"].append({"type": "text", "text": text})  # unanswered tool results at the tail: same turn
    else:
        msgs.append({"role": "user", "content": text})
    return _run(cfg, sid, sess, client)


def decide(cfg: dict, sid: str, approve: bool, client=None) -> dict:
    sid, sess = _session(sid)
    pending = sess["pending"]
    if not pending:
        return {"session": sid, "events": [{"type": "error", "text": "nothing pending"}], "done": True}
    sess["pending"] = None
    events: list = []
    results = []
    for b in pending:
        if approve or tools.TOOLS.get(b.name, {}).get("effect") == "read":
            results.append(_execute(b, events))
        else:
            events.append({"type": "result", "name": b.name, "text": "declined by the developer", "error": True})
            results.append({"type": "tool_result", "tool_use_id": b.id, "content": "The developer declined this action.",
                            "is_error": True})
    sess["messages"].append({"role": "user", "content": results})
    out = _run(cfg, sid, sess, client)
    out["events"] = events + out["events"]
    return out


def reset(sid: str) -> None:
    with _LOCK:
        _SESSIONS.pop(sid, None)


def _execute(block, events: list) -> dict:
    events.append({"type": "tool", "name": block.name, "input": block.input,
                   "effect": tools.TOOLS.get(block.name, {}).get("effect", "read")})
    text, is_err = tools.call(block.name, dict(block.input or {}))
    if len(text) > MAX_RESULT_CHARS:
        text = text[:MAX_RESULT_CHARS] + f"\n... [truncated, {len(text)} chars]"
    events.append({"type": "result", "name": block.name, "text": text[:1500], "error": is_err})
    res = {"type": "tool_result", "tool_use_id": block.id, "content": text}
    if is_err:
        res["is_error"] = True
    return res


def _run(cfg: dict, sid: str, sess: dict, client=None) -> dict:
    s = settings(cfg)
    events: list = []
    if not s["enabled"]:
        return {"session": sid, "events": [{"type": "error", "text": "AI assistant disabled (ai.enabled=false)"}], "done": True}
    try:
        client = client or _client()
    except ImportError:
        sess["messages"].pop()
        return {"session": sid, "events": [{"type": "error", "text": status(cfg)["hint"]}], "done": True}
    kw = {}
    if s["fallbacks"] and s["model"] in FALLBACK_MODELS:
        kw = {"betas": ["server-side-fallback-2026-07-01"], "fallbacks": "default"}
    defs, system = _tool_defs(), _system(cfg)
    for _ in range(int(s["max_steps"])):
        try:
            resp = client.beta.messages.create(
                model=s["model"], max_tokens=int(s["max_tokens"]), system=system, tools=defs, messages=sess["messages"],
                output_config={"effort": s["effort"]}, cache_control={"type": "ephemeral"}, **kw)
        except Exception as e:  # auth, rate limit, network: report, keep history consistent
            if sess["messages"] and sess["messages"][-1]["role"] == "user" and isinstance(sess["messages"][-1]["content"], str):
                sess["messages"].pop()
            events.append({"type": "error", "text": f"{type(e).__name__}: {e}"})
            return {"session": sid, "events": events, "done": True}
        if resp.stop_reason == "refusal":
            if isinstance(sess["messages"][-1]["content"], str):
                sess["messages"].pop()
            events.append({"type": "error", "text": "Claude declined this request. Rephrase it or start a new conversation."})
            return {"session": sid, "events": events, "done": True}
        sess["messages"].append({"role": "assistant", "content": resp.content})
        for b in resp.content:
            if b.type == "text" and b.text.strip():
                events.append({"type": "text", "text": b.text})
        uses = [b for b in resp.content if b.type == "tool_use"]
        if resp.stop_reason != "tool_use" or not uses:
            if resp.stop_reason == "max_tokens":
                events.append({"type": "error", "text": "answer cut off (max_tokens) - ask me to continue"})
            return {"session": sid, "events": events, "done": True}
        if s["confirm_writes"] and any(tools.TOOLS.get(b.name, {}).get("effect", "read") != "read" for b in uses):
            sess["pending"] = uses
            events.append({"type": "pending", "calls": [{"name": b.name, "input": b.input,
                                                         "effect": tools.TOOLS.get(b.name, {}).get("effect", "read")} for b in uses]})
            return {"session": sid, "events": events, "done": False}
        sess["messages"].append({"role": "user", "content": [_execute(b, events) for b in uses]})
    events.append({"type": "error", "text": f"stopped after {s['max_steps']} steps - say 'continue' to go on"})
    return {"session": sid, "events": events, "done": True}
