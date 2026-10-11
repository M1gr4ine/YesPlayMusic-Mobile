'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { parseLrc, activeIndex, timestampMs } = require('../assets/lyrics.js');

test('parses one, two and three decimal timestamp digits', () => {
  assert.equal(timestampMs('01:02.3'), 62300);
  assert.equal(timestampMs('01:02.34'), 62340);
  assert.equal(timestampMs('01:02.345'), 62345);
  assert.equal(timestampMs('x'), null);
});

test('expands multiple timestamps and applies a signed offset', () => {
  const lines = parseLrc('[offset:-250]\n[00:01.00][00:02.00]同一句歌词\n[00:03.00]下一句');
  assert.deepEqual(lines, [
    { time: 750, text: '同一句歌词', translation: '' },
    { time: 1750, text: '同一句歌词', translation: '' },
    { time: 2750, text: '下一句', translation: '' }
  ]);
});

test('pairs translation lines without losing untimed metadata or blank lyrics', () => {
  const lines = parseLrc('[ar:歌手]\n[00:04.00]远处的灯\n[00:04.00]');
  assert.deepEqual(lines, [{ time: 4000, text: '远处的灯', translation: '' }]);
  const paired = parseLrc('[00:04.00]远处的灯', '[00:04.00]远方灯火');
  assert.deepEqual(paired, [{ time: 4000, text: '远处的灯', translation: '远方灯火' }]);
});

test('selects the last lyric at or before the current playback position', () => {
  const lines = parseLrc('[00:02.00]第一行\n[00:05.50]第二行\n[00:09.00]第三行');
  assert.equal(activeIndex(lines, 0), -1);
  assert.equal(activeIndex(lines, 2000), 0);
  assert.equal(activeIndex(lines, 8999), 1);
  assert.equal(activeIndex(lines, 9000), 2);
  assert.equal(activeIndex([], 9000), -1);
});
