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

function startController(request, patch) {
  // Only the native I/O ports are replaced; exercise the real controller and revision gate.
  class NoTimers {
    setSession() {}
    setForeground() {}
    stop() {}
  }
  const api = { request, patch };
  const Controller = new Function('Api', 'SubmissionKeys', 'RequestFlight', 'RouteRevision', 'ForegroundPoll', 'AppVisibility', 'util',
    executable + '\nreturn DispatchController;')(api, SubmissionKeys, RequestFlight, RouteRevision, NoTimers,
    { subscribe: callback => { callback(true); return () => {}; } }, { generateRandomUUID: () => 'test-key' });
  const controller = new Controller();
  controller.startSession({ id: 'manager-1', role: 'MANAGER' }, 'test-session', () => {});
  return controller;
}

const board = (revision, userId = 'manager-1') => ({ revision, me: { userId }, changes: [] });

test('background refresh clears an HTTP error only after a current-account accepted board', async () => {
  let result;
  const controller = startController(async () => {
    if (result instanceof Error) throw result;
    return result;
  });
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

test('manual refresh during a settings save leaves the read to the completed mutation', async () => {
  let reads = 0;
  let complete;
  const pending = new Promise(resolve => { complete = resolve; });
  const controller = startController(async () => board(++reads), () => pending);
  const saving = controller.saveSettings({ acceptingNewTasks: true });
  await controller.refresh();
  assert.equal(reads, 0);

  complete();
  assert.equal(await saving, true);
  assert.equal(reads, 1);
  assert.equal(controller.snapshot().board.revision, 1);
  controller.stopSession();
});

test('route updates describe up to three changes and prompt a station check when details are absent', async () => {
  let result = board(12);
  const controller = startController(async () => result);
  await controller.refresh();
  assert.equal(controller.snapshot().notice, '');

  result = {...board(13), changes: ['新增站点', '顺序调整', '上门时间变化', '地点已更新']};
  await controller.refresh();
  assert.equal(controller.snapshot().notice, '路线已更新：新增站点；顺序调整；上门时间变化');

  controller.dismissNotice();
  await controller.refresh();
  assert.equal(controller.snapshot().notice, '');
  result = board(14);
  await controller.refresh();
  assert.equal(controller.snapshot().notice, '路线已更新，请核对剩余站点。');
  controller.stopSession();
});
