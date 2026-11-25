'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { parseLrc, activeIndex, timestampMs } = require('../../assets/lyrics.js');

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

test('pairs QQ translations whose timestamps drift and skips "//" placeholders', () => {
  const lines = parseLrc('[00:05.10]詞\n[00:22.48]夜\n[00:30.00]雪', '[00:05.10]//\n[00:22.47]夜晚\n[00:30.03]下雪');
  assert.deepEqual(lines, [
    { time: 5100, text: '詞', translation: '' },
    { time: 22480, text: '夜', translation: '夜晚' },
    { time: 30000, text: '雪', translation: '下雪' }
  ]);
});

test('filters known timed metadata while retaining sections and text after tags', () => {
  const primary = '[ti:Like Him]\n[ar:Artist]\n[00:00.00][ti:Like Him]\n'
    + '[00:01.00][Intro: spoken]\n[00:02.00][Verse 1]\n[00:03.00][Chorus]\n'
    + '[00:04.00][note: keep this]\n[00:05.00][AR:Artist][al:Album]Real lyric\n'
    + '[00:06.00][length:3:45][au:Writer][offset:5][re:App][ve:1][by:Editor]\n[00:07.00][tool:editor]\n'
    + '[00:08.00][00:09.00][Intro: repeat]';
  const translated = '[00:01.00][Intro: 翻译]\n[00:05.00][by:Editor]译文\n[00:06.00][ve:1]';
  assert.deepEqual(parseLrc(primary, translated), [
    { time: 1000, text: '[Intro: spoken]', translation: '[Intro: 翻译]' },
    { time: 2000, text: '[Verse 1]', translation: '' },
    { time: 3000, text: '[Chorus]', translation: '' },
    { time: 4000, text: '[note: keep this]', translation: '' },
    { time: 5000, text: 'Real lyric', translation: '译文' },
    { time: 8000, text: '[Intro: repeat]', translation: '' },
    { time: 9000, text: '[Intro: repeat]', translation: '' }
  ]);
});

test('selects the last lyric at or before the current playback position', () => {
  const lines = parseLrc('[00:02.00]第一行\n[00:05.50]第二行\n[00:09.00]第三行');
  assert.equal(activeIndex(lines, 0), -1);
  assert.equal(activeIndex(lines, 2000), 0);
  assert.equal(activeIndex(lines, 8999), 1);
  assert.equal(activeIndex(lines, 9000), 2);
  assert.equal(activeIndex([], 9000), -1);
});
