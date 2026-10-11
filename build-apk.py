#!/usr/bin/env python3
"""Build YesPlayMusic Mobile with an installed Android SDK and JDK."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
WORK = BUILD / "work"
DIST = ROOT / "dist"
MIN_SDK = "26"
TARGET_SDK = "37"


def fail(message: str) -> "NoReturn":
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(2)


def check_direct_child(parent: Path, child: Path, label: str) -> Path:
    """Resolve a generated path and reject symlinks or paths outside parent."""
    parent_real = parent.resolve()
    child_real = child.resolve()
    lexical = Path(os.path.abspath(child))
    if os.path.normcase(str(child_real)) != os.path.normcase(str(lexical)) or child_real.parent != parent_real:
        fail(f"Refusing unsafe {label} path: {child_real}")
    return child_real


def prepare_work() -> Path:
    root_real = ROOT.resolve()
    build_real = check_direct_child(root_real, BUILD, "build directory")
    work_real = check_direct_child(build_real, WORK, "temporary build directory")
    if work_real.exists():
        # WORK has been resolved and checked as a direct child of ROOT/build.
        shutil.rmtree(work_real)
    work_real.mkdir(parents=True)
    return work_real


def sdk_root_from(args: argparse.Namespace) -> Path | None:
    value = args.sdk_root or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    return Path(value).expanduser().resolve() if value else None


def version_numbers(name: str) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", name)) or (0,)


def locate_android_jar(args: argparse.Namespace, sdk_root: Path | None) -> Path:
    if args.android_jar:
        jar = args.android_jar.expanduser().resolve()
    elif sdk_root:
        platforms = sdk_root / "platforms"
        candidates = sorted(
            (p / "android.jar" for p in platforms.glob("android-*/") if (p / "android.jar").is_file()),
            key=lambda p: version_numbers(p.parent.name),
        )
        if not candidates:
            fail(f"No platforms/android-*/android.jar found under {sdk_root}")
        jar = candidates[-1].resolve()
    else:
        fail("Set ANDROID_SDK_ROOT or ANDROID_HOME, or pass --android-jar")
    if not jar.is_file():
        fail(f"Android platform jar not found: {jar}")
    return jar


def find_tool(directory: Path, name: str) -> Path:
    names = [name]
    if os.name == "nt":
        names = [name + ".exe", name + ".bat", name]
    for candidate_name in names:
        candidate = directory / candidate_name
        if candidate.is_file():
            return candidate.resolve()
    fail(f"Missing {name} in {directory}")


def toolset_complete(directory: Path) -> bool:
    try:
        find_tool(directory, "aapt2")
        find_tool(directory, "zipalign")
        return (directory / "lib" / "d8.jar").is_file() and (directory / "lib" / "apksigner.jar").is_file()
    except SystemExit:
        return False


def locate_build_tools(args: argparse.Namespace, sdk_root: Path | None) -> Path:
    if args.build_tools:
        directory = args.build_tools.expanduser().resolve()
        if not toolset_complete(directory):
            fail(f"Incomplete Android build-tools directory: {directory}")
        return directory
    if not sdk_root:
        fail("Set ANDROID_SDK_ROOT or ANDROID_HOME, or pass --build-tools")
    parent = sdk_root / "build-tools"
    candidates = sorted(
        (p for p in parent.iterdir() if p.is_dir() and toolset_complete(p)),
        key=lambda p: version_numbers(p.name),
    ) if parent.is_dir() else []
    if not candidates:
        fail(f"No complete build-tools installation found under {parent}")
    return candidates[-1].resolve()


def executable(name: str) -> Path:
    java_home = os.environ.get("JAVA_HOME")
    suffixes = (".exe", "") if os.name == "nt" else ("",)
    if java_home:
        for suffix in suffixes:
            candidate = Path(java_home).expanduser() / "bin" / (name + suffix)
            if candidate.is_file():
                return candidate.resolve()
    for candidate_name in (name + suffix for suffix in suffixes):
        located = shutil.which(candidate_name)
        if located:
            return Path(located).resolve()
    fail(f"Cannot find {name}; install a JDK and set JAVA_HOME or PATH")


def run(label: str, *command: object, env: dict[str, str] | None = None) -> None:
    print(f"  {label}", flush=True)
    subprocess.run([str(part) for part in command], cwd=ROOT, env=env, check=True)


def make_debug_keystore(keytool: Path, keystore: Path) -> None:
    if keystore.is_symlink():
        fail(f"Refusing to use a symlink as the local debug keystore: {keystore}")
    if keystore.exists():
        return
    run(
        "Generate local Android debug keystore",
        keytool,
        "-genkeypair",
        "-v",
        "-keystore",
        keystore,
        "-storepass",
        "android",
        "-alias",
        "androiddebugkey",
        "-keypass",
        "android",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "10000",
        "-dname",
        "CN=Android Debug,O=Android,C=US",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    signing = parser.add_mutually_exclusive_group()
    signing.add_argument("--debug", action="store_true", help="Build a debuggable APK signed with a local debug key")
    signing.add_argument("--release", action="store_true", help="Build a non-debuggable APK; this is the default")
    parser.add_argument("--sdk-root", type=Path, help="Android SDK root (otherwise ANDROID_SDK_ROOT/ANDROID_HOME)")
    parser.add_argument("--android-jar", type=Path, help="Path to platforms/android-*/android.jar")
    parser.add_argument("--build-tools", type=Path, help="Path to one Android build-tools version directory")
    parser.add_argument("--keystore", type=Path, help="User-owned release signing keystore (required for release)")
    parser.add_argument("--alias", default="upload", help="Release key alias (default: upload)")
    args = parser.parse_args()
    debug = args.debug

    if not (ROOT / "AndroidManifest.xml").is_file() or not (ROOT / "src").is_dir():
        fail("Run this script from the repository copy that contains AndroidManifest.xml and src/")

    sdk_root = sdk_root_from(args)
    android_jar = locate_android_jar(args, sdk_root)
    tools = locate_build_tools(args, sdk_root)
    java = executable("java")
    javac = executable("javac")
    keytool = executable("keytool")
    aapt2 = find_tool(tools, "aapt2")
    zipalign = find_tool(tools, "zipalign")
    d8 = tools / "lib" / "d8.jar"
    apksigner = tools / "lib" / "apksigner.jar"

    build_real = check_direct_child(ROOT.resolve(), BUILD, "build directory")
    dist_real = check_direct_child(ROOT.resolve(), DIST, "dist directory")
    if build_real.exists() and not build_real.is_dir():
        fail(f"Build path is not a directory: {build_real}")
    if dist_real.exists() and not dist_real.is_dir():
        fail(f"Output path is not a directory: {dist_real}")
    build_real.mkdir(exist_ok=True)
    dist_real.mkdir(exist_ok=True)
    work = prepare_work()

    manifest_text = (ROOT / "AndroidManifest.xml").read_text(encoding="utf-8")
    manifest_text = re.sub(
        r'android:debuggable="(?:true|false)"',
        f'android:debuggable="{"true" if debug else "false"}"',
        manifest_text,
        count=1,
    )
    manifest = work / "AndroidManifest.xml"
    manifest.write_text(manifest_text, encoding="utf-8", newline="\n")

    classes = work / "classes"
    dex_dir = work / "dex"
    classes.mkdir()
    dex_dir.mkdir()
    sources = sorted((ROOT / "src").rglob("*.java"))
    if not sources:
        fail("No Java sources found under src/")

    resources_zip = work / "resources.zip"
    resources_apk = work / "resources.apk"
    run("Compile Android resources", aapt2, "compile", "--dir", ROOT / "res", "-o", resources_zip)
    run(
        "Link Android resources",
        aapt2,
        "link",
        "-o",
        resources_apk,
        "-I",
        android_jar,
        "--manifest",
        manifest,
        "--min-sdk-version",
        MIN_SDK,
        "--target-sdk-version",
        TARGET_SDK,
        "-0",
        "wav",
        "-A",
        ROOT / "assets",
        resources_zip,
    )
    run(
        "Compile Java sources",
        javac,
        "-encoding",
        "UTF-8",
        "-source",
        "8",
        "-target",
        "8",
        "-classpath",
        android_jar,
        "-d",
        classes,
        *sources,
    )
    class_files = sorted(classes.rglob("*.class"))
    run(
        "Convert Java bytecode to DEX",
        java,
        "-cp",
        d8,
        "com.android.tools.r8.D8",
        "--lib",
        android_jar,
        "--min-api",
        MIN_SDK,
        "--output",
        dex_dir,
        *class_files,
    )

    unsigned = work / "unsigned.apk"
    with zipfile.ZipFile(resources_apk, "r") as src, zipfile.ZipFile(unsigned, "w") as dst:
        for item in src.infolist():
            dst.writestr(item, src.read(item.filename))
        for dex_file in sorted(dex_dir.glob("*.dex")):
            dst.write(dex_file, dex_file.name, compress_type=zipfile.ZIP_STORED)
    aligned = work / "aligned.apk"
    run("Align APK", zipalign, "-f", "-p", "4", unsigned, aligned)

    environment = os.environ.copy()
    if debug:
        keystore = build_real / "debug.keystore"
        make_debug_keystore(keytool, keystore)
        alias = "androiddebugkey"
        environment["YPM_DEBUG_KS_PASS"] = "android"
        environment["YPM_DEBUG_KEY_PASS"] = "android"
        signing_args = [
            "--ks",
            keystore,
            "--ks-pass",
            "env:YPM_DEBUG_KS_PASS",
            "--key-pass",
            "env:YPM_DEBUG_KEY_PASS",
            "--ks-key-alias",
            alias,
        ]
        output = dist_real / "YesPlayMusic-Mobile-debug.apk"
    else:
        if not args.keystore:
            fail("Release builds require --keystore. Use --debug for a local debug build.")
        keystore = args.keystore.expanduser().resolve()
        if not keystore.is_file():
            fail(f"Release keystore not found: {keystore}")
        if not os.environ.get("YPM_KS_PASS") or not os.environ.get("YPM_KEY_PASS"):
            fail("Set YPM_KS_PASS and YPM_KEY_PASS in the environment before signing a release APK")
        signing_args = [
            "--ks",
            keystore,
            "--ks-pass",
            "env:YPM_KS_PASS",
            "--key-pass",
            "env:YPM_KEY_PASS",
            "--ks-key-alias",
            args.alias,
        ]
        output = dist_real / "YesPlayMusic-Mobile.apk"

    if output.is_symlink():
        fail(f"Refusing to write through an APK output symlink: {output}")
    if output.exists() and output.resolve().parent != dist_real:
        fail(f"Refusing to overwrite an APK outside dist/: {output.resolve()}")

    run("Sign APK", java, "-jar", apksigner, "sign", *signing_args, "--out", output, aligned, env=environment)
    run("Verify APK signature", java, "-jar", apksigner, "verify", "--verbose", output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(f"APK: {output}")
    print(f"Size: {output.stat().st_size} bytes")
    print(f"SHA-256: {digest}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        print(f"error: {Path(str(exc.cmd[0])).name} failed with exit code {exc.returncode}", file=sys.stderr)
        raise SystemExit(exc.returncode or 1)
