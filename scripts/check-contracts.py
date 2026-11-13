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


def check_compatibility(old, current, path='API', request_body=False):
    """Old public fields and required inputs must retain their wire meaning."""
    if isinstance(old, dict):
        for key, value in old.items():
            if key in ('description', 'summary', 'info', 'operationId'):
                continue
            if key not in current:
                raise ValueError(f'{path}.{key}: removed')
            # Only an operation's input body admits new enum values. Response and shared
            # component enums retain the existing strict contract (clients may exhaust them).
            input_body = request_body or (key == 'requestBody' and path.startswith('API.paths.')
                and path.rsplit('.', 1)[-1] in ('get','post','put','patch','delete','head','options'))
            check_compatibility(value, current[key], f'{path}.{key}', input_body)
        if isinstance(old.get('required'), list) and set(current.get('required', [])) != set(old['required']):
            raise ValueError(f'{path}: changed required fields')
    elif isinstance(old, list):
        if request_body and path.endswith('.enum') and isinstance(current, list):
            if (all(isinstance(value, str) for value in old + current)
                    and len(set(current)) == len(current) and set(old).issubset(current)):
                return
        if old != current:
            raise ValueError(f'{path}: changed list/enum contract')
    elif old != current:
        raise ValueError(f'{path}: changed from {old!r} to {current!r}')


def check_upload_contract(current):
    usage = current['paths']['/files']['post']['requestBody']['content']['multipart/form-data']['schema']['properties']['usage']['enum']
    if set(usage) != {'REPORT', 'RESULT', 'INSPECTION', 'REMEDIATION'}:
        raise ValueError('Multipart upload contract must include the student REMEDIATION evidence usage')


def check_compatibility_fixtures():
    def request(schema):
        return {'paths': {'/files': {'post': {'requestBody': {'content': {'multipart/form-data': {'schema': schema}}}}}}}

    def response(schema):
        return {'paths': {'/files': {'post': {'responses': {'200': {'content': {'application/json': {'schema': schema}}}}}}}}

    check_compatibility(request({'enum': ['REPORT']}), request({'enum': ['REPORT', 'REMEDIATION']}))
    negatives = [(request({'enum': ['REPORT', 'RESULT']}), request({'enum': ['REPORT']})),
                 (response({'enum': ['REPORT']}), response({'enum': ['REPORT', 'OTHER']})),
                 (request({'required': ['file']}), request({'required': ['file', 'extra']})),
                 ({'components': {'schemas': {'Usage': {'enum': ['REPORT']}}}},
                  {'components': {'schemas': {'Usage': {'enum': ['REPORT', 'OTHER']}}}})]
    for before, after in negatives:
        try:
            check_compatibility(before, after)
        except ValueError:
            continue
        raise AssertionError('Compatibility gate accepted an incompatible fixture')


def main():
    before = {path:digest(path) for path in ARTIFACTS}
    npm = shutil.which('npm.cmd') or shutil.which('npm')
    if not npm:
        raise SystemExit('npm is required; run npm ci --prefix web first')
    for command in ([sys.executable,'contracts/generate_openapi.py'],[npm,'--prefix','web','run','generate:api'],[sys.executable,'harmony/generate-models.py']):
        subprocess.run(command, cwd=ROOT, check=True)
    old = json.loads((ROOT / 'contracts/compatibility/v1.1.json').read_text(encoding='utf-8'))
    current = json.loads((ROOT / ARTIFACTS[0]).read_text(encoding='utf-8'))
    check_upload_contract(current)
    check_compatibility_fixtures()
    check_compatibility(old, current)
    drift = [path for path in ARTIFACTS if digest(path) != before[path]]
    if drift:
        raise SystemExit('Generated contract drift: '+', '.join(drift))
    print('API 1.1 compatibility and all generated artifacts passed.')


if __name__ == '__main__':
    main()
