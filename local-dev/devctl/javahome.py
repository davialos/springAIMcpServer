"""Resolve a JAVA_HOME setting: explicit path, or ``auto:<major>`` via macOS /usr/libexec/java_home."""
from __future__ import annotations

import os
import subprocess


def resolve(spec: str) -> str:
    spec = (spec or "").strip()
    if spec.startswith("auto:"):
        try:
            out = subprocess.run(["/usr/libexec/java_home", "-v", spec[5:]], capture_output=True, text=True, timeout=10)
            if out.returncode == 0 and out.stdout.strip():
                return out.stdout.strip()
        except (OSError, subprocess.SubprocessError):
            pass
        # Homebrew's openjdk@N is keg-only: not known to java_home unless symlinked into /Library/Java/JavaVirtualMachines
        for prefix in ("/opt/homebrew", "/usr/local"):
            home = f"{prefix}/opt/openjdk@{spec[5:]}/libexec/openjdk.jdk/Contents/Home"
            if os.path.isdir(home):
                return home
        return os.environ.get("JAVA_HOME", "")
    return os.path.expanduser(spec)
