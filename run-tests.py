#!/usr/bin/env python3
"""Run the JavaScript and JVM unit tests for YesPlayMusic Mobile."""

from __future__ import annotations

import argparse
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
TESTS = ROOT / "tests"

spec = importlib.util.spec_from_file_location("build_apk", ROOT / "build-apk.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


def run_js() -> None:
    node = shutil.which("node") or build.fail("Cannot find node on PATH (Node.js 18 or newer is required)")
    tests = sorted(str(path) for path in (TESTS / "js").glob("*.test.js"))
    subprocess.run([node, "--test", *tests], cwd=ROOT, check=True)


def run_java(args: argparse.Namespace) -> None:
    json_jar = args.json_jar or os.environ.get("YPM_JSON_JAR")
    if not json_jar or not Path(json_jar).is_file():
        build.fail("JVM tests need a real org.json jar (android.jar only has stubs): pass --json-jar or set YPM_JSON_JAR")
    android_jar = build.locate_android_jar(args, build.sdk_root_from(args))
    sources = [*sorted((ROOT / "src").rglob("*.java")), *sorted((TESTS / "java").rglob("*.java"))]
    with tempfile.TemporaryDirectory(prefix="ypm-tests-") as classes:
        subprocess.run([str(build.executable("javac")), "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-classpath", str(android_jar), "-d", classes, *map(str, sources)], cwd=ROOT, check=True)
        # org.json must precede android.jar so the real implementation shadows the throwing stubs.
        classpath = os.pathsep.join([str(Path(json_jar).resolve()), classes, str(android_jar)])
        subprocess.run([str(build.executable("java")), "-cp", classpath, "dev.yesplaymusic.probe.AllTests"],
                       cwd=ROOT, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--js-only", action="store_true", help="Skip the JVM tests")
    parser.add_argument("--json-jar", help="org.json jar for the JVM tests (otherwise YPM_JSON_JAR)")
    parser.add_argument("--sdk-root", type=Path, help="Android SDK root (otherwise ANDROID_SDK_ROOT/ANDROID_HOME)")
    parser.add_argument("--android-jar", type=Path, help="Path to platforms/android-*/android.jar")
    args = parser.parse_args()
    run_js()
    if not args.js_only:
        run_java(args)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        raise SystemExit(exc.returncode or 1)
