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

test('returning to review selects only a currently qualifying recheck of the same room and inspection', async () => {
  let items = [];
  const Api = {epoch:1, request:async () => ({items})};
  const {ReadScope} = await load('features/ReadScope.ets', {Api});
  const {ServiceCenterController} = await load('features/service/ServiceCenterController.ets', {Api,ReadScope});
  const center = new ServiceCenterController({fail:e=>{throw e;}});
  center.state.selected = {id:'task', roomId:'room', inspectionId:'original'};
  const saved = {id:'check', roomId:'room', relatedInspectionId:'original', round:'RECHECK', totalScore:80};
  await center.loadRechecks(saved);
  assert.equal(center.state.recheckId, 'check');
  items = [{...saved, voidedAt: '2026-11-20T10:30:00Z'}];
  await center.loadRechecks(saved);
  assert.equal(center.state.recheckId, '');
  items = [];
  await center.loadRechecks({...saved, roomId:'different'});
  assert.deepEqual(center.state.rechecks, []);
  await center.loadRechecks({...saved, totalScore:59});
  assert.equal(center.state.recheckId, '');
});

test('leaving a remediation retires a pending source-inspection lookup', async () => {
  let finish;
  const Api = {epoch:1, request:()=>new Promise(resolve=>{finish=resolve;})};
  const {ReadScope} = await load('features/ReadScope.ets', {Api});
  const {ServiceCenterController} = await load('features/service/ServiceCenterController.ets', {Api, ReadScope});
  const center = new ServiceCenterController({fail:e=>{throw e;}});
  center.state.selected = {id:'task', inspectionId:'original',status:'SUBMITTED'};
  let opened=false;
  const pending=center.beginRecheck(async()=>{opened=true;});
  center.suspend();
  finish({id:'original'});
  await pending;
  assert.equal(opened,false);
});

test('opening a visitor message still opens its record when the versioned read fails', async () => {
  const writes = [], opened = [], errors = [];
  const Api = {
    epoch: 1,
    post: async (path, body, key) => {
      writes.push([path, body, key]);
      throw new Error('read failed');
    },
  };
  const { ReadScope } = await load('features/ReadScope.ets', { Api });
  const { ServiceCenterController } = await load('features/service/ServiceCenterController.ets', { Api, ReadScope });
  const center = new ServiceCenterController({ openVisitor: id => opened.push(id), fail: e => errors.push(e.message) });
  await center.openMessage({ id: 'm', version: 2, readAt: null, businessType: 'VISITOR', businessId: 'v3' });
  assert.deepEqual(opened, ['v3']);
  assert.deepEqual(writes, [['/messages/m/read', { expectedVersion: 2 }, 'generated-key']]);
  assert.deepEqual(errors, ['read failed']);
});

test('batch read skips previously read messages and returns to the last valid unread page', async () => {
  const writes = [], gets = [];
  const Api = {
    epoch: 2,
    post: async path => {
      writes.push(path);
      return { id: path.split('/')[2], readAt: 'today', version: 4 };
    },
    request: async path => {
      gets.push(path);
      return { items: [], total: 10 };
    },
  };
  const { ReadScope } = await load('features/ReadScope.ets', { Api });
  const { ServiceCenterController } = await load('features/service/ServiceCenterController.ets', { Api, ReadScope });
  let decrements = 0;
  const center = new ServiceCenterController({ messageRead: () => decrements++, fail: e => { throw e; } });
  Object.assign(center.state, {
    messages: [{ id: '1', readAt: null, version: 3 }, { id: '2', readAt: 'already', version: 2 }, { id: '3', readAt: null, version: 1 }],
    messagePage: 2, unreadOnly: true, messageTotal: 12,
  });
  await center.readPage();
  assert.equal(decrements, 2);
  assert.deepEqual(writes, ['/messages/1/read', '/messages/3/read']);
  assert.deepEqual(gets, ['/messages?pageSize=10&page=1&unreadOnly=true']);
});
