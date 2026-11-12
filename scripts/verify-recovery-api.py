"""Verify a recovered service through private HTTP reads; never target primary port 8080."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request
from datetime import datetime, timezone


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8082)
    parser.add_argument("--order-id", required=True)
    parser.add_argument("--file-id", required=True)
    parser.add_argument("--expected-image", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    if args.port == 8080 or not 1024 <= args.port <= 65535:
        raise ValueError("Use an isolated recovered API port, never the primary service")
    if not args.order_id.isdecimal() or not args.file_id.isdecimal():
        raise ValueError("Use explicit recovered numeric record IDs")
    image = args.expected_image.resolve(strict=True)
    if "recovery" not in image.parts or not image.is_relative_to(Path(__file__).resolve().parents[1] / "data/recovery"):
        raise ValueError("Expected image must be in the paired private recovery directory")
    username = os.environ["RECOVERY_API_USERNAME"]
    password = os.environ["RECOVERY_API_PASSWORD"]
    base = f"http://127.0.0.1:{args.port}/api/v1"
    token = None

    def request(method, path, body=None, binary=False):
        data = None if body is None else json.dumps(body).encode()
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=20) as response:
            content = response.read()
            return content if binary else json.loads(content)["data"]

    try:
        health = request("GET", "/health")
        if health.get("status") != "UP":
            raise RuntimeError("Recovered service is unhealthy")
        login = request("POST", "/auth/login", {"username": username, "password": password, "clientType": "MOBILE"})
        token = login["token"]
        order = request("GET", "/repairs/" + args.order_id)
        events = request("GET", "/repairs/" + args.order_id + "/events")
        content = request("GET", "/files/" + args.file_id + "/content", binary=True)
        image_hash = hashlib.sha256(content).hexdigest()
        if str(order["id"]) != args.order_id or not events or image_hash != hashlib.sha256(image.read_bytes()).hexdigest():
            raise RuntimeError("Recovered API history or image differs from its paired snapshot")
        report = {"passed": True, "verifiedAt": datetime.now(timezone.utc).isoformat(), "port": args.port,
                  "recoveredApiHealth": "UP", "authenticatedOrderRead": True,
                  "orderId": args.order_id, "status": order["status"], "progressCount": len(events),
                  "privateImageReadable": True, "fileId": args.file_id, "imageBytes": len(content),
                  "imageSha256": image_hash, "matchesPairedImage": True, "credentialsOrTokenRecorded": False}
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report))
    finally:
        if token:
            request("POST", "/auth/logout")


if __name__ == "__main__":
    main()
