import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
const source = await readFile(new URL('../entry/src/main/ets/features/mobility/MobilityEstimator.ets', import.meta.url), 'utf8');
const { MobilityEstimator, StepBaseline } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));
const point = (time, latitude = 30, accuracy = 5, speed) => ({ observedMs: time, elapsedMs: time, latitude, longitude: 110,
  accuracyMeters: accuracy, speedMps: speed, speedAccuracyMps: 0.5 });

test('stationary GPS noise and overlapping uncertainty do not manufacture walking speed', () => {
  const e = new MobilityEstimator(); e.accept(point(10000), 10000);
  const value = e.accept(point(20000, 30.00001), 20000);
  assert.equal(value.speedMps, undefined); assert.equal(value.motion, 'UNKNOWN');
  assert.equal(e.accept(point(30000, 30.00002, 5, 0), 30000).motion, 'STILL');
});
test('reliable speed wins, implausible and stale observations remain unknown', () => {
  const e = new MobilityEstimator();
  assert.equal(e.accept(point(10000, 30, 5, 1.3), 10000).speedMps, 1.3);
  assert.equal(e.accept(point(20000, 30, 5, 40), 20000).speedMps, undefined);
  assert.equal(e.accept(point(15000, 30, 5, 1), 21000), null);
  assert.equal(e.accept(point(30000, 30, 5, 1), 100000), null);
});
test('significant displacement uses monotonic same-stream time only', () => {
  const e = new MobilityEstimator(); e.accept(point(10000), 10000);
  const value = e.accept(point(20000, 30.00015), 20000);
  assert.ok(value.speedMps > 1 && value.speedMps < 2);
  e.reset(); assert.equal(e.accept(point(500, 30.0003), 500).speedMps, undefined);
});
test('pedometer starts with a baseline and resets on reboot, pauses and long gaps', () => {
  const steps = new StepBaseline();
  assert.equal(steps.delta(500, 1000), undefined); assert.equal(steps.delta(512, 11000), 12);
  assert.equal(steps.delta(2, 21000), undefined); assert.equal(steps.delta(20, 200000), undefined);
  steps.reset(); assert.equal(steps.delta(40, 210000), undefined);
});
test('nonwalking system speed cannot be rescued by a contradictory walking displacement', () => {
  const e = new MobilityEstimator(); e.accept(point(10000), 10000);
  assert.equal(e.accept(point(20000, 30.00015, 5, 20), 20000).speedMps, undefined);
});
test('invalid hardware timestamps never reach ISO encoding or poison the step baseline', () => {
  const e = new MobilityEstimator();
  assert.equal(e.accept({ ...point(10000), elapsedMs: NaN }, 10000), null);
  assert.equal(e.accept({ ...point(10000), observedMs: NaN }, 10000), null);
  const steps = new StepBaseline();
  assert.equal(steps.delta(10, 1000), undefined); assert.equal(steps.delta(NaN, 2000), undefined);
  assert.equal(steps.delta(20, 3000), undefined); assert.equal(steps.delta(22, 5000), 2);
});
