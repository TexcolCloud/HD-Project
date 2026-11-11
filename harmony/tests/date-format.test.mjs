import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

// Labels.ets is pure TypeScript. Exercise its actual source without a UI/mock copy.
process.env.TZ = 'UTC';
const source = await readFile(new URL('../entry/src/main/ets/services/Labels.ets', import.meta.url), 'utf8');
const { dateText, inspectionTimestamp, beijingClock, visitWindowRange, availabilityTimestamp } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));

test('service timestamps use the school timezone even when the device is on UTC', () => {
  assert.match(dateText('2026-11-06T16:05:00Z'), /^2026\/11\/7\s+00:05:00$/);
});

test('Harmony SDK h24 midnight formatting cannot display 24 as the next day hour', t => {
  t.mock.method(Date.prototype, 'toLocaleString', () => '2026/11/12 24:19:44');
  assert.equal(dateText('2026-11-11T16:19:44Z'), '2026/11/12 00:19:44');
  assert.equal(dateText('2026-12-31T16:00:00Z'), '2027/1/1 00:00:00');
  assert.equal(dateText('2026-11-11T15:59:59Z'), '2026/11/11 23:59:59');
  assert.equal(dateText('unavailable'), 'unavailable');
});

test('a backfilled inspection sends the chosen date and clock in Beijing time', () => {
  assert.equal(inspectionTimestamp('2026-11-06', '18:30', Date.parse('2026-11-07T02:00:00Z')), '2026-11-06T18:30:00+08:00');
});

test('inspection input rejects invalid dates, clocks and future instants', () => {
  const now = Date.parse('2026-11-07T02:00:00Z');
  assert.equal(beijingClock(Date.parse('2026-11-06T16:05:00Z')), '00:05');
  assert.throws(() => inspectionTimestamp('2026-02-30', '09:00', now), /日期无效/);
  assert.throws(() => inspectionTimestamp('2026-11-06', '24:00', now), /HH:mm/);
  assert.throws(() => inspectionTimestamp('2026-11-07', '11:00', now), /未来/);
});


test('visit windows preserve explicit Beijing bounds and can be omitted', () => {
  const now = Date.parse('2026-11-07T02:00:00Z');
  assert.equal(visitWindowRange(false, '', '', '', '', now), null);
  assert.deepEqual(visitWindowRange(true, '2026-11-08', '09:00', '2026-11-08', '11:00', now),
    {start: '2026-11-08T09:00:00+08:00', end: '2026-11-08T11:00:00+08:00'});
});

test('visit and availability bounds reject reversed or expired local times', () => {
  const now = Date.parse('2026-11-07T02:00:00Z');
  assert.throws(() => visitWindowRange(true, '2026-11-08', '11:00', '2026-11-08', '09:00', now), /晚于开始/);
  assert.throws(() => visitWindowRange(true, '2026-11-06', '09:00', '2026-11-06', '11:00', now), /已结束/);
  assert.equal(availabilityTimestamp('2026-11-07', '18:00', now), '2026-11-07T18:00:00+08:00');
  assert.throws(() => availabilityTimestamp('2026-11-07', '09:00', now), /晚于现在/);
});
