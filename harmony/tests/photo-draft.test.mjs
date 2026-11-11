import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
const raw = await readFile(new URL('../entry/src/main/ets/features/photos/PhotoDraftController.ets', import.meta.url), 'utf8');
const executable = stripTypeScriptTypes(raw.replace(/^import[\s\S]*?;[ \t]*(?:\r?\n|$)/gm, '')).replace(/^export /gm, '');
function fixture() {
  const errors = []; let count = 0;
  const Api = { epoch: 1, choose: async () => ['gallery-photo'], upload: async uri => ({ uri, asset: { id: String(++count) } }), request: async () => ({}) };
  const camera = { takePhoto: async () => 'camera-photo' };
  const Controller = new Function('Api', 'ApiError', 'DeviceActions', 'http', executable + '\nreturn PhotoDraftController;')(
    Api, Error, camera, { RequestMethod: { DELETE: 'DELETE' } });
  return { Api, camera, errors, controller: new Controller(error => errors.push(error)) };
}
test('system camera and gallery share the six-image ceiling and keep prior draft on cancel', async () => {
  const f = fixture();
  await f.controller.choose('REPORT', {}, true); await f.controller.choose('REPORT', {});
  f.camera.takePhoto = async () => '';
  await f.controller.choose('REPORT', {}, true);
  assert.equal(f.controller.snapshot('REPORT').draft.uploaded.length, 2);
  f.Api.choose = async () => Array.from({ length: 10 }, (_, i) => 'uri-' + i);
  await f.controller.choose('REPORT', {}); await f.controller.choose('REPORT', {});
  assert.equal(f.controller.snapshot('REPORT').draft.uploaded.length, 6); assert.equal(f.errors.length, 1);
});
test('a camera result returned after account replacement cannot upload or alter a new draft', async () => {
  const f = fixture(); let finish, uploads = 0;
  f.camera.takePhoto = () => new Promise(resolve => finish = resolve);
  f.Api.upload = async () => { uploads++; };
  const pending = f.controller.choose('RESULT', {}, true);
  f.Api.epoch++; f.controller.reset(); finish('old-camera-photo'); await pending;
  assert.equal(uploads, 0); assert.equal(f.controller.snapshot('RESULT').draft.uploaded.length, 0);
  assert.equal(f.controller.snapshot('RESULT').busy, false);
});
test('upload failure preserves the selected URI and successful retry removes only that failure', async () => {
  const f = fixture(); f.Api.upload = async () => { throw new Error('offline'); };
  await f.controller.choose('HYGIENE', {});
  assert.deepEqual(f.controller.snapshot('HYGIENE').draft.failed, ['gallery-photo']);
  f.Api.upload = async uri => ({ uri, asset: { id: 'saved' } });
  await f.controller.retry('gallery-photo', 'HYGIENE');
  assert.equal(f.controller.snapshot('HYGIENE').draft.failed.length, 0);
  assert.equal(f.controller.snapshot('HYGIENE').draft.uploaded[0].asset.id, 'saved');
});
test('an upload 401 reaches the session owner even though transport already cleared its token', async () => {
  const f = fixture();
  f.Api.upload = async () => { f.Api.epoch++; throw Object.assign(new Error('expired'), { status: 401, sessionEpoch: f.Api.epoch }); };
  await f.controller.choose('REPORT', {});
  assert.equal(f.errors.length, 1); assert.equal(f.errors[0].status, 401);
  assert.equal(f.controller.snapshot('REPORT').draft.failed.length, 0);
});
