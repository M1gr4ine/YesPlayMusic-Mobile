(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.YPMLyrics = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  function timestampMs(value) {
    const match = String(value).match(/^(\d+):(\d{1,2})(?:[.:](\d{1,3}))?$/);
    if (!match) return null;
    const fraction = match[3] || '0';
    const millis = fraction.length === 1 ? Number(fraction) * 100
      : fraction.length === 2 ? Number(fraction) * 10 : Number(fraction);
    return (Number(match[1]) * 60 + Number(match[2])) * 1000 + millis;
  }

  function readLrc(text) {
    const entries = [];
    let offset = 0;
    const source = String(text || '').replace(/\r/g, '');
    const offsetMatch = source.match(/^\[offset:([+-]?\d+)\]/im);
    if (offsetMatch) offset = Number(offsetMatch[1]);

    for (const rawLine of source.split('\n')) {
      const line = rawLine.trim();
      if (!line) continue;
      const stamps = [];
      const stampPattern = /\[(\d+:\d{1,2}(?:[.:]\d{1,3})?)\]/g;
      let match;
      while ((match = stampPattern.exec(line)) !== null) {
        const time = timestampMs(match[1]);
        if (time !== null) stamps.push(time + offset);
      }
      if (!stamps.length) continue;
      const lyric = line.replace(stampPattern, '').trim();
      if (!lyric || /^\[[a-z]+:/i.test(lyric)) continue;
      for (const time of stamps) entries.push({ time: Math.max(0, time), text: lyric });
    }

    return entries.sort((a, b) => a.time - b.time);
  }

  const TRANSLATION_TOLERANCE_MS = 300;

  function nearestTime(sortedTimes, target, tolerance) {
    let low = 0;
    let high = sortedTimes.length - 1;
    let best = null;
    while (low <= high) {
      const middle = (low + high) >> 1;
      const time = sortedTimes[middle];
      if (best === null || Math.abs(time - target) < Math.abs(best - target)) best = time;
      if (time < target) low = middle + 1;
      else if (time > target) high = middle - 1;
      else return time;
    }
    return best !== null && Math.abs(best - target) <= tolerance ? best : null;
  }

  function parseLrc(lyrics, translatedLyrics) {
    const primary = readLrc(lyrics);
    const translated = readLrc(translatedLyrics);
    const byTime = new Map();
    for (const item of primary) {
      const line = byTime.get(item.time) || { time: item.time, text: '', translation: '' };
      line.text = line.text ? `${line.text}\n${item.text}` : item.text;
      byTime.set(item.time, line);
    }
    const times = Array.from(byTime.keys()).sort((a, b) => a - b);
    for (const item of translated) {
      // QQ marks untranslated lines with "//".
      if (/^\/+$/.test(item.text)) continue;
      // Translation timestamps can drift a few milliseconds from the original line.
      const time = nearestTime(times, item.time, TRANSLATION_TOLERANCE_MS);
      const key = time === null ? item.time : time;
      const line = byTime.get(key) || { time: key, text: '', translation: '' };
      line.translation = line.translation ? `${line.translation}\n${item.text}` : item.text;
      byTime.set(key, line);
    }
    return Array.from(byTime.values()).sort((a, b) => a.time - b.time);
  }

  function activeIndex(lines, positionMs) {
    if (!Array.isArray(lines) || lines.length === 0) return -1;
    const position = Math.max(0, Number(positionMs) || 0);
    let low = 0;
    let high = lines.length - 1;
    let found = -1;
    while (low <= high) {
      const middle = (low + high) >> 1;
      if (lines[middle].time <= position) {
        found = middle;
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }
    return found;
  }

  return { parseLrc, activeIndex, timestampMs };
});
