import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

async function load() {
  const source = await readFile(new URL('../entry/src/main/ets/services/LocalDrafts.ets', import.meta.url), 'utf8');
  return import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));
}

test('drafts survive process replacement and never cross account or server', async () => {
  const { LocalDrafts } = await load();
  const data = new Map();
  const storage = { get: key => data.get(key) || '', put: (key, value) => data.set(key, value), remove: key => data.delete(key) };
  new LocalDrafts(storage, 'server-a', '1', () => 100).save('repair', '{"title":"水池漏水"}');
  assert.equal(new LocalDrafts(storage, 'server-a', '1', () => 101).read('repair'), '{"title":"水池漏水"}');
  assert.equal(new LocalDrafts(storage, 'server-a', '2', () => 101).read('repair'), '');
  assert.equal(new LocalDrafts(storage, 'server-b', '1', () => 101).read('repair'), '');
});

test('expired, future-dated and corrupt drafts are discarded without breaking login', async () => {
  const { LocalDrafts } = await load();
  const data = new Map();
  const storage = { get: key => data.get(key) || '', put: (key, value) => data.set(key, value), remove: key => data.delete(key) };
  const drafts = new LocalDrafts(storage, 's', 'u', () => 100);
  drafts.save('repair', '{}');
  assert.equal(new LocalDrafts(storage, 's', 'u', () => 100 + 8 * 86400000).read('repair'), '');
  drafts.save('repair', '{}');
  assert.equal(new LocalDrafts(storage, 's', 'u', () => 0).read('repair'), '');
  data.set('s:u:repair', '{bad');
  assert.equal(drafts.read('repair'), '');
  assert.equal(data.has('s:u:repair'), false);
});

test('an unresolved submission retains the same key after restart and clears only after success', async () => {
  const { LocalDrafts } = await load();
  const data = new Map();
  const storage = { get: key => data.get(key) || '', put: (key, value) => data.set(key, value), remove: key => data.delete(key) };
  const a = new LocalDrafts(storage, 's', 'u', () => 100);
  const key = a.reserveKey('repair', '{"title":"漏水"}', () => 'first');
  const b = new LocalDrafts(storage, 's', 'u', () => 101);
  assert.equal(b.reserveKey('repair', '{"title":"漏水"}', () => 'second'), key);
  b.completeKey('repair');
  assert.equal(b.reserveKey('repair', '{"title":"漏水"}', () => 'third'), 'third');
});

test('editing an unresolved submission cannot silently create another operation', async () => {
  const { LocalDrafts } = await load();
  const data = new Map();
  const storage = { get: key => data.get(key) || '', put: (key, value) => data.set(key, value), remove: key => data.delete(key) };
  const drafts = new LocalDrafts(storage, 's', 'u', () => 100);
  drafts.reserveKey('repair', '{"title":"漏水"}', () => 'first');
  assert.throws(() => drafts.reserveKey('repair', '{"title":"漏水修改后"}', () => 'second'), /核对/);
  assert.equal(drafts.reserveKey('repair', '{"title":"漏水"}', () => 'third'), 'first');
});
