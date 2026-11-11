import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
const text = await readFile(new URL('../entry/src/main/ets/services/ResourceSlot.ets', import.meta.url), 'utf8');
const { ResourceSlot } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(text)).toString('base64'));
test('destroying a view releases a native resource that arrives afterwards', () => {
  const freed = [], slot = new ResourceSlot(value => freed.push(value));
  const ticket = slot.begin(); slot.clear();
  assert.equal(slot.accept(ticket, 'late-pixels'), false); assert.deepEqual(freed, ['late-pixels']);
});
test('replacement, repeated clear and out-of-order decode each release exactly once', () => {
  const freed = [], slot = new ResourceSlot(value => freed.push(value));
  const first = slot.begin(); slot.accept(first, 'first');
  const second = slot.begin(); slot.accept(second, 'second'); slot.accept(first, 'old-decode');
  slot.clear(); slot.clear(); assert.deepEqual(freed, ['first', 'old-decode', 'second']);
});
