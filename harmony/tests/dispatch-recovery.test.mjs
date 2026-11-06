import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

async function loadHelper(path) {
  const source = await readFile(new URL(path, import.meta.url), 'utf8');
  return import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));
}
const [{ SubmissionKeys }, { RequestFlight }, { RouteRevision }] = await Promise.all([
  loadHelper('../entry/src/main/ets/services/SubmissionKeys.ets'),
  loadHelper('../entry/src/main/ets/features/dispatch/RequestFlight.ets'),
  loadHelper('../entry/src/main/ets/features/dispatch/RouteRevision.ets')
]);
const source = await readFile(new URL('../entry/src/main/ets/features/dispatch/DispatchController.ets', import.meta.url), 'utf8');
const executable = stripTypeScriptTypes(source.replace(/^import[\s\S]*?;[ \t]*(?:\r?\n|$)/gm, '')).replace(/^export /gm, '');

test('background refresh clears an HTTP error only after a current-account accepted board', async () => {
  // Only the native I/O ports are replaced; exercise the real controller and revision gate.
  class NoTimers {
    setSession() {}
    setForeground() {}
    stop() {}
  }
  let result;
  const api = { request: async () => {
    if (result instanceof Error) throw result;
    return result;
  } };
  const Controller = new Function('Api', 'SubmissionKeys', 'RequestFlight', 'RouteRevision', 'ForegroundPoll', 'AppVisibility', 'util',
    executable + '\nreturn DispatchController;')(api, SubmissionKeys, RequestFlight, RouteRevision, NoTimers,
    { subscribe: callback => { callback(true); return () => {}; } }, { generateRandomUUID: () => 'test-key' });
  const controller = new Controller();
  controller.startSession({ id: 'manager-1', role: 'MANAGER' }, 'test-session', () => {});
  const board = (revision, userId = 'manager-1') => ({ revision, me: { userId }, changes: [] });
  result = board(8); await controller.refresh(false);
  result = Object.assign(new Error('service restarting'), { status: 500, traceId: '' });
  await controller.refresh(false);
  assert.equal(controller.snapshot().error, 'service restarting');
  assert.equal(controller.snapshot().offline, false);
  result = board(9, 'manager-2'); await controller.refresh(false);
  assert.equal(controller.snapshot().error, 'service restarting');
  result = board(7); await controller.refresh(false);
  assert.equal(controller.snapshot().error, 'service restarting');
  assert.equal(controller.snapshot().board.revision, 8);
  result = board(8); await controller.refresh(false);
  assert.equal(controller.snapshot().error, '');
  assert.equal(controller.snapshot().offline, false);
  controller.stopSession();
});
