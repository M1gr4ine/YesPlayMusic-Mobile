'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadAppFunctions, fakeDocument, fakeClock } = require('./support/app-harness');

function createPlayer({ preference = 'qq', source = '', buffering = false, error = false, reducedMotion = false } = {}) {
  const clock = fakeClock();
  const favoriteLoads = [];
  const standby = { active: false, syncs: 0 };
  const app = loadAppFunctions([
    'currentTrackTitle', 'sourceLabel', 'updateSourceLabels', 'stopGlobalMessageTimers', 'hideGlobalMessage',
    'clearErrorMessage', 'setMessage', 'updatePlayerChrome', 'currentPlayMode', 'saveSettings',
    'favoriteProvider', 'favoriteTrackKey'
  ], {
    $: fakeDocument(),
    standbyActive: () => standby.active,
    syncStandby() { standby.syncs++; },
    window: { matchMedia: () => ({ matches: reducedMotion }), Native: { settings() {} } },
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    lastTrackSignature: '',
    globalMessageDismissTimer: 0,
    globalMessageFoldTimer: 0,
    activeGlobalErrorKey: '',
    PLAY_MODE_LABELS: { order: '顺序播放', all: '列表循环', one: '单曲循环', shuffle: '随机播放' },
    state: {
      settings: { sourcePreference: preference },
      favorite: { key: '' },
      media: {
        track: { id: '1001', provider: 'netease', title: 'Track', artist: 'Artist', album: 'Album', artwork: '', duration: 1000 },
        source, playing: false, buffering, error: error || null, position: 0, duration: 1000, shuffle: false, repeat: 'off'
      }
    },
    loadFavoriteStatus(track) {
      favoriteLoads.push(track.id);
      app.state.favorite = { key: app.favoriteTrackKey(track) };
    },
    invalidateFavoriteStatus() {},
    updateMiniFavoriteButton() {},
    updateMiniSubtitle() {},
    updateArt() {},
    setToggleIcon() {},
    setIcon() {},
    updateProgress() {},
    renderQueueIfChanged() {},
    updateLyricsActive() {},
    applyTheme() {},
    renderSettingsAccount() {}
  });
  return {
    app,
    clock,
    favoriteLoads,
    standby,
    poll: () => app.updatePlayerChrome(),
    label: () => app.$('#player-source').textContent,
    banner: () => app.$('#global-message')
  };
}

test('source label prefers the confirmed source, then reports matching or failure for the chosen one', () => {
  const cases = [
    [{ preference: 'netease', source: 'qq', buffering: true, error: true }, 'QQ 音源'],
    [{ source: 'provider-x' }, '音源'],
    [{ buffering: true }, '正在匹配QQ'],
    [{ error: true }, 'QQ音源不可用'],
    [{}, '音源未确认']
  ];
  for (const [options, expected] of cases) {
    const player = createPlayer(options);
    player.poll();
    assert.equal(player.label(), expected, JSON.stringify(options));
  }
});

test('source label follows status and preference changes on the same track without touching playback', () => {
  const player = createPlayer({ preference: 'netease', buffering: true });
  player.poll();
  player.app.state.media.playing = true;
  player.app.saveSettings({ sourcePreference: 'qq' });
  assert.equal(player.label(), '正在匹配QQ');
  assert.equal(player.app.state.media.source, '');
  player.app.state.media.buffering = false;
  player.app.state.media.error = true;
  player.poll();
  assert.equal(player.label(), 'QQ音源不可用');
});

test('play, pause and source re-resolution keep the favorite status; a new track reloads it', () => {
  const player = createPlayer({ source: 'qq' });
  player.poll();
  const media = player.app.state.media;
  media.playing = true;
  player.poll();
  media.source = '';
  media.buffering = true;
  player.poll();
  media.source = 'qq';
  media.buffering = false;
  player.poll();
  assert.deepEqual(player.favoriteLoads, ['1001']);

  media.track = Object.assign({}, media.track, { id: '1002' });
  player.poll();
  assert.deepEqual(player.favoriteLoads, ['1001', '1002']);
});

test('a playback error shows once, then folds away after four seconds', () => {
  const player = createPlayer({ error: true });
  player.poll();
  assert.equal(player.banner().hidden, false);
  player.clock.tick(3000);
  player.poll();
  player.clock.tick(1000);
  assert.equal(player.banner().classList.contains('is-collapsing'), true);
  player.clock.tick(190);
  assert.equal(player.banner().hidden, true);
  player.poll();
  assert.equal(player.banner().hidden, true, 'the same error is not shown again');
  assert.equal(player.clock.pending(), 0);
});

test('an error on a new track restarts the banner, and recovery hides it at once', () => {
  const player = createPlayer({ error: true });
  player.poll();
  player.clock.tick(3000);
  player.app.state.media.track = Object.assign({}, player.app.state.media.track, { id: '1002' });
  player.poll();
  player.clock.tick(1000);
  assert.equal(player.banner().classList.contains('is-collapsing'), false);

  player.app.state.media.error = null;
  player.poll();
  assert.equal(player.banner().hidden, true);
  assert.equal(player.clock.pending(), 0);
});

test('reduced motion hides the error without the fold transition', () => {
  const player = createPlayer({ error: true, reducedMotion: true });
  player.poll();
  player.clock.tick(4000);
  assert.equal(player.banner().hidden, true);
  assert.equal(player.banner().classList.contains('is-collapsing'), false);
});

test('informational messages stay until replaced', () => {
  const player = createPlayer();
  player.app.setMessage('Browser preview message', false);
  player.clock.tick(10000);
  assert.equal(player.banner().hidden, false);
  assert.equal(player.banner().textContent, 'Browser preview message');
  assert.equal(player.clock.pending(), 0);
});

test('while the landscape standby page is shown, the hidden portrait player is left alone', () => {
  const player = createPlayer({ source: 'qq' });
  player.poll();
  player.standby.active = true;
  player.app.state.media.track = Object.assign({}, player.app.state.media.track, { id: '1002', title: 'Next' });
  player.poll();
  assert.equal(player.app.$('#player-title').textContent, 'Track', 'portrait title untouched');
  assert.equal(player.standby.syncs, 1);
  assert.deepEqual(player.favoriteLoads, ['1001', '1002'], 'the heart still follows the new track');

  player.standby.active = false;
  player.poll();
  assert.equal(player.app.$('#player-title').textContent, 'Next', 'portrait catches up on exit');
});
