import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
const source = await readFile(new URL('../entry/src/main/ets/features/dispatch/RequestFlight.ets', import.meta.url), 'utf8');
const { RequestFlight } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));

test('manual refresh and the visible-route poll share the same in-flight read', async () => {
  const flight = new RequestFlight(); let calls = 0; let resolve;
  const fetch = () => { calls++; return new Promise(done => { resolve = done; }); };
  const manual = flight.run(1, fetch), poll = flight.run(1, fetch);
  assert.equal(calls, 1); assert.equal(manual, poll);
  resolve('version-9'); assert.equal(await poll, 'version-9');
  const fresh = flight.run(1, fetch); assert.equal(calls, 2); resolve('version-10'); await fresh;
});

test('an old epoch completion cannot clear the replacement account read', async () => {
  const flight = new RequestFlight(); const pending = []; let calls = 0;
  const fetch = () => { calls++; return new Promise(resolve => pending.push(resolve)); };
  const old = flight.run(1, fetch);
  flight.reset(); const current = flight.run(2, fetch);
  pending[0]('old'); await old;
  assert.equal(flight.run(2, fetch), current); assert.equal(calls, 2);
  pending[1]('new'); assert.equal(await current, 'new');
});
