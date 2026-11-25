'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const lyricsModule = require('../../assets/lyrics.js');
const { loadAppFunctions } = require('./support/app-harness');

function createLyricsHarness(responder) {
  const requests = [];
  const timers = [];
  let renderCount = 0;
  const state = {
    media: { track: null, queue: [], index: -1, playing: false, buffering: false, source: '', lyricsTargets: [], error: null },
    lyrics: [],
    lyricsLoading: false,
    lyricsIndex: -2,
    lyricsGeneration: 0
  };
  const lyricsSession = {
    trackIdentity: '',
    inFlight: false,
    resolvedKey: '',
    hasLines: false,
    hasTranslation: false,
    retriedError: false,
    tried: new Map(),
    retryTimer: 0
  };
  let rawStateJson = '';
  const app = loadAppFunctions([
    'artistNames', 'artistAliasNames', 'artistRefs', 'normalizeTrack', 'mediaQueue',
    'normalizeLyricTarget', 'normalizeMediaState', 'lyricsText', 'resolveLyricsTargets',
    'lyricsTimelinesCompatible', 'resetLyricsSession', 'requestLyricTarget',
    'fetchLyricsForCurrent', 'pollMediaState'
  ], {
    state,
    lyricsSession,
    lastProcessedMediaJson: null,
    pendingPlayerModes: null,
    mediaQueueCache: { version: -1, queue: [] },
    safeHttpsUrl: value => String(value || ''),
    nativeReady: () => true,
    parseNativeObject: (raw, fallback) => {
      try { return JSON.parse(raw); } catch (_) { return fallback; }
    },
    applyPendingPlayerModes: () => {},
    animateMiniTrackHandoff: () => null,
    finishMiniTrackHandoff: () => {},
    updatePlayerChrome: () => {},
    updateProgress: () => {},
    updateLyricsActive: () => {},
    loadRecentHistory: () => {},
    renderLyrics: () => { renderCount += 1; },
    document: { hidden: false },
    setTimeout: (fn, ms) => {
      const id = timers.length + 1;
      timers.push({ id, fn, ms, cleared: false });
      return id;
    },
    clearTimeout: id => {
      const entry = timers.find(t => t.id === id);
      if (entry) entry.cleared = true;
    },
    request: (method, args) => {
      requests.push({ method, args });
      return responder(method, args, requests.length);
    },
    window: {
      YPMLyrics: lyricsModule,
      Native: {
        getState: () => rawStateJson
      }
    }
  });

  const poll = payload => {
    rawStateJson = JSON.stringify(payload);
    app.pollMediaState();
  };
  const flushMicrotasks = () => new Promise(resolve => setImmediate(resolve));

  return { app, state, requests, timers, poll, flushMicrotasks, getRenderCount: () => renderCount };
}

test('Like Him stream-opening -> stream-prepared never sends NetEase ID to qqLyrics and keeps NetEase lyrics', async () => {
  const ncmLrc = '[00:01.201][Intro]\n[00:03.137]Damn, every time I look at you\n[00:07.605]You got me feeling like him';
  const ncmTrans = '[00:03.137]每次看着你\n[00:07.605]我都觉得自己像他';
  const ctx = createLyricsHarness(async (method, args) => {
    if (method === 'lyrics' && args.id === '2640855415') {
      return { code: 200, lrc: { lyric: ncmLrc }, tlyric: { lyric: ncmTrans } };
    }
    throw new Error(`unexpected ${method}:${JSON.stringify(args)}`);
  });

  // Snapshot 1: stream-opening with source=qq, unverified track.qqMid empty, live lyricsTargets carries catalog + verified QQ MID
  ctx.poll({
    track: { id: '2640855415', provider: 'netease', title: 'Like Him', artist: 'Tyler, The Creator', duration: 278047 },
    source: 'qq',
    playing: false,
    buffering: true,
    lyricsTargets: [
      { provider: 'netease', id: '2640855415' },
      { provider: 'qq', id: '001iCu2X0UNowl' }
    ],
    queue: []
  });
  await ctx.flushMicrotasks();

  assert.equal(ctx.requests.length, 1);
  assert.equal(ctx.requests[0].method, 'lyrics');
  assert.equal(ctx.requests[0].args.id, '2640855415');
  assert.ok(ctx.state.lyrics.length >= 2);
  assert.ok(ctx.state.lyrics.some(l => l.translation));

  // Snapshot 2: stream-prepared persists qqMid=001iCu2X0UNowl -> already has translated lyrics, no redundant re-fetch
  ctx.poll({
    track: {
      id: '2640855415', provider: 'netease', title: 'Like Him', artist: 'Tyler, The Creator',
      duration: 278047, qqMid: '001iCu2X0UNowl', qqMidVersion: 2
    },
    source: 'qq',
    playing: true,
    buffering: false,
    lyricsTargets: [
      { provider: 'netease', id: '2640855415' },
      { provider: 'qq', id: '001iCu2X0UNowl' }
    ],
    queue: []
  });
  await ctx.flushMicrotasks();

  assert.equal(ctx.requests.length, 1);
  assert.ok(ctx.state.lyrics.length >= 2);
});

