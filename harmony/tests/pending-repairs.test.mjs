import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

const source = await readFile(new URL('../entry/src/main/ets/services/PendingRepairs.ets', import.meta.url), 'utf8');
const { pendingRepairPaths, newestUnique } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));

test('new completed records cannot hide an older unresolved order from the manager preview', () => {
  const records = [
    {id: 'old-open', status: 'SUBMITTED', submittedAt: '2026-11-01T00:00:00Z'},
    ...Array.from({length: 8}, (_, i) => ({id: `done-${i}`, status: 'COMPLETED', submittedAt: `2026-11-07T0${i}:00:00Z`}))
  ];
  const pages = pendingRepairPaths().map(path => {
    const query = new URL(path, 'http://example.invalid').searchParams;
    const status = query.get('status');
    return records.filter(order => !status || order.status === status)
      .sort((a, b) => b.submittedAt.localeCompare(a.submittedAt)).slice(0, Number(query.get('pageSize')));
  });
  assert.deepEqual(newestUnique(pages, item => item.id, item => item.submittedAt, 3).map(item => item.id), ['old-open']);
});

test('all four unresolved states compete for the preview by submission time', () => {
  const records = ['SUBMITTED', 'ACCEPTED', 'PROCESSING', 'PENDING_CONFIRMATION', 'CANCELLED', 'REJECTED']
    .map((status, index) => ({id: status, status, submittedAt: `2026-11-0${index + 1}T00:00:00Z`}));
  const pages = pendingRepairPaths().map(path => {
    const status = new URL(path, 'http://example.invalid').searchParams.get('status');
    return records.filter(order => !status || order.status === status);
  });
  assert.deepEqual(newestUnique(pages, item => item.id, item => item.submittedAt, 3).map(item => item.id),
    ['PENDING_CONFIRMATION', 'PROCESSING', 'ACCEPTED']);
});
