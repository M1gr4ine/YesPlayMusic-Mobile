(function () {
  'use strict';

  // Landscape lyrics standby page, modelled on the desktop fullscreen lyrics view.
  // Built for long screen-on sessions: every setter is a no-op unless something visible changed,
  // the backdrop is a tiny pre-blurred bitmap (no CSS filters), lyrics move with one compositor
  // transform per line change, and the clock and progress repaint at most once a minute / second.

  const BACKDROP_WIDTH = 48;
  const BACKDROP_HEIGHT = 27;

  function formatTime(milliseconds) {
    const seconds = Math.max(0, Math.floor((Number(milliseconds) || 0) / 1000));
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  }

  function create(root, options) {
    const find = id => root.querySelector(`#${id}`);
    const nodes = {
      backdrop: find('standby-backdrop'),
      cover: find('standby-cover'),
      title: find('standby-title'),
      artist: find('standby-artist'),
      elapsed: find('standby-elapsed'),
      duration: find('standby-duration'),
      fill: find('standby-bar-fill'),
      like: find('standby-like'),
      toggle: find('standby-toggle'),
      mode: find('standby-mode'),
      lock: find('standby-lock'),
      translation: find('standby-translation'),
      lyrics: find('standby-lyrics'),
      lines: find('standby-lines')
    };
    const clocks = Array.from(root.querySelectorAll('[data-standby-clock]'));
    const setIcon = options.setIcon;
    const backdropContext = nodes.backdrop.getContext('2d');
    nodes.backdrop.width = BACKDROP_WIDTH;
    nodes.backdrop.height = BACKDROP_HEIGHT;

    let active = false;
    let trackKey = null;
    let backdropUrl = '';
    let lines = [];
    let showTranslation = true;
    let lyricsLoading = false;
    let lyricsDirty = true;
    let lineNodes = [];
    let lineCenters = [];
    let viewportHeight = 0;
    let lineIndex = -1;
    let shownSecond = -1;
    let shownDuration = -1;
    let clockTimer = 0;
    let locked = null;
    const playback = {};

    function renderClock() {
      const now = new Date();
      const time = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
      clocks.forEach(clock => { clock.textContent = time; });
      clockTimer = setTimeout(renderClock, 60000 - (now.getTime() % 60000) + 20);
    }

    function stopClock() {
      clearTimeout(clockTimer);
      clockTimer = 0;
    }

    function drawBackdrop(url) {
      backdropUrl = url;
      nodes.backdrop.classList.remove('is-ready');
      if (!url) return;
      const image = new Image();
      image.decoding = 'async';
      image.referrerPolicy = 'no-referrer';
      image.onload = () => {
        if (backdropUrl !== url) return;
        // Drawn once per track: a tiny blurred bitmap the GPU stretches to full screen for free.
        backdropContext.filter = 'blur(2px) saturate(1.25)';
        backdropContext.drawImage(image, -4, -4, BACKDROP_WIDTH + 8, BACKDROP_HEIGHT + 8);
        nodes.backdrop.classList.add('is-ready');
      };
      image.src = url;
    }

    function renderLines() {
      lyricsDirty = false;
      lineNodes = [];
      lineCenters = [];
      const fragment = document.createDocumentFragment();
      lines.forEach((line, index) => {
        const node = document.createElement('p');
        node.className = index === lineIndex ? 'standby-line is-active' : 'standby-line';
        node.dataset.time = String(line.time);
        const text = document.createElement('span');
        text.textContent = line.text || '…';
        node.append(text);
        if (showTranslation && line.translation) {
          const translation = document.createElement('span');
          translation.className = 'standby-translation';
          translation.textContent = line.translation;
          node.append(translation);
        }
        lineNodes.push(node);
        fragment.append(node);
      });
      nodes.lines.replaceChildren(fragment);
      root.classList.toggle('no-lyrics', !lines.length);
      root.classList.toggle('is-loading', lyricsLoading);
      nodes.translation.hidden = !lines.some(line => line.translation);
      nodes.translation.setAttribute('aria-pressed', String(showTranslation));
      nodes.translation.setAttribute('aria-label', showTranslation ? '隐藏中文翻译' : '显示中文翻译');
    }

    // One layout read for every line; reused until the lyrics or the viewport change.
    function measure() {
      viewportHeight = nodes.lyrics.clientHeight;
      lineCenters = lineNodes.map(node => node.offsetTop + node.offsetHeight / 2);
    }

    function placeLines(animate) {
      if (!lineCenters.length) return;
      const center = lineCenters[Math.max(0, lineIndex)];
      nodes.lines.style.transitionDuration = animate ? '' : '0s';
      nodes.lines.style.transform = `translate3d(0,${Math.round(viewportHeight * 0.42 - center)}px,0)`;
    }

    function refreshLayout() {
      if (!active) return;
      if (lyricsDirty) renderLines();
      measure();
      placeLines(false);
    }

    function setActive(next) {
      next = !!next;
      if (next === active) return;
      active = next;
      root.hidden = !active;
      if (active) {
        shownSecond = -1;
        shownDuration = -1;
        renderClock();
        refreshLayout();
      } else {
        stopClock();
      }
    }

    function setTrack(track) {
      const key = track ? track.key : '';
      if (key === trackKey) return;
      trackKey = key;
      root.classList.toggle('is-idle', !track);
      nodes.title.textContent = track ? track.title : '';
      nodes.artist.textContent = track ? track.artist : '';
      nodes.cover.replaceChildren();
      if (track && track.cover) {
        const image = document.createElement('img');
        image.alt = '';
        image.decoding = 'async';
        image.referrerPolicy = 'no-referrer';
        image.src = track.cover;
        image.onerror = () => image.remove();
        nodes.cover.append(image);
      }
      drawBackdrop(track && track.backdrop || '');
    }

    function setLyrics(next, settings) {
      const translation = settings.translation !== false;
      const loading = !!settings.loading;
      if (next === lines && translation === showTranslation && loading === lyricsLoading) return;
      if (next !== lines) lineIndex = -1;
      lines = next;
      showTranslation = translation;
      lyricsLoading = loading;
      lyricsDirty = true;
      refreshLayout();
    }

    function setLine(index) {
      if (index === lineIndex) return;
      if (lineNodes[lineIndex]) lineNodes[lineIndex].classList.remove('is-active');
      lineIndex = index;
      if (lineNodes[index]) lineNodes[index].classList.add('is-active');
      if (active && !lyricsDirty) placeLines(true);
    }

    function setProgress(position, duration) {
      if (!active) return;
      const second = Math.floor(position / 1000);
      const total = Math.floor(duration / 1000);
      if (second === shownSecond && total === shownDuration) return;
      if (total !== shownDuration) nodes.duration.textContent = formatTime(duration);
      shownSecond = second;
      shownDuration = total;
      nodes.elapsed.textContent = formatTime(position);
      nodes.fill.style.transform = `scaleX(${duration > 0 ? Math.min(1, position / duration) : 0})`;
    }

    function setPlayback(next) {
      if (next.playing !== playback.playing) {
        setIcon(nodes.toggle, next.playing ? 'pause' : 'play');
        nodes.toggle.setAttribute('aria-label', next.playing ? '暂停' : '播放');
      }
      if (next.liked !== playback.liked || next.likeDisabled !== playback.likeDisabled) {
        nodes.like.classList.toggle('is-liked', !!next.liked);
        nodes.like.setAttribute('aria-pressed', String(!!next.liked));
        nodes.like.disabled = !!next.likeDisabled;
      }
      if (next.mode !== playback.mode) {
        setIcon(nodes.mode, next.mode === 'shuffle' ? 'shuffle' : next.mode === 'order' ? 'order' : 'repeat');
        nodes.mode.classList.toggle('is-selected', next.mode !== 'order');
        nodes.mode.classList.toggle('is-repeat-one', next.mode === 'one');
        nodes.mode.setAttribute('aria-label', next.modeLabel || '播放模式');
      }
      Object.assign(playback, next);
    }

    function setLocked(next) {
      next = !!next;
      if (next === locked) return;
      locked = next;
      root.classList.toggle('is-locked', locked);
      setIcon(nodes.lock, locked ? 'lock' : 'unlock');
      nodes.lock.setAttribute('aria-pressed', String(locked));
      nodes.lock.setAttribute('aria-label', locked ? '解除全屏锁定' : '锁定全屏');
    }

    root.addEventListener('click', event => {
      const line = event.target.closest('.standby-line');
      if (line) { options.seek(Number(line.dataset.time) || 0); return; }
      const button = event.target.closest('button[data-standby-action]');
      if (button && !button.disabled) options.action(button.dataset.standbyAction);
    });
    window.addEventListener('resize', refreshLayout);

    return {
      get active() { return active; },
      setActive,
      setTrack,
      setLyrics,
      setLine,
      setProgress,
      setPlayback,
      setLocked
    };
  }

  window.YPMStandby = Object.freeze({ create });
})();