test('falls back to newly arrived verified QQ MID when NetEase lyrics are empty', async () => {
  const qqLrc = '[00:03.08]Line one\n[00:07.50]Line two';
  const qqTrans = '[00:03.08]第一行\n[00:07.50]第二行';
  const ctx = createLyricsHarness(async (method, args) => {
    if (method === 'lyrics') return { code: 200, lrc: { lyric: '' }, tlyric: { lyric: '' } };
    if (method === 'qqLyrics' && args.mid === '001iCu2X0UNowl') {
      return { code: 200, lrc: { lyric: qqLrc }, tlyric: { lyric: qqTrans } };
    }
    throw new Error(`unexpected ${method}`);
  });

  // First poll: only NetEase catalog target known -> returns empty
  ctx.poll({
    track: { id: '2640855415', provider: 'netease', title: 'Like Him', artist: 'Tyler, The Creator', duration: 278047 },
    source: 'qq',
    playing: false,
    buffering: true,
    queue: []
  });
  await ctx.flushMicrotasks();
  assert.equal(ctx.requests.length, 1);
  assert.equal(ctx.requests[0].method, 'lyrics');
  assert.equal(ctx.state.lyrics.length, 0);

  // Second poll: verified QQ target arrives -> automatically tries qqLyrics and populates lyrics
  ctx.poll({
    track: {
      id: '2640855415', provider: 'netease', title: 'Like Him', artist: 'Tyler, The Creator',
      duration: 278047, qqMid: '001iCu2X0UNowl', qqMidVersion: 2
    },
    source: 'qq',
    playing: true,
    buffering: false,
    lyricsTargets: [
      { provider: 'netease', id: '2640855415' },
      { provider: 'qq', id: '001iCu2X0UNowl' }
    ],
    queue: []
  });
  await ctx.flushMicrotasks();
  assert.equal(ctx.requests.length, 2);
  assert.equal(ctx.requests[1].method, 'qqLyrics');
  assert.equal(ctx.requests[1].args.mid, '001iCu2X0UNowl');
  assert.equal(ctx.state.lyrics.length, 2);
  assert.equal(ctx.state.lyrics[0].translation, '第一行');
});

test('QQ catalog bridged to NetEase upgrades translation in background without clearing original lyrics', async () => {
  const qqOrig = '[00:03.00]First line\n[00:10.00]Second line\n[00:20.00]Third line\n[00:30.00]Fourth line';
  const ncmOrig = '[00:03.10]First line\n[00:10.10]Second line\n[00:20.10]Third line\n[00:30.20]Fourth line';
  const ncmTrans = '[00:03.10]第一行\n[00:10.10]第二行\n[00:20.10]第三行\n[00:30.20]第四行';
  const ctx = createLyricsHarness(async (method, args) => {
    if (method === 'qqLyrics' && args.mid === '0020FvID2rdOu0') {
      return { code: 200, lrc: { lyric: qqOrig }, tlyric: { lyric: '' } };
    }
    if (method === 'lyrics' && args.id === '2664365687') {
      return { code: 200, lrc: { lyric: ncmOrig }, tlyric: { lyric: ncmTrans } };
    }
    throw new Error(`unexpected ${method}`);
  });

  ctx.poll({
    track: { id: 'qq:0020FvID2rdOu0', provider: 'qq', title: 'Like Him', artist: 'Tyler, The Creator', duration: 278000 },
    source: 'netease',
    playing: true,
    buffering: false,
    lyricsTargets: [
      { provider: 'qq', id: '0020FvID2rdOu0' },
      { provider: 'netease', id: '2664365687' }
    ],
    queue: []
  });
  await ctx.flushMicrotasks();
  await ctx.flushMicrotasks();

  assert.equal(ctx.requests.length, 2);
  assert.equal(ctx.requests[0].method, 'qqLyrics');
  assert.equal(ctx.requests[1].method, 'lyrics');
  assert.equal(ctx.state.lyrics.length, 4);
  assert.equal(ctx.state.lyrics[0].translation, '第一行');
});

