"""Summarize JUnit XML without treating pure Java/H2 suites as real openGauss proof."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REAL_CAPABLE = {
    "AccountLifecycleIntegrationTest", "AiAdapterTest", "BackendRefactorIntegrationTest",
    "DispatchIntegrationTest", "MySqlConcurrencyTest", "PlatformIntegrationTest",
    "ServiceFollowupIntegrationTest", "MobilityServiceTest", "MobilityHttpIntegrationTest",
}
ALWAYS_H2 = {"DbJdbcCompatibilityTest", "OpenGaussSchemaTest", "FileCleanupTest"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", choices=("h2", "opengauss"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    suites = []
    for path in sorted((ROOT / "backend/target/surefire-reports").glob("TEST-*.xml")):
        node = ET.parse(path).getroot()
        name = node.attrib["name"]
        short = name.rsplit(".", 1)[-1]
        if short in REAL_CAPABLE:
            backend = "openGauss" if args.database == "opengauss" else "H2 or optional database tests skipped"
        elif short in ALWAYS_H2:
            backend = "H2"
        else:
            backend = "pure Java / architecture"
        suites.append({"name": name, "backend": backend,
                       **{key: int(node.attrib.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")},
                       "seconds": float(node.attrib.get("time", 0))})
    if not suites:
        raise SystemExit("No JUnit results found")
    totals = {key: sum(s[key] for s in suites) for key in ("tests", "failures", "errors", "skipped")}
    groups = {}
    for suite in suites:
        group = groups.setdefault(suite["backend"], {key: 0 for key in totals})
        for key in totals:
            group[key] += suite[key]
    report = {"databaseRun": args.database, "verifiedAt": datetime.now(timezone.utc).isoformat(),
              "passed": totals["failures"] == 0 and totals["errors"] == 0,
              "totals": totals, "byBackend": groups, "suites": suites,
              "scope": "JUnit suite classification reflects its actual configured backend; skips and retained H2/pure checks are separate."}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"databaseRun": args.database, "totals": totals, "byBackend": groups}))
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
