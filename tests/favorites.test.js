'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const appSource = fs.readFileSync(path.join(__dirname, '../assets/app.js'), 'utf8');

function extractBetween(startMarker, endMarker) {
  const start = appSource.indexOf(startMarker);
  const end = appSource.indexOf(endMarker, start);
  assert.notEqual(start, -1, 'production marker exists: ' + startMarker);
  assert.notEqual(end, -1, 'production marker exists: ' + endMarker);
  return appSource.slice(start, end);
}

const favoriteFunctions = extractBetween(
  '  function favoriteProvider(track) {',
  '  function setShuffle(enabled, silent) {'
);
const trackActionFavoriteFunctions = extractBetween(
  '  async function loadTrackActionFavorite(actions) {',
  '  function openTrackActionPlaylists() {'
);

function createHarness(handler) {
  const calls = [];
  const toasts = [];
  const loginPrompts = [];
  const button = {
    classList: { toggle() {} },
    attributes: {},
    setAttribute(name, value) { this.attributes[name] = value; },
    disabled: false,
    title: ''
  };
  const context = {
    state: {
      media: { track: null, source: 'qq' },
      settings: { sourcePreference: 'qq' },
      authMutation: '',
      favoriteGeneration: 0,
      favorite: { key: '', provider: '', available: false, loggedIn: false, liked: null, loading: false, mutating: false }
    },
    request(method, args) {
      calls.push({ method, args: Object.assign({}, args) });
      try {
        return Promise.resolve(handler(method, args || {}));
      } catch (error) {
        return Promise.reject(error);
      }
    },
    showToast(message) { toasts.push(String(message)); },
    apiRequestSucceeded(response) {
      if (!response || typeof response !== 'object' || response.code == null) return true;
      return Number(response.code) === 0 || Number(response.code) === 200;
    },
    $(selector) { return selector === '#mini-like' ? button : null; },
    renderTrackActions() {},
    closeTrackActions() { context.trackActions = null; },
    promptProviderLogin(provider) { loginPrompts.push(provider); },
    providerName(provider) { return provider === 'qq' ? 'QQ 音乐' : '网易云音乐'; },
    trackActions: null
  };
  vm.createContext(context);
  const prelude = [

    'function trackActionsCurrent(generation) { return !!trackActions && trackActions.generation === generation; }',
    favoriteFunctions,
    trackActionFavoriteFunctions,
    'globalThis.favoriteApi = {',
    '  state: state,',
    '  favoriteProvider: favoriteProvider,',
    '  favoriteTrackKey: favoriteTrackKey,',
    '  favoriteMessage: favoriteMessage,',
    '  loadFavoriteStatus: loadFavoriteStatus,',
    '  toggleFavorite: toggleFavorite,',
    '  loadTrackActionFavorite: loadTrackActionFavorite,',
    '  toggleTrackActionFavorite: toggleTrackActionFavorite,',
    '  setTrackActions: function(value) { trackActions = value; },',
    '  getTrackActions: function() { return trackActions; }',
    '};'
  ].join('\n');
  vm.runInContext(prelude, context);
  return { api: context.favoriteApi, calls, toasts, loginPrompts, button };
}

const qqTrack = {
  id: 'qq:002mdBXj3BYeJz',
  qqMid: '002mdBXj3BYeJz',
  provider: 'qq',
  title: '花',
  artist: '藤井风',
  duration: 246000
};

function defaultResponse(method, args) {
  if (method === 'matchTrack') {
    return { code: 200, matched: true, id: '918273', title: '花', artist: '藤井風' };
  }
  if (method === 'likeStatus' || method === 'qqLikeStatus') {
    return { code: 200, loggedIn: true, liked: false };
  }
  if (method === 'setLiked' || method === 'qqSetLiked') {
    return { code: 200, loggedIn: true, liked: !!args.liked };
  }
  return { code: 200 };
}

test('favorite response keeps supported Chinese punctuation', () => {
  const harness = createHarness(defaultResponse);
  assert.equal(
    harness.api.favoriteMessage({ message: '\u6536\u85cf\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002' }, 'fallback'),
    '\u6536\u85cf\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002'
  );
});

test('NetEase catalog track stays in NetEase favorites while QQ is the actual stream', async () => {
  const harness = createHarness(defaultResponse);
  const track = { id: '681234', provider: 'netease', title: '花', artist: '藤井風', duration: 246000 };
  harness.api.state.media.track = track;
  harness.api.state.media.source = 'qq';

  await harness.api.loadFavoriteStatus(track);
  await harness.api.toggleFavorite();

  assert.deepEqual(harness.calls.map(call => call.method), ['likeStatus', 'setLiked']);
  assert.equal(harness.calls[0].args.id, '681234');
  assert.equal(harness.calls[1].args.id, '681234');
  assert.equal(harness.calls[1].args.liked, true);
});

test('QQ catalog favorite resolves to the matched NetEase ID for status and writes', async () => {
  const harness = createHarness(defaultResponse);
  harness.api.state.media.track = qqTrack;

  await harness.api.loadFavoriteStatus(qqTrack);
  await harness.api.toggleFavorite();

  assert.deepEqual(harness.calls.map(call => call.method), ['matchTrack', 'likeStatus', 'setLiked']);
  assert.equal(harness.calls[1].args.id, '918273');
  assert.equal(harness.calls[2].args.id, '918273');
  assert.equal(harness.calls[2].args.liked, true);
  assert.equal(harness.api.state.favorite.provider, 'netease');
});

