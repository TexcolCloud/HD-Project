import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

async function load(file, bindings) {
  if (file.endsWith('ServiceCenterController.ets')) {
    const { SubmissionKeys } = await load('services/SubmissionKeys.ets', {});
    bindings = { ...bindings, isSessionExpiry: e => e.status === 401 && e.sessionEpoch === bindings.Api.epoch, SubmissionKeys, util: { generateRandomUUID: () => 'generated-key' } };
  }
  const raw = await readFile(new URL('../entry/src/main/ets/' + file, import.meta.url), 'utf8');
  const names = [...raw.matchAll(/^export (?:class|function) (\w+)/gm)].map(m => m[1]);
  const source = stripTypeScriptTypes(raw.replace(/^import[\s\S]*?;[ \t]*(?:\r?\n|$)/gm, '').replace(/^@Observed\s*$/gm, '')).replace(/^export /gm, '');
  return new Function(...Object.keys(bindings), source + '\nreturn {' + names.join(',') + '};')(...Object.values(bindings));
}

test('an inbox response from a previous account cannot enter the current account', async () => {
  const responses = [];
  const Api = { epoch: 1, request: () => new Promise(resolve => responses.push(resolve)) };
  const { ReadScope } = await load('features/ReadScope.ets', { Api });
  const { ServiceCenterController } = await load('features/service/ServiceCenterController.ets', { Api, ReadScope });
  const center = new ServiceCenterController({ fail: e => { throw e; } });
  const old = center.loadMessages();
  center.reset();
  const current = center.loadMessages();
  responses[1]({ items: [{ id: 'current' }], total: 1 });
  await current;
  responses[0]({ items: [{ id: 'old' }], total: 1 });
  await old;
  assert.deepEqual(center.state.messages, [{ id: 'current' }]);
});

test('retrying an uncertain remediation submission retains its idempotency key', async () => {
  const keys = [];
  const Api = { epoch: 1, post: async (_path, _body, key) => { keys.push(key); throw new Error('network'); } };
  const { ReadScope } = await load('features/ReadScope.ets', { Api });
  const { ServiceCenterController } = await load('features/service/ServiceCenterController.ets', { Api, ReadScope });
  const center = new ServiceCenterController({ fail() {} });
  center.state.selected = { id: '1', version: 1 };
  center.state.feedback = '已清理';
  await center.feedback([]);
  await center.feedback([]);
  assert.ok(keys[0]);
  assert.equal(keys[0], keys[1]);
});

test('a late feedback result cannot replace another remediation detail', async () => {
  let finish;
  const Api = { epoch: 1, post: () => new Promise(resolve => { finish = resolve; }) };
  const { ReadScope } = await load('features/ReadScope.ets', { Api });
  const { ServiceCenterController } = await load('features/service/ServiceCenterController.ets', { Api, ReadScope });
  const center = new ServiceCenterController({ fail: e => { throw e; }, flash() {} });
  center.state.selected = { id: 'A', version: 1 };
  const pending = center.feedback([]);
  center.suspend();
  center.state.selected = { id: 'B', version: 1 };
  finish({ id: 'A', version: 2 });
  await pending;
  assert.equal(center.state.selected.id, 'B');
});
