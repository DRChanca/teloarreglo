"""Read wizard dotenv files as data, never execute their contents."""
import os
from pathlib import Path
import shlex

ROOT = Path(__file__).resolve().parent.parent


def configuration():
    values = {}
    path = ROOT / ".env"
    if path.exists():
        for line in path.read_text().splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            tokens = shlex.split(line, comments=True)
            if len(tokens) != 1 or "=" not in tokens[0]:
                raise ValueError("Invalid .env entry; rerun the setup wizard")
            key, value = tokens[0].split("=", 1)
            values[key] = value
    values.update(os.environ)
    return values


def public_build_environment():
    values = configuration()
    # Passwords are never passed from .env to Gradle.
    env = dict(os.environ)
    for prefix in ("TEST", "PRESENTATION"):
        key = f"{prefix}_SUPABASE_PUBLISHABLE_KEY"
        value = values.get(key, "")
        if value and not value.startswith("sb_publishable_"):
            raise ValueError(f"{key}: only publishable keys may be included in an APK")
        for name in (key, f"{prefix}_SUPABASE_URL"):
            env[name] = values.get(name, "")
    if not env.get("JAVA_HOME") and Path("/usr/libexec/java_home").exists():
        import subprocess
        env["JAVA_HOME"] = subprocess.check_output(
            ["/usr/libexec/java_home", "-v", "21"], text=True
        ).strip()
    sdk = Path.home() / "Library/Android/sdk"
    if not env.get("ANDROID_HOME") and sdk.exists():
        env["ANDROID_HOME"] = str(sdk)
    return env
