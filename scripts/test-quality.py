"""Executable PMD positive/negative Java 21 fixtures, including a syntax failure."""
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = {
    'valid': ('record Point(int x) { String describe(Object value) { if (value instanceof String text) { return text; } return """\npoint\n"""; } }', False),
    'violation': ('class bad_name { void bad_method() { if (true) System.out.println("bad"); } }', True),
    'parse-error': ('class Broken { this is not Java; }', True),
}
results = {}
for name, (source, must_fail) in FIXTURES.items():
    directory = ROOT / '.tools/quality-fixtures' / name
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'Fixture.java').write_text(source, encoding='utf-8')
    report = f'output/quality/fixture-{name}.json'
    result = subprocess.run([sys.executable,str(ROOT / 'scripts/check-java.py'),
        '--source',str(directory.relative_to(ROOT)), '--report',report], cwd=ROOT)
    if (result.returncode != 0) != must_fail:
        raise SystemExit(f'{name}: unexpected scanner result {result.returncode}')
    data = json.loads((ROOT / report).read_text(encoding='utf-8'))
    if name == 'parse-error' and not data.get('processingErrors'):
        raise SystemExit('Syntax error was not recognized as a processing error')
    results[name] = 'PASS (expected failure)' if must_fail else 'PASS'
print(json.dumps(results))
