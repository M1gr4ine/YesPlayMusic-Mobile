'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadAppFunctions } = require('./support/app-harness');

function createHarness(nativeSettings, systemDark) {
  const saved = [];
  const chromeCalls = [];
  const themeMeta = {
    content: '#f6f5f1',
    setAttribute(name, value) {
      if (name === 'content') this.content = value;
    }
  };
  const makeChoiceButton = (attr, value) => ({
    dataset: { [attr]: value },
    selected: false,
    ariaChecked: '',
    classList: {
      toggle(name, flag) {
        if (name === 'is-selected') this.selected = !!flag;
      }
    },
    setAttribute(name, val) {
      if (name === 'aria-checked') this.ariaChecked = val;
    }
  });
  // Bind toggle to button object
  const bindButton = (attr, value) => {
    const btn = makeChoiceButton(attr, value);
    btn.classList.toggle = (name, flag) => {
      if (name === 'is-selected') btn.selected = !!flag;
    };
    return btn;
  };
  const themeButtons = [
    bindButton('themeChoice', 'system'),
    bindButton('themeChoice', 'light'),
    bindButton('themeChoice', 'dark')
  ];
  const uiButtons = [
    bindButton('uiChoice', 'classic'),
    bindButton('uiChoice', 'material3')
  ];
  const docEl = { dataset: {} };
  const state = {
    settings: { sourcePreference: 'auto', theme: 'system', uiStyle: 'classic', statusLyrics: true, lyricsTranslation: true },
    searchProvider: 'netease',
    media: { track: null }
  };

  const app = loadAppFunctions(['readSettings', 'saveSettings', 'applyTheme', 'syncSystemChrome'], {
    state,
    document: { documentElement: docEl },
    $: selector => (selector === 'meta[name="theme-color"]' ? themeMeta : null),
    $$: selector => {
      if (selector.includes('data-theme-choice')) return themeButtons;
      if (selector.includes('data-ui-choice')) return uiButtons;
      return [];
    },
    parseNativeObject: (raw, fallback) => {
      try { return JSON.parse(raw); } catch (_) { return fallback; }
    },
    renderSettingsAccount: () => {},
    updateSourceLabels: () => {},
    showToast: () => {},
    window: {
      matchMedia: () => ({ matches: !!systemDark }),
      Native: {
        getSettings: () => JSON.stringify(nativeSettings),
        settings: json => saved.push(JSON.parse(json)),
        chrome: dark => chromeCalls.push(dark)
      }
    }
  });

  return { app, state, docEl, themeMeta, themeButtons, uiButtons, saved, chromeCalls };
}

test('readSettings defaults uiStyle to classic and ignores unknown values', () => {
  const ctx = createHarness({ theme: 'light', uiStyle: 'unknown-style' }, false);
  ctx.app.readSettings();
  assert.equal(ctx.state.settings.uiStyle, 'classic');
  assert.equal(ctx.docEl.dataset.ui, 'classic');
  assert.equal(ctx.docEl.dataset.theme, 'light');
  assert.equal(ctx.themeMeta.content, '#f6f5f1');
  assert.equal(ctx.uiButtons[0].selected, true);
  assert.equal(ctx.uiButtons[0].ariaChecked, 'true');
  assert.equal(ctx.uiButtons[1].selected, false);
  assert.equal(ctx.uiButtons[1].ariaChecked, 'false');
});

test('saveSettings switches cleanly between classic and material3 across light and dark modes', () => {
  const ctx = createHarness({ theme: 'dark', uiStyle: 'classic' }, false);
  ctx.app.readSettings();
  assert.equal(ctx.themeMeta.content, '#121211');
  assert.deepEqual(ctx.chromeCalls, [true]);

  ctx.app.saveSettings({ uiStyle: 'material3' });
  assert.equal(ctx.docEl.dataset.ui, 'material3');
  assert.equal(ctx.themeMeta.content, '#121318');
  assert.equal(ctx.uiButtons[1].selected, true);
  assert.equal(ctx.uiButtons[1].ariaChecked, 'true');
  assert.equal(ctx.saved.at(-1).uiStyle, 'material3');

  ctx.app.saveSettings({ theme: 'light' });
  assert.equal(ctx.docEl.dataset.theme, 'light');
  assert.equal(ctx.themeMeta.content, '#fbf8ff');
  assert.equal(ctx.chromeCalls.at(-1), false);

  ctx.app.saveSettings({ uiStyle: 'classic' });
  assert.equal(ctx.docEl.dataset.ui, 'classic');
  assert.equal(ctx.themeMeta.content, '#f6f5f1');
  assert.equal(ctx.saved.at(-1).uiStyle, 'classic');
});
