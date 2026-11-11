"""Bounded read benchmark; reports aggregate latency/errors, never session tokens or data."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import runpy
import time

Api = runpy.run_path(str(Path(__file__).with_name('acceptance-api.py')))['Api']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='http://127.0.0.1:8081/api/v1')
    parser.add_argument('--label', required=True)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--requests', type=int, default=60)
    parser.add_argument('--concurrency', type=int, default=4)
    args = parser.parse_args()
    if not args.base.startswith('http://127.0.0.1:8081/'):
        raise SystemExit('Benchmark is restricted to isolated local port 8081')
    if not 10 <= args.requests <= 1000 or not 1 <= args.concurrency <= 20:
        raise SystemExit('Use 10..1000 requests and 1..20 workers')
    clients = [Api(args.base, 'manager1') for _ in range(args.concurrency)]
    report = {'label':args.label,'at':datetime.now(timezone.utc).isoformat(),
        'concurrency':args.concurrency,'requestsPerEndpoint':args.requests,'warmupPerWorker':2,'endpoints':{}}
    paths = ['/repairs?page=1&pageSize=20','/statistics/repairs','/dispatch/board']
    try:
        for path in paths:
            for client in clients:
                for _ in range(2):
                    client.ok('GET', path)
            def run(worker):
                values = []
                for _ in range(worker, args.requests, args.concurrency):
                    started = time.perf_counter()
                    status, _ = clients[worker].request('GET', path)
                    values.append(((time.perf_counter()-started)*1000,status))
                return values
            started = time.perf_counter()
            with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
                measured = [value for batch in pool.map(run,range(args.concurrency)) for value in batch]
            elapsed = time.perf_counter()-started
            timings = sorted(value[0] for value in measured)
            def percentile(p):
                return round(timings[min(len(timings)-1,int((len(timings)-1)*p))],2)
            report['endpoints'][path] = {'p50Ms':percentile(.5),'p95Ms':percentile(.95),
                'p99Ms':percentile(.99),'maxMs':round(max(timings),2),'errors':sum(status!=200 for _,status in measured),
                'throughputPerSecond':round(len(timings)/elapsed,2)}
            print(json.dumps({path:report['endpoints'][path]}),flush=True)
    finally:
        for client in clients:
            client.request('POST','/auth/logout')
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(report,indent=2),encoding='utf-8')


if __name__ == '__main__':
    main()
