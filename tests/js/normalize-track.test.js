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
