"""Local builds: git worktree per (repo, branch) -> build command -> artifacts collected under builds_dir/runs/<id>.

A build runs as a detached ``devctl _run-build`` process that only talks to the filesystem
(meta.json + build.log), so the CLI, the dashboard and the MCP server all see the same truth
and builds survive dashboard restarts.
"""
from __future__ import annotations

import fnmatch
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

from . import config, javahome, repos

RUNNING, SUCCESS, FAILED = "running", "success", "failed"


def _safe(s: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", s)


def runs_dir(cfg: dict) -> Path:
    return config.builds_root(cfg) / "runs"


def _meta_path(cfg, build_id) -> Path:
    if not re.fullmatch(r"[A-Za-z0-9._-]+", build_id):
        raise ValueError("bad build id")
    return runs_dir(cfg) / build_id / "meta.json"


def read_meta(cfg: dict, build_id: str) -> dict:
    p = _meta_path(cfg, build_id)
    if not p.exists():
        raise ValueError(f"unknown build: {build_id}")
    return json.loads(p.read_text())


def _write_meta(cfg, meta) -> None:
    p = _meta_path(cfg, meta["id"])
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(meta, indent=2))
    tmp.replace(p)


def repo_settings(cfg: dict, repo: str) -> dict:
    p = repos.repo_path(cfg, repo)
    system = repos.detect_build_system(p)
    s = {"build_cmd": repos.default_build_cmd(p, system), "artifact_globs": repos.default_artifact_globs(system),
         "java_home": "", "in_place": False, "env": {}}
    s.update(cfg.get("repos", {}).get(repo, {}))
    return s


def start(cfg: dict, repo: str, branch: str, fetch: bool = True) -> dict:
    """Create a build record and spawn the detached runner. Returns the meta (status=running)."""
    repos.repo_path(cfg, repo)  # validates
    if not re.fullmatch(r"[A-Za-z0-9._/@+-]+", branch) or branch.startswith("-"):
        raise ValueError(f"invalid branch name: {branch}")
    build_id = f"{time.strftime('%Y%m%d-%H%M%S')}-{_safe(repo)}-{_safe(branch)}"
    d = runs_dir(cfg) / build_id
    d.mkdir(parents=True, exist_ok=False)
    meta = {"id": build_id, "repo": repo, "branch": branch, "status": RUNNING, "started": time.time(),
            "finished": None, "commit": None, "artifacts": [], "error": None, "fetch": fetch}
    _write_meta(cfg, meta)
    with open(d / "build.log", "ab") as log:
        subprocess.Popen([sys.executable, "-m", "devctl", "_run-build", build_id], stdout=log,
                         stderr=subprocess.STDOUT, start_new_session=True, cwd=str(Path(__file__).resolve().parent.parent),
                         env={**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parent.parent)})
    return meta


def run(cfg: dict, build_id: str) -> int:
    """Runner body (executes inside the detached process; stdout is the build log)."""
    meta = read_meta(cfg, build_id)
    repo, branch = meta["repo"], meta["branch"]
    try:
        src = repos.repo_path(cfg, repo)
        st = repo_settings(cfg, repo)
        if meta.get("fetch"):
            print(f"$ git fetch --prune ({repo})", flush=True)
            subprocess.run(["git", "-C", str(src), "fetch", "--prune", "--quiet"], timeout=180)
        ref = branch
        if subprocess.run(["git", "-C", str(src), "rev-parse", "--verify", "-q", f"refs/heads/{branch}"],
                          capture_output=True).returncode != 0:
            ref = f"origin/{branch}"
        if st["in_place"]:
            if repos.git(src, "status", "--porcelain"):
                raise RuntimeError("in_place build requires a clean working tree")
            repos.git(src, "checkout", branch)
            wt = src
        else:
            wt = config.builds_root(cfg) / "work" / _safe(repo) / _safe(branch)
            if (wt / ".git").exists():
                repos.git(wt, "checkout", "--detach", "--force", ref)
            else:
                wt.parent.mkdir(parents=True, exist_ok=True)
                repos.git(src, "worktree", "add", "--force", "--detach", str(wt), ref)
        meta["commit"] = repos.git(wt, "rev-parse", "--short", "HEAD")
        _write_meta(cfg, meta)
        env = {**os.environ, **{k: str(v) for k, v in st["env"].items()}}
        jh = javahome.resolve(st["java_home"] or cfg["java_home"])
        if jh:
            env["JAVA_HOME"] = jh
            env["PATH"] = f"{jh}/bin:{env.get('PATH', '')}"
        cmd = st["build_cmd"]
        if not cmd:
            raise RuntimeError(f"no build command known for {repo}; set repos.{repo}.build_cmd")
        print(f"$ {cmd}   (in {wt}, commit {meta['commit']})", flush=True)
        rc = subprocess.run(cmd, shell=True, cwd=str(wt), env=env).returncode
        if rc != 0:
            raise RuntimeError(f"build command exited with {rc}")
        meta["artifacts"] = _collect(cfg, wt, st["artifact_globs"], runs_dir(cfg) / build_id / "artifacts")
        print(f"collected {len(meta['artifacts'])} artifact(s): {', '.join(meta['artifacts']) or '-'}", flush=True)
        meta["status"] = SUCCESS
    except Exception as e:
        meta["status"], meta["error"] = FAILED, str(e)
        print(f"BUILD FAILED: {e}", flush=True)
    meta["finished"] = time.time()
    _write_meta(cfg, meta)
    return 0 if meta["status"] == SUCCESS else 1


_SKIP = ("-sources.", "-javadoc.", "-tests.", "original-", ".original")


def _collect(cfg, wt: Path, globs: list, dest: Path) -> list:
    dest.mkdir(parents=True, exist_ok=True)
    out = []
    for g in globs:
        for f in sorted(wt.glob(g)):
            if not f.is_file() or any(s in f.name for s in _SKIP) or "node_modules" in f.parts:
                continue
            target = dest / f.name
            if target.exists():
                continue  # first match wins on duplicate names
            shutil.copy2(f, target)
            out.append(f.name)
    return out


def list_builds(cfg: dict, limit: int = 30, repo: str = "", only_success: bool = False) -> list:
    d = runs_dir(cfg)
    if not d.is_dir():
        return []
    out = []
    for m in sorted(d.iterdir(), reverse=True):
        try:
            meta = json.loads((m / "meta.json").read_text())
        except Exception:
            continue
        if (repo and meta["repo"] != repo) or (only_success and meta["status"] != SUCCESS):
            continue
        out.append(meta)
        if len(out) >= limit:
            break
    return out


def read_log(cfg: dict, build_id: str, offset: int = 0, max_bytes: int = 200_000) -> dict:
    _meta_path(cfg, build_id)
    p = runs_dir(cfg) / build_id / "build.log"
    if not p.exists():
        return {"text": "", "offset": 0}
    with open(p, "rb") as f:
        f.seek(offset)
        data = f.read(max_bytes)
    return {"text": data.decode("utf-8", "replace"), "offset": offset + len(data)}


def pick_artifact(cfg: dict, build_id: str, pattern: str) -> Path:
    meta = read_meta(cfg, build_id)
    if meta["status"] != SUCCESS:
        raise ValueError(f"build {build_id} is {meta['status']}")
    hits = [a for a in meta["artifacts"] if fnmatch.fnmatch(a, pattern)]
    if len(hits) != 1:
        raise ValueError(f"artifact pattern '{pattern}' matched {len(hits)} of {meta['artifacts']} in {build_id}")
    return runs_dir(cfg) / build_id / "artifacts" / hits[0]
