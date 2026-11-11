"""Pinned Java formatter in four-space AOSP mode; check by default, --write to apply."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VERSION = '1.28.0'
DIGEST = '32342e7c1b4600f80df3471da46aee8012d3e1445d5ea1be1fb71289b07cc735'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    jar = ROOT / 'backend/.mvn/google-java-format.jar'
    if not jar.exists() or hashlib.sha256(jar.read_bytes()).hexdigest() != DIGEST:
        jar.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(f'https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/{VERSION}/google-java-format-{VERSION}-all-deps.jar', jar)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != DIGEST:
        raise SystemExit('Java formatter checksum mismatch')
    files = sorted(str(path) for path in (ROOT / 'backend/src').rglob('*.java'))
    mode = ['--replace'] if args.write else ['--dry-run','--set-exit-if-changed']
    result = subprocess.run(['java','-jar',str(jar),'--aosp',*mode,*files], cwd=ROOT)
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
