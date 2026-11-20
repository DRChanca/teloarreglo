#!/usr/bin/env python3
"""Repeatable Android commands; only public configuration reaches Gradle."""
import argparse
from pathlib import Path
import subprocess
import time

from configuration import ROOT, public_build_environment

parser = argparse.ArgumentParser()
parser.add_argument("command", choices=["build", "check", "ui-test", "install"])
parser.add_argument("--environment", choices=["pruebas", "presentacion"], default="pruebas")
parser.add_argument("--serial", help="adb serial, required for installation")
args = parser.parse_args()
env = public_build_environment()
variant = args.environment.capitalize() + "Debug"
if args.command == "install":
    if not args.serial:
        parser.error("--serial is required; installation must not target an arbitrary device")
    adb = Path(env["ANDROID_HOME"]) / "platform-tools/adb"
    apk = ROOT / f"app/build/outputs/apk/{args.environment}/debug/app-{args.environment}-debug.apk"
    subprocess.run([str(adb), "-s", args.serial, "install", "-r", str(apk)], check=True)
elif args.command == "build":
    subprocess.run(["./gradlew", f":app:assemble{variant}"], cwd=ROOT, env=env, check=True)
elif args.command == "check":
    subprocess.run(
        ["./gradlew", f":app:compile{variant}Kotlin", f":app:test{variant}UnitTest", f":app:lint{variant}"],
        cwd=ROOT, env=env, check=True,
    )
else:
    if args.serial:
        env["ANDROID_SERIAL"] = args.serial
    command = ["./gradlew", f":app:connected{variant}AndroidTest"]
    for attempt in range(1, 4):
        result = subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
        print(result.stdout, end="")
        resolution_failed = (
            "Could not resolve all artifacts for configuration 'classpath'" in result.stdout
            or "Could not resolve all dependencies" in result.stdout
            or "could not resolve plugin artifact" in result.stdout
        )
        if result.returncode == 0:
            break
        if not resolution_failed or attempt == 3:
            raise subprocess.CalledProcessError(result.returncode, command)
        print(f"Transient Gradle dependency resolution failure; retrying ({attempt}/3)...", flush=True)
        time.sleep(10)
