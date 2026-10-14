"""Loopback transport checks; TRANSPORT_CHECK_CLASSPATH must contain compiled checks + app transport classes and runtime dependencies."""
from pathlib import Path
import os, subprocess, sys
root = Path(__file__).resolve().parent
classpath = os.environ.get("TRANSPORT_CHECK_CLASSPATH")
if not classpath:
    raise SystemExit("Set TRANSPORT_CHECK_CLASSPATH to the compiled transport/check classes and their JVM dependencies.")
# The second NFS run makes the fixture refuse READDIRPLUS, exercising the plain READDIR fallback.
for name, main, env in [("smb", "SmbSmokeKt", {}), ("nfs", "NfsSmokeKt", {}), ("nfs", "NfsSmokeKt", {"NFS_NO_READDIRPLUS": "1"})]:
    server = subprocess.Popen([sys.executable, str(root / (name + "_server.py"))], stdout=subprocess.PIPE, text=True, env={**os.environ, **env})
    try:
        if not server.stdout.readline():
            raise RuntimeError(name + " fixture failed to start")
        subprocess.run(["java", "-cp", classpath, main], check=True, timeout=70)
    finally:
        server.terminate()
        try:
            server.wait(timeout=3)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
