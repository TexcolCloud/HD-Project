import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';

const source = await readFile(new URL('../entry/src/main/ets/components/Palette.ets', import.meta.url), 'utf8');
const { palette } = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));
const { colors, themes } = JSON.parse(await readFile(new URL('../../design/tokens.json', import.meta.url), 'utf8'));
function luminance(hex) {
  const channels = hex.slice(1).match(/../g).map(pair => parseInt(pair, 16) / 255)
    .map(v => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}
function ratio(first, second) {
  const a = luminance(first), b = luminance(second);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}
test('both R3 themes distinguish input boundaries and keep text legible on the content surface', () => {
  assert.equal(colors.borderStrong, themes.day.colors.borderStrong);
  for (const night of [false, true]) {
    const theme = palette(night), tokens = themes[night ? 'night' : 'day'].colors;
    assert.equal(theme.outline, tokens.borderStrong);
    assert.equal(theme.line, tokens.border);
    assert.ok(ratio(theme.outline, theme.surface) >= 3);
    assert.ok(ratio(theme.ink, theme.surface) >= 4.5);
    assert.ok(ratio(theme.muted, theme.surface) >= 4.5);
  }
});
