import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
const base = new URL('../entry/src/main/ets/features/mobility/', import.meta.url);
const pure = await readFile(new URL('MobilityEstimator.ets', base), 'utf8');
const { MobilityEstimator } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(pure)).toString('base64'));
const raw = await readFile(new URL('MobilityController.ets', base), 'utf8');
// Replace only native I/O module boundaries; the complete public controller executes unchanged.
const executable = stripTypeScriptTypes(raw.replace(/^import[\s\S]*?;[ \t]*(?:\r?\n|$)/gm, '')).replace(/^export /gm, '');
const policy = { consentVersion: 'mobility-v1', maxBatchSize: 20, maxBodyBytes: 16384, reportIntervalSeconds: 15, leaseSeconds: 120, contextTtlSeconds: 60 };
function fixture(timers = { setTimeout, clearTimeout }) {
  const state = { source: 1, version: 1, sessionState: 'ACTIVE' };
  const view = () => ({ session: { id: 'work-1', userId: 'manager-1', state: state.sessionState, sourceEpoch: state.source,
    version: state.version, leaseExpiresAt: new Date(Date.now() + 120000).toISOString(), backgroundRequested: false }, context: null, anchors: [], policy });
  class ApiError extends Error {}
  const calls = [], devices = [];
  let id = 0, visibility;
  const Api = { epoch: 1, request: async () => view(), post: async (path, body) => {
    calls.push({ path, body });
    if (path.endsWith('/samples')) return { batchId: body.batchId, context: null, acceptedCount: body.samples.length };
    if (path.endsWith('/pause')) state.sessionState = 'PAUSED';
    if (path.endsWith('/resume')) { state.sessionState = 'ACTIVE'; state.source++; }
    if (path.endsWith('/end')) state.sessionState = 'ENDED';
    return view();
  } };
  class Device {
    active = false; background = false; callbacks = []; stops = 0;
    constructor() { devices.push(this); }
    async start(_context, background, callback) { this.active = true; this.background = background; this.callbacks.push(callback); }
    stop() { this.active = false; this.stops++; }
    isCollecting() { return this.active; }
    isBackgroundEnabled() { return this.background && this.active; }
    emit(index = this.callbacks.length - 1) {
      this.callbacks[index]({ position: { observedMs: Date.now(), elapsedMs: Date.now(), latitude: 30, longitude: 110,
        accuracyMeters: 5, speedMps: 1.2, speedAccuracyMps: 0.5 }, stepDelta: 4, moving: true });
    }
  }
  const Controller = new Function('Api', 'ApiError', 'util', 'AppVisibility', 'SystemMobility', 'MobilityEstimator', 'setTimeout', 'clearTimeout', executable + '\nreturn MobilityController;')(
    Api, ApiError, { generateRandomUUID: () => 'id-' + (++id) }, { subscribe(callback) { visibility = callback; callback(true); return () => {}; } }, Device, MobilityEstimator, timers.setTimeout, timers.clearTimeout);
  const controller = new Controller();
  return { Api, calls, devices, controller, state, view, visibility: foreground => visibility(foreground) };
}
const settle = () => new Promise(resolve => setImmediate(resolve));
const attach = async f => { f.controller.attach({ id: 'manager-1', role: 'MANAGER' }, {}, () => {}); await settle(); };

test('permission-free login does not start sensors; explicit start, pause and resume reset stream/source', async () => {
  const f = fixture(); await attach(f);
  assert.equal(f.devices[0].callbacks.length, 0);
  await f.controller.start(false); f.devices[0].emit(); await settle();
  const first = f.calls.find(call => call.path.endsWith('/samples')).body;
  await f.controller.action('pause');
  assert.equal(f.devices[0].active, false); const before = f.calls.length;
  f.devices[0].emit(0); await settle(); assert.equal(f.calls.length, before);
  await f.controller.action('resume'); f.devices[0].emit(); await settle();
  const batches = f.calls.filter(call => call.path.endsWith('/samples'));
  assert.equal(batches.length, 2); assert.equal(batches[1].body.sourceEpoch, 2);
  assert.notEqual(batches[1].body.samples[0].streamId, first.samples[0].streamId);
  assert.equal(batches[1].body.samples[0].sequence, 1);
  f.controller.detach();
});

