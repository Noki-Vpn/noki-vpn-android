"""Reproduce the bundled geosite from its pinned source and vendored modules."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import zipfile


SOURCE_SHA256 = "09e547ab2e7b76a24081243ef8e7ef0432d70c2551f2e7efca1a3f290b731cee"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-archive", type=Path, default=Path(__file__).resolve().parents[1] / "third_party/geosite/SOURCE.zip")
    parser.add_argument("--go", default="go", help="Go executable matching the pinned version")
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    go = str(Path(args.go).resolve()) if Path(args.go).exists() else args.go
    data = args.source_archive.read_bytes()
    if sha256(data) != SOURCE_SHA256:
        raise ValueError("Geosite source archive checksum mismatch")
    if args.output_dir:
        output = args.output_dir.resolve()
        output.mkdir(parents=True, exist_ok=False)
    else:
        output = Path(tempfile.mkdtemp(prefix="noki-geosite-"))
    with zipfile.ZipFile(args.source_archive) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or len(names) > 10000 or sum(item.file_size for item in archive.infolist()) > 64 * 1024 * 1024:
            raise ValueError("Invalid geosite source archive size or duplicate entries")
        inputs = json.loads(archive.read("build-inputs.json"))
        if set(names) != set(inputs["files"]) | {"build-inputs.json"}:
            raise ValueError("Geosite source member list mismatch")
        for item in archive.infolist():
            target = (output / item.filename).resolve()
            if not target.is_relative_to(output) or stat.S_ISLNK(item.external_attr >> 16):
                raise ValueError("Unsafe geosite source member")
            contents = archive.read(item)
            if item.filename != "build-inputs.json" and sha256(contents) != inputs["files"][item.filename]:
                raise ValueError("Geosite source member checksum mismatch: " + item.filename)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(contents)
    env = os.environ.copy()
    env.update(GOTOOLCHAIN="local", GOWORK="off", GOPROXY="off", GOSUMDB="off", GOFLAGS="-mod=vendor",
               GOCACHE=str(output / ".gocache"), GOMODCACHE=str(output / ".gomodcache"))
    version = subprocess.check_output([go, "version"], env=env, text=True).split()[2]
    if version != inputs["go_version"]:
        raise ValueError("Expected " + inputs["go_version"] + ", got " + version)
    subprocess.run([go, "run", "./", "--datapath=./data", "--outputdir=" + str(output),
                    "--outputname=geosite.dat"], cwd=output / "src", env=env, check=True)
    artifact = output / "geosite.dat"
    digest = sha256(artifact.read_bytes())
    if digest != inputs["asset_sha256"]:
        raise ValueError("Geosite byte reproduction mismatch: " + digest)
    print(json.dumps({"sha256": digest, "bytes": artifact.stat().st_size, "path": str(artifact)}))


if __name__ == "__main__":
    main()
