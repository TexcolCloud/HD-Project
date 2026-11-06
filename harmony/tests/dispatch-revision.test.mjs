import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
const source = await readFile(new URL('../entry/src/main/ets/features/dispatch/RouteRevision.ets', import.meta.url), 'utf8');
const { RouteRevision } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));
test('a delayed older route cannot replace the current plan; equal versions may refresh details', () => {
  const gate = new RouteRevision();
  assert.equal(gate.accept(8), true);
  assert.equal(gate.accept(7), false);
  assert.equal(gate.value(), 8);
  assert.equal(gate.accept(8), true);
  assert.equal(gate.accept(9), true);
  gate.clear(); assert.equal(gate.accept(0), true);
});
