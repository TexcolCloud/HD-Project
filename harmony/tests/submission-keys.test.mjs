import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

const source = await readFile(new URL('../entry/src/main/ets/services/SubmissionKeys.ets', import.meta.url), 'utf8');
const { SubmissionKeys, shouldReleaseAiKey } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));

test('identical content submitted after a successful save is a new operation', () => {
  let sequence = 0;
  const keys = new SubmissionKeys(() => `key-${++sequence}`);
  const first = keys.reserve('repair', '{"title":"排水缓慢"}');
  keys.complete('repair');
  assert.notEqual(keys.reserve('repair', '{"title":"排水缓慢"}'), first);
});

test('an unresolved retry keeps its key across unrelated form operations', () => {
  let sequence = 0;
  const keys = new SubmissionKeys(() => `key-${++sequence}`);
  const first = keys.reserve('visitor', '{"phone":"13800000000"}');
  keys.reserve('ai', '{"description":"排水缓慢"}');
  keys.complete('ai');
  assert.equal(keys.reserve('visitor', '{"phone":"13800000000"}'), first);
  assert.notEqual(keys.reserve('visitor', '{"phone":"13900000000"}'), first);
  keys.clear();
  assert.equal(keys.reserve('visitor', '{"phone":"13800000000"}'), 'key-4');
});


test('only an explicit terminal AI failure releases a key for a new user attempt', () => {
  assert.equal(shouldReleaseAiKey('AI_UNAVAILABLE'), true);
  assert.equal(shouldReleaseAiKey('AI_REQUEST_FAILED'), true);
  assert.equal(shouldReleaseAiKey('AI_RESULT_UNKNOWN'), true);
  assert.equal(shouldReleaseAiKey('AI_REQUEST_RUNNING'), false);
  assert.equal(shouldReleaseAiKey('AI_NOT_CONFIGURED'), false);
  assert.equal(shouldReleaseAiKey(''), false);
  assert.equal(shouldReleaseAiKey('NETWORK'), false);
});
