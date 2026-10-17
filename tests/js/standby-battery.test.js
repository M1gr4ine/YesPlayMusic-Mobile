'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// These tests exercise the shipped standby renderer; only the DOM and native bridge are doubles.
function createStandby() {
  const nodes = new Map();
  const listeners = new Map();
  const makeNode = () => {
    const classes = new Set();
    return {
      hidden: false, textContent: '', attributes: {}, style: {}, clientHeight: 300,
      classList: {
        add(value) { classes.add(value); }, remove(value) { classes.delete(value); },
        toggle(value, force) { if (force) classes.add(value); else classes.delete(value); },
        contains(value) { return classes.has(value); }
      },
      setAttribute(key, value) { this.attributes[key] = String(value); },
      append() {}, replaceChildren() {}, addEventListener() {},
      getContext() { return {}; }
    };
  };
  const root = makeNode();
  root.querySelector = selector => {
    if (!nodes.has(selector)) nodes.set(selector, makeNode());
    return nodes.get(selector);
  };
  root.querySelectorAll = () => [root.querySelector('[data-standby-clock]')];
  let nativeBattery = { level: 37, charging: false };
  const window = {
    Native: { getBattery: () => JSON.stringify(nativeBattery) },
    addEventListener(type, listener) { listeners.set(type, listener); }
  };
  const context = vm.createContext({
    window, document: { createDocumentFragment: makeNode, createElement: makeNode },
    setTimeout: () => 1, clearTimeout() {}, Image: function () {}, Date
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../..', 'assets/standby.js'), 'utf8'), context);
  const standby = window.YPMStandby.create(root, { setIcon() {}, action() {}, seek() {} });
  return { standby, root, node: id => root.querySelector('#' + id), listeners,
    native(value) { nativeBattery = value; } };
}

test('entering landscape reads actual battery and native events refresh percentage and charging state', () => {
  const page = createStandby();
  page.standby.setActive(true);
  assert.equal(page.node('standby-battery-percent').textContent, '37%');
  assert.equal(page.node('standby-battery-fill').attributes.width, '7.4');
  assert.equal(page.node('standby-battery').classList.contains('is-charging'), false);
  page.listeners.get('native-battery')({ detail: { level: 86, charging: true } });
  assert.equal(page.node('standby-battery-percent').textContent, '86%');
  assert.equal(page.node('standby-battery').classList.contains('is-charging'), true);
  assert.match(page.node('standby-battery').attributes['aria-label'], /86%/);
});

test('zero is a valid battery reading, while invalid or missing readings remain unknown', () => {
  const page = createStandby();
  page.native({ level: 0, charging: false });
  page.standby.setActive(true);
  assert.equal(page.node('standby-battery-percent').textContent, '0%');
  for (const level of [null, undefined, -1, 101, 'unknown']) {
    page.listeners.get('native-battery')({ detail: { level, charging: false } });
    assert.equal(page.node('standby-battery-percent').textContent, '--%');
  }
});

test('returning to landscape and resuming foreground refresh stale battery', () => {
  const page = createStandby();
  page.standby.setActive(true);
  page.standby.setActive(false);
  page.native({ level: 64, charging: true });
  page.listeners.get('native-battery')({ detail: { level: 61, charging: false } });
  assert.equal(page.node('standby-battery-percent').textContent, '37%');
  page.standby.setActive(true);
  assert.equal(page.node('standby-battery-percent').textContent, '64%');
  page.native({ level: 69, charging: true });
  page.listeners.get('native-resume')();
  assert.equal(page.node('standby-battery-percent').textContent, '69%');
});
