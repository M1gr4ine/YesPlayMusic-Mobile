'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadAppFunctions } = require('./support/app-harness');

const app = loadAppFunctions(['artistNames', 'artistAliasNames', 'artistMembers', 'artistRefs', 'normalizeTrack'], { safeHttpsUrl: value => String(value || '') });
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

test('keeps each collaborator and their own aliases through a queue round trip', () => {
  const track = normalize({ id: 2125538091, name: 'CARNIVAL', ar: [
    { id: 59188409, name: '¥$' }, { id: 37520, name: 'Kanye West', alias: ['ye'] },
    { id: 789235, name: 'Ty Dolla $ign' }
  ] });
  assert.deepEqual(track.artistMembers, [{ providerId: '59188409', name: '¥$' },
    { providerId: '37520', name: 'Kanye West', aliases: ['ye'] },
    { providerId: '789235', name: 'Ty Dolla $ign' }]);
  assert.deepEqual(normalize(track).artistMembers, track.artistMembers);
  assert.deepEqual(track.artistAliases, []);

  const qq = normalize({ id: 'qq:single', title: 'Famous', singer: [{ id: 12, name: 'Ye (侃爷)' }] });
  assert.equal(qq.artistMembers[0].providerId, '12');
  assert.equal(qq.artistRefs, undefined);

  const stale = normalize({ id: 'qq:old', title: 'Famous', artist: 'Another Artist',
    artistMembers: [{ name: 'Kanye West', aliases: ['ye'] }], artistAliases: ['ye'] });
  assert.deepEqual(stale.artistAliases, []);
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
