"""Check publishable files against actual local openGauss/SSH secrets without printing values."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
environment = ROOT / 'deploy/.env.opengauss'
secrets = {}
if environment.exists():
    for line in environment.read_text(encoding='utf-8-sig').splitlines():
        if '=' not in line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key in ('SSH_PASSWORD', 'OG_PASSWORD', 'AI_API_KEY') and value:
            secrets[key] = value.encode('utf-8')
paths = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).split(b'\0')
failures = []
for name in set(paths):
    if not name:
        continue
    path = ROOT / name.decode('utf-8')
    if not path.is_file():
        continue
    data = path.read_bytes()
    for key, value in secrets.items():
        if value in data:
            failures.append((str(path.relative_to(ROOT)),key))
if failures:
    for path, key in failures:
        print(f'Blocked: {path} contains local {key}')
    raise SystemExit(1)
print(f'Publishable files checked against {len(secrets)} configured local secrets; no values printed.')
