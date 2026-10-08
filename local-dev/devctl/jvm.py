"""Find local JVMs (jps) and run diagnostic commands on them (jcmd).

Every JVM devctl starts carries ``-Ddevctl.service=<name>`` so it can be found again without guessing;
JVMs started elsewhere (IDE, terminal) can still be addressed by pid.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess

from . import javahome

MARK = "-Ddevctl.service="


def _tool(cfg: dict, name: str) -> str:
    for spec in (cfg.get("java_home", ""), os.environ.get("JAVA_HOME", "")):
        jh = javahome.resolve(spec) if spec else ""
        if jh and os.path.exists(os.path.join(jh, "bin", name)):
            return os.path.join(jh, "bin", name)
    found = shutil.which(name)
    if not found:
        raise RuntimeError(f"{name} not found - install a JDK (brew install openjdk@21) or set java_home")
    return found


def list_jvms(cfg: dict) -> list:
    """[{pid, main, service}] for every JVM of this user, except the jps/jcmd tools themselves."""
    r = subprocess.run([_tool(cfg, "jps"), "-lv"], capture_output=True, text=True, timeout=20)
    out = []
    for line in r.stdout.splitlines():
        parts = line.split(" ", 2)
        if len(parts) < 2 or not parts[0].isdigit():
            continue
        main = parts[1]
        if main.endswith((".Jps", ".JCmd")) or main in ("jdk.jcmd/sun.tools.jps.Jps", "jdk.jcmd/sun.tools.jcmd.JCmd"):
            continue
        m = re.search(re.escape(MARK) + r"(\S+)", parts[2] if len(parts) > 2 else "")
        out.append({"pid": int(parts[0]), "main": main, "service": m.group(1) if m else None})
    return out


def find_pid(cfg: dict, service: str) -> int:
    hits = [j["pid"] for j in list_jvms(cfg) if j["service"] == service]
    if not hits:
        raise ValueError(f"no running JVM for {service} (started by devctl with {MARK}{service}); is it running?")
    return max(hits)


def jcmd(cfg: dict, pid: int, *args: str, timeout: int = 60) -> str:
    r = subprocess.run([_tool(cfg, "jcmd"), str(int(pid)), *args], capture_output=True, text=True, timeout=timeout)
    text = (r.stdout + r.stderr).strip()
    if r.returncode != 0:
        raise RuntimeError(f"jcmd {' '.join(args)}: {text[-500:]}")
    return text
