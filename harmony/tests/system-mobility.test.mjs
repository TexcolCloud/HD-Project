import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
const base = new URL('../entry/src/main/ets/features/mobility/', import.meta.url);
const pure = await readFile(new URL('MobilityEstimator.ets', base), 'utf8');
const { StepBaseline } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(pure)).toString('base64'));
const raw = await readFile(new URL('SystemMobility.ets', base), 'utf8');
const executable = stripTypeScriptTypes(raw.replace(/^import[\s\S]*?;[ \t]*(?:\r?\n|$)/gm, '')).replace(/^export /gm, '');
function fixture({ grant = true, supportsSensors = true } = {}) {
  const events = new Map(), sensorEvents = new Map(), permissions = new Map(), nativeCalls = [];
  const access = { requestPermissionsFromUser: async (_context, list) => ({ authResults: list.map(() => grant ? 0 : -1) }),
    on: (name, _list, callback) => permissions.set(name, callback), off: name => permissions.delete(name) };
  const abilityAccessCtrl = { createAtManager: () => access, PermissionStateChangeType: { PERMISSION_REVOKED_OPER: 0 } };
  const location = { isLocationEnabled: () => true, UserActivityScenario: { DAILY_LIFE_SERVICE: 0 },
    on: (name, request, callback) => { nativeCalls.push(['on', name, request?.interval]); events.set(name, callback || request); },
    off: name => { nativeCalls.push(['off', name]); events.delete(name); } };
  const sensor = { SensorId: { PEDOMETER: 1, ACCELEROMETER: 2 }, getSingleSensor: async () => { if (!supportsSensors) throw new Error('no sensor'); },
    on: (id, callback) => sensorEvents.set(id, callback), off: id => sensorEvents.delete(id) };
  const battery = { batterySOC: 80 };
  const tasks = new Map();
  const bg = { startBackgroundRunning: async () => { nativeCalls.push(['background-start']); return { continuousTaskId: 7 }; },
    stopBackgroundRunning: async (_context, id) => nativeCalls.push(['background-stop', id]), on: (name, callback) => tasks.set(name, callback), off: name => tasks.delete(name) };
  const notification = { isNotificationEnabled: async () => true };
  const wantAgent = { getWantAgent: async () => ({}), OperationType: {}, WantAgentFlags: {} };
  const Type = new Function('abilityAccessCtrl', 'geoLocationManager', 'sensor', 'batteryInfo', 'backgroundTaskManager', 'notificationManager', 'wantAgent', 'StepBaseline', executable + '\nreturn SystemMobility;')(
    abilityAccessCtrl, location, sensor, battery, bg, notification, wantAgent, StepBaseline);
  return { device: new Type(), access, events, sensorEvents, permissions, tasks, nativeCalls, battery };
}
const location = () => ({ latitude: 30, longitude: 110, accuracy: 5, speed: 1, speedAccuracy: 0.5,
  timeStamp: Date.now(), timeSinceBoot: 123450000000 });

test('denying position starts no sensors or native location and leaves manual flow available', async () => {
  const f = fixture({ grant: false }), messages = [];
  await f.device.start({}, false, () => assert.fail('unexpected data'), value => messages.push(value));
  assert.equal(f.device.isCollecting(), false); assert.equal(f.events.size, 0); assert.equal(f.sensorEvents.size, 0);
  assert.match(messages[0], /手工/);
});
test('canceling while the system permission page is pending cannot resurrect a listener', async () => {
  const f = fixture(); let complete;
  f.access.requestPermissionsFromUser = () => new Promise(resolve => complete = resolve);
  const starting = f.device.start({}, false, () => assert.fail('unexpected data'), () => {});
  f.device.stop(); complete({ authResults: [0, 0] }); await starting;
  assert.equal(f.events.size, 0); assert.equal(f.sensorEvents.size, 0);
});
test('native on/off lifecycle normalizes nanoseconds and does not turn missing pedometer into zero', async () => {
  const f = fixture({ supportsSensors: false }), observations = [];
  await f.device.start({}, false, value => observations.push(value), () => {});
  const old = f.events.get('locationChange'); old(location());
  assert.equal(observations[0].position.elapsedMs, 123450); assert.equal(observations[0].stepDelta, undefined);
  f.device.stop(); old(location());
  assert.equal(observations.length, 1); assert.equal(f.events.size, 0); assert.equal(f.permissions.size, 0);
});
test('pedometer first observation is a baseline; later differences are work-segment only', async () => {
  const f = fixture(), observations = [];
  await f.device.start({}, false, value => observations.push(value), () => {});
  const steps = f.sensorEvents.get(1);
  steps({ steps: 200, timestamp: 1000000000 }); steps({ steps: 210, timestamp: 11000000000 });
  f.events.get('locationChange')(location());
  assert.equal(observations[0].stepDelta, 10); f.device.stop();
  assert.equal(f.sensorEvents.size, 0);
});
test('revoking a permission immediately releases all subscriptions and reports stopped state', async () => {
  const f = fixture(), messages = [];
  await f.device.start({}, false, () => {}, (message, stopped) => messages.push({ message, stopped }));
  f.permissions.get('selfPermissionStateChange')({ change: 0 });
  assert.equal(f.device.isCollecting(), false); assert.equal(f.events.size, 0); assert.equal(f.sensorEvents.size, 0);
  assert.equal(messages.at(-1).stopped, true);
});
test('background mode uses an actual continuous task and stops only its owned task ID', async () => {
  const f = fixture(); await f.device.start({}, true, () => {}, () => {});
  assert.equal(f.device.isBackgroundEnabled(), true);
  f.tasks.get('continuousTaskCancel')({ reason: 1, id: 7 });
  assert.equal(f.device.isBackgroundEnabled(), false); assert.equal(f.events.size, 0);
  assert.ok(f.nativeCalls.some(call => call[0] === 'background-stop' && call[1] === 7));
});
test('a suspended system background task stops capture until the user explicitly starts again', async () => {
  const f = fixture(), messages = []; await f.device.start({}, true, () => {}, (message, stopped) => messages.push(stopped));
  f.tasks.get('continuousTaskSuspend')({ suspendState: true, continuousTaskId: 7 });
  assert.equal(f.device.isCollecting(), false); assert.equal(f.device.isBackgroundEnabled(), false);
  assert.equal(messages.at(-1), true);
});
test('low battery lowers the native location request rate instead of only discarding fixes', async () => {
  const f = fixture(); await f.device.start({}, false, () => {}, () => {});
  f.battery.batterySOC = 10; f.events.get('locationChange')(location());
  assert.ok(f.nativeCalls.some(call => call[0] === 'on' && call[1] === 'locationChange' && call[2] === 30));
  f.device.stop();
});
