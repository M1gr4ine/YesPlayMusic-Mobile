'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadAppFunctions, fakeDocument } = require('./support/app-harness');

const FAVORITE_FUNCTIONS = [
  'apiRequestSucceeded', 'favoriteProvider', 'favoriteTrackKey', 'favoriteRequest', 'favoriteTargetFor',
  'favoriteMessage', 'favoriteRequestIsCurrent', 'updateMiniFavoriteButton', 'lastKnownLiked',
  'invalidateFavoriteStatus', 'loadFavoriteStatus', 'toggleFavorite', 'trackActionsCurrent',
  'loadTrackActionFavorite', 'syncMiniFavorite', 'toggleTrackActionFavorite'
];

const qqTrack = { id: 'qq:002mdBXj3BYeJz', qqMid: '002mdBXj3BYeJz', provider: 'qq', title: '花', artist: '藤井风', duration: 246000 };
const neteaseTrack = { id: '681234', provider: 'netease', title: '花', artist: '藤井風', duration: 246000 };

function defaultResponse(method, args) {
  if (method === 'matchTrack') return { code: 200, matched: true, id: '918273', title: '花', artist: '藤井風' };
  if (method === 'likeStatus') return { code: 200, loggedIn: true, liked: false };
  if (method === 'setLiked') return { code: 200, loggedIn: true, liked: !!args.liked };
  return { code: 200 };
}

function createFavorites(handler = defaultResponse) {
  const calls = [];
  const toasts = [];
  const app = loadAppFunctions(FAVORITE_FUNCTIONS, {
    $: fakeDocument(),
    state: { media: { track: null }, authMutation: '', favoriteGeneration: 0, favorite: { key: '' } },
    trackActions: null,
    request(method, args) {
      calls.push({ method, args });
      try { return Promise.resolve(handler(method, args || {})); } catch (error) { return Promise.reject(error); }
    },
    showToast(message) { toasts.push(String(message)); },
    renderTrackActions() {},
    closeTrackActions() { app.trackActions = null; },
    promptProviderLogin() {}
  });
  const methods = () => calls.map(call => call.method);
  const heartLiked = () => app.$('#mini-like').classList.contains('is-liked');
  return { app, calls, toasts, methods, heartLiked };
}

async function play(favorites, track) {
  favorites.app.state.media.track = track;
  await favorites.app.loadFavoriteStatus(track);
}

test('a NetEase catalog song is favorited by its own ID even when QQ supplies the stream', async () => {
  const favorites = createFavorites();
  await play(favorites, neteaseTrack);
  await favorites.app.toggleFavorite();
  assert.deepEqual(favorites.methods(), ['likeStatus', 'setLiked']);
  assert.deepEqual(favorites.calls.map(call => call.args.id), ['681234', '681234']);
  assert.equal(favorites.heartLiked(), true);
});

test('a QQ catalog song is favorited through its matched NetEase ID, forwarding artist aliases', async () => {
  const favorites = createFavorites();
  const track = Object.assign({}, qqTrack, { artistAliases: ['Fujii Kaze'] });
  await play(favorites, track);
  await favorites.app.toggleFavorite();
  assert.deepEqual(favorites.methods(), ['matchTrack', 'likeStatus', 'setLiked']);
  assert.deepEqual(favorites.calls[0].args.artistAliases, ['Fujii Kaze']);
  assert.equal(favorites.calls[2].args.id, '918273');
  assert.equal(favorites.calls[2].args.liked, true);
});

test('an unmatched QQ song is never written and explains why', async () => {
  const favorites = createFavorites((method, args) =>
    method === 'matchTrack' ? { code: 200, matched: false } : defaultResponse(method, args));
  await play(favorites, qqTrack);
  await favorites.app.toggleFavorite();
  assert.equal(favorites.app.state.favorite.available, false);
  assert.ok(favorites.toasts.some(message => message.includes('无法收藏')));
  assert.deepEqual(favorites.methods(), ['matchTrack']);
});

test('a slow match for the previous song cannot overwrite the current song status', async () => {
  let releaseMatch;
  const favorites = createFavorites((method, args) =>
    method === 'matchTrack' ? new Promise(resolve => { releaseMatch = resolve; }) : defaultResponse(method, args));
  favorites.app.state.media.track = qqTrack;
  const staleLoad = favorites.app.loadFavoriteStatus(qqTrack);
  await play(favorites, neteaseTrack);
  releaseMatch({ code: 200, matched: true, id: '918273' });
  await staleLoad;
  assert.equal(favorites.app.state.favorite.key, favorites.app.favoriteTrackKey(neteaseTrack));
  assert.equal(favorites.calls.some(call => call.args.id === '918273'), false);
});

test('reloading the same song keeps the red heart instead of blinking it off', async () => {
  let statusCalls = 0;
  let releaseStatus;
  const favorites = createFavorites((method, args) => {
    if (method !== 'likeStatus') return defaultResponse(method, args);
    if (statusCalls++ === 0) return { code: 200, loggedIn: true, liked: true };
    return new Promise(resolve => { releaseStatus = resolve; });
  });
  await play(favorites, neteaseTrack);
  assert.equal(favorites.heartLiked(), true);

  favorites.app.invalidateFavoriteStatus(true);
  assert.equal(favorites.heartLiked(), true, 'auth refresh');
  const reload = favorites.app.loadFavoriteStatus(neteaseTrack);
  assert.equal(favorites.heartLiked(), true, 'status reload');
  assert.equal(favorites.app.$('#mini-like').disabled, true, 'no writes while revalidating');
  await new Promise(setImmediate);
  releaseStatus({ code: 200, loggedIn: true, liked: true });
  await reload;
  assert.equal(favorites.heartLiked(), true);

  favorites.app.invalidateFavoriteStatus(false);
  assert.equal(favorites.heartLiked(), false, 'logout clears the heart');
});

test('the track action sheet writes the same NetEase target and updates the mini heart', async () => {
  const favorites = createFavorites();
  await play(favorites, qqTrack);
  const actions = { track: qqTrack, key: favorites.app.favoriteTrackKey(qqTrack), generation: 5, favorite: {}, target: null };
  favorites.app.trackActions = actions;
  await favorites.app.loadTrackActionFavorite(actions);
  await favorites.app.toggleTrackActionFavorite();
  const writes = favorites.calls.filter(call => call.method === 'setLiked');
  assert.equal(writes.length, 1);
  assert.equal(writes[0].args.id, '918273');
  assert.equal(favorites.heartLiked(), true);
});
