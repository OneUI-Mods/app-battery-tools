#!/usr/bin/env python3
"""Build and package a release without exposing private signing properties."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.ryuya.appbatterytools"
NAME = "AppBatteryTools"
CERTIFICATE = (ROOT / "signing-certificate.sha256").read_text().strip()
TARGET_SDK = "37"
TOOLS = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "build-tools/37.0.0"


def run(*args, capture=False):
    return subprocess.run([str(x) for x in args], cwd=ROOT, check=True,
                          text=True, capture_output=capture)


def validate(apk):
    badging = run(TOOLS / "aapt2", "dump", "badging", apk, capture=True).stdout
    if (f"name='{PACKAGE}'" not in badging or
            f"targetSdkVersion:'{TARGET_SDK}'" not in badging or
            "application-debuggable" in badging):
        raise ValueError("Wrong package, SDK, or debuggable APK")
    manifest = run(TOOLS / "aapt2", "dump", "xmltree", apk,
                   "--file", "AndroidManifest.xml", capture=True).stdout.lower()
    if any(word in manifest for word in ("testactivity", "fixtureactivity", "probeactivity", "instrumentation")):
        raise ValueError("Test component in release")
    if "io.github.libxposed.service.XposedProvider".lower() not in manifest:
        raise ValueError("Missing API 102 Binder provider")
    if "e: service" in manifest or "android.permission.foreground_service" in manifest:
        raise ValueError("This module must not install a resident service")
    run(TOOLS / "zipalign", "-c", "-P", "16", "4", apk)


def verify(apk):
    validate(apk)
    report = run(TOOLS / "apksigner", "verify", "--verbose", "--print-certs", apk, capture=True).stdout
    certificates = set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", report))
    if certificates != {CERTIFICATE}:
        raise ValueError("Distribution signer mismatch")
    return report


def signing_properties():
    private = ROOT / ".signing"
    return private / "release.keystore", {"keyAlias": "appbatterytools-release"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref", default="HEAD")
    parser.add_argument("--check-release-apk", type=Path)
    args = parser.parse_args()
    if args.check_release_apk:
        verify(args.check_release_apk.resolve())
        print("Signed release verified")
        return
    run("git", "diff", "--exit-code", args.source_ref, "--", ".")
    if run("git", "ls-files", "--others", "--exclude-standard", capture=True).stdout:
        raise ValueError("Commit release sources before packaging")
    store, properties = signing_properties()
    run(ROOT / "gradlew", ":app:testDebugUnitTest", ":app:assembleRelease", ":app:lintRelease", ":app:spotlessCheck", ":app:writeRuntimeDependencyList")
    run("python3", ROOT / "tools/check_license_catalog.py")
    folder = ROOT / "app/build/outputs/apk/release"
    output = json.loads((folder / "output-metadata.json").read_text())
    if output["variantName"] != "release" or len(output["elements"]) != 1:
        raise ValueError("Expected one release variant")
    element = output["elements"][0]
    version = element["versionName"]
    unsigned = folder / element["outputFile"]
    validate(unsigned)
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    apk = dist / f"{NAME}-{version}-release.apk"
    with tempfile.TemporaryDirectory(prefix="oneui-release-") as temporary:
        private = Path(temporary)
        aligned = private / "aligned.apk"
        run(TOOLS / "zipalign", "-P", "16", "-f", "4", unsigned, aligned)
        run(TOOLS / "apksigner", "sign", "--ks", store,
            "--ks-key-alias", properties["keyAlias"],
            "--ks-pass", "file:" + str(ROOT / ".signing/password"),
            "--key-pass", "file:" + str(ROOT / ".signing/password"),
            "--debuggable-apk-permitted", "false", "--out", apk, aligned)
    report = dist / f"{NAME}-{version}-signature.txt"
    report.write_text(verify(apk), encoding="utf-8")
    source = dist / f"{NAME}-{version}-source.zip"
    run("git", "archive", "--format=zip", f"--prefix={NAME}-{version}/", f"--output={source}", args.source_ref)
    metadata = dist / "release-metadata.json"
    metadata.write_text(json.dumps({"schema": 1, "packageName": PACKAGE,
        "versionCode": element["versionCode"], "versionName": version,
        "apk": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}, indent=2) + "\n")
    files = [apk, source, report, metadata]
    (dist / f"SHA256SUMS-{version}.txt").write_text("".join(
        hashlib.sha256(file.read_bytes()).hexdigest() + "  " + file.name + "\n" for file in files))
    print("Release APK, continuous signer, matching source, metadata and hashes verified")


if __name__ == "__main__":
    main()
