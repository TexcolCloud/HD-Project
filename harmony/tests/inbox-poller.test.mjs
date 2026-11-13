import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

async function load(file, bindings) {
  const raw = await readFile(new URL('../entry/src/main/ets/features/' + file, import.meta.url), 'utf8');
  const source = stripTypeScriptTypes(raw.replace(/^import[\s\S]*?;[ \t]*(?:\r?\n|$)/gm, '')).replace(/^export /gm, '');
  const names = [...raw.matchAll(/^export class (\w+)/gm)].map(m => m[1]);
  return new Function(...Object.keys(bindings), source + '\nreturn {' + names.join(',') + '};')(...Object.values(bindings));
}

test('a current-session inbox 401 reaches the shell after transport retires its token', async () => {
  const failures = [];
  const Api = { epoch: 1, token: 'old', request: async () => { Api.token = ''; Api.epoch++; throw Object.assign(new Error('expired'), {status: 401, sessionEpoch: Api.epoch}); } };
  const { ForegroundPoll } = await load('dispatch/ForegroundPoll.ets', {setInterval: () => 1, clearInterval() {}});
  const AppVisibility = {subscribe: f => { f(true); return () => {}; }};
  const { InboxPoller } = await load('service/InboxPoller.ets', { Api, ForegroundPoll, AppVisibility });
  const poller = new InboxPoller();
  poller.start(() => {}, async () => {}, e => failures.push(e));
  await new Promise(resolve => setImmediate(resolve));
  poller.stop();
  assert.equal(failures.length, 1);
  assert.equal(failures[0].status, 401);
});

test('an older unread message entering the first page does not trigger a new-message notification', async () => {
  let pulse, phase = 0, notifications = 0;
  const pages = [[{id: '30'}, {id: '29'}], [{id: '29'}, {id: '28'}], [{id: '31'}, {id: '30'}]];
  const Api = { token: 'session', request: async path => path.includes('summary') ? {unreadCount: 30} : {items: pages[phase]} };
  const { ForegroundPoll } = await load('dispatch/ForegroundPoll.ets', {setInterval: callback => { pulse = callback; return 1; }, clearInterval() {}});
  const AppVisibility = {subscribe: f => { f(true); return () => {}; }};
  const { InboxPoller } = await load('service/InboxPoller.ets', { Api, ForegroundPoll, AppVisibility });
  const poller = new InboxPoller();
  poller.start(() => {}, async () => { notifications++; }, e => { throw e; });
  await new Promise(resolve => setImmediate(resolve));
  phase = 1; pulse(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(notifications, 0);
  phase = 2; pulse(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(notifications, 1);
  poller.stop();
});
