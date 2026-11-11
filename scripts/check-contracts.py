"""Regenerate all three contract artifacts and fail on drift or API 1.1 removal."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ('contracts/openapi.json','web/src/generated/api.ts','harmony/entry/src/main/ets/models/ApiModels.ets')


def digest(path):
    # Git/Windows may normalize line endings; compare generated text content across hosts.
    return hashlib.sha256((ROOT / path).read_text(encoding='utf-8-sig').encode('utf-8')).hexdigest()


def check_compatibility(old, current, path='API'):
    """Old public fields and required inputs must retain their wire meaning."""
    if isinstance(old, dict):
        for key, value in old.items():
            if key in ('description', 'summary', 'info', 'operationId'):
                continue
            if key not in current:
                raise ValueError(f'{path}.{key}: removed')
            check_compatibility(value, current[key], f'{path}.{key}')
        if isinstance(old.get('required'), list) and set(current.get('required', [])) != set(old['required']):
            raise ValueError(f'{path}: changed required fields')
    elif isinstance(old, list):
        if old != current:
            raise ValueError(f'{path}: changed list/enum contract')
    elif old != current:
        raise ValueError(f'{path}: changed from {old!r} to {current!r}')


def main():
    before = {path:digest(path) for path in ARTIFACTS}
    npm = shutil.which('npm.cmd') or shutil.which('npm')
    if not npm:
        raise SystemExit('npm is required; run npm ci --prefix web first')
    for command in ([sys.executable,'contracts/generate_openapi.py'],[npm,'--prefix','web','run','generate:api'],[sys.executable,'harmony/generate-models.py']):
        subprocess.run(command, cwd=ROOT, check=True)
    old = json.loads((ROOT / 'contracts/compatibility/v1.1.json').read_text(encoding='utf-8'))
    current = json.loads((ROOT / ARTIFACTS[0]).read_text(encoding='utf-8'))
    check_compatibility(old, current)
    drift = [path for path in ARTIFACTS if digest(path) != before[path]]
    if drift:
        raise SystemExit('Generated contract drift: '+', '.join(drift))
    print('API 1.1 compatibility and all generated artifacts passed.')


if __name__ == '__main__':
    main()
