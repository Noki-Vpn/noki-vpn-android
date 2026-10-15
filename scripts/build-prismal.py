"""Build the pinned Prismal release AAR with JAVA_HOME and ANDROID_HOME set."""
import argparse
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

COMMIT = "2cab1c9c82241fba945e2b23080c7a477c1d1982"
ARCHIVE_SHA256 = "d8433381d0a61fc14a051df26f8a7314cae1aee9609a1539c3554459581881b9"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(
        f"https://codeload.github.com/styropyr0/PrismalAGSL/zip/{COMMIT}", timeout=30
    ) as response:
        archive = response.read()
    if hashlib.sha256(archive).hexdigest() != ARCHIVE_SHA256:
        raise RuntimeError("Prismal source archive checksum mismatch")
    workspace = Path(tempfile.mkdtemp(prefix="prismal-", dir=args.work_dir.resolve()))
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        for member in source.infolist():
            if not (workspace / member.filename).resolve().is_relative_to(workspace):
                raise RuntimeError("Invalid Prismal archive path")
        source.extractall(workspace)
    root = workspace / f"PrismalAGSL-{COMMIT}"
    command = (["cmd.exe", "/c", "gradlew.bat"] if os.name == "nt" else ["bash", "gradlew"])
    command += [":Prismal:assembleRelease", ":Prismal:generatePomFileForReleasePublication",
                "--no-daemon", "--no-configuration-cache", "--max-workers=1", "--console=plain"]
    child = subprocess.Popen(command, cwd=root,
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    try:
        code = child.wait(timeout=900)
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"], check=False, timeout=30)
        else:
            child.kill()
        raise RuntimeError("Prismal release build exceeded 15 minutes")
    if code:
        raise RuntimeError(f"Prismal release build failed ({code}); source retained at {root}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / "Prismal/build/outputs/aar/Prismal-release.aar", args.output)
    shutil.copyfile(root / "Prismal/build/publications/release/pom-default.xml", args.output.with_suffix(".pom"))
    print(f"SHA256 {hashlib.sha256(args.output.read_bytes()).hexdigest()} {args.output}")


if __name__ == "__main__":
    main()