test('A -> B -> A drops stale A response and bounded error retry recovers once', async () => {
  const deferred = [];
  let failOnce = true;
  const ctx = createLyricsHarness((method, args) => {
    if (args.id === '111') {
      return new Promise(resolve => deferred.push(resolve));
    }
    if (args.id === '222') {
      if (failOnce) {
        failOnce = false;
        return Promise.reject(new Error('network blip'));
      }
      return Promise.resolve({ code: 200, lrc: { lyric: '[00:05.00]Recovered B' }, tlyric: { lyric: '' } });
    }
    return Promise.resolve({ code: 200, lrc: { lyric: '' } });
  });

  // Track A starts
  ctx.poll({ track: { id: '111', provider: 'netease', title: 'A', artist: 'X' }, source: 'netease', playing: true, queue: [] });
  // Switch to Track B -> fails once, schedules single bounded retry (not on 300ms polls)
  ctx.poll({ track: { id: '222', provider: 'netease', title: 'B', artist: 'X' }, source: 'netease', playing: true, queue: [] });
  await ctx.flushMicrotasks();
  assert.equal(ctx.state.lyrics.length, 0);
  assert.equal(ctx.timers.length, 1);
  // Stale A resolves late -> must NOT overwrite Track B
  deferred[0]({ code: 200, lrc: { lyric: '[00:01.00]Stale A' } });
  await ctx.flushMicrotasks();
  assert.equal(ctx.state.lyrics.length, 0);
  // Bounded retry fires -> Track B recovers
  ctx.timers[0].fn();
  await ctx.flushMicrotasks();
  assert.equal(ctx.state.lyrics.length, 1);
  assert.equal(ctx.state.lyrics[0].text, 'Recovered B');
  // Switch back to Track A -> starts fresh generation and accepts new A response cleanly
  ctx.poll({ track: { id: '111', provider: 'netease', title: 'A', artist: 'X' }, source: 'netease', playing: true, queue: [] });
  assert.equal(deferred.length, 2);
  deferred[1]({ code: 200, lrc: { lyric: '[00:02.00]Fresh A' } });
  await ctx.flushMicrotasks();
  assert.equal(ctx.state.lyrics.length, 1);
  assert.equal(ctx.state.lyrics[0].text, 'Fresh A');
});

test('updateMiniSubtitle works with YPMMiniLyrics controller and lyricsSession without ReferenceError', () => {
  const updates = [];
  const state = {
    media: { track: { id: '2640855415', provider: 'netease', title: 'Like Him', artist: 'Tyler, The Creator' }, playing: true, buffering: false, position: 4000, duration: 278000 },
    lyrics: [{ time: 3000, text: 'Damn, every time I look at you', translation: '每次看着你' }]
  };
  const app = loadAppFunctions(['updateMiniSubtitle'], {
    state,
    miniLyricsController: null,
    lyricsSession: { trackIdentity: 'netease:2640855415', resolvedKey: 'netease:2640855415' },
    $: selector => (selector === '#mini-subtitle' ? { textContent: '' } : null),
    standbyActive: () => false,
    mediaPosition: () => 4000,
    window: {
      YPMLyrics: lyricsModule,
      YPMMiniLyrics: { create: () => ({ update: payload => updates.push(payload) }) }
    }
  });
  app.updateMiniSubtitle(0);
  assert.equal(updates.length, 1);
  assert.equal(updates[0].text, 'Damn, every time I look at you');
  assert.ok(updates[0].key.includes('netease:2640855415'));
});

test('retry timer cleared by newly arrived target does not burn bounded retry budget', async () => {
  let ncmCalls = 0;
  let qqCalls = 0;
  const ctx = createLyricsHarness(async (method) => {
    if (method === 'lyrics') {
      ncmCalls += 1;
      if (ncmCalls === 1) throw new Error('temp error');
      return { code: 200, lrc: { lyric: '[00:04.00]Recovered NetEase' }, tlyric: { lyric: '' } };
    }
    if (method === 'qqLyrics') {
      qqCalls += 1;
      return { code: 200, lrc: { lyric: '' }, tlyric: { lyric: '' } };
    }
    throw new Error('unexpected');
  });

  ctx.poll({ track: { id: '2640855415', provider: 'netease', title: 'Like Him', artist: 'Tyler, The Creator' }, source: 'netease', playing: false, buffering: true, queue: [] });
  await ctx.flushMicrotasks();
  assert.equal(ctx.timers.length, 1);
  assert.equal(ctx.timers[0].cleared, false);

  // Verified QQ target arrives before timer fires -> cancels timer 1, tries QQ (empty), then still schedules retry!
  ctx.poll({
    track: { id: '2640855415', provider: 'netease', title: 'Like Him', artist: 'Tyler, The Creator' },
    source: 'qq', playing: true, buffering: false,
    lyricsTargets: [{ provider: 'netease', id: '2640855415' }, { provider: 'qq', id: '001iCu2X0UNowl' }],
    queue: []
  });
  await ctx.flushMicrotasks();
  assert.equal(qqCalls, 1);
  assert.equal(ctx.timers[0].cleared, true);
  assert.equal(ctx.timers.length, 2);
  ctx.timers[1].fn();
  await ctx.flushMicrotasks();
  assert.equal(ctx.state.lyrics.length, 1);
  assert.equal(ctx.state.lyrics[0].text, 'Recovered NetEase');
});
