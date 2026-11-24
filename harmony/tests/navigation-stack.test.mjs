import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {stripTypeScriptTypes} from 'node:module';

const raw = await readFile(new URL('../entry/src/main/ets/features/NavigationStack.ets', import.meta.url), 'utf8');
const { NavigationStack, BackPanels, rootTab, bottomTabs, activeTab, requiresLogoutOnBack } = await import('data:text/javascript,' + encodeURIComponent(stripTypeScriptTypes(raw)));

for (const pages of [
  ['profile', 'remediations', 'remediation-detail', 'remediation-assignment'],
  ['home', 'route', 'messages', 'detail'],
  ['home', 'detail'],
  ['home', 'hygiene', 'remediation-create'],
  ['home', 'route', 'action'],
]) {
  test(pages.join(' → ') + ' restores each page and its scroll offset', () => {
    const nav = new NavigationStack();
    nav.reset(pages[0]);
    pages.slice(1).forEach((page, index) => {
      nav.enter(page, pages[index], (index + 1) * 100, String(index));
      nav.enter(page, page, 0);
    });
    for (let i = pages.length - 2; i >= 0; i--)
      assert.deepEqual(nav.back(), {page: pages[i], scrollY: (i + 1) * 100, recordId: String(i), resume: undefined});
    assert.equal(nav.back(), null);
  });
}

test('a tab starts its own root and only forced password change asks for logout', () => {
  const nav = new NavigationStack();
  nav.enter('route', 'home', 180);
  nav.reset('repairs');
  assert.equal(nav.back(), null);
  nav.enter('detail', 'repairs', 72);
  assert.equal(nav.previous(), 'repairs');
  assert.equal(requiresLogoutOnBack('password', true), true);
  assert.equal(requiresLogoutOnBack('password', false), false);
  assert.equal(requiresLogoutOnBack('activate', true), false);
});

test('inner panels get first refusal and release their handlers on exit', () => {
  const panels = new BackPanels();
  let outer = 0;
  const removeOuter = panels.register(() => {outer++; return true;});
  const removeInner = panels.register(() => true);
  assert.equal(panels.close(), true);
  assert.equal(outer, 0);
  removeInner();
  assert.equal(panels.close(), true);
  assert.equal(outer, 1);
  removeOuter();
  assert.equal(panels.close(), false);
});

test('manager field lists share a root tab while secondary pages retain history',()=>{
  assert.deepEqual(bottomTabs(true).map(t=>[t.id,t.page]),[['tab-home','home'],['tab-route','route'],['tab-repairs','repairs'],['tab-field','hygiene']]);
  assert.deepEqual(bottomTabs(false).map(t=>t.label),['首页','我的报修','我的']);
  for(const p of ['visitors','remediations','hygiene']) assert.equal(activeTab(p),'hygiene');
  for(const page of ['home','repairs','profile']) assert.ok(rootTab(page));
  for(const page of ['route','hygiene','visitors','remediations']){
    assert.ok(rootTab(page,true));assert.equal(rootTab(page,false),false);
  }
  for(const page of ['action','remediation-detail','visitor-create','hygiene-create']) assert.equal(rootTab(page,true),false);
});