test('late batch ACK and old native callback cannot populate a replacement login', async () => {
  const f = fixture(); await attach(f); await f.controller.start(false);
  let finish;
  const post = f.Api.post;
  f.Api.post = (path, body) => path.endsWith('/samples') ? new Promise(resolve => finish = resolve) : post(path, body);
  f.devices[0].emit(); f.Api.epoch++; f.controller.detach();
  finish({ context: { userId: 'manager-1', contextVersion: 20, latitude: 30 } });
  await settle(); f.devices[0].emit(); await settle();
  assert.equal(f.controller.snapshot().view, null); assert.equal(f.controller.snapshot().collecting, false);
});

test('foreground-only capture stops locally before the pause HTTP request completes', async () => {
  const f = fixture(); await attach(f); await f.controller.start(false);
  f.Api.post = () => new Promise(() => {});
  f.visibility(false);
  assert.equal(f.devices[0].active, false); assert.equal(f.controller.snapshot().collecting, false);
  f.controller.detach();
});

test('single-flight batches cap queued samples and preserve identical retry identity', async () => {
  const realNow = Date.now; let clock = realNow(); Date.now = () => clock;
  try {
    const f = fixture(); await attach(f); await f.controller.start(false);
    const sent = []; let reject;
    f.Api.post = (path, body) => { sent.push(body); return new Promise((_resolve, no) => { reject = no; }); };
    f.devices[0].emit();
    for (let i = 0; i < 100; i++) { clock += 100; f.devices[0].emit(); }
    assert.equal(sent.length, 1);
    reject(new Error('offline')); await settle();
    clock += 15000; f.devices[0].emit();
    assert.equal(sent.length, 2); assert.deepEqual(sent[0], sent[1]);
    assert.ok(sent[1].samples.length <= 20); assert.ok(JSON.stringify(sent[1]).length <= 16384);
    f.controller.detach(); reject(new Error('offline')); await settle();
  } finally { Date.now = realNow; }
});
test('an older same-account GET cannot restore a superseded source after explicit start', async () => {
  const f = fixture(); await attach(f); let complete;
  f.Api.request = () => new Promise(resolve => complete = resolve);
  const pending = f.controller.refresh();
  f.Api.post = async () => ({ ...f.view(), session: { ...f.view().session, id: 'new-work' } });
  await f.controller.start(false, true);
  complete({ ...f.view(), session: { ...f.view().session, id: 'old-work' } });
  await pending;
  assert.equal(f.controller.snapshot().view.session.id, 'new-work'); assert.equal(f.controller.snapshot().collecting, true);
  f.controller.detach();
});
test('a native callback after lease expiry stops capture without uploading another observation', async () => {
  const realNow = Date.now; let clock = realNow(); Date.now = () => clock;
  try {
    const f = fixture(); await attach(f); await f.controller.start(false);
    const before = f.calls.length; clock += 130000; f.devices[0].emit(); await settle();
    assert.equal(f.calls.length, before); assert.equal(f.controller.snapshot().collecting, false);
    assert.match(f.controller.snapshot().error, /租约/); f.controller.detach();
  } finally { Date.now = realNow; }
});
test('manual anchor confirmation refreshes the server lease past the original native deadline', async () => {
  const realNow = Date.now; const start = realNow(); let clock = start, id = 0;
  Date.now = () => clock;
  const timers = new Map();
  try {
    const f = fixture({ setTimeout: (callback, delay) => { timers.set(++id, { callback, deadline: clock + delay }); return id; }, clearTimeout: key => timers.delete(key) });
    let lease = start + 120000;
    f.Api.request = async () => ({ ...f.view(), session: { ...f.view().session, leaseExpiresAt: new Date(lease).toISOString() } });
    await attach(f);
    f.devices[0].start = async () => { f.devices[0].active = false; }; // permission denied, manual mode
    await f.controller.start(false);
    clock = start + 60000;
    f.Api.post = async () => { lease = start + 180000; return { userId: 'manager-1', contextVersion: 1 }; };
    await f.controller.confirm({ id: 'anchor-1', enabled: true });
    clock = start + 121000;
    for (const [key, timer] of [...timers]) if (timer.deadline <= clock) { timers.delete(key); timer.callback(); }
    assert.equal(f.controller.snapshot().error, '');
    assert.equal(Date.parse(f.controller.snapshot().view.session.leaseExpiresAt), start + 180000);
    clock = start + 181000;
    for (const [key, timer] of [...timers]) if (timer.deadline <= clock) { timers.delete(key); timer.callback(); }
    assert.match(f.controller.snapshot().error, /租约/); f.controller.detach();
  } finally { Date.now = realNow; }
});
