#!/usr/bin/env python3
"""Smoke the existing ordinary signed QA bytes on a CI emulator, never rebuild them.

Preparation verifies same-head provenance, all compiled QA flags, R8 and signing.
Runtime runs exactly one existing external UIAutomator method, not the seeded
legacy-plan test, debug app, qaRegression target, provider flow or physical phone.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "performance-evidence/final-qa-smoke"
PACKAGE = "com.polinalinen.madre.qa"
DRIVER = "com.polinalinen.madre.macrobenchmark"
METHOD = DRIVER + ".QaOwnerAcceptance#actualR8SettingsProbeAndLocalFpsAreVisible"
CERT = "9ACA5F5F57F301EC8359AD670ECEB6387156132007C07FF36A31266326468258"
FRAMES = ("qa-haptic-diagnostics", "qa-fps-settings", "qa-night-settings", "qa-night-toc")


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(args, log=None, timeout=180):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if log:
        log.write_text(result.stdout)
    if result.returncode:
        raise RuntimeError("Command failed; see raw smoke logs: " + str(args[0]))
    return result.stdout


def one(root, pattern):
    paths = list(Path(root).rglob(pattern))
    if len(paths) != 1:
        raise ValueError("Expected exactly one " + pattern)
    return paths[0]


def verify_provenance(proof, account, head, apk_sha, mapping_sha):
    expected = {"head": head, "apk_sha256": apk_sha, "mapping_sha256": mapping_sha,
                "variant": "qa", "R8": True, "debuggable": False}
    for key, value in expected.items():
        if type(proof.get(key)) is not type(value) or proof[key] != value:
            raise ValueError("Same-run ordinary QA provenance mismatch: " + key)
    if account.get("apk_sha256") != apk_sha or account.get("BUILD_TYPE") != "qa":
        raise ValueError("Account proof is not for these ordinary QA bytes")
    for flag in ("YANDEX_ID", "FAMILY_LINK_UI", "FAMILY_ROSTER_V2", "ALICE"):
        if account.get(flag) is not True:
            raise ValueError("Compiled QA flag not enabled: " + flag)


def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    source = Path(os.environ["MADRE_EXACT_QA_DIR"])
    apk, mapping = one(source, "app-qa.apk"), one(source, "mapping.txt")
    proof = json.loads(one(source, "qa-binary-provenance.json").read_text())
    account = json.loads(one(source, "qa-account-gate.json").read_text())
    head = run(["git", "rev-parse", "HEAD"]).strip()
    verify_provenance(proof, account, head, sha(apk), sha(mapping))
    # Compare actual DEX version with exact source, never trust an artifact label.
    run(["python3", "scripts/verify_apk_account_gate.py", apk], OUT / "actual-account-gate.json")
    actual = json.loads((OUT / "actual-account-gate.json").read_text())
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    version = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"', gradle, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*(\d+)', gradle, re.M)
    if version != [actual.get("version")] or codes != [str(actual.get("versionCode"))]:
        raise ValueError("Ordinary QA DEX version differs from source")
    verify_provenance(proof, actual, head, sha(apk), sha(mapping))
    run(["python3", "scripts/verify_apk_signature.py", apk, "--expected", CERT], OUT / "signature.txt")
    run(["python3", "scripts/verify_qa_release_like.py", apk, "--mapping", mapping], OUT / "actual-r8.json")
    config = one(source, "configuration.txt").read_text()
    if "qa-persistence-api.pro" in config:
        raise ValueError("Test-only persistence ABI is not ordinary delivery")
    driver = one(ROOT / "macrobenchmark/build/outputs/apk/benchmark", "*.apk")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    aapt = sdk / "build-tools" / os.environ.get("MADRE_BUILD_TOOLS_VERSION", "36.0.0") / "aapt"
    badging = run([aapt, "dump", "badging", driver], OUT / "driver-badging.txt")
    identity = re.search(r"package: name='([^']+)'", badging)
    if not identity or identity.group(1) not in (DRIVER, DRIVER + ".test"):
        raise ValueError("Unexpected external driver package")
    driver_package = identity.group(1)
    manifest = run([aapt, "dump", "xmltree", driver, "AndroidManifest.xml"], OUT / "driver-manifest.txt")
    targets = re.findall(r'android:targetPackage[^\n]*?="([^"]+)"', manifest)
    if targets != [driver_package]:
        raise ValueError("Driver must instrument itself, not the ordinary QA app")
    shutil.copy2(apk, OUT / "target.apk")
    shutil.copy2(driver, OUT / "driver.apk")
    controls = {"head": head, "run_id": os.environ["GITHUB_RUN_ID"],
                "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
                "apk_sha256": sha(apk), "driver_sha256": sha(driver),
                "driver_package": driver_package, "method": METHOD,
                "target_rebuilt": False, "test_only_persistence_ABI": False,
                "provider_acceptance": "NOT_RUN", "physical_device_acceptance": "NOT_RUN"}
    (OUT / "controls.json").write_text(json.dumps(controls, indent=2) + "\n")


def validate_instrumentation(text):
    # am instrument may exit zero on failure. A skipped/empty/test-selection run is not PASS.
    if (not re.search(r"(?m)^OK \(1 test\)\s*$", text)
            or "INSTRUMENTATION_CODE: -1" not in text
            or re.search(r"INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b", text)
            or re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=", text)
            or "INSTRUMENTATION_STATUS: test=actualR8SettingsProbeAndLocalFpsAreVisible" not in text):
        raise ValueError("Exact ordinary QA instrumented test did not pass once without skips")


def runtime():
    # Admission before the FIRST adb command. Even a CI host must name a single emulator.
    serial = os.environ.get("ANDROID_SERIAL", "")
    if not (os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("CI") == "true"
            and re.fullmatch(r"emulator-\d+", serial)):
        raise ValueError("Only an explicitly selected GitHub CI emulator is authorized")
    controls = json.loads((OUT / "controls.json").read_text())
    if (controls["head"] != run(["git", "rev-parse", "HEAD"]).strip()
            or controls["run_id"] != os.environ["GITHUB_RUN_ID"]
            or controls["apk_sha256"] != sha(OUT / "target.apk")
            or controls["driver_sha256"] != sha(OUT / "driver.apk")):
        raise ValueError("Smoke preparation is stale or payload changed")
    def adb(*args, log=None):
        return run(["adb", "-s", serial, *args], log)
    if (adb("get-serialno").strip() != serial
            or adb("shell", "getprop", "ro.kernel.qemu").strip() != "1"
            or adb("shell", "getprop", "ro.build.version.sdk").strip() != "35"):
        raise ValueError("Wrong emulator identity/API")
    def installed(label):
        paths = adb("shell", "pm", "path", PACKAGE).strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:/"):
            raise ValueError("Expected one installed ordinary QA base APK")
        local = OUT / (label + "-installed.apk")
        adb("pull", paths[0].removeprefix("package:"), local)
        if sha(local) != controls["apk_sha256"]:
            raise ValueError("Installed APK differs from the single producer")
    # Fresh CI AVD, no accounts/login/QR; disable its radios before app launch.
    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")
    adb("install", "-r", OUT / "target.apk", log=OUT / "target-install.txt")
    installed("before")
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    adb("install", "-r", OUT / "driver.apk", log=OUT / "driver-install.txt")
    try:
        text = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", METHOD,
                   controls["driver_package"] + "/androidx.test.runner.AndroidJUnitRunner",
                   log=OUT / "instrumentation.txt")
        validate_instrumentation(text)
        installed("after")
        adb("pull", "/sdcard/Download/madre-qa-p0", OUT / "native")
        import xml.etree.ElementTree as ET
        for frame in FRAMES:
            if not (OUT / "native" / (frame + ".png")).is_file():
                raise ValueError("Missing actual QA smoke frame: " + frame)
            tree = ET.parse(OUT / "native" / (frame + ".xml"))
            if not any(node.get("package") == PACKAGE for node in tree.iter("node")):
                raise ValueError("Actual QA window absent from native hierarchy")
        (OUT / "result.json").write_text(json.dumps(controls | {"status": "PASS", "tests": 1}, indent=2) + "\n")
    finally:
        # Best-effort evidence must not mask the original failing exit status.
        for args, log in [(("logcat", "-d"), OUT / "logcat.txt"),
                          (("shell", "dumpsys", "package", PACKAGE), OUT / "installed-package.txt"),
                          (("pull", "/sdcard/Download/madre-qa-p0", OUT / "native-final"), OUT / "native-final-pull.txt")]:
            try:
                adb(*args, log=log)
            except (OSError, RuntimeError, subprocess.SubprocessError):
                pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare", action="store_true")
    args = parser.parse_args()
    (prepare if args.prepare else runtime)()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
