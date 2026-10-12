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
  if (start < 0) return '';
  const open = appSource.indexOf('{', start);
  let depth = 0;
  let quote = '';
  let lineComment = false;
  let blockComment = false;
  for (let i = open; i < appSource.length; i++) {
    const current = appSource[i];
    const next = appSource[i + 1];
    if (lineComment) { if (current === '\n') lineComment = false; continue; }
    if (blockComment) { if (current === '*' && next === '/') { blockComment = false; i++; } continue; }
    if (quote) { if (current === '\\') { i++; continue; } if (current === quote) quote = ''; continue; }
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
  'setMessage',
  'updatePlayerChrome'
].map(extractFunction).filter(Boolean).join('\n');

function createFakeClock() {
  let now = 0;
  let nextId = 1;
  const timers = new Map();
  return {
    setTimeout(callback, delay = 0) {
      const id = nextId++;
      timers.set(id, { id, due: now + Number(delay || 0), callback });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
    tick(milliseconds) {
      const target = now + milliseconds;
      while (true) {
        let next = null;
        for (const timer of timers.values()) if (timer.due <= target && (!next || timer.due < next.due)) next = timer;
        if (!next) break;
        now = next.due;
        timers.delete(next.id);
        next.callback();
      }
      now = target;
    },
    count() { return timers.size; }
  };
}

const elements = new Map();
function fakeElement() {
  const classes = new Set();
  return {
    hidden: true,
    textContent: '',
    scrollHeight: 58,
    get offsetHeight() { return 58; },
    dataset: {},
    style: { height: '', setProperty() {} },
    classList: {
      add(name) { classes.add(name); },
      remove(...names) { names.forEach(name => classes.delete(name)); },
      contains(name) { return classes.has(name); },
      toggle(name, force) {
        if (force === undefined) force = !classes.has(name);
        if (force) classes.add(name); else classes.delete(name);
        return force;
      }
    },
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

let clock;
const context = vm.createContext({
  state: null,
  lastTrackSignature: '',
  globalMessageDismissTimer: 0,
  globalMessageFoldTimer: 0,
  activeGlobalErrorKey: '',
  $,
  window: { matchMedia: () => ({ matches: false }), Native: { settings() {} } },
  updateMiniSubtitle() {},
  invalidateFavoriteStatus() {},
  updateArt() {},
  loadFavoriteStatus() {},
  setToggleIcon() {},
  updateMiniFavoriteButton() {},
  updateProgress() {},
  renderQueueIfChanged() {},
  updateLyricsActive() {},
  setMessage: undefined,
  applyTheme() {},
  renderSettingsAccount() {},
  setTimeout(callback, delay) { return clock.setTimeout(callback, delay); },
  clearTimeout(id) { clock.clearTimeout(id); }
});
vm.runInContext(productionFunctions, context, { filename: 'assets/app.js production functions' });

function reset({ trackId = 'track-1', provider = 'netease', error = true, reducedMotion = false } = {}) {
  clock = createFakeClock();
  context.globalMessageDismissTimer = 0;
  context.globalMessageFoldTimer = 0;
  context.activeGlobalErrorKey = '';
  context.window.matchMedia = () => ({ matches: reducedMotion });
  context.state = {
    settings: { sourcePreference: 'qq' },
    favorite: { key: '' },
    media: {
      track: { id: trackId, provider, title: 'Track', artist: 'Artist', album: 'Album', artwork: '', duration: 1000 },
      source: '', playing: false, buffering: false, error: error ? true : null,
      position: 0, duration: 1000, updatedAt: 0, shuffle: false, repeat: 'off'
    }
  };
  context.lastTrackSignature = '';
  context.setTimeout = (callback, delay) => clock.setTimeout(callback, delay);
  context.clearTimeout = id => clock.clearTimeout(id);
  for (const node of elements.values()) {
    node.hidden = true;
    node.textContent = '';
    node.dataset = {};
    node.style.height = '';
    node.classList.remove('is-error', 'is-collapsing');
  }
}
function banner() { return $('#global-message'); }

// Drive the real production update path used by the one-second media-state poll.
function poll() { context.updatePlayerChrome(); }

test('error remains visible for four seconds, then folds and releases its space', () => {
  reset();
  poll();
  assert.equal(banner().hidden, false);
  clock.tick(3999);
  assert.equal(banner().classList.contains('is-collapsing'), false);
  clock.tick(1);
  assert.equal(banner().classList.contains('is-collapsing'), true);
  assert.equal(banner().hidden, false);
  clock.tick(189);
  assert.equal(banner().hidden, false);
  clock.tick(1);
  assert.equal(banner().hidden, true);
});

test('repeated polls do not reset the timer or reopen the same dismissed error', () => {
  reset();
  poll();
  clock.tick(3000);
  poll();
  clock.tick(1000);
  assert.equal(banner().classList.contains('is-collapsing'), true);
  clock.tick(190);
  assert.equal(banner().hidden, true);
  poll();
  assert.equal(banner().hidden, true);
  assert.equal(clock.count(), 0);
});

test('a new track with the same playback error shows a fresh banner', () => {
  reset();
  poll();
  clock.tick(4190);
  assert.equal(banner().hidden, true);
  context.state.media.track.id = 'track-2';
  poll();
  assert.equal(banner().hidden, false);
  assert.equal(banner().classList.contains('is-collapsing'), false);
});

test('successful recovery hides the error immediately and cancels both timers', () => {
  reset();
  poll();
  clock.tick(2000);
  context.state.media.error = null;
  context.state.media.playing = true;
  poll();
  assert.equal(banner().hidden, true);
  assert.equal(banner().textContent, '');
  assert.equal(clock.count(), 0);
  clock.tick(5000);
  assert.equal(banner().hidden, true);
});

test('a changed error event cancels the previous dismissal deadline', () => {
  reset();
  poll();
  clock.tick(3000);
  context.state.media.track.id = 'track-2';
  poll();
  clock.tick(1000);
  assert.equal(banner().hidden, false);
  assert.equal(banner().classList.contains('is-collapsing'), false);
  clock.tick(3000);
  assert.equal(banner().classList.contains('is-collapsing'), true);
});

test('reduced-motion hides at the timeout without a fold transition', () => {
  reset({ reducedMotion: true });
  poll();
  clock.tick(4000);
  assert.equal(banner().hidden, true);
  assert.equal(banner().classList.contains('is-collapsing'), false);
  assert.equal(clock.count(), 0);
});

test('non-error global messages remain persistent', () => {
  reset({ error: false });
  context.setMessage('Browser preview message', false);
  clock.tick(10000);
  assert.equal(banner().hidden, false);
  assert.equal(banner().textContent, 'Browser preview message');
  assert.equal(clock.count(), 0);
});
