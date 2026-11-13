"""Bounded mixed workload on isolated port 8081; fictional data and aggregate reports only."""
import argparse
import base64
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import runpy
import threading
import time
import uuid

Api = runpy.run_path(str(Path(__file__).with_name('acceptance-api.py')))['Api']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:8081/api/v1')
    parser.add_argument('--database', required=True)
    parser.add_argument('--iterations', type=int, default=8)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if args.base != 'http://127.0.0.1:8081/api/v1' or not re.fullmatch(r'dormlink_[a-z0-9_]+_test', args.database):
        raise SystemExit('Only isolated local port 8081 and a dormlink_*_test database are accepted')
    if not 3 <= args.iterations <= 20:
        raise SystemExit('Use 3..20 iterations')
    report = {'startedAt': datetime.now(timezone.utc).isoformat(), 'database': args.database,
              'scope': 'bounded synthetic mixed workload, not production capacity or hardware evidence',
              'topLevelWorkers': 3, 'acceptRaceWorkers': 2, 'iterations': args.iterations,
              'requests': {}, 'checks': {}, 'unexpected': [], 'passed': False}
    lock = threading.Lock()
    measured = defaultdict(list)
    clients = []
    orders, visitors = [], []
    suffix = uuid.uuid4().hex[:10]
    started = time.monotonic()
    run_deadline = started + 180

    def client(username):
        value = Api(args.base, username)
        clients.append(value)
        return value

    def call(api, group, method, path, body=None, expected=(200,), **kwargs):
        if time.monotonic() >= run_deadline:
            raise TimeoutError('Mixed workload exceeded its three-minute request budget')
        start = time.perf_counter()
        try:
            status, payload = api.request(method, path, body, **kwargs)
        except Exception as cause:
            with lock:
                report['unexpected'].append({'group': group, 'failure': type(cause).__name__})
            raise
        with lock:
            measured[group].append(((time.perf_counter() - start) * 1000, status))
            if status not in expected:
                report['unexpected'].append({'group': group, 'status': status,
                    'code': payload.get('error', {}).get('code') if isinstance(payload, dict) else 'binary'})
        if status not in expected:
            raise AssertionError(f'{group}: unexpected HTTP {status}')
        return status, payload.get('data') if isinstance(payload, dict) else payload

    def ok(api, group, method, path, body=None, **kwargs):
        return call(api, group, method, path, body, **kwargs)[1]

    student, manager, contender, outsider, observer, device = (
        client('student1'), client('manager1'), client('manager1'),
        client('student2'), client('manager1'), client('manager1'))
    room = ok(student, 'setup', 'GET', '/me/room')
    ok(manager, 'heartbeat', 'POST', '/dispatch/heartbeat', {})
    ok(contender, 'heartbeat', 'POST', '/dispatch/heartbeat', {})
    student_id = student.user['studentId']
    for i in range(3):
        visitors.append(ok(manager, 'setup', 'POST', '/visitors', {
            'visitorName': f'QA-mixed-{suffix}-{i}', 'phone': '13800000000',
            'visitedStudentId': student_id, 'purpose': '虚构可靠性验收',
            'arrivedAt': (datetime.now(timezone.utc) - timedelta(hours=13)).isoformat(), 'confirmDuplicate': True}))

    def repair_worker():
        png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4//8/AAX+Av4N70a4AAAAAElFTkSuQmCC')
        with ThreadPoolExecutor(max_workers=2) as race:
            for i in range(args.iterations):
                boundary = 'DormLink' + uuid.uuid4().hex
                raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="usage"\r\n\r\nREPORT\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n').encode() + png + f'\r\n--{boundary}--\r\n'.encode()
                image = ok(student, 'upload', 'POST', '/files', raw=raw, content_type='multipart/form-data; boundary=' + boundary)
                call(outsider, 'private-denial', 'GET', f'/files/{image["id"]}/content', expected=(404,))
                body = {'title': f'QA-mixed-{suffix}-{i}', 'description': '虚构管道滴水验收',
                    'category': 'UTILITIES', 'priority': 'NORMAL', 'contactName': '虚构学生',
                    'contactPhone': '13800000000', 'reportImageIds': [image['id']]}
                key = str(uuid.uuid4())
                order = ok(student, 'repair-create', 'POST', '/repairs', body, key=key)
                duplicate = ok(student, 'repair-idempotent', 'POST', '/repairs', body, key=key)
                assert duplicate['id'] == order['id'], 'duplicate repair created'
                call(student, 'idempotency-conflict', 'POST', '/repairs', {**body, 'title': body['title'] + ' changed'}, key=key, expected=(409,))
                path = '/repairs/' + order['id']
                futures = [race.submit(call, actor, 'accept-race', 'POST', path + '/accept',
                    {'expectedVersion': order['version']}, expected=(200, 409)) for actor in (manager, contender)]
                codes = sorted(value.result()[0] for value in futures)
                assert codes == [200, 409], f'accept race {codes}'
                order = ok(manager, 'repair-state', 'GET', path)
                order = ok(manager, 'repair-state', 'POST', path + '/start', {'expectedVersion': order['version']})
                order = ok(manager, 'repair-state', 'POST', path + '/finish', {'expectedVersion': order['version'], 'resultDescription': '虚构处理完成', 'resultImageIds': []})
                order = ok(student, 'repair-state', 'POST', path + '/confirm', {'expectedVersion': order['version']})
                assert order['status'] == 'COMPLETED'
                events = ok(student, 'consistency', 'GET', path + '/events')
                assert [event['action'] for event in events] == ['submit', 'accept', 'start', 'finish', 'confirm']
                assert len({event['id'] for event in events}) == 5
                assert ok(student, 'private-content', 'GET', f'/files/{image["id"]}/content')
                call(outsider, 'private-denial', 'GET', f'/files/{image["id"]}/content', expected=(404,))
                orders.append(order['id'])
                ok(manager, 'heartbeat', 'POST', '/dispatch/heartbeat', {})
        report['checks']['repairLifecycleAndUniqueHistory'] = len(orders)
        report['checks']['privateImageBindings'] = len(orders)

    def read_worker():
        for i in range(args.iterations * 4):
            board = ok(observer, 'dispatch-board', 'GET', '/dispatch/board')
            identifiers = [task['orderId'] for route in board['routes'] for task in route['tasks']]
            identifiers += [entry['task']['orderId'] for entry in board['unassigned']]
            assert len(identifiers) == len(set(identifiers)), 'duplicate order in plan'
            assert board['onlineCount'] == 1, 'sessions counted as distinct managers'
            ok(observer, 'messages', 'GET', '/messages?pageSize=100')
            ok(observer, 'messages', 'GET', '/messages/summary')
            ok(observer, 'repair-list', 'GET', '/repairs?pageSize=20')
            if i % 8 == 0:
                call(observer, 'replan-race', 'POST', '/dispatch/replan', {}, expected=(200, 409))
            time.sleep(.15)
        report['checks']['snapshotOrdersUniqueAndAccountsDeduplicated'] = True

    def device_worker():
        view = ok(device, 'mobility', 'GET', '/mobility/session')
        view = ok(device, 'mobility', 'POST', '/mobility/sessions', {
            'clientInstanceId': 'synthetic-' + suffix, 'consentVersion': view['policy']['consentVersion'],
            'backgroundRequested': False, 'takeover': False})
        session = view['session']
        start = time.monotonic()
        accepted = 0
        for i in range(1, args.iterations * 2 + 1):
            body = {'sourceEpoch': session['sourceEpoch'], 'batchId': f'{suffix}-{i}', 'samples': [{
                'sequence': i, 'streamId': suffix, 'observedAt': datetime.now(timezone.utc).isoformat(),
                'elapsedMs': (time.monotonic() - start) * 1000, 'stepDelta': 1, 'motion': 'UNKNOWN', 'clientQuality': 'LOW'}]}
            path = f'/mobility/sessions/{session["id"]}/samples'
            ack = ok(device, 'observations', 'POST', path, body)
            assert ack['acceptedCount'] == 1
            replay = ok(device, 'observation-replay', 'POST', path, body)
            assert replay == ack
            call(device, 'source-denial', 'POST', path, {**body, 'sourceEpoch': session['sourceEpoch'] + 1}, expected=(409,))
            accepted += 1
            time.sleep(1.05)
        view = ok(device, 'mobility', 'GET', '/mobility/session')
        view = ok(device, 'mobility', 'POST', f'/mobility/sessions/{session["id"]}/pause', {
            'sourceEpoch': view['session']['sourceEpoch'], 'expectedVersion': view['session']['version']})
        assert view['context']['latitude'] is None and view['context']['speedMps'] is None
        ok(device, 'mobility', 'POST', f'/mobility/sessions/{session["id"]}/end', {
            'sourceEpoch': view['session']['sourceEpoch'], 'expectedVersion': view['session']['version']})
        report['checks']['syntheticObservationsAdmittedAndFenced'] = accepted
        report['checks']['pauseClearsLocation'] = True

    try:
        with ThreadPoolExecutor(max_workers=3) as workers:
            tasks = [workers.submit(function) for function in (repair_worker, read_worker, device_worker)]
            for task in tasks:
                task.result()
        expected_ids = {value['id'] for value in visitors}
        deadline = time.monotonic() + 70
        while True:
            inbox = ok(manager, 'followup-consistency', 'GET', '/messages?pageSize=100')
            found = {value['businessId'] for value in inbox['items'] if value['kind'] == 'VISITOR_CHECKOUT_REMINDER'}
            if expected_ids <= found:
                break
            if time.monotonic() >= deadline:
                raise AssertionError('bounded reminder sweep did not deliver fixture reminders')
            time.sleep(2)
        report['checks']['backgroundFollowupDelivered'] = len(expected_ids)
        for visitor in visitors:
            current = ok(manager, 'consistency', 'GET', '/visitors/' + visitor['id'])
            assert current['status'] == 'VISITING', 'follow-up silently checked visitor out'
            ok(manager, 'cleanup', 'POST', '/visitors/' + visitor['id'] + '/leave', {'expectedVersion': current['version']})
        report['passed'] = not report['unexpected']
    except Exception as cause:
        report['failureType'] = type(cause).__name__
        report['passed'] = False
        raise
    finally:
        for api in clients:
            api.request('POST', '/auth/logout', {})
        for name, values in measured.items():
            timings = sorted(value[0] for value in values)
            report['requests'][name] = {'count': len(values), 'statusCounts': dict(Counter(status for _, status in values)),
                'p50Ms': round(timings[int((len(timings) - 1) * .5)], 2),
                'p95Ms': round(timings[int((len(timings) - 1) * .95)], 2), 'maxMs': round(max(timings), 2)}
        all_values = [value for values in measured.values() for value in values]
        all_timings = sorted(value[0] for value in all_values)
        report['statusCounts'] = dict(Counter(status for _, status in all_values))
        report['serverErrorCount'] = sum(status >= 500 for _, status in all_values)
        report['expectedConflictCount'] = sum(status == 409 for _, status in all_values)
        report['p95Ms'] = round(all_timings[int((len(all_timings) - 1) * .95)], 2) if all_timings else None
        report['finishedAt'] = datetime.now(timezone.utc).isoformat()
        report['elapsedSeconds'] = round(time.monotonic() - started, 2)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': report['passed'], 'requests': sum(len(value) for value in measured.values()),
                          'unexpected': len(report['unexpected']), 'checks': report['checks']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
