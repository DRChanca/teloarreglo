#!/usr/bin/env python3
"""Repeatable Android commands; only public configuration reaches Gradle."""
import argparse
from pathlib import Path
import subprocess

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
    subprocess.run(["./gradlew", f":app:connected{variant}AndroidTest"], cwd=ROOT, env=env, check=True)
