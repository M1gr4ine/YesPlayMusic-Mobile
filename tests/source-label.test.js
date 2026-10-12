'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const root = path.resolve(__dirname, '..');
const appSource = fs.readFileSync(path.join(root, 'assets', 'app.js'), 'utf8');

function extractFunction(name) {
  const start = appSource.indexOf(`function ${name}(`);
  if (start < 0) throw new Error(`Could not load production function ${name}`);
  const open = appSource.indexOf('{', start);
  let depth = 0;
  let quote = '';
  let lineComment = false;
  let blockComment = false;

  for (let i = open; i < appSource.length; i++) {
    const current = appSource[i];
    const next = appSource[i + 1];
    if (lineComment) {
      if (current === '\n') lineComment = false;
      continue;
    }
    if (blockComment) {
      if (current === '*' && next === '/') { blockComment = false; i++; }
      continue;
    }
    if (quote) {
      if (current === '\\') { i++; continue; }
      if (current === quote) quote = '';
      continue;
    }
    if (current === '/' && next === '/') { lineComment = true; i++; continue; }
    if (current === '/' && next === '*') { blockComment = true; i++; continue; }
    if (current === "'" || current === '"' || current === '`') { quote = current; continue; }
    if (current === '{') depth++;
    else if (current === '}' && --depth === 0) return appSource.slice(start, i + 1);
  }
  throw new Error(`Could not parse production function ${name}`);
}

const productionFunctions = [
  'currentTrackTitle',
  'sourceLabel',
  'updateSourceLabels',
  'clearErrorMessage',
  'updatePlayerChrome',
  'saveSettings'
].map(extractFunction).join('\n');

const elements = new Map();
function fakeElement() {
  return {
    hidden: false,
    textContent: '',
    dataset: {},
    style: { height: '', setProperty() {} },
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    setAttribute() {},
    replaceChildren() {},
    append() {},
    addEventListener() {}
  };
}
function $(selector) {
  if (!elements.has(selector)) elements.set(selector, fakeElement());
  return elements.get(selector);
}

const context = vm.createContext({
  state: null,
  lastTrackSignature: '',
  globalMessageDismissTimer: 0,
  globalMessageFoldTimer: 0,
  activeGlobalErrorKey: '',
  $,
  window: { matchMedia: () => ({ matches: false }), Native: { settings() {} } },
  setTimeout() { return 1; },
  clearTimeout() {},
  updateMiniSubtitle() {},
  invalidateFavoriteStatus() {},
  updateArt() {},
  loadFavoriteStatus() {},
  setToggleIcon() {},
  updateMiniFavoriteButton() {},
  updateProgress() {},
  renderQueueIfChanged() {},
  updateLyricsActive() {},
  setMessage() {},
  applyTheme() {},
  renderSettingsAccount() {}
});
vm.runInContext(productionFunctions, context, { filename: 'assets/app.js production functions' });

function reset({ preference = 'qq', source = '', buffering = false, error = false, provider = 'netease', playing = false } = {}) {
  const track = { id: 'track-1', provider, title: 'Track', artist: 'Artist', album: 'Album', artwork: '', duration: 1000 };
  context.state = {
    settings: { sourcePreference: preference },
    searchProvider: preference,
    favorite: { key: '' },
    media: {
      track,
      source,
      playing,
      buffering,
      error: error ? true : null,
      position: 0,
      duration: 1000,
      updatedAt: 0,
      shuffle: false,
      repeat: 'off'
    }
  };
  context.lastTrackSignature = '';
  context.globalMessageDismissTimer = 0;
  context.globalMessageFoldTimer = 0;
  context.activeGlobalErrorKey = '';
  for (const node of elements.values()) {
    node.hidden = false;
    node.textContent = '';
    node.dataset = {};
    node.style.height = '';
  }
}
function displayedLabel() { return $('#player-source').textContent; }

test('a confirmed playback source takes precedence over preference and transient status', () => {
  reset({ preference: 'netease', source: 'qq', buffering: true, error: true });
  context.updatePlayerChrome();
  assert.equal(displayedLabel(), 'QQ 音源');
});

test('an unresolved buffering track reports the selected source being matched', () => {
  reset({ preference: 'qq', buffering: true, provider: 'netease' });
  context.updatePlayerChrome();
  assert.equal(displayedLabel(), '正在匹配QQ');
});

test('an unresolved error reports the selected source as unavailable', () => {
  reset({ preference: 'qq', error: true, provider: 'netease' });
  context.updatePlayerChrome();
  assert.equal(displayedLabel(), 'QQ音源不可用');
});

test('a buffering transition to idle refreshes the label with an unchanged track signature', () => {
  reset({ preference: 'qq', buffering: true });
  context.updatePlayerChrome();
  context.state.media.buffering = false;
  context.updatePlayerChrome();
  assert.equal(displayedLabel(), '音源未确认');
});

test('an error transition refreshes the label with an unchanged track signature', () => {
  reset({ preference: 'qq' });
  context.updatePlayerChrome();
  context.state.media.error = true;
  context.updatePlayerChrome();
  assert.equal(displayedLabel(), 'QQ音源不可用');
});

test('changing preference refreshes an unresolved label without interrupting playback or assigning a source', () => {
  reset({ preference: 'netease', buffering: true, playing: true });
  context.updatePlayerChrome();
  context.saveSettings({ sourcePreference: 'qq' });
  assert.equal(displayedLabel(), '正在匹配QQ');
  assert.equal(context.state.media.playing, true);
  assert.equal(context.state.media.source, '');
});

test('an unrecognized confirmed source does not inherit the preferred platform', () => {
  reset({ preference: 'qq', source: 'provider-x' });
  context.updatePlayerChrome();
  assert.equal(displayedLabel(), '音源');
});

test('an idle unresolved track does not present preference as a confirmed source', () => {
  reset({ preference: 'qq', provider: 'netease' });
  context.updatePlayerChrome();
  assert.equal(displayedLabel(), '音源未确认');
});
