import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

const raw = await readFile(new URL('../entry/src/main/ets/services/Api.ets', import.meta.url), 'utf8');
const source = stripTypeScriptTypes(raw.replace(/^import[\s\S]*?;[ \t]*(?:\r?\n|$)/gm, '')).replace(/^export /gm, '');
function apiWith(request, images = {}, fs = {}) {
  const http = { createHttp: () => ({ request, destroy() {} }), RequestMethod: { GET: 'GET', POST: 'POST' }, HttpDataType: { STRING: 0, ARRAY_BUFFER: 1 } };
  return new Function('http', 'rcp', 'util', 'image', 'fs', 'photoAccessHelper', source + '\nreturn Api;')(
    http, {}, { generateRandomUUID: () => 'request-key' }, images, fs, {});
}

test('ordinary successful read from a previous login cannot reach a new account', async () => {
  let complete;
  const Api = apiWith(() => new Promise(resolve => { complete = resolve; }));
  Api.token = 'account-a';
  const old = Api.request('/me/room');
  Api.token = ''; Api.token = 'account-b';
  complete({ responseCode: 200, result: JSON.stringify({ data: { roomNo: 'A302' } }) });
  await assert.rejects(old, error => error.code === 'SESSION_REPLACED');
  assert.equal(Api.token, 'account-b');
});

test('old unauthorized response cannot expire or display an error in a newer login', async () => {
  let complete;
  const Api = apiWith(() => new Promise(resolve => { complete = resolve; }));
  Api.token = 'account-a'; const old = Api.request('/me/room');
  Api.token = 'account-b';
  complete({ responseCode: 401, result: JSON.stringify({ error: { code: 'AUTH', message: 'expired' } }) });
  await assert.rejects(old, error => error.code === 'SESSION_REPLACED');
  assert.equal(Api.token, 'account-b');
});
test('current-account 401 remains an expiry error after clearing its own token', async () => {
  const Api = apiWith(async () => ({ responseCode: 401, result: '{"error":{"code":"SESSION_EXPIRED","message":"expired"}}' }));
  Api.token = 'current';
  await assert.rejects(Api.request('/me/room'), error => error.status === 401 && error.code === 'SESSION_EXPIRED' && error.sessionEpoch === Api.epoch);
  assert.equal(Api.token, '');
});

test('a canceled request cannot become current again after token A → B → A', async () => {
  let complete;
  const Api = apiWith(() => new Promise(resolve => { complete = resolve; }));
  Api.token = 'a'; const old = Api.request('/repairs');
  Api.token = 'b'; Api.token = 'a';
  complete({ responseCode: 200, result: '{"data":[]}' });
  await assert.rejects(old, error => error.code === 'SESSION_REPLACED');
});

test('a PixelMap decoded after login replacement is released before rejection', async () => {
  let decode, freed = 0, sources = 0;
  const Api = apiWith(async () => ({ responseCode: 200, result: new ArrayBuffer(10) }), { createImageSource: () => ({
    getImageInfo: async () => ({ size: { width: 4000, height: 3000 } }),
    createPixelMap: options => { assert.deepEqual(options.desiredSize, { width: 1800, height: 1350 }); return new Promise(resolve => decode = resolve); },
    release: () => sources++
  }) });
  Api.token = 'a'; const loading = Api.image('/api/v1/files/1');
  await new Promise(resolve => setImmediate(resolve)); Api.token = 'b';
  decode({ release: () => freed++ });
  await assert.rejects(loading, error => error.code === 'SESSION_REPLACED');
  assert.equal(freed, 1); assert.equal(sources, 1);
});

test('photo processing is single-flight, bounded, metadata-free and releases on account switch', async () => {
  let pack, opens = 0, closes = 0, sources = 0, pixels = 0, packers = 0;
  const images = { createImageSource: fd => { assert.equal(fd, 3); return {
    getImageInfo: async () => ({ size: { width: 10000, height: 8000 } }),
    createPixelMap: async options => { assert.deepEqual(options.desiredSize, { width: 1800, height: 1440 }); return { release: () => pixels++ }; },
    release: () => sources++
  }; }, createImagePacker: () => ({ packToData: (_pixels, options) => {
    assert.equal(options.needsPackProperties, false); return new Promise(resolve => pack = resolve);
  }, release: () => packers++ }) };
  const fs = { OpenMode: { READ_ONLY: 0 }, open: async () => { opens++; return { fd: 3 }; }, stat: async () => ({ size: 40000000 }), close: async () => closes++ };
  const Api = apiWith(async () => { throw new Error('must never upload old account photo'); }, images, fs);
  Api.token = 'a'; const upload = Api.upload('selected-photo', 'REPORT');
  await new Promise(resolve => setImmediate(resolve));
  await assert.rejects(Api.upload('second-photo', 'REPORT'), /上一张图片/);
  Api.token = 'b'; pack(new ArrayBuffer(10));
  await assert.rejects(upload, error => error.code === 'SESSION_REPLACED');
  assert.deepEqual({ opens, closes, sources, pixels, packers }, { opens: 1, closes: 1, sources: 1, pixels: 1, packers: 1 });
});