test('unmatched QQ song cannot be favorited and explains why', async () => {
  const harness = createHarness((method, args) => {
    if (method === 'matchTrack') return { code: 200, matched: false };
    return defaultResponse(method, args);
  });
  harness.api.state.media.track = qqTrack;

  await harness.api.loadFavoriteStatus(qqTrack);
  await harness.api.toggleFavorite();

  assert.equal(harness.api.state.favorite.available, false);
  assert.match(harness.api.state.favorite.errorMessage, /\u65e0\u6cd5\u6536\u85cf/);
  assert.ok(harness.toasts.some(message => message.includes('\u65e0\u6cd5\u6536\u85cf')));
  assert.equal(harness.calls.some(call => call.method === 'setLiked' || call.method === 'qqSetLiked'), false);
  assert.equal(harness.calls.some(call => call.method === 'qqLikeStatus'), false);
});

test('a delayed QQ match cannot replace the favorite status of the next track', async () => {
  let releaseMatch;
  const harness = createHarness((method, args) => {
    if (method === 'matchTrack' || method === 'qqLikeStatus') {
      if (method === 'matchTrack' || args.id === qqTrack.id) {
        return new Promise(resolve => { releaseMatch = resolve; });
      }
    }
    return defaultResponse(method, args);
  });
  const nextTrack = { id: '991122', provider: 'netease', title: '下一首', artist: '歌手', duration: 200000 };
  harness.api.state.media.track = qqTrack;
  const oldLoad = harness.api.loadFavoriteStatus(qqTrack);
  await new Promise(resolve => setImmediate(resolve));

  harness.api.state.media.track = nextTrack;
  await harness.api.loadFavoriteStatus(nextTrack);
  assert.equal(typeof releaseMatch, 'function');
  releaseMatch({ code: 200, matched: true, id: '918273' });
  await oldLoad;
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(harness.api.state.favorite.key, harness.api.favoriteTrackKey(nextTrack));
  assert.equal(harness.api.state.favorite.liked, false);
  assert.equal(harness.calls.some(call => call.method === 'likeStatus' && call.args.id === '918273'), false);
});

test('track actions share the NetEase favorite target and synchronize the mini heart', async () => {
  const harness = createHarness(defaultResponse);
  harness.api.state.media.track = qqTrack;
  await harness.api.loadFavoriteStatus(qqTrack);
  const action = {
    track: qqTrack,
    provider: 'qq',
    key: harness.api.favoriteTrackKey(qqTrack),
    generation: 5,
    favorite: { loading: true, loggedIn: false, liked: null, mutating: false, message: '' },
    target: null
  };
  harness.api.setTrackActions(action);

  await harness.api.loadTrackActionFavorite(action);
  await harness.api.toggleTrackActionFavorite();

  const writes = harness.calls.filter(call => call.method === 'setLiked' || call.method === 'qqSetLiked');
  assert.equal(writes.length, 1);
  assert.equal(writes[0].method, 'setLiked');
  assert.equal(writes[0].args.id, '918273');
  assert.equal(harness.api.state.favorite.liked, true);
});

test('unmatched QQ track action has an explicit no-write state', async () => {
  const harness = createHarness((method, args) => {
    if (method === 'matchTrack') return { code: 200, matched: false };
    return defaultResponse(method, args);
  });
  harness.api.state.media.track = qqTrack;
  const action = {
    track: qqTrack,
    provider: 'qq',
    key: harness.api.favoriteTrackKey(qqTrack),
    generation: 8,
    favorite: { loading: true, loggedIn: false, liked: null, mutating: false, message: '' },
    target: null
  };
  harness.api.setTrackActions(action);

  await harness.api.loadTrackActionFavorite(action);
  await harness.api.toggleTrackActionFavorite();

  assert.match(action.favorite.message, /\u65e0\u6cd5\u6536\u85cf/);
  assert.equal(harness.calls.some(call => call.method === 'setLiked' || call.method === 'qqSetLiked'), false);
  assert.equal(harness.calls.some(call => call.method === 'qqLikeStatus'), false);
});

test('QQ artist aliases are forwarded when matching to the NetEase catalog', async () => {
  let matchArgs = null;
  const harness = createHarness((method, args) => {
    if (method === 'matchTrack') {
      matchArgs = args;
      return { code: 200, matched: true, id: '7345', title: '忘れられないの', artist: 'サカナクション' };
    }
    return defaultResponse(method, args);
  });
  const track = {
    id: 'qq:alias-fixture',
    provider: 'qq',
    title: '忘れられないの',
    artist: 'sakanaction',
    artistAliases: ['Sakanaction', 'サカナクション', '\u9c7c\u97f5'],
    duration: 250000
  };
  harness.api.state.media.track = track;

  await harness.api.loadFavoriteStatus(track);

  assert.deepEqual(matchArgs.artistAliases, track.artistAliases);
  assert.equal(harness.calls.some(call => call.method === 'likeStatus' && call.args.id === '7345'), true);
});
