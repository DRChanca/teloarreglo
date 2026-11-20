#!/usr/bin/env python3
"""Check that a direct IDE-style Gradle build reads .env without the Python wrapper.

Only field names and pass/fail are printed, never configuration values or secrets.
"""
import json
import os
import re
import subprocess
from unittest.mock import patch

from configuration import ROOT, configuration


def main():
    env = dict(os.environ)
    for prefix in ("TEST", "PRESENTATION"):
        for suffix in ("SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY"):
            env.pop(f"{prefix}_{suffix}", None)
    with patch.dict(os.environ, env, clear=True):
        expected = configuration()
    if not env.get("JAVA_HOME") and os.path.exists("/usr/libexec/java_home"):
        env["JAVA_HOME"] = subprocess.check_output(
            ["/usr/libexec/java_home", "-v", "21"], text=True,
        ).strip()
    subprocess.run([
        "./gradlew", ":app:generatePruebasDebugBuildConfig",
        ":app:generatePresentacionDebugBuildConfig", "--console=plain",
    ], cwd=ROOT, env=env, check=True, stdout=subprocess.DEVNULL)
    for variant, prefix in (("pruebas", "TEST"), ("presentacion", "PRESENTATION")):
        path = ROOT / f"app/build/generated/source/buildConfig/{variant}/debug/es/teloarreglo/BuildConfig.java"
        source = path.read_text()
        for field, suffix in (("SUPABASE_URL", "SUPABASE_URL"), ("SUPABASE_KEY", "SUPABASE_PUBLISHABLE_KEY")):
            wanted = expected.get(f"{prefix}_{suffix}")
            if not wanted:
                raise SystemExit(f"Missing public configuration: {prefix}_{suffix}")
            match = re.search(rf'\b{field} = ("(?:\\.|[^"\\])*");', source)
            if not match or json.loads(match[1]) != wanted:
                raise SystemExit(f"FAIL: direct Gradle did not load {prefix}_{suffix} from .env")
        assert "PASSWORD" not in source and "TEST_EMAIL" not in source, "Private fields must not reach BuildConfig"
        print(f"PASS: {variant} reads public configuration directly; no credential fields")


if __name__ == "__main__":
    main()
