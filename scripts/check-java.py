"""Pinned Java 21 PMD gate; scanner/configuration/parse errors fail closed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '7.29.0'
DIGEST = 'd27177c185b837fa57f097e482d1253468aa035379c5eb2671fa0a279109b179'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='backend/src/main/java')
    parser.add_argument('--report', default='output/quality/pmd.json')
    args = parser.parse_args()
    tools = ROOT / '.tools'
    tools.mkdir(exist_ok=True)
    executable = tools / f'pmd-bin-{VERSION}' / 'bin' / ('pmd.bat' if os.name == 'nt' else 'pmd')
    if not executable.exists():
        archive = tools / f'pmd-{VERSION}.zip'
        if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != DIGEST:
            urllib.request.urlretrieve(f'https://github.com/pmd/pmd/releases/download/pmd_releases/{VERSION}/pmd-dist-{VERSION}-bin.zip', archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != DIGEST:
            raise SystemExit('PMD archive checksum mismatch')
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(tools)
        if os.name != 'nt':
            executable.chmod(0o755)
    report = ROOT / args.report
    report.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run([str(executable), 'check', '--dir', str(ROOT / args.source),
        '--rulesets', str(ROOT / 'backend/config/pmd.xml'), '--use-version', 'java-21',
        '--format', 'json', '--report-file', str(report), '--no-cache', '--no-progress'], cwd=ROOT)
    if not report.exists():
        raise SystemExit('PMD did not produce a report')
    try:
        data = json.loads(report.read_text(encoding='utf-8'))
    except (ValueError, OSError):
        raise SystemExit('PMD failed to produce a valid JSON report; scanner/configuration errors cannot pass')
    errors = data.get('processingErrors', []) + data.get('configurationErrors', [])
    violations = sum(len(file['violations']) for file in data.get('files', []))
    print(json.dumps({'tool':f'PMD {VERSION}','java':'21','violations':violations,'errors':len(errors),'report':str(report.relative_to(ROOT))}))
    raise SystemExit(result.returncode if result.returncode else (1 if violations or errors else 0))


if __name__ == '__main__':
    main()
