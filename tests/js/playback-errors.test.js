'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadAppFunctions } = require('./support/app-harness');

test('playTracks remaps clicked index when earlier entries are invalid', () => {
  const sent = [];
  let opened = false;
  const app = loadAppFunctions(['artistNames', 'artistAliasNames', 'artistRefs', 'normalizeTrack', 'playTracks'], {
    safeHttpsUrl: value => String(value || ''),
    nativeReady: () => true,
    window: { Native: { command: payload => sent.push(JSON.parse(payload)) } },
    openPlayer: () => { opened = true; },
    showToast: () => {}
  });

  app.playTracks([
    { id: 'broken' }, // missing title -> filtered out
    { id: '409736441', title: 'FML', artist: 'Kanye West / The Weeknd', duration: 236120, provider: 'netease' },
    { id: '999999', title: 'Other', artist: 'Other', duration: 200000, provider: 'netease' }
  ], 1);

  assert.equal(opened, true);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].index, 0);
  assert.equal(sent[0].queue[0].id, '409736441');
  assert.equal(sent[0].queue.length, 2);
});

test('normalizeMediaState preserves structured error codes and playbackErrorMessage distinguishes causes', () => {
  const app = loadAppFunctions(['artistNames', 'artistAliasNames', 'artistRefs', 'normalizeTrack', 'mediaQueue', 'normalizeMediaState', 'playbackErrorMessage'], {
    safeHttpsUrl: value => String(value || ''),
    mediaQueueCache: { version: -1, queue: [] },
    window: { Native: {} }
  });

  const unmatched = app.normalizeMediaState({ error: 'match_not_found', queue: [] });
  assert.equal(unmatched.error, 'match_not_found');
  assert.ok(app.playbackErrorMessage(unmatched.error, 'auto').includes('未找到同一版本'));

  const expired = app.normalizeMediaState({ error: 'qq_auth_expired', queue: [] });
  assert.equal(expired.error, 'qq_auth_expired');
  assert.ok(app.playbackErrorMessage(expired.error, 'auto').includes('QQ 音乐登录状态已失效'));

  const legacyBool = app.normalizeMediaState({ error: true, queue: [] });
  assert.equal(legacyBool.error, 'stream_unavailable');
  assert.ok(app.playbackErrorMessage(legacyBool.error, 'auto').includes('无法播放'));
});
