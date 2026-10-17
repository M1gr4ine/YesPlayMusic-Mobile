'use strict';

// Loads named top-level functions from the production assets/app.js into an isolated VM context,
// so tests exercise the shipped code without a browser or the WebView bridge.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const appLines = fs.readFileSync(path.join(__dirname, '../../../assets/app.js'), 'utf8').split(/\r?\n/);

// app.js is one IIFE: every top-level function starts at a two-space indent and closes with "  }".
function extractFunction(name) {
  const start = appLines.findIndex(line => new RegExp(`^  (async )?function ${name}\\(`).test(line));
  if (start < 0) throw new Error(`assets/app.js has no top-level function ${name}`);
  const first = appLines[start];
  if (first.split('{').length === first.split('}').length) return first;
  const end = appLines.indexOf('  }', start);
  return appLines.slice(start, end + 1).join('\n');
}

// The landscape standby page is closed unless a test supplies its own hooks.
const STANDBY_CLOSED = { standbyActive: () => false, syncStandby() {} };

function loadAppFunctions(names, globals) {
  const context = vm.createContext(Object.assign({}, STANDBY_CLOSED, globals));
  vm.runInContext(names.map(extractFunction).join('\n'), context, { filename: 'assets/app.js' });
  return context;
}

function fakeElement() {
  const classes = new Set();
  const attributes = {};
  return {
    hidden: false,
    disabled: false,
    title: '',
    textContent: '',
    scrollHeight: 58,
    offsetHeight: 58,
    dataset: {},
    attributes,
    style: { height: '', setProperty() {} },
    classList: {
      add(...names) { names.forEach(name => classes.add(name)); },
      remove(...names) { names.forEach(name => classes.delete(name)); },
      contains(name) { return classes.has(name); },
      toggle(name, force) {
        const on = force === undefined ? !classes.has(name) : !!force;
        if (on) classes.add(name); else classes.delete(name);
        return on;
      }
    },
    setAttribute(name, value) { attributes[name] = String(value); },
    removeAttribute(name) { delete attributes[name]; },
    replaceChildren() {},
    append() {},
    addEventListener() {}
  };
}

// Returns a `$` lookup that lazily creates one fake element per selector.
function fakeDocument() {
  const elements = new Map();
  return selector => {
    if (!elements.has(selector)) elements.set(selector, fakeElement());
    return elements.get(selector);
  };
}

module.exports = { loadAppFunctions, fakeDocument };
