const test = require('node:test');
const assert = require('node:assert/strict');
const { loadAppFunctions, fakeDocument } = require('./support/app-harness');

function player() {
  let resolve;
  const result = new Promise(done => { resolve = done; });
  const app = loadAppFunctions(['openPlayerDetail', 'trackActionTarget', 'favoriteTrackKey', 'favoriteProvider'], {
    $: fakeDocument(),
    state: { fullPlayer: true, playerDetailLookup: null, media: { track: { id: '123', provider: 'netease', artist: 'Artist' } } },
    playerGeneration: 1,
    trackRefsFor: () => result,
    closePlayer() { app.state.fullPlayer = false; app.playerGeneration++; },
    openArtist(id) { app.destination = id; },
    openAlbum(id) { app.albumDestination = id; },
    openTrackActions() { throw new Error('single artist must not open a sheet'); },
    showToast(message) { app.message = message; }
  });
  return { app, resolve };
}

test('player artist resolves in place and navigates directly without an action sheet', async () => {
  const { app, resolve } = player();
  const pending = app.openPlayerDetail('artist');
  assert.equal(app.state.fullPlayer, true);
  assert.equal(app.destination, undefined);
  resolve({ artists: [{ id: '42', name: 'Artist' }], albumId: '' });
  await pending;
  assert.equal(app.destination, '42');
  assert.equal(app.state.fullPlayer, false);
  assert.equal(app.state.playerDetailLookup, null);
});

test('artist lookup cannot navigate after the user changes tracks', async () => {
  const { app, resolve } = player();
  const pending = app.openPlayerDetail('artist');
  app.state.media.track = { id: '456', provider: 'netease', artist: 'Other' };
  resolve({ artists: [{ id: '42', name: 'Artist' }], albumId: '' });
  await pending;
  assert.equal(app.destination, undefined);
  assert.equal(app.state.fullPlayer, true);
  assert.equal(app.state.playerDetailLookup, null);
});

test('closing and reopening the player cancels an old artist lookup', async () => {
  const { app, resolve } = player();
  const pending = app.openPlayerDetail('artist');
  app.closePlayer();
  app.state.fullPlayer = true;
  resolve({ artists: [{ id: '42', name: 'Artist' }], albumId: '' });
  await pending;
  assert.equal(app.destination, undefined);
});


test('player album resolves in place and opens the album without an action sheet', async () => {
  const { app, resolve } = player();
  const pending = app.openPlayerDetail('album');
  assert.equal(app.state.fullPlayer, true);
  resolve({ artists: [{ id: '42', name: 'Artist' }], albumId: '99' });
  await pending;
  assert.equal(app.albumDestination, '99');
  assert.equal(app.destination, undefined);
  assert.equal(app.state.fullPlayer, false);
  assert.equal(app.state.playerDetailLookup, null);
});
