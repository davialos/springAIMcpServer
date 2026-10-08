"""Repository discovery and git branch listing for the workspace folder."""
from __future__ import annotations

import subprocess
from pathlib import Path

from . import config


def git(path: Path, *args: str, timeout: int = 60) -> str:
    r = subprocess.run(["git", "-C", str(path), *args], capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout.strip()


def detect_build_system(p: Path) -> str:
    if (p / "pom.xml").exists():
        return "maven"
    if (p / "build.gradle").exists() or (p / "build.gradle.kts").exists():
        return "gradle"
    if (p / "package.json").exists():
        return "npm"
    return "unknown"


def default_build_cmd(p: Path, system: str) -> str:
    if system == "maven":
        mvn = "./mvnw" if (p / "mvnw").exists() else "mvn"
        return f"{mvn} -B -DskipTests package"
    if system == "gradle":
        g = "./gradlew" if (p / "gradlew").exists() else "gradle"
        return f"{g} build -x test"
    if system == "npm":
        return "npm ci && npm run build"
    return ""


def default_artifact_globs(system: str) -> list:
    return {"maven": ["**/target/*.war", "**/target/*.jar"], "gradle": ["**/build/libs/*.war", "**/build/libs/*.jar"],
            "npm": ["dist/**/*"]}.get(system, [])


def discover(cfg: dict) -> list:
    ws = config.path(cfg, "workspace")
    if not ws.is_dir():
        return []
    out = []
    for d in sorted(ws.iterdir()):
        if d.is_dir() and (d / ".git").exists():
            sysname = detect_build_system(d)
            try:
                cur = git(d, "rev-parse", "--abbrev-ref", "HEAD")
            except Exception:
                cur = "?"
            out.append({"name": d.name, "path": str(d), "build_system": sysname, "current_branch": cur})
    return out


def repo_path(cfg: dict, name: str) -> Path:
    p = config.path(cfg, "workspace") / name
    if not (p / ".git").exists() or ".." in name or "/" in name:
        raise ValueError(f"unknown repository: {name}")
    return p


def branches(cfg: dict, name: str, fetch: bool = False) -> list:
    p = repo_path(cfg, name)
    if fetch:
        try:
            git(p, "fetch", "--prune", "--quiet", timeout=120)
        except Exception:
            pass
    raw = git(p, "for-each-ref", "--sort=-committerdate", "--format=%(refname:short)", "refs/heads", "refs/remotes/origin")
    names, seen = [], set()
    for ref in raw.splitlines():
        if ref.endswith("/HEAD") or ref == "origin":
            continue
        short = ref[7:] if ref.startswith("origin/") else ref
        if short not in seen:
            seen.add(short)
            names.append(short)
    return names
