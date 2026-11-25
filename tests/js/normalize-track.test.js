'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadAppFunctions } = require('./support/app-harness');

const app = loadAppFunctions(['artistNames', 'artistAliasNames', 'artistRefs', 'normalizeTrack'], { safeHttpsUrl: value => String(value || '') });
const normalize = raw => JSON.parse(JSON.stringify(app.normalizeTrack(raw, 'netease')));

test('collects provider artist aliases without changing the displayed artist', () => {
  const track = normalize({ id: 1372726248, name: '新宝島', dt: 305000,
    ar: [{ name: 'サカナクション', alias: ['Sakanaction'], alia: ['Sakanaction'], tns: ['鱼韵'] }] });
  assert.equal(track.artist, 'サカナクション');
  assert.deepEqual(track.artistAliases, ['Sakanaction', '鱼韵']);
  assert.equal(track.provider, 'netease');
});

test('keeps aliases when queued metadata is normalized again', () => {
  const track = normalize({ id: '1', title: 'Song', artist: 'Artist', artistAliases: ['Alias'], provider: 'netease' });
  assert.deepEqual(track.artistAliases, ['Alias']);
});

test('accepts only trimmed, deduplicated string aliases plus a translation field', () => {
  const track = normalize({ id: 1, name: 'Song', ar: [{ name: 'Artist', alias: ['  Known  ', 'Known', {}, 0], trans: 'Translation' }] });
  assert.deepEqual(track.artistAliases, ['Known', 'Translation']);
});

test('short durations in milliseconds stay idempotent across round-trips while interval converts seconds to ms', () => {
  const first = normalize({ id: 10, name: 'Short Interlude', dt: 5000, ar: [{ name: 'Artist' }] });
  assert.equal(first.duration, 5000);
  const second = normalize(first);
  assert.equal(second.duration, 5000);

  const fromSeconds = normalize({ id: 'qq:abc', title: 'FML', interval: 236, provider: 'qq' });
  assert.equal(fromSeconds.duration, 236000);
});

test('preserves verified qqMidVersion and mediaMid through normalization', () => {
  const track = normalize({ id: '409736441', title: 'FML', artist: 'Kanye West', provider: 'netease', qqMid: '0005toRJ3QqtAx', mediaMid: '003mHViD1MfUDY', qqMidVersion: 2 });
  assert.equal(track.qqMid, '0005toRJ3QqtAx');
  assert.equal(track.mediaMid, '003mHViD1MfUDY');
  assert.equal(track.qqMidVersion, 2);
});
