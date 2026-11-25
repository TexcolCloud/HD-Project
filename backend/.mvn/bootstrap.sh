#!/bin/sh
set -eu
cd "$(dirname "$0")"
# Python's standard library also works on hosts without unzip or sha512sum.
if python3 -V >/dev/null 2>&1; then
    python=python3
else
    python=python
fi
exec "$python" - <<'PY'
import hashlib
import os
from pathlib import Path
import shutil
import tempfile
from urllib.request import urlopen
from zipfile import ZipFile

base = Path.cwd()
name = "apache-maven-3.9.12"
archive = base / (name + "-bin.zip")
url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.12/" + archive.name
expected = "0ef0d074761a55e7b982fd7f2cd6ea2da028289c73156ebd488c0513efbff8a64ac5bc0c11c3b8bbced930ce15238ecef344b0e065ae54f7cdca8c86cf39e736"


def verified(path):
    return path.is_file() and hashlib.sha512(path.read_bytes()).hexdigest() == expected


with tempfile.TemporaryDirectory(prefix=name + "-", dir=base) as scratch:
    stage = Path(scratch)
    if not verified(archive):
        download = stage / archive.name
        with urlopen(url, timeout=60) as response, download.open("wb") as output:
            shutil.copyfileobj(response, output)
        if not verified(download):
            raise SystemExit("Maven SHA-512 checksum mismatch")
        os.replace(download, archive)
    with ZipFile(archive) as bundle:
        bundle.extractall(stage)
    launcher = Path(name) / "bin/mvn"
    (stage / launcher).chmod(0o755)
    if not (base / launcher).exists():
        try:
            (stage / name).rename(base / name)
        except OSError:
            if not (base / launcher).is_file():
                raise
    (base / launcher).chmod(0o755)
PY
