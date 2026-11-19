#!/usr/bin/env python3
"""Verify MD4, AES-CMAC vectors and SMB2/3 signing using a minified APK on Android.

Build with :app:assembleRelease first. Requires a matching R8 mapping.txt, an
Android SDK, JDK and connected emulator/device. Does not install or launch the app.
--check-only checks reflective class retention in R8's mapping without a device.
Example: python tools/verify-smb-release-crypto.py --sdk /path/to/Android/Sdk
"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import uuid


ROOT = Path(__file__).resolve().parent.parent


def run(command):
    return subprocess.run([str(value) for value in command], check=True,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          timeout=180).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--mapping", type=Path, default=ROOT / "app/build/outputs/mapping/release/mapping.txt")
    parser.add_argument("--sdk", type=Path, default=os.getenv("ANDROID_SDK_ROOT") or os.getenv("ANDROID_HOME"))
    parser.add_argument("--serial", help="adb device serial (required when several devices are connected)")
    parser.add_argument("--check-only", action="store_true", help="check R8 class retention without running Android")
    args = parser.parse_args()
    for path in ([args.mapping] if args.check_only else [args.apk, args.mapping]):
        if not path.is_file():
            parser.error(f"missing build artifact: {path}")

    names = {
        "org.bouncycastle.jce.provider.BouncyCastleProvider": None,
        "jcifs.internal.smb2.Smb2SigningDigest": None,
    }
    reflective = {
        "org.bouncycastle.jcajce.provider.digest.MD4$Mappings",
        "org.bouncycastle.jcajce.provider.digest.MD4$Digest",
        "org.bouncycastle.jcajce.provider.symmetric.AES$Mappings",
        "org.bouncycastle.jcajce.provider.symmetric.AES$AESCMAC",
    }
    retained = set()
    with args.mapping.open(encoding="utf-8") as mapping:
        for line in mapping:
            if not line.startswith(("org.bouncycastle.", "jcifs.internal.smb2.Smb2SigningDigest -> ")):
                continue
            original, separator, renamed = line.strip().partition(" -> ")
            if separator and original in names:
                names[original] = renamed.rstrip(":")
            if separator and original in reflective and renamed.rstrip(":") == original:
                retained.add(original)
    if not all(names.values()):
        parser.error("mapping does not contain the SMB crypto classes")
    missing = reflective - retained
    if missing:
        parser.error("reflective SMB provider classes removed or renamed: " + ", ".join(sorted(missing)))
    print("PASS R8 retained MD4 and AES-CMAC registration/implementation classes", flush=True)
    if args.check_only:
        return
    if not args.sdk:
        parser.error("set ANDROID_HOME or pass --sdk")

    adb = [args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")]
    if args.serial:
        adb += ["-s", args.serial]
    java = shutil.which("java")
    javac = shutil.which("javac")
    if not java or not javac:
        parser.error("java and javac must be on PATH")
    d8_candidates = list((args.sdk / "build-tools").glob("*/lib/d8.jar"))
    if not d8_candidates:
        parser.error("install Android SDK build-tools")
    d8 = max(d8_candidates, key=lambda path: tuple(int(p) for p in path.parents[1].name.split(".") if p.isdigit()))
    platforms = list((args.sdk / "platforms").glob("android-*/android.jar"))
    if not platforms:
        parser.error("install an Android SDK platform")
    android_jar = max(platforms, key=lambda path: tuple(int(p) for p in path.parent.name.split("-")[-1].split(".") if p.isdigit()))
    remote = f"/data/local/tmp/streamvault-smb-crypto-{uuid.uuid4().hex}"
    with tempfile.TemporaryDirectory(prefix="streamvault-smb-crypto-") as temporary:
        output = Path(temporary)
        source = ROOT / "tools/smb-release-crypto/SmbReleaseCryptoProbe.java"
        run([javac, "--release", "8", "-classpath", android_jar, "-d", output, source])
        run([java, "-cp", d8, "com.android.tools.r8.D8", "--min-api", "25", "--lib", android_jar,
             "--output", output, output / "SmbReleaseCryptoProbe.class"])
        run(adb + ["shell", "mkdir", "-p", remote])
        try:
            run(adb + ["push", args.apk, f"{remote}/app.apk"])
            run(adb + ["push", output / "classes.dex", f"{remote}/probe.dex"])
            result = run(adb + ["shell", f"CLASSPATH={remote}/probe.dex", "app_process", "/system/bin",
                                "SmbReleaseCryptoProbe", f"{remote}/app.apk", *names.values()])
            print(result, end="")
            if "SMB_RELEASE_CRYPTO_PASS" not in result:
                raise RuntimeError("Android probe did not report success")
        finally:
            run(adb + ["shell", "rm", "-rf", remote])


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stdout or str(error))
        raise SystemExit(1)
