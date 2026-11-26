import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

const raw = await readFile(new URL('../entry/src/main/ets/features/account/AccountInput.ets', import.meta.url), 'utf8');
const source = stripTypeScriptTypes(raw).replace(/^export /gm, '');
const { accountCode, cooldownSeconds } =
  new Function(source + '\nreturn { accountCode, cooldownSeconds };')();

test('SMS codes preserve leading zero and accept exactly six ASCII digits', () => {
  assert.equal(accountCode('034567'), '034567');
  assert.equal(accountCode('34567'), '');
  assert.equal(accountCode('０３４５６７'), '');
});

test('resend countdown begins at 60 seconds and ends at the boundary', () => {
  assert.equal(cooldownSeconds(0, 2_000), 0);
  assert.equal(cooldownSeconds(10_000, 10_000), 60);
  assert.equal(cooldownSeconds(10_000, 69_999), 1);
  assert.equal(cooldownSeconds(10_000, 70_000), 0);
});

test('graphic answers accept lowercase and exclude ambiguous glyphs', () => {
  assert.equal(accountCode(' a2q9 '), 'A2Q9');
  for (const code of ['O2Q9', 'A1Q9', 'I2Q9', 'L2Q9', 'A2Q', 'A2Q93']) assert.equal(accountCode(code), '');
});
