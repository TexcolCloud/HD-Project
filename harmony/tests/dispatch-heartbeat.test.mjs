import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

const source = await readFile(new URL('../entry/src/main/ets/features/dispatch/ForegroundPoll.ets', import.meta.url), 'utf8');
const { ForegroundPoll } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));

class FakeScheduler {
  callbacks = new Map(); sequence = 0;
  constructor(period = 30000) { this.period = period; }
  every(callback, milliseconds) { assert.equal(milliseconds, this.period); this.callbacks.set(++this.sequence, callback); return this.sequence; }
  cancel(handle) { this.callbacks.delete(handle); }
  tick() { for (const callback of this.callbacks.values()) callback(); }
}
const settle = () => new Promise(resolve => setImmediate(resolve));

test('one foreground login owns one timer; background, logout and student sessions own none', async () => {
  const timer = new FakeScheduler(); let calls = 0; const received = [];
  const pulse = new ForegroundPoll(async () => ++calls, result => received.push(result), error => { throw error; }, 30000, timer);
  pulse.setSession('manager-1-session-a'); pulse.setSession('manager-1-session-a');
  await settle();
  assert.equal(timer.callbacks.size, 1); assert.deepEqual(received, [1]);
  pulse.setForeground(false); assert.equal(timer.callbacks.size, 0);
  timer.tick(); await settle(); assert.equal(calls, 1);
  pulse.setForeground(true); await settle(); assert.equal(timer.callbacks.size, 1); assert.equal(calls, 2);
  pulse.stop(); assert.equal(timer.callbacks.size, 0);
  pulse.setSession(''); await settle(); assert.equal(timer.callbacks.size, 0);
});

test('late results from an old account are discarded and overlapping ticks are skipped', async () => {
  const timer = new FakeScheduler(); const resolvers = []; const received = [];
  const pulse = new ForegroundPoll(() => new Promise(resolve => resolvers.push(resolve)), result => received.push(result), () => {}, 30000, timer);
  pulse.setSession('manager-old'); timer.tick(); timer.tick();
  assert.equal(resolvers.length, 1);
  pulse.setSession('manager-new'); assert.equal(timer.callbacks.size, 1); assert.equal(resolvers.length, 2);
  resolvers[0]('old'); resolvers[1]('new'); await settle();
  assert.deepEqual(received, ['new']);
  timer.tick(); pulse.stop(); resolvers[2]('after-logout'); await settle();
  assert.deepEqual(received, ['new']); assert.equal(timer.callbacks.size, 0);
});

test('an old account failure cannot expire the replacement account', async () => {
  const timer = new FakeScheduler(); const pending = []; const errors = [];
  const pulse = new ForegroundPoll(() => new Promise((resolve, reject) => pending.push({resolve, reject})),
    () => {}, error => errors.push(error.message), 30000, timer);
  pulse.setSession('old'); pulse.setSession('new');
  pending[0].reject(new Error('401 from old session')); pending[1].resolve('current');
  await settle(); assert.deepEqual(errors, []); assert.equal(timer.callbacks.size, 1);
  pulse.stop();
});


test('a visible route polls at five seconds without a heartbeat and stops offscreen or in background', async () => {
  const timer = new FakeScheduler(5000); let reads = 0; const results = [];
  const poll = new ForegroundPoll(async () => ++reads, result => results.push(result), error => { throw error; }, 5000, timer);
  poll.setSession('visible-route'); await settle(); assert.deepEqual(results, [1]);
  timer.tick(); await settle(); assert.deepEqual(results, [1, 2]);
  poll.setForeground(false); assert.equal(timer.callbacks.size, 0); timer.tick(); await settle(); assert.equal(reads, 2);
  poll.setForeground(true); await settle(); assert.equal(reads, 3); assert.equal(timer.callbacks.size, 1);
  poll.setSession(''); assert.equal(timer.callbacks.size, 0);
});
