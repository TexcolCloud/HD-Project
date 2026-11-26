"""Check publishable files for credentials. Diagnostics contain only paths and rule names."""
import codecs
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = {
    'API secret key': re.compile(rb'\bsk-[A-Za-z0-9]{32,}\b'),
    'private key header': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----'),
    'GitHub token': re.compile(rb'\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}\b'),
    'GitHub fine-grained token': re.compile(rb'\bgithub_pat_[A-Za-z0-9_]{82}\b'),
    'AWS access key ID': re.compile(rb'\bAKIA[0-9A-Z]{16}\b'),
    'live API key': re.compile(rb'\bsk-(?:live|proj)-[A-Za-z0-9_-]{20,}\b'),
}


def local_secrets():
    secrets = {}
    files = {
        '.env.opengauss': ('SSH_PASSWORD', 'OG_PASSWORD', 'OG_ADMIN_PASSWORD', 'AI_API_KEY',
                           'APP_SMS_GATEWAY_TOKEN', 'APP_SMS_SIGNING_KEY'),
        '.env': ('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD'),
    }
    for name, keys in files.items():
        path = ROOT / 'deploy' / name
        if not path.is_file():
            continue
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            key, _, value = line.partition('=')
            if key in keys and value:
                secrets[key] = value.encode('utf-8')
    return secrets


def matches(data, secrets):
    # Windows tooling can write otherwise ordinary text as UTF-16 or UTF-32.
    text = data
    for marks, encoding in (((codecs.BOM_UTF32_LE, codecs.BOM_UTF32_BE), 'utf-32'),
                            ((codecs.BOM_UTF16_LE, codecs.BOM_UTF16_BE), 'utf-16')):
        if data.startswith(marks):
            text = data.decode(encoding, errors='replace').encode('utf-8')
            break
    for name, value in secrets.items():
        if value in data or (text is not data and value in text):
            yield name
    if b'\0' not in text:
        for name, pattern in PATTERNS.items():
            if pattern.search(text):
                yield name


def report_path(relative, secrets):
    name = os.fsencode(relative.as_posix())
    for pattern in PATTERNS.values():
        name = pattern.sub(b'[redacted]', name)
    for value in secrets.values():
        name = name.replace(value, b'[redacted]')
    return os.fsdecode(name).replace('\r', r'\r').replace('\n', r'\n')


def main():
    files = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT)
    secrets = local_secrets()
    blocked = False
    for name in sorted(set(files.split(b'\0')) - {b''}):
        relative = Path(os.fsdecode(name))
        path = ROOT / relative
        if path.is_symlink():
            data = os.fsencode(os.readlink(path))
        elif path.is_file():
            data = path.read_bytes()
        else:
            continue
        for rule in matches(data, secrets):
            print(f'Blocked: {report_path(relative, secrets)} matches {rule}')
            blocked = True
    if not blocked:
        print(f'Publishable files checked against {len(PATTERNS)} patterns and '
              f'{len(secrets)} configured local secrets; no values printed.')
    return int(blocked)


if __name__ == '__main__':
    raise SystemExit(main())
