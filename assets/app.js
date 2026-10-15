(function () {
  'use strict';

  const $ = (selector, root) => (root || document).querySelector(selector);
  const $$ = (selector, root) => Array.from((root || document).querySelectorAll(selector));
  const icons = {
    listen: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8"><path d="M4.5 10.6 12 4l7.5 6.6v8.1a1.8 1.8 0 0 1-1.8 1.8H6.3a1.8 1.8 0 0 1-1.8-1.8z"/><path d="M9.2 20.5v-6.2h5.6v6.2"/></svg>',
    browse: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8"><circle cx="12" cy="12" r="8.5"/><path d="m15.6 8.4-2.3 5-4.9 2.2 2.2-4.9z"/></svg>',
    library: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8"><path d="M5 4.5h13.5A1.5 1.5 0 0 1 20 6v13.5H6.5A1.5 1.5 0 0 1 5 18z"/><path d="M5 18.5a2 2 0 0 1 2-2h13M8 8h8M8 11.5h8"/></svg>',
    search: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8"><circle cx="10.7" cy="10.7" r="6.7"/><path d="m15.7 15.7 4.1 4.1"/></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M8 5.5a1 1 0 0 1 1.5-.86l9.1 5.25a1 1 0 0 1 0 1.73l-9.1 5.25A1 1 0 0 1 8 16z"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><path d="M7 5.5A1.5 1.5 0 0 1 8.5 4h1A1.5 1.5 0 0 1 11 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-1A1.5 1.5 0 0 1 7 18.5zm6 0A1.5 1.5 0 0 1 14.5 4h1A1.5 1.5 0 0 1 17 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-1a1.5 1.5 0 0 1-1.5-1.5z"/></svg>',
    next: '<svg viewBox="0 0 24 24"><path d="M5.5 5.5a1 1 0 0 1 1.5-.86l9.1 5.25a1 1 0 0 1 0 1.73L7 16.87a1 1 0 0 1-1.5-.86zM18 5h1.7v14H18z"/></svg>',
    previous: '<svg viewBox="0 0 24 24"><path d="M18.5 5.5a1 1 0 0 0-1.5-.86l-9.1 5.25a1 1 0 0 0 0 1.73l9.1 5.25a1 1 0 0 0 1.5-.86zM4.3 5H6v14H4.3z"/></svg>',
    shuffle: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8"><path d="M16 4h4v4M4 7h2.7c4.5 0 5.3 10 10.6 10H20m-4 3 4-3-4-3M4 17h2.7c1.7 0 2.8-1.6 3.8-3.6M14 7c.9-1.2 1.9-2 3.3-2H20m-4 3 4-3-4-3"/></svg>',
    repeat: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8"><path d="M17 2.5 20.5 6 17 9.5M4 10V8a2 2 0 0 1 2-2h14.5M7 21.5 3.5 18 7 14.5M20 14v2a2 2 0 0 1-2 2H3.5"/></svg>',
    more: '<svg viewBox="0 0 24 24" fill="none" stroke-width="2"><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></svg>',
    close: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8"><path d="m6 6 12 12M18 6 6 18"/></svg>',
    back: '<svg viewBox="0 0 24 24" fill="none" stroke-width="2"><path d="m15 5-7 7 7 7"/></svg>',
    heart: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8"><path d="M20.8 8.8c0 5.2-8.8 10.3-8.8 10.3S3.2 14 3.2 8.8a4.4 4.4 0 0 1 8.8-.3 4.4 4.4 0 0 1 8.8.3Z"/></svg>',
    music: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.7"><path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/></svg>',
    queue: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8"><path d="M4 6.5h12M4 11.5h12M4 16.5h7"/><path d="m16 15 4 3-4 3z"/></svg>',
    listAdd: '<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8"><path d="M4 6.5h11M4 11.5h11M4 16.5h7"/><path d="M17.5 13v7M14 16.5h7"/></svg>'
  };

  const state = {
    tab: 'listen',
    settings: { sourcePreference: 'auto', theme: 'system', statusLyrics: true },
    account: null,
    qqStatus: null,
    home: { playlists: [], songs: [] },
    homeLoaded: false,
    homeLoading: false,
    homeError: false,
    library: [],
    libraryLoaded: false,
    libraryLoading: false,
    libraryError: false,
    libraryRequestGeneration: 0,
    libraryOwnerKey: '',
    recentHistory: [],
    recentHistoryTotal: 0,
    recentHistoryLoaded: false,
    recentHistoryLoading: false,
    recentHistoryError: false,
    recentHistoryRequestGeneration: 0,
    playlist: null,
    playlistId: null,
    playlistLoading: false,
    playlistError: false,
    playlistRequestGeneration: 0,
    playlistOriginScrollTop: 0,
    tabScrollTop: { listen: 0, library: 0, search: 0 },
    searchQuery: '',
    searchProvider: 'netease',
    searchType: 'songs',
    searchResults: null,
    searchLoading: false,
    searchError: false,
    media: { track: null, queue: [], index: -1, playing: false, position: 0, duration: 0, source: '', shuffle: false, repeat: 'off', error: null },
    fullPlayer: false,
    playerExpanded: false,
    queueSheetOpen: false,
    playerView: 'lyrics',
    lyrics: [],
    lyricsLoading: false,
    lyricsIndex: -2,
    lyricsGeneration: 0,
    toastTimer: 0,
    searchTimer: 0,
    captchaTimer: 0,
    captchaRemaining: 0,
    searchRequestGeneration: 0,
    authGeneration: 0,
    authMutation: '',
    favoriteGeneration: 0,
    favorite: { key: '', provider: '', available: false, loggedIn: false, liked: null, loading: false, mutating: false },
    polling: false
  };

  const pending = new Map();
  let requestSequence = 0;
  let lastQueueSignature = '';
  let lastQueueIndex = null;
  let lastTrackSignature = '';
  let lastLyricsKey = '';
  const springAnimations = new WeakMap();
  const tabOrder = ['listen', 'library', 'search'];
  let tabTransitionGeneration = 0;
  let tabTransitionInFlight = false;
  let queueSheetGeneration = 0;
  let playerGeneration = 0;
  let accountMotion = null;
  let playerPanelGeneration = 0;
  let playerPanelGesture = null;
  let playerPanelClickUntil = 0;
  let playerPanelClickTarget = null;
  let lyricsManualUntil = 0;
  let lyricsFollowTimer = 0;
  let playerScrollSettleTimer = 0;
  let pendingMiniHandoff = null;
  let miniHandoffGeneration = 0;
  let tabGesture = null;
  let trackActions = null;
  let trackActionsGeneration = 0;
  let trackActionsSheetGeneration = 0;
  let pendingPlayerModes = null;
  let seekDragging = false;
  // Native IME visibility from WindowInsets; null until the Android shell reports it.
  let nativeKeyboardVisible = null;
  let mediaQueueCache = { version: undefined, queue: [] };
  let queueSignatureSource = null;
  let queueSignatureValue = '';
  const QUEUE_ROW_HEIGHT = 61;
  const QUEUE_WINDOW_OVERSCAN = 8;
  const MINI_TRACK_DRAG_FACTOR = 0.22;
  const MINI_TRACK_TRAVEL_LIMIT = 28;
  const MINI_TRACK_HANDOFF_DISTANCE = 22;
  const miniOpacityAnimations = new WeakMap();

  function motionPosition(element) {
    const transform = getComputedStyle(element).transform;
    if (!transform || transform === 'none') return { x: 0, y: 0, vx: 0, vy: 0 };
    try {
      const matrix = new DOMMatrixReadOnly(transform);
      return { x: matrix.m41, y: matrix.m42, vx: 0, vy: 0 };
    } catch (_) {
      const values = transform.match(/matrix(?:3d)?\(([^)]+)\)/);
      if (!values) return { x: 0, y: 0, vx: 0, vy: 0 };
      const parts = values[1].split(',').map(Number);
      return transform.startsWith('matrix3d')
        ? { x: parts[12] || 0, y: parts[13] || 0, vx: 0, vy: 0 }
        : { x: parts[4] || 0, y: parts[5] || 0, vx: 0, vy: 0 };
    }
  }

  function springSample(animation, now) {
    const seconds = Math.max(0, (now - animation.startedAt) / 1000);
    const decay = Math.exp(-animation.omega * seconds);
    const ex = animation.startX - animation.targetX;
    const ey = animation.startY - animation.targetY;
    const cx = animation.startVx + animation.omega * ex;
    const cy = animation.startVy + animation.omega * ey;
    const xOffset = ex + cx * seconds;
    const yOffset = ey + cy * seconds;
    return {
      x: animation.targetX + xOffset * decay,
      y: animation.targetY + yOffset * decay,
      vx: (cx - animation.omega * xOffset) * decay,
      vy: (cy - animation.omega * yOffset) * decay
    };
  }

  function setMotionPosition(element, x, y) {
    element.style.transform = `translate3d(${x}px,${y}px,0)`;
  }

  function cancelMiniOpacity(element, restoreOpacity) {
    if (!element) return 1;
    const animation = miniOpacityAnimations.get(element);
    const computedOpacity = Number.parseFloat(getComputedStyle(element).opacity);
    const opacity = Number.isFinite(computedOpacity) ? computedOpacity : 1;
    if (animation) {
      miniOpacityAnimations.delete(element);
      animation.cancel();
    }
    element.style.opacity = String(restoreOpacity === undefined ? opacity : restoreOpacity);
    return restoreOpacity === undefined ? opacity : restoreOpacity;
  }

  function fadeMiniOpacity(element, targetOpacity, generation, onRest) {
    if (!element) return;
    const fromOpacity = cancelMiniOpacity(element);
    const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduceMotion || typeof element.animate !== 'function') {
      element.style.opacity = String(targetOpacity);
      if (onRest) onRest();
      return;
    }
    const animation = element.animate(
      [{ opacity: fromOpacity }, { opacity: targetOpacity }],
      { duration: 175, easing: 'cubic-bezier(.22,.75,.25,1)', fill: 'forwards' }
    );
    miniOpacityAnimations.set(element, animation);
    animation.onfinish = () => {
      if (miniOpacityAnimations.get(element) !== animation) return;
      miniOpacityAnimations.delete(element);
      element.style.opacity = String(targetOpacity);
      animation.cancel();
      if (generation === miniHandoffGeneration && onRest) onRest();
    };
    animation.oncancel = () => {
      if (miniOpacityAnimations.get(element) === animation) miniOpacityAnimations.delete(element);
    };
  }

  function cancelSpring(element) {
    const animation = springAnimations.get(element);
    if (!animation) return motionPosition(element);
    if (animation.kind === 'waapi') {
      const rendered = motionPosition(element);
      const currentTime = animation.player.currentTime;
      const elapsed = currentTime !== null && Number.isFinite(Number(currentTime))
        ? Number(currentTime)
        : 0;
      const sampled = springSample(animation, animation.startedAt + elapsed);
      const position = { x: rendered.x, y: rendered.y, vx: sampled.vx, vy: sampled.vy };
      springAnimations.delete(element);
      animation.player.onfinish = null;
      animation.player.oncancel = null;
      animation.player.cancel();
      setMotionPosition(element, position.x, position.y);
      return position;
    }
    cancelAnimationFrame(animation.frame);
    const position = springSample(animation, performance.now());
    springAnimations.delete(element);
    setMotionPosition(element, position.x, position.y);
    return position;
  }

  function rafSpringTo(element, targetX, targetY, velocityX, velocityY, onRest) {
    const current = cancelSpring(element);
    const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduceMotion) {
      setMotionPosition(element, targetX, targetY);
      if (onRest) onRest();
      return;
    }
    const animation = {
      kind: 'js',
      targetX, targetY, startX: current.x, startY: current.y,
      startVx: Number(velocityX) || 0, startVy: Number(velocityY) || 0,
      startedAt: performance.now(), omega: 22, frame: 0, onRest
    };
    const tick = now => {
      if (springAnimations.get(element) !== animation) return;
      const position = springSample(animation, now);
      setMotionPosition(element, position.x, position.y);
      if (Math.abs(position.x - targetX) < 0.45 && Math.abs(position.y - targetY) < 0.45 && Math.abs(position.vx) < 7 && Math.abs(position.vy) < 7) {
        springAnimations.delete(element);
        setMotionPosition(element, targetX, targetY);
        if (animation.onRest) animation.onRest();
        return;
      }
      animation.frame = requestAnimationFrame(tick);
    };
    springAnimations.set(element, animation);
    animation.frame = requestAnimationFrame(tick);
  }

  // Springs are sampled into a Web Animation so the compositor keeps them smooth even while
  // the main thread is busy; rafSpringTo is the fallback when animate() is unavailable.
  function springTo(element, targetX, targetY, velocityX, velocityY, onRest) {
    const current = cancelSpring(element);
    const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduceMotion) {
      setMotionPosition(element, targetX, targetY);
      if (onRest) onRest();
      return;
    }
    const startedAt = performance.now();
    const animation = {
      kind: 'waapi', targetX, targetY, startX: current.x, startY: current.y,
      startVx: Number(velocityX) || 0, startVy: Number(velocityY) || 0,
      startedAt, omega: 22, onRest
    };
    const frameMs = 1000 / 120;
    const maxFrames = 72;
    const keyframes = [{ transform: `translate3d(${current.x}px,${current.y}px,0)` }];
    let duration = maxFrames * frameMs;
    let settled = false;
    for (let frame = 1; frame <= maxFrames; frame += 1) {
      const time = frame * frameMs;
      const position = springSample(animation, startedAt + time);
      const atRest = Math.abs(position.x - targetX) < 0.45 && Math.abs(position.y - targetY) < 0.45
        && Math.abs(position.vx) < 7 && Math.abs(position.vy) < 7;
      keyframes.push({ transform: atRest
        ? `translate3d(${targetX}px,${targetY}px,0)`
        : `translate3d(${position.x}px,${position.y}px,0)` });
      if (atRest) {
        duration = time;
        settled = true;
        break;
      }
    }
    if (!settled) keyframes[keyframes.length - 1] = { transform: `translate3d(${targetX}px,${targetY}px,0)` };
    if (typeof element.animate !== 'function') {
      rafSpringTo(element, targetX, targetY, velocityX, velocityY, onRest);
      return;
    }
    let player;
    try {
      player = element.animate(keyframes, { duration, easing: 'linear', fill: 'forwards' });
    } catch (_) {
      rafSpringTo(element, targetX, targetY, velocityX, velocityY, onRest);
      return;
    }
    animation.player = player;
    springAnimations.set(element, animation);
    player.onfinish = () => {
      if (springAnimations.get(element) !== animation) return;
      springAnimations.delete(element);
      setMotionPosition(element, targetX, targetY);
      player.cancel();
      if (animation.onRest) animation.onRest();
    };
    player.oncancel = () => {
      if (springAnimations.get(element) === animation) springAnimations.delete(element);
    };
  }

  function projectedTravel(velocity, deceleration) {
    const rate = deceleration === undefined ? 0.994 : deceleration;
    return (velocity / 1000) * rate / (1 - rate);
  }

  function tabSpringVelocity(velocity, startX, targetX) {
    const delta = targetX - startX;
    if (!delta) return 0;
    const towardTarget = Math.max(0, (Number(velocity) || 0) * Math.sign(delta));
    return Math.sign(delta) * Math.min(towardTarget, 1400, Math.abs(delta) * 22 * 0.78);
  }

  function sampleGesture(gesture, x, y) {
    const now = performance.now();
    gesture.samples.push({ x, y, t: now });
    while (gesture.samples.length > 5 || (gesture.samples.length > 2 && now - gesture.samples[0].t > 120)) gesture.samples.shift();
  }

  function gestureVelocity(samples, axis) {
    if (!samples || samples.length < 2) return 0;
    const first = samples[0]; const last = samples[samples.length - 1];
    if (!first || !last || last.t <= first.t) return 0;
    return (last[axis] - first[axis]) / (last.t - first.t) * 1000;
  }

  function parseJson(value, fallback) {
    if (value && typeof value === 'object') return value;
    if (typeof value !== 'string') return fallback;
    try { return JSON.parse(value); } catch (_) { return fallback; }
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function setIcon(node, name) {
    if (node && icons[name]) node.innerHTML = icons[name];
  }

  function setToggleIcon(node, name) {
    if (!node || node.dataset.icon === name) return;
    setIcon(node, name);
    node.dataset.icon = name;
  }

  function showToast(message) {
    const toast = $('#toast');
    if (!toast) return;
    toast.textContent = message;
    toast.classList.add('is-visible');
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => toast.classList.remove('is-visible'), 2300);
  }

  let globalMessageDismissTimer = 0;
  let globalMessageFoldTimer = 0;
  let activeGlobalErrorKey = '';

  function clearErrorMessage() {
    if (!activeGlobalErrorKey) return;
    clearTimeout(globalMessageDismissTimer);
    clearTimeout(globalMessageFoldTimer);
    globalMessageDismissTimer = 0;
    globalMessageFoldTimer = 0;
    activeGlobalErrorKey = '';
    const node = $('#global-message');
    if (!node) return;
    node.hidden = true;
    node.textContent = '';
    node.style.height = '';
    node.classList.remove('is-error', 'is-collapsing');
  }

  function setMessage(message, isError, errorKey) {
    const node = $('#global-message');
    if (!node) return;
    if (!message) {
      clearTimeout(globalMessageDismissTimer);
      clearTimeout(globalMessageFoldTimer);
      globalMessageDismissTimer = 0;
      globalMessageFoldTimer = 0;
      activeGlobalErrorKey = '';
      node.hidden = true;
      node.textContent = '';
      node.style.height = '';
      node.classList.remove('is-error', 'is-collapsing');
      return;
    }
    if (isError) {
      const nextKey = String(errorKey || message);
      if (nextKey === activeGlobalErrorKey) return;
      clearTimeout(globalMessageDismissTimer);
      clearTimeout(globalMessageFoldTimer);
      activeGlobalErrorKey = nextKey;
    } else {
      clearTimeout(globalMessageDismissTimer);
      clearTimeout(globalMessageFoldTimer);
      globalMessageDismissTimer = 0;
      globalMessageFoldTimer = 0;
      activeGlobalErrorKey = '';
    }
    node.textContent = message;
    node.style.height = '';
    node.classList.remove('is-collapsing');
    node.hidden = false;
    node.classList.toggle('is-error', !!isError);
    if (!isError) return;
    const scheduledKey = activeGlobalErrorKey;
    globalMessageDismissTimer = setTimeout(() => {
      if (activeGlobalErrorKey !== scheduledKey) return;
      globalMessageDismissTimer = 0;
      const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      if (reduceMotion) {
        node.hidden = true;
        node.style.height = '';
        node.classList.remove('is-collapsing');
        return;
      }
      node.style.height = String(node.scrollHeight) + 'px';
      void node.offsetHeight;
      node.classList.add('is-collapsing');
      node.style.height = '0px';
      globalMessageFoldTimer = setTimeout(() => {
        if (activeGlobalErrorKey !== scheduledKey) return;
        globalMessageFoldTimer = 0;
        node.hidden = true;
        node.style.height = '';
        node.classList.remove('is-collapsing');
      }, 190);
    }, 4000);
  }

  function nativeReady() {
    return !!(window.Native && typeof window.Native.request === 'function' && typeof window.Native.command === 'function');
  }

  function safeFailure() {
    return new Error('服务暂时不可用，请检查网络后重试。');
  }

  function request(method, args) {
    if (!nativeReady()) return Promise.reject(new Error('请在已安装的 YesPlayMusic 手机应用中使用此功能。'));
    const requestId = `ui-${Date.now()}-${++requestSequence}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(requestId);
        reject(safeFailure());
      }, 25000);
      pending.set(requestId, { resolve, reject, timer });
      try {
        window.Native.request(requestId, String(method), JSON.stringify(args || {}));
      } catch (_) {
        clearTimeout(timer);
        pending.delete(requestId);
        reject(safeFailure());
      }
    });
  }

  window.YPM = window.YPM || {};
  window.YPM.resolve = function (requestId, resultJsonString, errorOrNull) {
    const task = pending.get(String(requestId));
    if (!task) return;
    pending.delete(String(requestId));
    clearTimeout(task.timer);
    if (errorOrNull) {
      task.reject(safeFailure());
      return;
    }
    const result = parseJson(resultJsonString, null);
    if (result === null) {
      task.reject(safeFailure());
      return;
    }
    task.resolve(result);
  };

  function parseNativeObject(value, fallback) {
    const result = parseJson(value, fallback);
    return result && typeof result === 'object' ? result : fallback;
  }

  function safeHttpsUrl(value) {
    if (typeof value !== 'string' || !value) return '';
    try {
      const url = new URL(value);
      if (url.username || url.password) return '';
      const host = url.hostname.toLowerCase();
      const imageHost = host === 'music.126.net' || host.endsWith('.music.126.net') || host === 'nos.netease.com' || host.endsWith('.nos.netease.com') ||
        host === 'music.163.com' || host.endsWith('.music.163.com') || host === 'qq.com' || host.endsWith('.qq.com') ||
        host === 'qpic.cn' || host.endsWith('.qpic.cn') || host === 'gtimg.cn' || host.endsWith('.gtimg.cn') ||
        host === 'imgcache.qq.com' || host.endsWith('.imgcache.qq.com');
      if (!imageHost) return '';
      if (url.protocol === 'http:') url.protocol = 'https:';
      if (url.protocol !== 'https:') return '';
      return url.href;
    } catch (_) { return ''; }
  }

  function artistNames(track) {
    const artists = track.artists || track.ar || track.singers || track.singer || track.artist;
    if (Array.isArray(artists)) {
      return artists.map(item => typeof item === 'string' ? item : (item && (item.name || item.title || item.singerName)) || '').filter(Boolean).join(' / ');
    }
    if (artists && typeof artists === 'object') return artists.name || artists.singerName || '';
    return typeof artists === 'string' ? artists : '';
  }

  function artistAliasNames(track) {
    const names = [];
    const seen = new Set();
    const add = value => {
      if (typeof value !== 'string' || names.length >= 32) return;
      const name = value.trim().slice(0, 128);
      if (!name || /[\u0000-\u001f]/.test(name)) return;
      const key = name.normalize('NFKC').toLowerCase();
      if (!seen.has(key)) { seen.add(key); names.push(name); }
    };
    if (Array.isArray(track.artistAliases)) track.artistAliases.forEach(add);
    const artists = track.artists || track.ar || track.singers || track.singer || track.artist;
    for (const artist of Array.isArray(artists) ? artists : [artists]) {
      if (!artist || typeof artist !== 'object') continue;
      for (const key of ['alias', 'alia', 'tns', 'trans']) {
        const value = artist[key];
        if (Array.isArray(value)) value.forEach(add);
        else add(value);
      }
    }
    return names;
  }

  function normalizeTrack(raw, provider) {
    if (!raw || typeof raw !== 'object') return null;
    if (raw.song && typeof raw.song === 'object') raw = raw.song;
    const id = raw.id ?? raw.songid ?? raw.songId ?? raw.songmid ?? raw.mid;
    const title = raw.title || raw.name || raw.songname || raw.songName;
    if (id === undefined || id === null || !title) return null;
    const normalizedId = String(id);
    const idProvider = /^qq:/i.test(normalizedId) ? 'qq' : /^\d+$/.test(normalizedId) ? 'netease' : '';
    const albumObject = raw.album && typeof raw.album === 'object' ? raw.album : raw.al;
    const albumName = typeof raw.album === 'string' ? raw.album : (albumObject && (albumObject.name || albumObject.title || albumObject.albumName)) || raw.albumName || '';
    const artwork = raw.artwork || raw.cover || raw.picUrl || raw.albumPic || raw.albumPicUrl || (albumObject && (albumObject.picUrl || albumObject.cover || albumObject.coverUrl)) || '';
    let duration = Number(raw.duration ?? raw.dt ?? raw.intervalMs ?? 0);
    if (duration > 0 && duration < 10000) duration *= 1000;
    const result = {
      id: normalizedId, title: String(title), artist: artistNames(raw) || '未知艺术家', album: String(albumName || ''),
      artistAliases: artistAliasNames(raw),
      artwork: safeHttpsUrl(artwork), duration: Number.isFinite(duration) ? duration : 0,
      provider: idProvider || raw.provider || provider || 'netease'
    };
    const qqMid = raw.qqMid || raw.songmid || raw.mid || raw.qq_mid;
    if (qqMid) result.qqMid = String(qqMid);
    const mediaMid = raw.media_mid || raw.mediaMid;
    if (mediaMid) result.mediaMid = String(mediaMid);
    return result;
  }

  function extractSongs(response, provider) {
    const candidates = [response && response.songs, response && response.result && response.result.songs,
      response && response.data && response.data.songs, response && response.result && response.result.data];
    let items = candidates.find(Array.isArray) || [];
    if (items.length && items[0] && items[0].songInfo) items = items.map(item => item.songInfo);
    return items.map(item => normalizeTrack(item, provider)).filter(Boolean);
  }

  function extractPlaylists(response) {
    const items = [response && response.playlists, response && response.result && response.result.playlists,
      response && response.result && response.result.playlist, response && response.playlist].find(Array.isArray) || [];
    return items.map(item => {
      if (!item || typeof item !== 'object' || item.id == null) return null;
      return { id: String(item.id), name: String(item.name || item.title || '未命名歌单'), artwork: safeHttpsUrl(item.coverImgUrl || item.picUrl || item.cover || item.artwork),
        count: Number.isFinite(Number(item.trackCount ?? item.tracks?.length)) ? Number(item.trackCount ?? item.tracks?.length) : null,
        creator: item.creator && String(item.creator.nickname || item.creator.name || '') };
    }).filter(Boolean);
  }

  function extractPlaylistDetail(response) {
    const playlist = response && response.playlist ? response.playlist : response;
    if (!playlist || typeof playlist !== 'object') return null;
    return {
      id: String(playlist.id || ''), name: String(playlist.name || playlist.title || '歌单'),
      artwork: safeHttpsUrl(playlist.coverImgUrl || playlist.picUrl || playlist.cover || ''),
      creator: playlist.creator && String(playlist.creator.nickname || playlist.creator.name || ''),
      description: String(playlist.description || ''),
      total: Number(playlist.totalTrackCount || playlist.trackCount || playlist.tracks?.length || 0),
      truncated: !!playlist.tracksTruncated,
      loadError: String(playlist.tracksLoadError || ''),
      tracks: (Array.isArray(playlist.tracks) ? playlist.tracks : []).map(item => normalizeTrack(item, item.provider || 'netease')).filter(Boolean)
    };
  }

  function displayName() {
    const profile = state.account;
    if (!profile) return '';
    return String(profile.nickname || profile.name || profile.userName || profile.account?.username || '');
  }

  function accountIdentity(account) {
    if (!account || typeof account !== 'object') return '';
    const id = account.userId || account.user_id || account.id || account.account && (account.account.id || account.account.userId) || account.nickname || account.name;
    return id ? String(id) : 'signed-in';
  }

  function isNeteaseLoggedIn(response) {
    if (!response || typeof response !== 'object') return false;
    if (typeof response.loggedIn === 'boolean') return response.loggedIn;
    const profile = response.profile;
    const account = response.account;
    return !!((profile && (profile.userId || profile.user_id || profile.nickname)) || (account && (account.id || account.userId)) || response.loggedIn === true);
  }

  function launchLogin(provider) {
    if (!window.Native || typeof window.Native.login !== 'function') {
      showToast('请在 YesPlayMusic 手机应用中登录');
      return;
    }
    try {
      window.Native.login(provider);
      showToast(provider === 'qq' ? '请在打开的 QQ 音乐授权页完成登录' : '请在打开的网易云授权页完成登录');
    } catch (_) { showToast('暂时无法打开登录页面'); }
  }

  function apiRequestSucceeded(response) {
    if (!response || typeof response !== 'object' || response.code === undefined || response.code === null) return true;
    const code = Number(response.code);
    return code === 0 || code === 200;
  }

  function loginFailure(response, fallback) {
    const code = Number(response && response.code);
    const message = String(response && (response.message || response.msg || response.data?.message || response.data?.blockText || response.data?.msg) || '').replace(/[\r\n]+/g,' ').slice(0,100);
    return new Error(`${fallback}${Number.isFinite(code) ? `（${code}）` : ''}${message ? `：${message}` : ''}`);
  }

  function setLoginFeedback(message) {
    const feedback = $('#netease-login-feedback');
    if (feedback) { feedback.textContent = message || ''; feedback.hidden = !message; }
  }

  function updateCaptchaButton() {
    const button = $('#send-captcha');
    if (!button) return;
    if (state.captchaRemaining > 0) {
      button.disabled = true;
      button.textContent = `${state.captchaRemaining} 秒后重发`;
    } else {
      button.disabled = false;
      button.textContent = '发送验证码';
      clearInterval(state.captchaTimer);
      state.captchaTimer = 0;
    }
  }

  async function sendNeteaseCaptcha() {
    const phone = $('#netease-phone').value.trim();
    const button = $('#send-captcha');
    if (!/^1\d{10}$/.test(phone)) { showToast('请输入 11 位中国大陆手机号'); return; }
    if (state.captchaRemaining > 0 || button.disabled) return;
    setLoginFeedback('');
    button.disabled = true; button.textContent = '正在发送…';
    try {
      const response = await request('sendCaptcha',{phone,countrycode:'86'});
      if (!apiRequestSucceeded(response)) throw loginFailure(response,'验证码发送失败');
      state.captchaRemaining = 60;
      updateCaptchaButton();
      state.captchaTimer = setInterval(() => { state.captchaRemaining = Math.max(0,state.captchaRemaining-1); updateCaptchaButton(); },1000);
      showToast('验证码已发送，请查看短信');
    } catch (error) {
      button.disabled = false; button.textContent = '发送验证码';
      setLoginFeedback(error.message || '验证码发送失败，请稍后再试');
    }
  }

  async function submitNeteaseSmsLogin(event) {
    event.preventDefault();
    const phone = $('#netease-phone').value.trim();
    const captcha = $('#netease-captcha').value.trim();
    const submit = $('#netease-sms-submit');
    if (!/^1\d{10}$/.test(phone)) { showToast('请输入 11 位中国大陆手机号'); $('#netease-phone').focus(); return; }
    if (!captcha) { showToast('请输入短信验证码'); $('#netease-captcha').focus(); return; }
    setLoginFeedback('');
    submit.disabled = true; submit.textContent = '正在登录…';
    try {
      const response = await request('loginCaptcha',{phone,captcha,countrycode:'86'});
      if (!apiRequestSucceeded(response)) throw loginFailure(response,'网易云登录失败');
      $('#netease-captcha').value = '';
      state.homeLoaded = false; state.libraryLoaded = false;
      await refreshAuth();
      if (state.account) {
        $('#netease-login-panel').hidden = true;
        $('#netease-phone').value = '';
        showToast('网易云音乐已登录');
      } else showToast('登录已提交，请稍后刷新账号状态');
    } catch (error) {
      $('#netease-captcha').value = '';
      showToast(error.message || '网易云登录失败，请稍后重试');
    } finally {
      submit.disabled = false; submit.textContent = '验证码登录';
    }
  }

  async function refreshAuth() {
    if (!nativeReady() || state.authMutation) return;
    const generation = ++state.authGeneration;
    invalidateFavoriteStatus(true);
    const previousAccountKey = accountIdentity(state.account);
    const previouslyLoggedIn = !!state.account;
    const results = await Promise.allSettled([request('loginStatus', {}), request('qqStatus', {})]);
    if (generation !== state.authGeneration || state.authMutation) return;
    const netease = results[0].status === 'fulfilled' ? results[0].value : null;
    const qq = results[1].status === 'fulfilled' ? results[1].value : null;
    const nextAccount = isNeteaseLoggedIn(netease) ? (netease.profile || netease.account || netease) : null;
    const nextAccountKey = accountIdentity(nextAccount);
    if (nextAccountKey !== previousAccountKey) {
      state.libraryRequestGeneration += 1;
      state.libraryLoading = false;
      state.library = [];
      state.libraryLoaded = false;
      state.libraryError = false;
      state.libraryOwnerKey = nextAccountKey;
    }
    state.account = nextAccount;
    state.qqStatus = qq && typeof qq === 'object' ? qq : null;
    if (state.media.track) loadFavoriteStatus(state.media.track);
    renderSettingsAccount();
    if (!previouslyLoggedIn && state.account) { state.homeLoaded = false; state.libraryLoaded = false; }
    if (state.tab === 'library') {
      renderTab();
      if (state.account && !state.libraryLoaded && !state.libraryLoading) loadLibrary();
    }
    if (state.tab === 'listen' && !state.homeLoaded && !state.homeLoading) loadHome();
  }

  function readSettings() {
    if (!window.Native || typeof window.Native.getSettings !== 'function') return;
    const settings = parseNativeObject(window.Native.getSettings(), {});
    if (['auto', 'netease', 'qq'].includes(settings.sourcePreference)) state.settings.sourcePreference = settings.sourcePreference;
    if (['system', 'light', 'dark'].includes(settings.theme)) state.settings.theme = settings.theme;
    if (typeof settings.statusLyrics === 'boolean') state.settings.statusLyrics = settings.statusLyrics;
    state.searchProvider = state.settings.sourcePreference === 'qq' ? 'qq' : 'netease';
    applyTheme();
  }

  function saveSettings(patch) {
    state.settings = Object.assign({}, state.settings, patch);
    if (patch.sourcePreference) state.searchProvider = patch.sourcePreference === 'qq' ? 'qq' : 'netease';
    applyTheme();
    renderSettingsAccount();
    if (patch.sourcePreference && state.media.track) updateSourceLabels();
    if (window.Native && typeof window.Native.settings === 'function') {
      try { window.Native.settings(JSON.stringify(state.settings)); } catch (_) { showToast('设置未能保存'); }
    }
  }

  function applyTheme() {
    document.documentElement.dataset.theme = state.settings.theme || 'system';
    $$('.theme-picker button').forEach(button => button.classList.toggle('is-selected', button.dataset.themeChoice === state.settings.theme));
    syncSystemChrome();
  }

  function syncSystemChrome() {
    if (!window.Native || typeof window.Native.chrome !== 'function') return;
    const dark = state.settings.theme === 'dark' || (state.settings.theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    try { window.Native.chrome(!!dark); } catch (_) {}
  }

  function openAccount(showNeteaseLogin) {
    accountMotion.open();
    if (showNeteaseLogin) $('#netease-login-panel').hidden = false;
    renderSettingsAccount();
    refreshAuth();
    refreshCacheSize();
  }

  function formatBytes(bytes) {
    const value = Math.max(0, Number(bytes) || 0);
    if (value < 1024 * 1024) return `${Math.round(value / 1024)} KB`;
    return `${(value / 1048576).toFixed(value < 10 * 1048576 ? 1 : 0)} MB`;
  }

  function showCacheSize(response) {
    const label = $('#cache-size');
    if (!label) return;
    label.textContent = response && Number.isFinite(Number(response.bytes))
      ? `封面图片与网络缓存 · ${formatBytes(response.bytes)}` : '暂时无法读取缓存大小';
  }

  function refreshCacheSize() {
    if (!nativeReady()) return;
    request('cacheInfo', {}).then(showCacheSize, () => showCacheSize(null));
  }

  async function clearCache() {
    const button = $('#clear-cache');
    if (!nativeReady() || button.disabled) return;
    button.disabled = true;
    button.textContent = '正在清除…';
    try {
      showCacheSize(await request('clearCache', {}));
      showToast('缓存已清除');
    } catch (_) {
      showToast('暂时无法清除缓存');
    } finally {
      button.disabled = false;
      button.textContent = '清除缓存';
    }
  }

  function closeAccount() {
    accountMotion.close();
  }

  function renderSettingsAccount() {
    const profile = state.account;
    const avatar = $('#account-avatar');
    const miniMark = $('#avatar-mark');
    $('#account-name').textContent = profile ? displayName() || '网易云音乐已登录' : '尚未登录网易云音乐';
    $('#account-description').textContent = profile ? '网易云音乐账号已连接' : '登录后同步你的歌单与收藏';
    $('#netease-login').textContent = profile ? '已登录' : '登录方式';
    $('#netease-login').disabled = !!profile || state.authMutation === 'netease';
    if (profile) $('#netease-login-panel').hidden = true;
    $('#netease-logout').hidden = !profile;
    $('#netease-logout').disabled = state.authMutation === 'netease';
    if (state.authMutation === 'netease') $('#netease-logout').textContent = '正在退出…';
    else $('#netease-logout').textContent = '退出网易云账号';
    const avatarUrl = profile && safeHttpsUrl(profile.avatarUrl || profile.avatar);
    avatar.replaceChildren();
    if (avatarUrl) {
      const image = document.createElement('img');
      image.alt = '';
      image.referrerPolicy = 'no-referrer';
      image.src = avatarUrl;
      image.onerror = () => setIcon(avatar, 'music');
      avatar.append(image);
    } else setIcon(avatar, 'music');
    if (miniMark) {
      miniMark.replaceChildren();
      if (profile && avatarUrl) {
        const image = document.createElement('img');
        image.alt = '';
        image.referrerPolicy = 'no-referrer';
        image.src = avatarUrl;
        image.onerror = () => {
          if (!miniMark.contains(image)) return;
          const initial = displayName().trim().slice(0, 1);
          if (initial) miniMark.textContent = initial;
          else setIcon(miniMark, 'music');
        };
        miniMark.append(image);
      } else if (profile) {
        const initial = displayName().trim().slice(0, 1);
        if (initial) miniMark.textContent = initial;
        else setIcon(miniMark, 'music');
      } else setIcon(miniMark, 'music');
    }
    $$('.source-option').forEach(button => button.setAttribute('aria-checked', String(button.dataset.source === state.settings.sourcePreference)));
    $('#status-lyrics').setAttribute('aria-checked', String(state.settings.statusLyrics !== false));
    const qqLoggedIn = !!(state.qqStatus && state.qqStatus.loggedIn);
    $('#qq-account-status').textContent = state.qqStatus ? (qqLoggedIn ? '已连接 QQ 音乐' : '未登录 QQ 音乐') : '暂时无法读取 QQ 登录状态';
    $('#qq-login').textContent = qqLoggedIn ? '已登录' : 'QQ 登录';
    $('#qq-login').disabled = qqLoggedIn || state.authMutation === 'qq';
    const qqLogout = $('#qq-logout');
    qqLogout.hidden = !qqLoggedIn;
    qqLogout.disabled = state.authMutation === 'qq';
    qqLogout.textContent = state.authMutation === 'qq' ? '正在退出…' : '退出 QQ 音乐账号';
    $('#qq-source-detail').textContent = qqLoggedIn ? '已连接账号，可搜索并尝试 QQ 音源' : '支持 QQ 搜索；登录后使用账号可播放曲目';
    applyTheme();
  }

  async function logoutProvider(provider, method) {
    if (state.authMutation) return;
    state.authGeneration += 1;
    state.authMutation = provider;
    invalidateFavoriteStatus(false);
    renderSettingsAccount();
    try {
      const response = await request(method, {});
      if (!apiRequestSucceeded(response)) throw safeFailure();
      if (provider === 'netease') {
        state.account = null;
        state.libraryRequestGeneration += 1;
        state.libraryOwnerKey = '';
        state.library = [];
        state.libraryLoaded = false;
        state.libraryLoading = false;
        state.libraryError = false;
        state.homeLoaded = false;
        showToast('已退出网易云音乐账号');
      } else {
        state.qqStatus = Object.assign({}, state.qqStatus || {}, { code: 200, loggedIn: false, canRefresh: false });
        showToast('已退出 QQ 音乐账号');
      }
    } catch (_) {
      showToast('暂时无法退出账号');
    } finally {
      state.authMutation = '';
      renderSettingsAccount();
      if (provider === 'netease') renderTab();
      await refreshAuth();
    }
  }

  // Request covers near their display size: originals are often over 1000px, and decoding
  // dozens of them for 50px thumbnails makes lists stutter and inflates memory.
  function sizedArtwork(url, cssPixels) {
    const safe = safeHttpsUrl(url);
    if (!safe || !cssPixels) return safe;
    const pixels = Math.ceil(cssPixels * Math.min(3, Math.max(1, window.devicePixelRatio || 1)));
    try {
      const parsed = new URL(safe);
      if (parsed.hostname.endsWith('.music.126.net')) {
        const size = Math.min(1024, Math.ceil(pixels / 20) * 20);
        parsed.searchParams.set('param', `${size}y${size}`);
        return parsed.href;
      }
      if (parsed.hostname === 'y.gtimg.cn') {
        const size = [150, 300, 500, 800].find(value => value >= pixels) || 800;
        return parsed.href.replace(/(T00\dR)\d+x\d+(M000)/, `$1${size}x${size}$2`);
      }
    } catch (_) {}
    return safe;
  }

  function makeCover(url, className, size) {
    const cover = element('span', `cover ${className || ''}`.trim());
    const fallback = element('span', 'cover-mark');
    setIcon(fallback, 'music');
    cover.append(fallback);
    const safe = sizedArtwork(url, size || 64);
    if (safe) {
      const img = document.createElement('img');
      img.alt = '';
      img.loading = 'lazy';
      img.decoding = 'async';
      img.referrerPolicy = 'no-referrer';
      img.src = safe;
      img.onerror = () => img.remove();
      cover.append(img);
    }
    return cover;
  }

  function loadingState(label) {
    const node = element('div', 'loading-state');
    node.append(element('span', 'spinner'), element('span', '', label || '正在加载…'));
    return node;
  }

  function emptyState(title, body, actionLabel, action) {
    const box = element('section', 'empty-state');
    const icon = element('span', 'empty-icon');
    setIcon(icon, 'music');
    box.append(icon, element('h3', '', title), element('p', '', body));
    if (actionLabel && action) {
      const button = element('button', 'primary-action', actionLabel);
      button.type = 'button';
      button.addEventListener('click', action);
      box.append(button);
    }
    return box;
  }

  function section(title, subtitle, actionLabel, action) {
    const wrap = element('section', 'section');
    const heading = element('div', 'section-heading');
    const left = element('div');
    left.append(element('h2', '', title));
    if (subtitle) left.append(element('p', '', subtitle));
    heading.append(left);
    if (actionLabel && action) {
      const button = element('button', 'section-action', actionLabel);
      button.type = 'button';
      button.addEventListener('click', action);
      heading.append(button);
    }
    wrap.append(heading);
    return { wrap, heading };
  }

  function renderPlaylistCard(playlist, compact) {
    const card = element('button', compact ? 'quick-card' : 'playlist-card');
    card.type = 'button';
    card.setAttribute('aria-label', `打开歌单 ${playlist.name}`);
    card.append(makeCover(playlist.artwork, '', compact ? 68 : 176));
    if (compact) card.append(element('span', 'quick-label', playlist.name));
    else {
      card.append(element('strong', '', playlist.name));
      if (playlist.count) card.append(element('small', '', `${playlist.count.toLocaleString()} 首歌曲`));
    }
    card.addEventListener('click', () => openPlaylist(playlist.id));
    return card;
  }

  function renderTrackRow(track, tracks, index, options) {
    const row = element('article', 'song-row');
    const main = element('button', 'song-row-main');
    main.type = 'button';
    main.setAttribute('aria-label', `播放 ${track.title}，${track.artist}`);
    main.append(makeCover(track.artwork, ''));
    const copy = element('span', 'song-copy');
    const title = element('strong', '', track.title);
    if (track.provider === 'qq') title.append(element('span', 'track-tag', 'QQ'));
    copy.append(title, element('span', '', [track.artist, track.album].filter(Boolean).join(' · ')));
    main.append(copy);
    main.addEventListener('click', () => {
      if (options && typeof options.onSelect === 'function') options.onSelect(track, index);
      else playTracks(tracks, index);
    });
    row.append(main);
    if (options && options.duration) row.append(element('span', 'song-duration', formatTime(track.duration)));
    if (options && options.actions) {
      const more = element('button', 'icon-button song-more');
      more.type = 'button';
      setIcon(more, 'more');
      more.setAttribute('aria-label', `更多操作：${track.title}`);
      more.addEventListener('click', () => openTrackActions(track));
      row.append(more);
    }
    return row;
  }

  function renderSongList(tracks, options) {
    const list = element('div', 'song-list');
    tracks.forEach((track, index) => list.append(renderTrackRow(track, tracks, index, options)));
    return list;
  }

  function renderFeature(target, subtitle, click) {
    const artworkUrl = sizedArtwork(target.artwork, 110);
    const card = element('button', `feature-card${artworkUrl ? ' has-art' : ''}`);
    card.type = 'button';
    card.setAttribute('aria-label', target.actionLabel || target.title);
    const art = element('span', 'feature-art');
    setIcon(art, 'music');
    if (artworkUrl) {
      const image = document.createElement('img');
      image.className = 'feature-cover'; image.alt = ''; image.decoding = 'async'; image.src = artworkUrl; image.referrerPolicy = 'no-referrer';
      image.onerror = () => image.remove(); art.append(image);
    }
    const copy = element('span', 'feature-copy');
    copy.append(element('span', 'eyebrow', target.eyebrow || 'TODAY’S MUSIC'), element('h2', '', target.title), element('p', '', subtitle || ''));
    const play = element('span', 'feature-play'); setIcon(play, 'play');
    card.append(art, copy, play); card.addEventListener('click', click);
    return card;
  }

  function renderListen() {
    const root = $('#tab-content');
    const greeting = element('p', 'greeting', state.account ? `欢迎回来，${displayName()}` : '今天想听点什么？');
    root.append(greeting);
    if (state.homeLoading) root.append(loadingState('正在为你整理音乐…'));
    if (state.homeError) root.append(emptyState('暂时无法加载', '检查网络连接后，可以重新获取推荐内容。', '重新加载', () => loadHome(true)));

    const firstPlaylist = state.home.playlists[0];
    const firstSong = state.home.songs[0];
    if (firstPlaylist || firstSong) {
      const featured = firstPlaylist
        ? { title: firstPlaylist.name, artwork: firstPlaylist.artwork, eyebrow: '为你挑选', actionLabel: '打开歌单' }
        : { title: firstSong.title, artwork: firstSong.artwork, eyebrow: '每日推荐', actionLabel: '播放推荐歌曲' };
      const action = firstPlaylist ? () => openPlaylist(firstPlaylist.id) : () => playTracks(state.home.songs, 0);
      root.append(renderFeature(featured, firstPlaylist ? `${firstPlaylist.count ? firstPlaylist.count + ' 首歌曲' : '精选歌单'}${firstPlaylist.creator ? ` · ${firstPlaylist.creator}` : ''}` : firstSong.artist, action));
    } else if (!state.homeLoading && !state.homeError && !state.account) {
      const login = element('section', 'login-hero');
      login.append(element('h2', '', '连接你的音乐世界'), element('p', '', '登录网易云音乐后，可以在这里查看你的歌单、每日推荐和收藏。'));
      const actions = element('div', 'login-actions');
      const button = element('button', 'primary-action', '网易云音乐登录'); button.type = 'button'; button.addEventListener('click', () => openAccount(true));
      actions.append(button); login.append(actions); root.append(login);
    } else if (!state.homeLoading && !state.homeError && state.account) {
      root.append(emptyState('还没有推荐内容', '稍后再来看看，或去搜索找一首歌。', '去搜索', () => navigateTab('search')));
    }

    if (state.home.playlists.length) {
      const grid = section('精选歌单', '', null, null);
      const rail = element('div', 'card-rail');
      state.home.playlists.slice(0, 12).forEach(item => rail.append(renderPlaylistCard(item, false)));
      grid.wrap.append(rail); root.append(grid.wrap);
      if (state.home.playlists.length >= 2) {
        const quick = section('快速打开', '', null, null);
        const quickGrid = element('div', 'quick-grid');
        state.home.playlists.slice(0, 4).forEach(item => quickGrid.append(renderPlaylistCard(item, true)));
        quick.wrap.append(quickGrid); root.append(quick.wrap);
      }
    }
    if (state.home.songs.length) {
      const songs = section('每日推薦', '来自网易云音乐的真实推荐', null, null);
      songs.wrap.append(renderSongList(state.home.songs.slice(0, 12), { duration: true, actions: true })); root.append(songs.wrap);
    }
  }

  function renderBrowse() {
    const root = $('#tab-content');
    root.append(element('p', 'greeting', '发现歌单与歌曲'));
    if (state.homeLoading) root.append(loadingState('正在加载精选内容…'));
    if (state.homeError) root.append(emptyState('内容暂时不可用', '检查网络后重新获取音乐平台内容。', '重新加载', () => loadHome(true)));
    if (state.home.playlists.length) {
      const list = section('精选歌单', '音乐平台实时返回', null, null);
      const rail = element('div', 'card-rail'); state.home.playlists.forEach(item => rail.append(renderPlaylistCard(item, false)));
      list.wrap.append(rail); root.append(list.wrap);
    }
    if (state.home.songs.length) {
      const songs = section('推荐歌曲', '点击一首开始播放', null, null);
      songs.wrap.append(renderSongList(state.home.songs.slice(0, 20), { duration: true, actions: true })); root.append(songs.wrap);
    }
    if (!state.homeLoading && !state.homeError && !state.home.playlists.length && !state.home.songs.length) {
      root.append(emptyState(state.account ? '暂时没有可浏览的内容' : '登录后发现更多音乐', state.account ? '当前接口没有返回精选歌单或歌曲。' : '网易云音乐账号登录后，展示你的推荐内容。', state.account ? '重新加载' : '网易云登录', state.account ? () => loadHome(true) : () => openAccount(true)));
    }
  }

  function renderLibrary() {
    const root = $('#tab-content');
    const recent = section('最近播放', '', '已播歌曲', () => openRecentHistory());
    if (state.recentHistoryLoading && !state.recentHistory.length) {
      recent.wrap.append(loadingState('正在读取本机播放记录…'));
    } else if (state.recentHistoryError && !state.recentHistory.length) {
      recent.wrap.append(emptyState('播放记录暂时无法读取', '稍后再试一次。', '重新加载', () => loadRecentHistory(true, 20)));
    } else if (state.recentHistory.length) {
      const rail = element('div', 'recent-track-rail');
      state.recentHistory.slice(0, 12).forEach((track, index) => {
        const card = element('button', 'recent-track-card');
        card.type = 'button';
        card.setAttribute('aria-label', `播放 ${track.title}，${track.artist}`);
        card.append(makeCover(track.artwork, 'recent-track-cover', 112));
        const copy = element('span', 'recent-track-copy');
        copy.append(element('strong', '', track.title), element('span', '', track.artist || track.album || ''));
        card.append(copy);
        card.addEventListener('click', () => playTracks(state.recentHistory, index));
        rail.append(card);
      });
      recent.wrap.append(rail);
    } else if (state.recentHistoryLoaded) {
      recent.wrap.append(emptyState('还没有播放记录', '在 YesPlayMusic 中播放歌曲后，会显示在这里。', null, null));
    }
    root.append(recent.wrap);

    const playlists = section('我的歌单', '', null, null);
    if (!state.account) {
      const login = element('section', 'login-hero library-login');
      login.append(element('h2', '', '登录同步你的歌单'), element('p', '', '登录网易云音乐后，个人歌单会显示在这里。'));
      const actions = element('div', 'login-actions');
      const button = element('button', 'primary-action', '网易云音乐登录'); button.type = 'button'; button.addEventListener('click', () => openAccount(true));
      const qq = element('button', 'secondary-button', 'QQ 音乐登录'); qq.type = 'button'; qq.addEventListener('click', () => launchLogin('qq'));
      actions.append(button, qq); login.append(actions); playlists.wrap.append(login);
    } else if (state.libraryLoading && !state.library.length) {
      playlists.wrap.append(loadingState('正在同步你的歌单…'));
    } else if (state.libraryError && !state.library.length) {
      playlists.wrap.append(emptyState('歌单暂时无法读取', '检查网络连接后再试一次。', '重新加载', () => loadLibrary(true)));
    } else if (state.library.length) {
      const list = element('div', 'my-playlist-list');
      state.library.forEach(item => {
        const row = element('button', 'my-playlist-row');
        row.type = 'button';
        row.setAttribute('aria-label', `打开歌单 ${item.name}`);
        row.append(makeCover(item.artwork, 'my-playlist-cover'));
        const copy = element('span', 'my-playlist-copy');
        copy.append(element('strong', '', item.name), element('span', '', Number.isFinite(item.count) ? `${item.count.toLocaleString()} 首歌曲` : (item.creator || '网易云音乐')));
        row.append(copy, element('span', 'my-playlist-chevron', '›'));
        row.addEventListener('click', () => openPlaylist(item.id));
        list.append(row);
      });
      playlists.wrap.append(list);
    } else if (state.libraryLoaded && !state.libraryLoading && !state.libraryError) {
      playlists.wrap.append(emptyState('这里还没有歌单', '网易云音乐账号没有返回个人歌单。', '重新同步', () => loadLibrary(true)));
    }
    root.append(playlists.wrap);

    const account = element('button', 'secondary-button library-account-button'); account.type = 'button'; account.textContent = '账号与音源设置'; account.addEventListener('click', () => openAccount()); root.append(account);
  }

  function renderSearch() {
    const root = $('#tab-content');
    const form = element('form', 'search-form'); form.setAttribute('role', 'search');
    const searchIcon = document.createElement('span'); setIcon(searchIcon, 'search'); form.append(searchIcon);
    const input = document.createElement('input'); input.id = 'search-input'; input.type = 'search'; input.autocomplete = 'off'; input.placeholder = '搜索歌曲、专辑、歌单'; input.value = state.searchQuery; input.setAttribute('aria-label', '搜索音乐');
    input.addEventListener('input', () => {
      state.searchQuery = input.value;
      clearTimeout(state.searchTimer);
      const value = state.searchQuery.trim();
      if (value) state.searchTimer = setTimeout(() => runSearch(value), 320);
      else { state.searchResults = null; state.searchError = false; renderTab(); $('#search-input')?.focus(); }
    });
    form.append(input);
    if (state.searchQuery) { const clear = element('button', 'search-clear', '×'); clear.type = 'button'; clear.setAttribute('aria-label','清除搜索'); clear.addEventListener('click', () => { state.searchQuery = ''; state.searchResults = null; renderTab(); $('#search-input')?.focus(); }); form.append(clear); }
    form.addEventListener('submit', event => { event.preventDefault(); clearTimeout(state.searchTimer); input.blur(); runSearch(input.value.trim()); });
    root.append(form);

    const providerRow = element('div', 'search-source-row');
    providerRow.append(element('span', '', '搜索平台'));
    const provider = document.createElement('select'); provider.className = 'source-select'; provider.setAttribute('aria-label','搜索平台');
    [['netease','网易云音乐'],['qq','QQ 音乐']].forEach(([value,label]) => { const option = document.createElement('option'); option.value = value; option.textContent = label; option.selected = state.searchProvider === value; provider.append(option); });
    provider.addEventListener('change', () => { state.searchProvider = provider.value; state.searchResults = null; if (state.searchQuery.trim()) runSearch(state.searchQuery.trim()); else renderTab(); });
    providerRow.append(provider); root.append(providerRow);

    const types = state.searchProvider === 'qq' ? [['songs','歌曲']] : [['songs','歌曲'],['playlists','歌单'],['albums','专辑'],['artists','艺人']];
    if (!types.some(([value]) => value === state.searchType)) state.searchType = 'songs';
    const tabRow = element('div', 'search-tabs');
    types.forEach(([value,label]) => {
      const button = element('button', `search-tab${state.searchType === value ? ' is-selected' : ''}`, label); button.type = 'button';
      button.addEventListener('click', () => { state.searchType = value; renderTab(); if (state.searchQuery.trim()) runSearch(state.searchQuery.trim()); }); tabRow.append(button);
    });
    root.append(tabRow);

    if (!state.searchQuery.trim()) {
      root.append(element('p', 'search-hint', state.searchProvider === 'qq' ? '在 QQ 音乐中搜索歌曲' : '搜索网易云音乐的歌曲、专辑和歌单'));
      return;
    }
    if (state.searchLoading) root.append(loadingState('正在搜索…'));
    if (state.searchError) {
      const failed = emptyState('没有连上搜索服务', '稍后重试，或检查手机网络。', '重新搜索', () => runSearch(state.searchQuery.trim()));
      if (state.searchProvider === 'qq' && state.qqStatus && !state.qqStatus.loggedIn) {
        const login = element('button','secondary-button','QQ 音乐登录'); login.type='button'; login.addEventListener('click',() => launchLogin('qq')); failed.append(login);
      }
      root.append(failed);
    }
    if (!state.searchLoading && !state.searchError && state.searchResults) {
      const data = state.searchResults;
      if (state.searchType === 'songs') {
        const tracks = data.songs || [];
        if (tracks.length) root.append(renderSongList(tracks, { duration: true, actions: true }));
        else {
          const none = emptyState('没有找到歌曲', '试试歌名、歌手名，或切换搜索平台。', null, null);
          if (state.searchProvider === 'qq' && state.qqStatus && !state.qqStatus.loggedIn) {
            const login = element('button','primary-action','QQ 音乐登录'); login.type='button'; login.addEventListener('click',() => launchLogin('qq')); none.append(login);
          }
          root.append(none);
        }
      } else {
        const key = state.searchType;
        const items = data[key] || [];
        if (key === 'playlists' && items.length) {
          const rail = element('div', 'card-rail'); items.forEach(item => rail.append(renderPlaylistCard(item, false))); root.append(rail);
        } else if (key === 'albums' && items.length) {
          const rail = element('div', 'card-rail'); items.forEach(item => {
            const album = { id: String(item.id), name: String(item.name || '专辑'), artwork: safeHttpsUrl(item.picUrl || item.coverImgUrl || ''), count: Number(item.size || 0) };
            const card = element('button','playlist-card'); card.type='button'; card.append(makeCover(album.artwork,'',176),element('strong','',album.name));
            card.addEventListener('click', () => loadAlbum(album.id)); rail.append(card);
          }); root.append(rail);
        } else if (key === 'artists' && items.length) {
          const list = element('div','song-list'); items.forEach(item => {
            const row = element('div','song-row'); row.append(makeCover(safeHttpsUrl(item.picUrl || ''),''));
            const copy = element('div','song-copy'); copy.append(element('strong','',String(item.name || '未知艺人')),element('span','',`${Number(item.albumSize || 0)} 张专辑`)); row.append(copy); list.append(row);
          }); root.append(list);
        } else root.append(emptyState('没有匹配内容', '换一个关键词再试试。', null, null));
      }
    }
  }

  function renderPlaylistPage() {
    const root = $('#tab-content');
    const back = element('button','back-button'); back.type='button'; setIcon(back,'back'); back.append(document.createTextNode('返回')); back.addEventListener('click', handleBack); root.append(back);
    if (state.playlistLoading) { root.append(loadingState('正在读取歌单…')); return; }
    if (state.playlistError || !state.playlist) {
      const retry = state.playlistId === 'recent-history' ? openRecentHistory : () => openPlaylist(state.playlistId);
      root.append(emptyState(state.playlistId === 'recent-history' ? '播放记录无法打开' : '歌单无法打开', '请确认网络连接后重试。', '重新加载', retry)); return;
    }
    const playlist = state.playlist;
    const total = Math.max(playlist.tracks.length, playlist.total || 0);
    const countLabel = total > playlist.tracks.length ? `已加载 ${playlist.tracks.length} / ${total} 首` : `${playlist.tracks.length} 首歌曲`;
    const hero = element('div','playlist-hero'); hero.append(makeCover(playlist.artwork,'',112));
    const copy = element('div','playlist-hero-copy'); copy.append(element('h2','',playlist.name),element('p','',`${playlist.creator ? playlist.creator + ' · ' : ''}${countLabel}`)); hero.append(copy); root.append(hero);
    if (playlist.description) { const desc = element('p','login-status-line',playlist.description); root.append(desc); }
    const controls = element('div','playlist-controls');
    const play = element('button','large-play'); play.type='button'; setIcon(play,'play'); play.append(document.createTextNode(total > playlist.tracks.length ? '播放已加载歌曲' : '全部播放')); play.addEventListener('click', () => { if (!playlist.tracks.length) return; if (state.media.shuffle) setShuffle(false, true); playTracks(playlist.tracks,0); }); controls.append(play);
    const shuffle = element('button','round-action'); shuffle.type='button'; setIcon(shuffle,'shuffle'); shuffle.setAttribute('aria-label','随机播放'); shuffle.addEventListener('click', () => { if (!playlist.tracks.length) return; setShuffle(true, true); playTracks(playlist.tracks, Math.floor(Math.random() * playlist.tracks.length)); }); controls.append(shuffle); root.append(controls);
    root.append(element('p','list-caption',countLabel));
    if (playlist.loadError) root.append(element('p','login-status-line',playlist.loadError));
    if (playlist.tracks.length) root.append(renderSongList(playlist.tracks,{duration:true,actions:true}));
    else root.append(emptyState('歌单里没有歌曲', '音乐平台没有返回可播放曲目。', null, null));
  }

  function renderTab(preserveScroll) {
    const root = $('#tab-content');
    const previousScrollTop = preserveScroll === false ? 0 : root.scrollTop;
    const titleMap = { listen: ['为你精选','听听'], library: ['你的音乐','我的'], search: ['找到下一首','搜索'] };
    const title = titleMap[state.tab];
    $('#top-eyebrow').textContent = title[0]; $('#top-title').textContent = title[1];
    $$('.tab-item').forEach(button => {
      const active = button.dataset.tab === state.tab;
      button.classList.toggle('is-active', active);
      button.toggleAttribute('aria-current', active);
      if (active) button.setAttribute('aria-current','page');
      else button.removeAttribute('aria-current');
    });
    root.replaceChildren();
    if (state.playlist || state.playlistLoading || state.playlistError) renderPlaylistPage();
    else if (state.tab === 'listen') renderListen();
    else if (state.tab === 'library') renderLibrary();
    else renderSearch();
    root.scrollTop = previousScrollTop;
  }

  function changeTab(tab, options) {
    if (!['listen','library','search'].includes(tab)) return;
    if (!(options && options.skipSavePreviousScroll) && !state.playlist && !state.playlistLoading && !state.playlistError) state.tabScrollTop[state.tab] = $('#tab-content').scrollTop;
    state.playlistRequestGeneration += 1;
    state.playlist = null; state.playlistId = null; state.playlistLoading = false; state.playlistError = false;
    state.tab = tab; renderTab(false);
    const scrollTop = options && Number.isFinite(options.scrollTop) ? Math.max(0, options.scrollTop) : 0;
    $('#tab-content').scrollTop = scrollTop;
    state.tabScrollTop[tab] = scrollTop;
    if (tab === 'library') { loadLibrary(); loadRecentHistory(true, 20); }
    if (tab === 'listen') loadHome();
  }

  function removeTabGhosts() {
    $$('.tab-page-ghost').forEach(node => {
      cancelSpring(node);
      node.remove();
    });
  }

  function createTabGhost(content, offsetX) {
    const rect = content.getBoundingClientRect();
    const style = getComputedStyle(content);
    let clipBottom = rect.bottom;
    ['#mini-player','.tab-bar'].forEach(selector => {
      const overlay = $(selector);
      if (!overlay || overlay.hidden || getComputedStyle(overlay).display === 'none') return;
      clipBottom = Math.min(clipBottom, overlay.getBoundingClientRect().top);
    });
    const ghost = element('div','tab-page-ghost');
    ghost.setAttribute('aria-hidden','true');
    ghost.inert = true;
    ghost.style.left = `${rect.left-offsetX}px`;
    ghost.style.top = `${rect.top}px`;
    ghost.style.width = `${rect.width}px`;
    ghost.style.height = `${Math.max(0,clipBottom-rect.top)}px`;
    ghost.style.padding = '0';
    const inner = element('div','tab-page-ghost-inner');
    inner.style.padding = `${style.paddingTop} ${style.paddingRight} ${style.paddingBottom} ${style.paddingLeft}`;
    inner.style.transform = `translate3d(0,${-content.scrollTop}px,0)`;
    Array.from(content.childNodes).forEach(node => inner.append(node));
    ghost.append(inner);
    ghost.querySelectorAll('[id]').forEach(node => node.removeAttribute('id'));
    ghost.querySelectorAll('[tabindex]').forEach(node => node.removeAttribute('tabindex'));
    $('#app').append(ghost);
    return ghost;
  }

  function navigateTab(tab, direction, velocityX, focusSearch) {
    if (!tabOrder.includes(tab)) return;
    const content = $('#tab-content');
    removeTabGhosts();
    if (tab === state.tab && !state.playlist && !state.playlistLoading && !state.playlistError) {
      const wasInFlight = tabTransitionInFlight;
      if (wasInFlight) {
        const generation = ++tabTransitionGeneration;
        tabTransitionInFlight = false;
        const current = cancelSpring(content);
        springTo(content,0,0,current.vx,current.vy,() => {
          if (generation !== tabTransitionGeneration) return;
          content.style.opacity='1'; content.style.willChange='';
          if (tab === 'search' && focusSearch) setTimeout(() => {
            if (generation === tabTransitionGeneration && state.tab === 'search') $('#search-input')?.focus({preventScroll:true});
          },40);
        });
      }
      if (!wasInFlight && tab === 'search' && focusSearch) setTimeout(() => { if (state.tab === 'search' && !tabTransitionInFlight) $('#search-input')?.focus({preventScroll:true}); },40);
      return;
    }
    const fromIndex = tabOrder.indexOf(state.tab);
    const toIndex = tabOrder.indexOf(tab);
    if (state.playlist || state.playlistLoading || state.playlistError || fromIndex === toIndex) {
      ++tabTransitionGeneration; tabTransitionInFlight = false;
      cancelSpring(content);
      setMotionPosition(content,0,0); content.style.opacity='1'; content.style.willChange='';
      changeTab(tab, { scrollTop: state.tabScrollTop[tab] || 0 });
      if (tab === 'search' && focusSearch) setTimeout(() => { if (state.tab === 'search') $('#search-input')?.focus({preventScroll:true}); },40);
      return;
    }
    const motionReduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (motionReduced) {
      ++tabTransitionGeneration; tabTransitionInFlight = false;
      cancelSpring(content); setMotionPosition(content,0,0); content.style.opacity='1'; content.style.willChange='';
      changeTab(tab, { scrollTop: state.tabScrollTop[tab] || 0 });
      if (tab === 'search' && focusSearch) setTimeout(() => { if (state.tab === 'search') $('#search-input')?.focus({preventScroll:true}); },40);
      return;
    }
    const actualDirection = direction || Math.sign(toIndex - fromIndex);
    const width = Math.max(content.clientWidth, window.innerWidth || 1);
    const outgoingX = -actualDirection * width;
    const generation = ++tabTransitionGeneration;
    tabTransitionInFlight = true;
    if (!state.playlist && !state.playlistLoading && !state.playlistError) state.tabScrollTop[state.tab] = content.scrollTop;
    const destinationScroll = state.tabScrollTop[tab] || 0;
    const current = cancelSpring(content);
    const ghost = createTabGhost(content,current.x);
    content.style.willChange = 'transform, opacity';
    const incomingX = current.x + actualDirection * width;
    changeTab(tab,{scrollTop:destinationScroll,skipSavePreviousScroll:true});
    setMotionPosition(content,incomingX,0);
    setMotionPosition(ghost,current.x,0);
    const initialVelocity = Number(velocityX)||current.vx;
    springTo(ghost,outgoingX,0,tabSpringVelocity(initialVelocity,current.x,outgoingX),0,() => ghost.remove());
    springTo(content,0,0,tabSpringVelocity(initialVelocity,incomingX,0),0,() => {
      if (generation !== tabTransitionGeneration) return;
      content.style.opacity='1';
      content.style.willChange='';
      tabTransitionInFlight=false;
      if (tab === 'search' && focusSearch) setTimeout(() => {
        if (generation === tabTransitionGeneration && state.tab === 'search' && !tabTransitionInFlight) $('#search-input')?.focus({preventScroll:true});
      },40);
    });
  }

  async function loadHome(force) {
    if (!nativeReady() || state.homeLoading || (state.homeLoaded && !force)) return;
    state.homeLoading = true; state.homeError = false;
    if (!state.playlist && state.tab === 'listen') renderTab();
    try {
      const response = await request('home', {});
      state.home = { playlists: extractPlaylists(response), songs: extractSongs(response, 'netease') };
      state.homeLoaded = true;
    } catch (_) { state.homeError = true; }
    finally { state.homeLoading = false; if (!state.playlist && state.tab === 'listen') renderTab(); }
  }

  async function loadLibrary(force) {
    const ownerKey = accountIdentity(state.account);
    if (!nativeReady() || !ownerKey || state.libraryLoading || (state.libraryLoaded && !force)) return;
    if (state.libraryOwnerKey !== ownerKey) {
      state.libraryRequestGeneration += 1;
      state.library = []; state.libraryLoaded = false; state.libraryError = false;
      state.libraryOwnerKey = ownerKey;
    }
    const generation = ++state.libraryRequestGeneration;
    state.libraryLoading = true; state.libraryError = false;
    if (!state.playlist && state.tab === 'library') renderTab();
    try {
      const response = await request('userPlaylists', {});
      if (generation !== state.libraryRequestGeneration || ownerKey !== accountIdentity(state.account)) return;
      state.library = extractPlaylists(response); state.libraryLoaded = true;
    } catch (_) {
      if (generation === state.libraryRequestGeneration && ownerKey === accountIdentity(state.account)) state.libraryError = true;
    } finally {
      if (generation === state.libraryRequestGeneration && ownerKey === accountIdentity(state.account)) {
        state.libraryLoading = false;
        if (!state.playlist && state.tab === 'library') renderTab();
      }
    }
  }

  function recentHistoryViewSignature() {
    if (state.recentHistory.length) {
      return JSON.stringify([
        'tracks',
        state.recentHistoryTotal,
        state.recentHistory.map(track => [
          track.provider, track.id, track.title, track.artist, track.album,
          track.artwork, track.duration, track.lastPlayedAt
        ])
      ]);
    }
    if (state.recentHistoryLoading) return 'loading';
    if (state.recentHistoryError) return 'error';
    return state.recentHistoryLoaded ? 'empty' : 'blank';
  }

  async function loadRecentHistory(force, requestedLimit) {
    if (!nativeReady() || (state.recentHistoryLoading && !force) || (state.recentHistoryLoaded && !force)) return;
    const limit = Math.min(100, Math.max(1, Math.floor(Number(requestedLimit) || 20)));
    const generation = ++state.recentHistoryRequestGeneration;
    state.recentHistoryLoading = true; state.recentHistoryError = false;
    if (!state.recentHistory.length && state.tab === 'library' && !state.playlist && !state.playlistLoading && !state.playlistError) renderTab();
    const visibleBeforeResponse = recentHistoryViewSignature();
    try {
      const response = await request('recentHistory', { limit });
      if (generation !== state.recentHistoryRequestGeneration) return;
      if (!apiRequestSucceeded(response) || !Array.isArray(response.tracks)) throw safeFailure();
      state.recentHistory = response.tracks.map(item => normalizeTrack(item, item && item.provider)).filter(Boolean);
      const total = Number(response.total);
      state.recentHistoryTotal = Number.isFinite(total) ? Math.max(state.recentHistory.length, Math.floor(total)) : state.recentHistory.length;
      state.recentHistoryLoaded = true;
    } catch (_) {
      if (generation === state.recentHistoryRequestGeneration) state.recentHistoryError = true;
    } finally {
      if (generation === state.recentHistoryRequestGeneration) {
        state.recentHistoryLoading = false;
        if (visibleBeforeResponse !== recentHistoryViewSignature() && state.tab === 'library' && !state.playlist && !state.playlistLoading && !state.playlistError) renderTab();
      }
    }
  }

  async function openRecentHistory() {
    const generation = ++state.playlistRequestGeneration;
    const historyGeneration = ++state.recentHistoryRequestGeneration;
    state.playlistOriginScrollTop = $('#tab-content')?.scrollTop || 0;
    state.playlistId = 'recent-history'; state.playlist = null; state.playlistLoading = true; state.playlistError = false;
    state.recentHistoryLoading = true; state.recentHistoryError = false;
    renderTab();
    try {
      const response = await request('recentHistory', { limit: 100 });
      if (generation !== state.playlistRequestGeneration || historyGeneration !== state.recentHistoryRequestGeneration) return;
      if (!apiRequestSucceeded(response) || !Array.isArray(response.tracks)) throw safeFailure();
      const tracks = response.tracks.map(item => normalizeTrack(item, item && item.provider)).filter(Boolean);
      const reportedTotal = Number(response.total);
      const total = Number.isFinite(reportedTotal) ? Math.max(tracks.length, Math.floor(reportedTotal)) : tracks.length;
      state.recentHistory = tracks;
      state.recentHistoryTotal = total;
      state.recentHistoryLoaded = true;
      state.playlist = {
        id: 'recent-history', name: '已播歌曲', artwork: tracks[0]?.artwork || '', creator: '',
        description: '按最近播放时间排列', total, truncated: total > tracks.length, loadError: '', tracks
      };
    } catch (_) {
      if (generation === state.playlistRequestGeneration && historyGeneration === state.recentHistoryRequestGeneration) {
        state.playlistError = true;
        state.recentHistoryError = true;
      }
    } finally {
      if (generation === state.playlistRequestGeneration && historyGeneration === state.recentHistoryRequestGeneration) {
        state.playlistLoading = false;
        state.recentHistoryLoading = false;
        renderTab(); $('#tab-content').scrollTop = 0;
      }
    }
  }

  async function openPlaylist(id) {
    if (!id) return;
    const generation = ++state.playlistRequestGeneration;
    state.playlistOriginScrollTop = $('#tab-content')?.scrollTop || 0;
    state.playlistId = String(id); state.playlist = null; state.playlistLoading = true; state.playlistError = false; renderTab();
    try {
      const playlist = extractPlaylistDetail(await request('playlist', { id: String(id) }));
      if (generation !== state.playlistRequestGeneration) return;
      if (!playlist) throw safeFailure();
      state.playlist = playlist;
    } catch (_) {
      if (generation === state.playlistRequestGeneration) state.playlistError = true;
    } finally {
      if (generation === state.playlistRequestGeneration) {
        state.playlistLoading = false; renderTab(); $('#tab-content').scrollTop = 0;
      }
    }
  }

  async function loadAlbum(id) {
    if (!id) return;
    const generation = ++state.playlistRequestGeneration;
    state.playlistOriginScrollTop = $('#tab-content')?.scrollTop || 0;
    state.playlistId = String(id); state.playlist = null; state.playlistLoading = true; state.playlistError = false; renderTab();
    try {
      const response = await request('album', { id: String(id) });
      if (generation !== state.playlistRequestGeneration) return;
      const album = response && response.album ? response.album : response;
      state.playlist = { id: String(id), name: String(album && album.name || '专辑'), artwork: safeHttpsUrl(album && (album.picUrl || album.coverImgUrl) || ''), creator: artistNames(album || {}), description: '', tracks: extractSongs(response,'netease') };
    } catch (_) { if (generation === state.playlistRequestGeneration) state.playlistError = true; }
    finally { if (generation === state.playlistRequestGeneration) { state.playlistLoading = false; renderTab(); $('#tab-content').scrollTop = 0; } }
  }

  async function runSearch(query) {
    const term = String(query || '').trim();
    if (!term || !nativeReady()) return;
    const oldInput = $('#search-input');
    const preserveFocus = !!(oldInput && document.activeElement === oldInput);
    const caret = preserveFocus ? oldInput.selectionStart : null;
    const restoreInput = () => {
      if (!preserveFocus) return;
      const input = $('#search-input');
      if (!input) return;
      input.focus({preventScroll:true});
      if (caret !== null && typeof input.setSelectionRange === 'function') input.setSelectionRange(caret,caret);
    };
    const generation = ++state.searchRequestGeneration;
    state.searchLoading = true; state.searchError = false; state.searchResults = null; state.searchQuery = term;
    if (!state.playlist && state.tab === 'search') renderTab();
    restoreInput();
    const provider = state.searchProvider;
    try {
      const response = provider === 'qq'
        ? await request('qqSearch', { keywords: term, limit: 30 })
        : await request('search', { keywords: term, type: ({ songs: 1, albums: 10, artists: 100, playlists: 1000 })[state.searchType] || 1, limit: 30 });
      if (generation !== state.searchRequestGeneration) return;
      const netease = response && response.result ? response.result : response;
      state.searchResults = provider === 'qq'
        ? { songs: extractSongs(response, 'qq') }
        : { songs: extractSongs(response,'netease'), playlists: extractPlaylists(response), albums: Array.isArray(netease.albums) ? netease.albums : [], artists: Array.isArray(netease.artists) ? netease.artists : [] };
    } catch (_) {
      if (generation === state.searchRequestGeneration) state.searchError = true;
    } finally {
      if (generation === state.searchRequestGeneration) {
        state.searchLoading = false;
        if (!state.playlist && state.tab === 'search') { renderTab(); restoreInput(); }
      }
    }
  }

  function playTracks(tracks, index) {
    const queue = Array.isArray(tracks) ? tracks.map(track => normalizeTrack(track, track.provider)).filter(Boolean) : [];
    if (!queue.length) { showToast('这组歌曲暂时没有可播放曲目'); return; }
    const targetIndex = Math.max(0, Math.min(queue.length - 1, Number(index) || 0));
    if (!nativeReady()) { showToast('请在手机应用中开始播放'); return; }
    try {
      window.Native.command(JSON.stringify({ type: 'play', queue, index: targetIndex }));
      openPlayer();
    } catch (_) { showToast('暂时无法开始播放'); }
  }

  function sendCommand(command) {
    if (!window.Native || typeof window.Native.command !== 'function') { showToast('请在手机应用中使用播放器'); return; }
    try { window.Native.command(JSON.stringify(command)); } catch (_) { showToast('播放器暂时无法响应'); }
  }

  function openPlayer() {
    const overlay = $('#player-overlay'); const sheet = $('#player-sheet');
    const wasVisible = overlay.classList.contains('is-open');
    const generation = ++playerGeneration;
    if (!wasVisible) {
      sheet.style.transform = `translate3d(0,${Math.max(window.innerHeight,sheet.offsetHeight)}px,0)`;
      $('#player-scroll').scrollTop = 0;
    }
    overlay.classList.add('is-open'); overlay.classList.remove('is-closing');
    overlay.setAttribute('aria-hidden','false');
    $('#player-scrim').style.opacity = '';
    const current = cancelSpring(sheet);
    state.fullPlayer = true;
    resetPlayerPanels();
    updateLyricsActive(true);
    syncSystemChrome();
    if (window.Native && typeof window.Native.haptic === 'function') { try { window.Native.haptic(); } catch (_) {} }
    requestAnimationFrame(() => {
      if (generation !== playerGeneration || !state.fullPlayer) return;
      springTo(sheet,0,0,current.vx,current.vy);
    });
  }

  function closePlayer(velocityY) {
    const overlay = $('#player-overlay'); const sheet = $('#player-sheet');
    if (!state.fullPlayer && !overlay.classList.contains('is-open')) return false;
    const generation = ++playerGeneration;
    resetPlayerPanels();
    const current = cancelSpring(sheet);
    $('#player-scrim').style.opacity = '';
    overlay.classList.add('is-closing');
    springTo(sheet,0,Math.max(window.innerHeight,sheet.offsetHeight),current.vx,Math.max(0,Number(velocityY)||current.vy),() => {
      if (generation !== playerGeneration) return;
      overlay.classList.remove('is-open','is-closing');
      overlay.setAttribute('aria-hidden','true');
      sheet.style.transform = '';
      state.fullPlayer = false;
      syncSystemChrome();
    });
    return true;
  }

  function openQueueSheet() {
    const overlay = $('#queue-overlay'); const sheet = $('#queue-sheet');
    const generation = ++queueSheetGeneration;
    const wasVisible = overlay.classList.contains('is-open');
    if (!wasVisible) sheet.style.transform = `translate3d(0,${Math.max(window.innerHeight,sheet.offsetHeight)}px,0)`;
    state.queueSheetOpen = true;
    overlay.classList.add('is-open'); overlay.classList.remove('is-closing');
    overlay.setAttribute('aria-hidden','false');
    $('#queue-scrim').style.opacity = '';
    renderQueueIfChanged();
    const current = cancelSpring(sheet);
    requestAnimationFrame(() => {
      if (generation !== queueSheetGeneration || !state.queueSheetOpen) return;
      springTo(sheet,0,0,current.vx,current.vy);
    });
  }

  function closeQueueSheet(velocityY) {
    if (!state.queueSheetOpen) return false;
    const overlay = $('#queue-overlay'); const sheet = $('#queue-sheet');
    const generation = ++queueSheetGeneration;
    const current = cancelSpring(sheet);
    $('#queue-scrim').style.opacity = '';
    overlay.classList.add('is-closing');
    springTo(sheet,0,Math.max(window.innerHeight,sheet.offsetHeight),current.vx,Math.max(0,Number(velocityY)||current.vy),() => {
      if (generation !== queueSheetGeneration) return;
      state.queueSheetOpen = false;
      overlay.classList.remove('is-open','is-closing');
      overlay.setAttribute('aria-hidden','true');
      sheet.style.transform = '';
      const scrim = $('#queue-scrim'); scrim.style.opacity = '';
    });
    return true;
  }

  function handleBack() {
    if (trackActions) {
      if (trackActions.view === 'playlists') { trackActions.view = 'menu'; renderTrackActions(); }
      else closeTrackActions();
      return true;
    }
    const accountOverlay = $('#account-overlay');
    if (accountOverlay && accountOverlay.classList.contains('is-open')) {
      closeAccount();
      return true;
    }
    if (state.queueSheetOpen) return closeQueueSheet();
    if (state.fullPlayer) {
      if (state.playerExpanded) collapsePlayerPanels();
      else closePlayer();
      return true;
    }
    if (state.playlist || state.playlistLoading || state.playlistError) {
      if (state.playlistId === 'recent-history' && state.playlistLoading) {
        state.recentHistoryRequestGeneration += 1;
        state.recentHistoryLoading = false;
      }
      state.playlistRequestGeneration += 1;
      state.playlist = null; state.playlistId = null; state.playlistLoading = false; state.playlistError = false;
      renderTab();
      $('#tab-content').scrollTop = state.playlistOriginScrollTop;
      return true;
    }
    return false;
  }

  window.YPM.back = handleBack;

  function updateKeyboard() {
    let keyboardOpen;
    if (nativeKeyboardVisible !== null) keyboardOpen = nativeKeyboardVisible;
    else {
      const viewport = window.visualViewport;
      keyboardOpen = !!(viewport && window.innerHeight - viewport.height > 140) || document.activeElement?.id === 'search-input';
    }
    $('#app')?.classList.toggle('keyboard-open',keyboardOpen);
  }

  window.YPM.setKeyboard = function (visible) {
    const wasVisible = nativeKeyboardVisible;
    nativeKeyboardVisible = !!visible;
    // Back (or the IME's own hide key) closes the keyboard without blurring the field; drop focus
    // so the tab bar, mini player and tab swipe come back.
    if (wasVisible && !nativeKeyboardVisible) {
      const active = document.activeElement;
      if (active && active.matches && active.matches('input,textarea,[contenteditable="true"]')) active.blur();
    }
    updateKeyboard();
    return true;
  };

  function formatTime(milliseconds) {
    const total = Math.max(0, Math.floor((Number(milliseconds) || 0) / 1000));
    const minutes = Math.floor(total / 60); const seconds = total % 60;
    return `${minutes}:${String(seconds).padStart(2,'0')}`;
  }

  function currentTrackTitle(track) { return track && track.title ? track.title : '尚未播放'; }
  function sourceLabel(source) {
    const value = String(source || '').toLowerCase();
    if (value.includes('qq')) return 'QQ \u97f3\u6e90';
    if (value.includes('netease') || value.includes('163') || value.includes('\u7f51\u6613')) return '\u7f51\u6613\u4e91';
    if (value) return '\u97f3\u6e90';
    const preference = state.settings.sourcePreference;
    if (state.media.error) {
      if (preference === 'qq') return 'QQ\u97f3\u6e90\u4e0d\u53ef\u7528';
      if (preference === 'netease') return '\u7f51\u6613\u4e91\u97f3\u6e90\u4e0d\u53ef\u7528';
      return '\u97f3\u6e90\u4e0d\u53ef\u7528';
    }
    if (state.media.buffering) {
      if (preference === 'qq') return '\u6b63\u5728\u5339\u914dQQ';
      if (preference === 'netease') return '\u6b63\u5728\u5339\u914d\u7f51\u6613\u4e91';
      return '\u6b63\u5728\u5339\u914d\u97f3\u6e90';
    }
    return '\u97f3\u6e90\u672a\u786e\u8ba4';
  }

  function updateSourceLabels() {
    if (!state.media.track) return;
    const label = sourceLabel(state.media.source);
    $('#player-source').textContent = label;
    $('#mini-source').textContent = label;
    $('#mini-source').hidden = false;
  }

  function updateArt(node, url, size) {
    if (!node) return;
    const fallback = element('span','art-mark');
    setIcon(fallback, 'music');
    node.replaceChildren(fallback);
    const safe = sizedArtwork(url, size);
    if (safe) {
      const img = document.createElement('img'); img.alt = ''; img.decoding = 'async'; img.referrerPolicy = 'no-referrer'; img.src = safe; img.onerror = () => img.remove(); node.append(img);
    }
  }

  let miniLyricsController = null;

  function updateMiniSubtitle(index) {
    const host = $('#mini-subtitle');
    if (!host) return;
    const track = state.media.track;
    const active = Number.isInteger(index) ? index : (state.lyrics.length && window.YPMLyrics ? window.YPMLyrics.activeIndex(state.lyrics,mediaPosition()) : -1);
    const line = track && active >= 0 ? state.lyrics[active] : null;
    const lyricText = line && String(line.text || '').trim();
    const text = lyricText || (track ? (track.artist || track.album || 'YesPlayMusic') : '');
    if (!miniLyricsController && window.YPMMiniLyrics) miniLyricsController = window.YPMMiniLyrics.create(host);
    if (miniLyricsController) {
      miniLyricsController.update({
        key: `${track ? `${track.provider}:${track.id}` : ''}:${lastLyricsKey}:${lyricText ? active : 'fallback'}`,
        text, lyric: !!lyricText, playing: !!track && state.media.playing && !state.media.buffering
      });
    } else if (host.textContent !== text) host.textContent = text;
  }

  function updatePlayerChrome() {
    const track = state.media.track;
    if (state.media.error) {
      const errorKey = track ? JSON.stringify([track.provider, track.id]) : 'no-track';
      setMessage('当前歌曲无法播放，请试试列表中的其他歌曲。', true, errorKey);
    } else {
      clearErrorMessage();
    }
    const hasTrack = !!track;
    $('#mini-player').hidden = !hasTrack;
    if (!hasTrack) {
      lastTrackSignature = '';
      updateMiniSubtitle(-1);
      if (state.favorite.key) invalidateFavoriteStatus(false);
      return;
    }
    updateSourceLabels();
    const trackSignature = JSON.stringify([track.id,track.provider,track.title,track.artist,track.album,track.artwork,state.media.source]);
    if (trackSignature !== lastTrackSignature) {
      lastTrackSignature = trackSignature;
      $('#mini-title').textContent = currentTrackTitle(track);
      $('#player-title').textContent = currentTrackTitle(track);
      $('#player-artist').textContent = [track.artist, track.album].filter(Boolean).join(' · ') || '未知艺术家';
      updateArt($('#mini-art'), track.artwork, 43);
      updateArt($('#player-artwork'), track.artwork, 360);
      loadFavoriteStatus(track);
    }
    setToggleIcon($('#mini-toggle'), state.media.playing ? 'pause' : 'play');
    updateMiniFavoriteButton();
    setToggleIcon($('#player-toggle'), state.media.playing ? 'pause' : 'play');
    $('#player-toggle').setAttribute('aria-label', state.media.playing ? '暂停' : '播放');
    updateProgress();
    const shuffleButton = $('#shuffle-button');
    shuffleButton.classList.toggle('is-selected',!!state.media.shuffle);
    shuffleButton.setAttribute('aria-pressed',String(!!state.media.shuffle));
    shuffleButton.setAttribute('aria-label',state.media.shuffle ? '随机播放已开启' : '顺序播放（随机播放已关闭）');
    const repeat = state.media.repeat || 'off';
    const repeatButton = $('#repeat-button');
    repeatButton.classList.toggle('is-selected',repeat !== 'off');
    repeatButton.dataset.mode = repeat;
    repeatButton.setAttribute('aria-label',repeat === 'one' ? '单曲循环' : repeat === 'all' ? '列表循环' : '循环已关闭');
    renderQueueIfChanged();
    updateLyricsActive();
  }

  // The service publishes the position once per second; between updates it is extrapolated
  // so lyrics and the progress bar advance at the 300 ms poll rate.
  function mediaPosition() {
    const media = state.media;
    let position = Math.max(0, Number(media.position) || 0);
    if (media.playing && !media.buffering && media.updatedAt) position += Math.max(0, Math.min(1500, Date.now() - media.updatedAt));
    const duration = media.duration || (media.track && media.track.duration) || 0;
    return duration ? Math.min(duration, position) : position;
  }

  function updateProgress() {
    const track = state.media.track;
    if (!track || seekDragging) return;
    const duration = Math.max(0, state.media.duration || track.duration || 0);
    const position = mediaPosition();
    const slider = $('#seek-slider');
    slider.max = String(Math.max(1,duration));
    slider.value = String(Math.min(duration || 1,position));
    slider.style.setProperty('--progress', `${duration ? (position / duration) * 100 : 0}%`);
    $('#elapsed-time').textContent = formatTime(position);
    $('#remaining-time').textContent = `−${formatTime(Math.max(0,duration-position))}`;
  }

  function favoriteProvider(track) {
    const id = String(track && track.id || '');
    const provider = String(track && track.provider || '').toLowerCase();
    return /^qq:/i.test(id) || provider.includes('qq') || (!!(track && (track.qqMid || track.songmid)) && !/^\d+$/.test(id))
      ? 'qq' : 'netease';
  }

  function favoriteTrackKey(track) {
    if (!track || track.id === undefined || track.id === null) return '';
    const provider = favoriteProvider(track);
    return provider === 'qq'
      ? 'qq:' + String(track.id) + ':' + String(track.qqMid || track.songmid || '')
      : 'netease:' + String(track.id);
  }

  function favoriteRequest(target, liked) {
    const args = { id: String(target && target.id || '') };
    return { method: liked === undefined ? 'likeStatus' : 'setLiked', args: liked === undefined ? args : Object.assign({}, args, { liked }) };
  }

  function favoriteTargetFor(track) {
    const failure = (message, retryable) => ({ target: null, errorMessage: message, retryable: !!retryable });
    if (!track || track.id === undefined || track.id === null) {
      return Promise.resolve(failure('\u65e0\u6cd5\u8bc6\u522b\u8fd9\u9996\u6b4c\uff0c\u65e0\u6cd5\u6536\u85cf', false));
    }
    if (favoriteProvider(track) !== 'qq') {
      const id = String(track.id);
      if (!/^[1-9]\d{0,23}$/.test(id)) {
        return Promise.resolve(failure('\u65e0\u6cd5\u8bc6\u522b\u8fd9\u9996\u7f51\u6613\u4e91\u6b4c\u66f2\uff0c\u65e0\u6cd5\u6536\u85cf', false));
      }
      return Promise.resolve({
        target: { id, title: String(track.title || ''), artist: String(track.artist || '') },
        errorMessage: '', retryable: false
      });
    }

    const title = String(track.title || '').trim();
    if (!title) return Promise.resolve(failure('\u6b4c\u66f2\u4fe1\u606f\u4e0d\u8db3\uff0c\u65e0\u6cd5\u6536\u85cf', false));
    const artist = String(track.artist || '');
    const args = {
      title,
      artist: artist === '\u672a\u77e5\u827a\u672f\u5bb6' ? '' : artist,
      artistAliases: Array.isArray(track.artistAliases) ? track.artistAliases : [],
      duration: Math.max(0, Math.floor(Number(track.duration) || 0))
    };
    const unavailable = '\u6682\u65f6\u65e0\u6cd5\u5339\u914d\u5230\u7f51\u6613\u4e91\u7248\u672c\uff0c\u65e0\u6cd5\u6536\u85cf\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5';
    const unmatched = '\u7f51\u6613\u4e91\u66f2\u5e93\u6ca1\u6709\u627e\u5230\u540c\u4e00\u9996\u6b4c\u66f2\uff0c\u65e0\u6cd5\u6536\u85cf';
    try {
      return request('matchTrack', args).then(response => {
        if (!response || !apiRequestSucceeded(response)) return failure(unavailable, true);
        const id = String(response.id || '');
        if (response.matched !== true || !/^[1-9]\d{0,23}$/.test(id)) return failure(unmatched, false);
        return {
          target: { id, title: String(response.title || track.title || ''), artist: String(response.artist || '') },
          errorMessage: '', retryable: false
        };
      }).catch(() => failure(unavailable, true));
    } catch (_) {
      return Promise.resolve(failure(unavailable, true));
    }
  }

  function favoriteMessage(response, fallback) {
    const message = String(response && response.message || '').trim().slice(0, 80);
    return /^[\u4e00-\u9fffA-Za-z0-9\s\uFF0C\u3002\uFF01\uFF1F\u3001\uFF1B\uFF1A\uFF08\uFF09()\-]+$/.test(message) ? message : fallback;
  }

  function favoriteRequestIsCurrent(generation, key) {
    return generation === state.favoriteGeneration && key === favoriteTrackKey(state.media.track);
  }

  function updateMiniFavoriteButton() {
    const button = $('#mini-like');
    if (!button) return;
    const key = favoriteTrackKey(state.media.track);
    const current = !!key && state.favorite.key === key;
    const liked = current && state.favorite.liked === true;
    button.classList.toggle('is-liked', liked);
    button.setAttribute('aria-pressed', String(liked));
    button.setAttribute('aria-label', liked ? '取消收藏' : '收藏到网易云音乐');
    button.disabled = !current || state.favorite.loading || state.favorite.mutating || state.authMutation === state.favorite.provider;
    button.setAttribute('aria-busy', String(current && (state.favorite.loading || state.favorite.mutating)));
    button.title = current && state.favorite.errorMessage
      ? state.favorite.errorMessage
      : (liked ? '从网易云音乐“我喜欢”中取消' : '收藏到网易云音乐“我喜欢”');
  }

  function invalidateFavoriteStatus(loading) {
    state.favoriteGeneration += 1;
    const track = state.media.track;
    const key = favoriteTrackKey(track);
    state.favorite = {
      key, provider: track ? 'netease' : '', target: null, available: false, loggedIn: false,
      liked: null, loading: !!(loading && key), mutating: false, errorMessage: '', retryable: false
    };
    updateMiniFavoriteButton();
  }

  async function loadFavoriteStatus(track) {
    const key = favoriteTrackKey(track);
    if (!key) return;
    const provider = 'netease';
    const generation = ++state.favoriteGeneration;
    state.favorite = { key, provider, target: null, available: false, loggedIn: false, liked: null, loading: true, mutating: false, errorMessage: '', retryable: false };
    updateMiniFavoriteButton();

    let resolution;
    try { resolution = await favoriteTargetFor(track); }
    catch (_) { resolution = { target: null, errorMessage: '\u6682\u65f6\u65e0\u6cd5\u5339\u914d\u7f51\u6613\u4e91\u7248\u672c\uff0c\u65e0\u6cd5\u6536\u85cf\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5', retryable: true }; }
    if (!favoriteRequestIsCurrent(generation, key)) return;
    if (!resolution || !resolution.target) {
      state.favorite = {
        key, provider, target: null, available: false, loggedIn: false, liked: null, loading: false, mutating: false,
        errorMessage: resolution && resolution.errorMessage || '\u65e0\u6cd5\u5339\u914d\u5230\u53ef\u6536\u85cf\u7684\u7f51\u6613\u4e91\u7248\u672c',
        retryable: !!(resolution && resolution.retryable)
      };
      updateMiniFavoriteButton();
      return;
    }

    let response = null;
    try {
      const info = favoriteRequest(resolution.target);
      response = await request(info.method, info.args);
    } catch (_) {}
    if (!favoriteRequestIsCurrent(generation, key)) return;
    if (response && response.loggedIn === false) {
      state.favorite = { key, provider, target: resolution.target, available: true, loggedIn: false, liked: false, loading: false, mutating: false, errorMessage: '', retryable: false };
    } else if (!response || typeof response !== 'object' || !apiRequestSucceeded(response) || typeof response.loggedIn !== 'boolean' || typeof response.liked !== 'boolean') {
      state.favorite = {
        key, provider, target: resolution.target, available: false, loggedIn: false, liked: null, loading: false, mutating: false,
        errorMessage: favoriteMessage(response, '\u65e0\u6cd5\u8bfb\u53d6\u7f51\u6613\u4e91\u6536\u85cf\u72b6\u6001'),
        retryable: true
      };
    } else {
      state.favorite = { key, provider, target: resolution.target, available: true, loggedIn: response.loggedIn, liked: response.liked, loading: false, mutating: false, errorMessage: '', retryable: false };
    }
    updateMiniFavoriteButton();
  }

  async function toggleFavorite() {
    const track = state.media.track;
    const key = favoriteTrackKey(track);
    if (!key || state.favorite.key !== key || state.favorite.loading || state.favorite.mutating) return;
    if (!state.favorite.available) {
      const retryable = !!state.favorite.retryable;
      showToast(state.favorite.errorMessage || '\u65e0\u6cd5\u8bfb\u53d6\u7f51\u6613\u4e91\u6536\u85cf\u72b6\u6001');
      if (retryable) loadFavoriteStatus(track);
      return;
    }
    if (!state.favorite.loggedIn) {
      showToast('\u767b\u5f55\u7f51\u6613\u4e91\u97f3\u4e50\u540e\u53ef\u6536\u85cf');
      return;
    }
    if (typeof state.favorite.liked !== 'boolean' || !state.favorite.target) {
      showToast(state.favorite.errorMessage || '\u65e0\u6cd5\u8bfb\u53d6\u7f51\u6613\u4e91\u6536\u85cf\u72b6\u6001');
      if (state.favorite.retryable) loadFavoriteStatus(track);
      return;
    }
    const generation = state.favoriteGeneration;
    const requestInfo = favoriteRequest(state.favorite.target, !state.favorite.liked);
    state.favorite.mutating = true;
    updateMiniFavoriteButton();
    try {
      const response = await request(requestInfo.method, requestInfo.args);
      if (!favoriteRequestIsCurrent(generation, key)) return;
      if (response && response.loggedIn === false) {
        state.favorite.loggedIn = false;
        state.favorite.liked = false;
        showToast('\u767b\u5f55\u7f51\u6613\u4e91\u97f3\u4e50\u540e\u53ef\u6536\u85cf');
      } else if (!response || typeof response !== 'object' || !apiRequestSucceeded(response) || typeof response.loggedIn !== 'boolean' || typeof response.liked !== 'boolean') {
        showToast(favoriteMessage(response, '\u6682\u65f6\u65e0\u6cd5\u66f4\u65b0\u7f51\u6613\u4e91\u6536\u85cf\u72b6\u6001'));
      } else {
        state.favorite.loggedIn = true;
        state.favorite.liked = response.liked;
        state.favorite.retryable = false;
        showToast(response.liked ? '\u5df2\u6536\u85cf\u5230\u7f51\u6613\u4e91\u201c\u6211\u559c\u6b22\u201d' : '\u5df2\u4ece\u7f51\u6613\u4e91\u201c\u6211\u559c\u6b22\u201d\u53d6\u6d88\u6536\u85cf');
      }
    } catch (_) {
      if (favoriteRequestIsCurrent(generation, key)) showToast('\u6682\u65f6\u65e0\u6cd5\u66f4\u65b0\u7f51\u6613\u4e91\u6536\u85cf\u72b6\u6001');
    } finally {
      if (favoriteRequestIsCurrent(generation, key)) {
        state.favorite.mutating = false;
        updateMiniFavoriteButton();
      }
    }
  }
  function setShuffle(enabled, silent) {
    if (!nativeReady()) { showToast('请在手机应用中使用播放器'); return; }
    sendCommand({ type: 'shuffle', enabled: !!enabled });
    setPendingPlayerMode('shuffle', !!enabled);
    if (!silent) showToast(enabled ? '随机播放' : '顺序播放');
  }

  function setRepeat(mode) {
    if (!nativeReady()) { showToast('请在手机应用中使用播放器'); return; }
    sendCommand({ type: 'repeat', mode });
    setPendingPlayerMode('repeat', mode);
    showToast(mode === 'one' ? '单曲循环' : mode === 'all' ? '列表循环' : '已关闭循环');
  }

  // Shows a mode change right away; state polls keep it until the service reports it.
  function setPendingPlayerMode(key, value) {
    pendingPlayerModes = Object.assign({}, pendingPlayerModes, { [key]: value, until: performance.now() + 2000 });
    state.media = Object.assign({}, state.media, { [key]: value });
    updatePlayerChrome();
  }

  function applyPendingPlayerModes(next) {
    if (!pendingPlayerModes) return;
    if (performance.now() > pendingPlayerModes.until) { pendingPlayerModes = null; return; }
    let settled = true;
    ['shuffle','repeat'].forEach(key => {
      if (!(key in pendingPlayerModes)) return;
      if (next[key] === pendingPlayerModes[key]) delete pendingPlayerModes[key];
      else { next[key] = pendingPlayerModes[key]; settled = false; }
    });
    if (settled) pendingPlayerModes = null;
  }

  function providerName(provider) {
    return provider === 'qq' ? 'QQ 音乐' : '网易云音乐';
  }

  function trackActionsCurrent(generation) {
    return !!trackActions && trackActions.generation === generation;
  }

  function openTrackActions(raw) {
    const track = normalizeTrack(raw, raw && raw.provider);
    if (!track) return;
    const provider = favoriteProvider(track);
    const key = favoriteTrackKey(track);
    const known = !!key && state.favorite.key === key && state.favorite.available && !state.favorite.loading
      && typeof state.favorite.liked === 'boolean' && !!(state.favorite.target && state.favorite.target.id);
    const target = known ? Object.assign({}, state.favorite.target)
      : provider === 'qq' ? null : { id: String(track.id), title: track.title, artist: track.artist };
    trackActions = {
      track, provider, key, generation: ++trackActionsGeneration, view: 'menu', addingId: '', target,
      // Playlists and favorites live in the NetEase account; QQ catalog tracks are matched first.
      favorite: known
        ? { loading: false, loggedIn: state.favorite.loggedIn, liked: state.favorite.liked, mutating: false, message: '', retryable: false }
        : { loading: true, loggedIn: false, liked: null, mutating: false, message: '', retryable: false },
      playlists: { loading: false, loaded: false, loggedIn: true, items: [], message: '' }
    };
    const cover = $('#track-actions-cover');
    cover.replaceChildren(makeCover(track.artwork, ''));
    $('#track-actions-title').textContent = track.title;
    $('#track-actions-subtitle').textContent = [track.artist, providerName(provider)].filter(Boolean).join(' · ');
    renderTrackActions();
    $('#track-actions-body').scrollTop = 0;
    showTrackActionsSheet();
    if (!known) loadTrackActionFavorite(trackActions);
  }
  function showTrackActionsSheet() {
    const overlay = $('#track-actions-overlay'); const sheet = $('#track-actions-sheet');
    const generation = ++trackActionsSheetGeneration;
    if (!overlay.classList.contains('is-open')) sheet.style.transform = `translate3d(0,${Math.max(window.innerHeight,sheet.offsetHeight)}px,0)`;
    overlay.classList.add('is-open'); overlay.classList.remove('is-closing');
    overlay.setAttribute('aria-hidden','false');
    $('#track-actions-scrim').style.opacity = '';
    const current = cancelSpring(sheet);
    requestAnimationFrame(() => {
      if (generation !== trackActionsSheetGeneration || !trackActions) return;
      springTo(sheet,0,0,current.vx,current.vy);
    });
  }

  function closeTrackActions(velocityY) {
    if (!trackActions) return false;
    trackActions = null;
    trackActionsGeneration += 1;
    const overlay = $('#track-actions-overlay'); const sheet = $('#track-actions-sheet');
    const generation = ++trackActionsSheetGeneration;
    const current = cancelSpring(sheet);
    $('#track-actions-scrim').style.opacity = '';
    overlay.classList.add('is-closing');
    springTo(sheet,0,Math.max(window.innerHeight,sheet.offsetHeight),current.vx,Math.max(0,Number(velocityY)||current.vy),() => {
      if (generation !== trackActionsSheetGeneration) return;
      overlay.classList.remove('is-open','is-closing');
      overlay.setAttribute('aria-hidden','true');
      sheet.style.transform = '';
    });
    return true;
  }

  function promptProviderLogin(provider) {
    closeTrackActions();
    if (provider === 'qq') launchLogin('qq');
    else openAccount(true);
  }

  function actionItem(icon, label, detail, onClick, options) {
    const settings = options || {};
    const button = element('button', `action-item${settings.className ? ` ${settings.className}` : ''}`);
    button.type = 'button';
    button.disabled = !!settings.disabled;
    const iconNode = element('span', 'action-item-icon');
    setIcon(iconNode, icon);
    const copy = element('span', 'action-item-copy');
    copy.append(element('strong', '', label));
    if (detail) copy.append(element('small', '', detail));
    button.append(iconNode, copy);
    if (settings.busy) button.append(element('span', 'spinner'));
    else if (settings.chevron) button.append(element('span', 'action-item-chevron', '›'));
    button.addEventListener('click', onClick);
    return button;
  }

  function renderTrackActions() {
    const body = $('#track-actions-body');
    const actions = trackActions;
    if (!body || !actions) return;
    body.replaceChildren();
    if (actions.view === 'playlists') { renderTrackActionPlaylists(body, actions); return; }
    const favorite = actions.favorite;
    const name = providerName(actions.provider);
    let label = '收藏';
    let detail = '收藏到网易云音乐“我喜欢”';
    if (favorite.loading) detail = '正在读取网易云收藏状态…';
    else if (favorite.mutating) { label = favorite.liked ? '正在取消收藏…' : '正在收藏…'; detail = ''; }
    else if (!favorite.loggedIn && !favorite.message) detail = '登录网易云音乐后可收藏';
    else if (favorite.liked === true) { label = '取消收藏'; detail = '已收藏到网易云音乐“我喜欢”'; }
    else if (favorite.liked === false) detail = '收藏到网易云音乐“我喜欢”';
    else detail = favorite.message || '暂时无法读取收藏状态，请重试';
    const list = element('div', 'action-list');
    list.append(
      actionItem('heart', label, detail, toggleTrackActionFavorite, {
        className: favorite.liked === true ? 'is-liked' : '',
        disabled: favorite.loading || favorite.mutating, busy: favorite.loading || favorite.mutating
      }),
      actionItem('listAdd', '添加到歌单', actions.provider === 'qq' ? '匹配网易云版本后，添加到网易云歌单' : '添加到网易云创建的歌单',
        openTrackActionPlaylists, { chevron: true })
    );
    body.append(list);
  }

  async function loadTrackActionFavorite(actions) {
    const generation = actions.generation;
    actions.favorite = { loading: true, loggedIn: false, liked: null, mutating: false, message: '', retryable: false };
    renderTrackActions();

    let resolution;
    if (actions.target) resolution = { target: actions.target, errorMessage: '', retryable: false };
    else {
      try { resolution = await favoriteTargetFor(actions.track); }
      catch (_) { resolution = { target: null, errorMessage: '\u6682\u65f6\u65e0\u6cd5\u5339\u914d\u7f51\u6613\u4e91\u7248\u672c\uff0c\u65e0\u6cd5\u6536\u85cf\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5', retryable: true }; }
    }
    if (!trackActionsCurrent(generation)) return;
    if (!resolution || !resolution.target) {
      actions.favorite = {
        loading: false, loggedIn: true, liked: null, mutating: false,
        message: resolution && resolution.errorMessage || '\u65e0\u6cd5\u5339\u914d\u5230\u53ef\u6536\u85cf\u7684\u7f51\u6613\u4e91\u7248\u672c',
        retryable: !!(resolution && resolution.retryable)
      };
      renderTrackActions();
      return;
    }
    actions.target = Object.assign({}, resolution.target);

    let response = null;
    try { response = await request('likeStatus', { id: String(actions.target.id) }); } catch (_) {}
    if (!trackActionsCurrent(generation)) return;
    if (response && response.loggedIn === false) {
      actions.favorite = { loading: false, loggedIn: false, liked: false, mutating: false, message: '', retryable: false };
    } else if (response && apiRequestSucceeded(response) && typeof response.liked === 'boolean' && typeof response.loggedIn === 'boolean') {
      actions.favorite = { loading: false, loggedIn: response.loggedIn, liked: response.liked, mutating: false, message: '', retryable: false };
    } else {
      actions.favorite = {
        loading: false, loggedIn: true, liked: null, mutating: false,
        message: favoriteMessage(response, '暂时无法读取网易云收藏状态，请重试'), retryable: true
      };
    }
    renderTrackActions();
  }

  function syncMiniFavorite(key, liked) {
    if (!key || state.favorite.key !== key || key !== favoriteTrackKey(state.media.track) || state.favorite.mutating) return;
    state.favorite.available = true;
    state.favorite.provider = 'netease';
    state.favorite.loggedIn = true;
    state.favorite.liked = liked;
    state.favorite.errorMessage = '';
    state.favorite.retryable = false;
    updateMiniFavoriteButton();
  }

  async function toggleTrackActionFavorite() {
    const actions = trackActions;
    if (!actions) return;
    const favorite = actions.favorite;
    if (favorite.loading || favorite.mutating) return;
    if (favorite.message) {
      showToast(favorite.message);
      if (favorite.retryable) loadTrackActionFavorite(actions);
      return;
    }
    if (!favorite.loggedIn) { promptProviderLogin('netease'); return; }
    if (typeof favorite.liked !== 'boolean' || !actions.target) { loadTrackActionFavorite(actions); return; }

    favorite.mutating = true;
    renderTrackActions();
    const info = favoriteRequest(actions.target, !favorite.liked);
    let response = null;
    try { response = await request(info.method, info.args); } catch (_) {}
    if (!trackActionsCurrent(actions.generation)) return;
    const valid = !!response && typeof response === 'object' && apiRequestSucceeded(response)
      && typeof response.loggedIn === 'boolean' && typeof response.liked === 'boolean';
    if (!valid) showToast(favoriteMessage(response, '暂时无法更新网易云收藏状态'));
    else if (!response.loggedIn) {
      favorite.loggedIn = false;
      favorite.liked = false;
      showToast('登录网易云音乐后可收藏');
    } else {
      syncMiniFavorite(actions.key, response.liked);
      showToast(response.liked ? '已收藏到网易云“我喜欢”' : '已从网易云“我喜欢”取消收藏');
    }
    if (!trackActionsCurrent(actions.generation)) return;
    if (valid && response.loggedIn) { closeTrackActions(); return; }
    favorite.mutating = false;
    if (valid) favorite.liked = response.liked;
    renderTrackActions();
  }
  function openTrackActionPlaylists() {
    const actions = trackActions;
    if (!actions) return;
    actions.view = 'playlists';
    renderTrackActions();
    $('#track-actions-body').scrollTop = 0;
    if (!actions.playlists.loaded && !actions.playlists.loading) loadTrackActionPlaylists(actions);
  }

  function normalizeOwnedPlaylist(item) {
    if (!item || typeof item !== 'object' || !/^\d{1,24}$/.test(String(item.id ?? ''))) return null;
    return { id: String(item.id), name: String(item.name || '未命名歌单'), artwork: safeHttpsUrl(item.artwork), count: Math.max(0, Math.floor(Number(item.count) || 0)) };
  }

  async function loadTrackActionPlaylists(actions) {
    const generation = actions.generation;
    actions.playlists = { loading: true, loaded: false, loggedIn: true, items: [], message: '' };
    renderTrackActions();
    let next;
    try {
      const response = await request('ownedPlaylists', {});
      if (!trackActionsCurrent(generation)) return;
      if (response && response.loggedIn === false) next = { loading: false, loaded: true, loggedIn: false, items: [], message: '' };
      else if (!apiRequestSucceeded(response) || !Array.isArray(response.playlists)) next = { loading: false, loaded: false, loggedIn: true, items: [], message: favoriteMessage(response, '暂时无法读取歌单') };
      else next = { loading: false, loaded: true, loggedIn: true, items: response.playlists.map(normalizeOwnedPlaylist).filter(Boolean), message: '' };
    } catch (_) {
      if (!trackActionsCurrent(generation)) return;
      next = { loading: false, loaded: false, loggedIn: true, items: [], message: '暂时无法读取歌单' };
    }
    if (next.loaded && next.loggedIn && !actions.target) {
      let resolution;
      try { resolution = await favoriteTargetFor(actions.track); }
      catch (_) { resolution = { target: null, retryable: true }; }
      if (!trackActionsCurrent(generation)) return;
      if (resolution && resolution.target) actions.target = Object.assign({}, resolution.target);
      else if (resolution && resolution.retryable) next.matchFailed = true;
      else next.unmatched = true;
    }
    actions.playlists = next;
    renderTrackActions();
  }
  function renderTrackActionPlaylists(body, actions) {
    const heading = element('div', 'action-subheading');
    const back = element('button', 'action-back');
    back.type = 'button';
    setIcon(back, 'back');
    back.append(document.createTextNode('返回'));
    back.addEventListener('click', () => { if (trackActionsCurrent(actions.generation)) { actions.view = 'menu'; renderTrackActions(); } });
    heading.append(back, element('h3', '', '添加到歌单'));
    body.append(heading);
    const note = (text, buttonLabel, onClick, primary) => {
      const box = element('div', 'action-note');
      box.append(element('p', '', text));
      if (buttonLabel) {
        const button = element('button', primary ? 'primary-action' : 'secondary-button', buttonLabel);
        button.type = 'button';
        button.addEventListener('click', onClick);
        box.append(button);
      }
      body.append(box);
    };
    const playlists = actions.playlists;
    const retry = () => loadTrackActionPlaylists(actions);
    if (playlists.loading) { body.append(loadingState(actions.target ? '正在读取你的歌单…' : '正在读取歌单并匹配网易云版本…')); return; }
    if (!playlists.loggedIn) { note('歌单保存在网易云音乐账号里，登录后就能添加。', '网易云音乐登录', () => promptProviderLogin('netease'), true); return; }
    if (!playlists.loaded) { note(playlists.message || '暂时无法读取歌单', '重新加载', retry); return; }
    if (playlists.matchFailed) { note('暂时无法匹配网易云版本，请稍后重试。', '重试', retry); return; }
    if (playlists.unmatched) { note('网易云音乐里没有找到同一版本的这首歌，暂时无法加入网易云歌单。'); return; }
    if (actions.provider === 'qq' && actions.target) {
      body.append(element('p', 'action-match', `将添加网易云版本：${actions.target.title}${actions.target.artist ? ` · ${actions.target.artist}` : ''}`));
    }
    if (!playlists.items.length) { note('你还没有自己创建的歌单，可以先在网易云音乐中新建。', '重新加载', retry); return; }
    const list = element('div', 'action-list');
    playlists.items.forEach(playlist => {
      const row = element('button', 'action-item action-playlist');
      row.type = 'button';
      row.disabled = !!actions.addingId;
      row.setAttribute('aria-label', `添加到歌单 ${playlist.name}`);
      row.append(makeCover(playlist.artwork, ''));
      const copy = element('span', 'action-item-copy');
      copy.append(element('strong', '', playlist.name), element('small', '', `${playlist.count.toLocaleString()} 首歌曲`));
      row.append(copy);
      if (actions.addingId === playlist.id) row.append(element('span', 'spinner'));
      row.addEventListener('click', () => addTrackToPlaylist(playlist));
      list.append(row);
    });
    body.append(list);
  }

  async function addTrackToPlaylist(playlist) {
    const actions = trackActions;
    if (!actions || actions.addingId || !actions.target) return;
    actions.addingId = playlist.id;
    renderTrackActions();
    let response = null;
    try { response = await request('playlistAddTrack', { pid: playlist.id, id: actions.target.id }); } catch (_) { response = null; }
    if (response && response.added === true) {
      showToast(`已添加到“${playlist.name}”`);
      state.libraryLoaded = false;
      if (state.tab === 'library' && !state.playlist && !state.playlistLoading && !state.playlistError) loadLibrary(true);
      if (trackActionsCurrent(actions.generation)) closeTrackActions();
      return;
    }
    if (response && response.duplicate) showToast('这首歌已经在这个歌单里了');
    else if (response && response.loggedIn === false) showToast('请先登录网易云音乐');
    else showToast(favoriteMessage(response, '添加到歌单失败，请稍后重试'));
    if (!trackActionsCurrent(actions.generation)) return;
    actions.addingId = '';
    renderTrackActions();
  }

  function lyricsText(response) {
    const object = response || {};
    const lrc = object.lrc && object.lrc.lyric || object.lyric || '';
    const translated = object.tlyric && object.tlyric.lyric || object.translation || '';
    return window.YPMLyrics ? window.YPMLyrics.parseLrc(lrc, translated) : [];
  }

  function fetchLyricsForCurrent() {
    const track = state.media.track;
    const provider = String(state.media.source || track && track.provider || '').toLowerCase().includes('qq') ? 'qq' : (track && track.provider || 'netease');
    const key = track ? `${provider}:${track.id}:${track.qqMid || ''}` : '';
    if (!key) {
      if (lastLyricsKey || state.lyricsLoading || state.lyrics.length) {
        lastLyricsKey = '';
        state.lyricsGeneration += 1;
        state.lyrics = []; state.lyricsIndex = -2; state.lyricsLoading = false;
        renderLyrics();
      }
      return;
    }
    if (key === lastLyricsKey) return;
    lastLyricsKey = key;
    const generation = ++state.lyricsGeneration;
    state.lyrics = []; state.lyricsIndex = -2; state.lyricsLoading = true;
    renderLyrics();
    const method = provider === 'qq' ? 'qqLyrics' : 'lyrics';
    request(method, { id: String(track.id), mid: track.qqMid || track.id, provider })
      .then(response => {
        if (generation !== state.lyricsGeneration) return;
        state.lyrics = lyricsText(response); state.lyricsLoading = false; state.lyricsIndex = -2; renderLyrics();
      })
      .catch(() => {
        if (generation !== state.lyricsGeneration) return;
        state.lyrics = []; state.lyricsLoading = false; state.lyricsIndex = -2; renderLyrics();
      });
  }

  function renderLyrics() {
    updateMiniSubtitle();
    const list = $('#lyrics-lines');
    if (!list) return;
    list.replaceChildren();
    if (state.lyricsLoading) { list.append(element('p','lyrics-empty','正在获取歌词…')); return; }
    if (!state.lyrics.length) { list.append(element('p','lyrics-empty',state.media.track ? '暂无可同步的歌词' : '播放歌曲后显示歌词')); return; }
    state.lyrics.forEach((line,index) => {
      const button = element('button',`lyric-line${index === state.lyricsIndex ? ' is-active' : ''}`);
      button.type='button'; button.dataset.index=String(index); button.dataset.time=String(line.time);
      button.setAttribute('aria-current',index === state.lyricsIndex ? 'true' : 'false');
      button.append(element('span','',line.text || '…'));
      if (line.translation) button.append(element('span','translation',line.translation));
      button.addEventListener('click', () => sendCommand({type:'seek',position:line.time}));
      list.append(button);
    });
    updateLyricsActive(true);
  }

  function updateLyricsActive(force) {
    const index = state.lyrics.length && window.YPMLyrics ? window.YPMLyrics.activeIndex(state.lyrics,mediaPosition()) : -1;
    updateMiniSubtitle(index);
    if (!state.lyrics.length) return;
    if (index === state.lyricsIndex && !force) return;
    state.lyricsIndex = index;
    $$('.lyric-line', $('#lyrics-lines')).forEach((line,i) => {
      const active = i === index;
      line.classList.toggle('is-active',active);
      line.setAttribute('aria-current',active ? 'true' : 'false');
    });
    if (index >= 0 && state.fullPlayer && state.playerView === 'lyrics' && !playerPanelGesture && performance.now() >= lyricsManualUntil) {
      const active = $('.lyric-line.is-active',$('#lyrics-lines'));
      const list = $('#lyrics-lines');
      if (active && list) {
        const activeTop = active.getBoundingClientRect().top - list.getBoundingClientRect().top + list.scrollTop;
        const viewport = $('#player-scroll').getBoundingClientRect();
        const bounds = list.getBoundingClientRect();
        const top = Math.max(bounds.top, viewport.top);
        const bottom = Math.min(bounds.bottom, viewport.bottom);
        if (bottom - top < 30) return;
        const center = (top + bottom) / 2 - bounds.top;
        const targetTop = Math.max(0, activeTop - center + active.offsetHeight / 2);
        list.scrollTo({top:targetTop,behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});
      }
    }
  }

  function renderQueueWindow(list, dismissOnSelect, signature, force) {
    if (!list) return;
    const queue = state.media.queue || [];
    const scroller = dismissOnSelect ? $('#queue-sheet-content') : list;
    if (!scroller) return;
    const viewportHeight = scroller.clientHeight;
    if (!viewportHeight) return;
    const maxScrollTop = Math.max(0, queue.length * QUEUE_ROW_HEIGHT - viewportHeight);
    const scrollTop = Math.min(maxScrollTop,Math.max(0,scroller.scrollTop || 0));
    if (scroller.scrollTop !== scrollTop) scroller.scrollTop = scrollTop;
    const start = Math.max(0, Math.floor(scrollTop / QUEUE_ROW_HEIGHT) - QUEUE_WINDOW_OVERSCAN);
    const end = Math.min(queue.length, Math.ceil((scrollTop + viewportHeight) / QUEUE_ROW_HEIGHT) + QUEUE_WINDOW_OVERSCAN);
    if (!force && list.dataset.queueSignature === signature && Number(list.dataset.queueStart) === start && Number(list.dataset.queueEnd) === end) return;

    list.replaceChildren();
    list.dataset.queueSignature = signature;
    list.dataset.queueStart = String(start);
    list.dataset.queueEnd = String(end);
    const spacer = height => {
      if (height <= 0) return null;
      const node = element('div', 'queue-window-spacer');
      node.setAttribute('aria-hidden', 'true');
      node.style.height = `${height}px`;
      return node;
    };
    if (!queue.length) {
      list.append(element('p', 'lyrics-empty', '播放列表为空'));
      return;
    }
    const top = spacer(start * QUEUE_ROW_HEIGHT);
    if (top) list.append(top);
    for (let index = start; index < end; index += 1) {
      const track = normalizeTrack(queue[index], queue[index] && queue[index].provider);
      if (!track) continue;
      const row = renderTrackRow(track, queue, index, {
        duration: true,
        onSelect: () => {
          if (dismissOnSelect) closeQueueSheet();
          playTracks(state.media.queue, index);
        }
      });
      row.dataset.queueIndex = String(index);
      if (index === state.media.index) { row.classList.add('is-current'); row.setAttribute('aria-current', 'true'); }
      list.append(row);
    }
    const bottom = spacer(Math.max(0, queue.length - end) * QUEUE_ROW_HEIGHT);
    if (bottom) list.append(bottom);
    scroller.scrollTop = scrollTop;
  }

  function installQueueVirtualization() {
    const attach = (list, dismissOnSelect, scroller) => {
      let frame = 0;
      scroller.addEventListener('scroll', () => {
        if (frame) return;
        frame = requestAnimationFrame(() => {
          frame = 0;
          renderQueueWindow(list, dismissOnSelect, lastQueueSignature, false);
        });
      }, { passive: true });
    };
    attach($('#queue-list'), false, $('#queue-list'));
    attach($('#queue-list-sheet'), true, $('#queue-sheet-content'));
  }

  function renderQueueIfChanged(force) {
    const queue = state.media.queue || [];
    if (queue !== queueSignatureSource) {
      queueSignatureSource = queue;
      queueSignatureValue = queue.map(track => track && track.id != null ? `${track.provider || 'netease'}:${String(track.id)}` : '').join('|');
    }
    const signature = queueSignatureValue;
    const changed = !!force || signature !== lastQueueSignature;
    const previousIndex = lastQueueIndex;
    const indexChanged = state.media.index !== lastQueueIndex;
    if (!changed && !indexChanged) return;
    if (changed) lastQueueSignature = signature;
    renderQueueWindow($('#queue-list'), false, signature, changed);
    renderQueueWindow($('#queue-list-sheet'), true, signature, changed);
    if (!changed && indexChanged) {
      $$('#queue-list .song-row[data-queue-index], #queue-list-sheet .song-row[data-queue-index]').forEach(row => {
        const current = Number(row.dataset.queueIndex) === state.media.index;
        row.classList.toggle('is-current', current);
        if (current) row.setAttribute('aria-current', 'true');
        else row.removeAttribute('aria-current');
      });
    }
    lastQueueIndex = state.media.index;
  }

  function playerExpandedTop() {
    const scroll = $('#player-scroll');
    return $('#player-overview').offsetHeight;
  }

  function syncPlayerExpansion() {
    const scroll = $('#player-scroll');
    const threshold = playerExpandedTop();
    state.playerExpanded = threshold > 0 && scroll.scrollTop >= threshold - 3;
    scroll.classList.toggle('is-expanded',state.playerExpanded);
    $('.player-context').textContent = state.playerExpanded ? (state.playerView === 'queue' ? '播放列表' : '歌词') : '正在播放';
  }

  function settlePlayerOverview(top) {
    const scroll = $('#player-scroll');
    clearTimeout(playerScrollSettleTimer);
    scroll.style.scrollSnapType = 'none';
    scroll.scrollTo({top,behavior:matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'});
    playerScrollSettleTimer = setTimeout(() => {
      scroll.style.scrollSnapType = ''; syncPlayerExpansion(); updateLyricsActive(true);
    },600);
  }

  function collapsePlayerPanels() { settlePlayerOverview(0); }

  function installPlayerExpansion() {
    const scroll = $('#player-scroll');
    let frame = 0;
    const resize = () => {
      const expanded = state.playerExpanded;
      const padding = getComputedStyle(scroll);
      const height = scroll.clientHeight - parseFloat(padding.paddingTop) - parseFloat(padding.paddingBottom)
        - $('#player-view-switch').offsetHeight - 4;
      scroll.style.setProperty('--player-panel-height',`${Math.max(200,height)}px`);
      if (expanded) scroll.scrollTop = playerExpandedTop();
      syncPlayerExpansion();
    };
    scroll.addEventListener('scroll',() => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0; const wasExpanded = state.playerExpanded; syncPlayerExpansion();
        if (!playerPanelGesture && wasExpanded !== state.playerExpanded) updateLyricsActive(true);
      });
    },{passive:true});
    if (window.ResizeObserver) new ResizeObserver(resize).observe(scroll);
    window.addEventListener('resize',resize);
    const overview = $('#player-overview');
    const tabs = $('#player-view-switch');
    let gesture = null;
    let suppressClickUntil = 0;
    const finishOverview = (event,cancelled) => {
      if (!gesture || gesture.pointerId !== event.pointerId) return;
      const current = gesture; gesture = null;
      if (current.host.hasPointerCapture(event.pointerId)) current.host.releasePointerCapture(event.pointerId);
      if (current.axis !== 'vertical') return;
      suppressClickUntil = performance.now() + 400;
      const velocity = gestureVelocity(current.samples,'y');
      const distance = scroll.scrollTop-current.scrollTop;
      const expanded = cancelled ? current.expanded : current.expanded
        ? !(distance < -72 || (distance < -8 && velocity > 650))
        : distance > 72 || (distance > 8 && velocity < -650);
      settlePlayerOverview(expanded ? playerExpandedTop() : 0);
    };
    [overview,tabs].forEach(host => {
      host.addEventListener('pointerdown',event => {
        if (!state.fullPlayer || event.isPrimary === false) return;
        if (event.clientX < 20 || event.clientX > window.innerWidth - 20) return;
        if (host === overview && event.target.closest('button,input')) return;
        if (event.button !== undefined && event.button !== 0) return;
        suppressClickUntil = 0;
        gesture = {host,pointerId:event.pointerId,axis:'pending',startX:event.clientX,startY:event.clientY,
          scrollTop:scroll.scrollTop,expanded:state.playerExpanded,samples:[{x:event.clientX,y:event.clientY,t:performance.now()}]};
      });
      host.addEventListener('pointermove',event => {
        if (!gesture || gesture.pointerId !== event.pointerId) return;
        const dx = event.clientX-gesture.startX; const dy = event.clientY-gesture.startY;
        if (gesture.axis === 'pending') {
          if (Math.max(Math.abs(dx),Math.abs(dy)) < 9) return;
          if (Math.abs(dx) > Math.abs(dy)*1.15) { gesture = null; return; }
          if (Math.abs(dy) <= Math.abs(dx)*1.15) return;
          gesture.axis = 'vertical';
          clearTimeout(playerScrollSettleTimer); scroll.style.scrollSnapType = 'none';
          host.setPointerCapture(event.pointerId);
        }
        event.preventDefault(); sampleGesture(gesture,event.clientX,event.clientY);
        scroll.scrollTop = Math.max(0,Math.min(playerExpandedTop(),gesture.scrollTop-dy));
      },{passive:false});
      host.addEventListener('pointerup',event => finishOverview(event,false));
      host.addEventListener('pointercancel',event => finishOverview(event,true));
      host.addEventListener('lostpointercapture',event => { if (event.target === host && gesture) finishOverview(event,true); });
      host.addEventListener('click',event => {
        if (performance.now() < suppressClickUntil) { event.preventDefault(); event.stopImmediatePropagation(); }
      },true);
    });
    resize();
  }

  function playerPanelTarget(view) {
    return view === 'queue' ? -$('#player-pager').clientWidth : 0;
  }

  function updatePlayerPanelSelection(view) {
    state.playerView = view;
    syncPlayerExpansion();
    $('#player-main').classList.toggle('view-lyrics', view === 'lyrics');
    $('#player-main').classList.toggle('view-queue', view === 'queue');
    $$('.view-switch').forEach(button => {
      const selected = button.dataset.playerView === view;
      button.classList.toggle('is-selected', selected);
      button.setAttribute('aria-selected', String(selected));
    });
    ['lyrics','queue'].forEach(name => {
      const panel = $(`#${name}-panel`);
      panel.hidden = false;
      panel.inert = name !== view;
      panel.setAttribute('aria-hidden', String(name !== view));
    });
  }

  function resetPlayerPanels() {
    playerPanelGeneration += 1;
    const gesture = playerPanelGesture;
    playerPanelGesture = null;
    const pager = $('#player-pager');
    if (gesture && pager.hasPointerCapture && pager.hasPointerCapture(gesture.pointerId)) pager.releasePointerCapture(gesture.pointerId);
    const track = $('#player-pager-track');
    cancelSpring(track);
    setMotionPosition(track, playerPanelTarget(state.playerView), 0);
    $('#player-pager').classList.remove('is-moving','is-dragging');
    updatePlayerPanelSelection(state.playerView);
    renderQueueWindow($('#queue-list'), false, lastQueueSignature, false);
  }

  function setPlayerView(view, options) {
    const next = view === 'queue' ? 'queue' : 'lyrics';
    const settings = options || {};
    const pager = $('#player-pager'); const track = $('#player-pager-track');
    const generation = ++playerPanelGeneration;
    const current = cancelSpring(track);
    playerPanelGesture = null;
    pager.classList.remove('is-dragging');
    updatePlayerPanelSelection(next);
    renderQueueWindow($('#queue-list'), false, lastQueueSignature, false);
    const target = playerPanelTarget(next);
    const finish = () => {
      if (generation !== playerPanelGeneration) return;
      pager.classList.remove('is-moving');
      if (next === 'lyrics') updateLyricsActive(true);
    };
    if (!state.fullPlayer || settings.instant) {
      setMotionPosition(track, target, 0);
      finish();
      return;
    }
    pager.classList.add('is-moving');
    const velocity = settings.velocity === undefined ? current.vx : settings.velocity;
    springTo(track, target, 0, tabSpringVelocity(velocity,current.x,target), 0, finish);
  }

  function installPlayerPanelSwipe() {
    const pager = $('#player-pager'); const track = $('#player-pager-track');
    const resumeLyricsLater = () => {
      clearTimeout(lyricsFollowTimer);
      lyricsFollowTimer = setTimeout(() => { lyricsManualUntil = 0; updateLyricsActive(true); }, 4200);
    };
    pager.addEventListener('pointerdown', event => {
      if (!state.fullPlayer || event.isPrimary === false || (event.button !== undefined && event.button !== 0)) return;
      // Keep the physical screen edges available for the Android back gesture.
      if (event.clientX < 20 || event.clientX > window.innerWidth - 20) return;
      playerPanelClickUntil = 0; playerPanelClickTarget = null;
      const current = cancelSpring(track);
      playerPanelGeneration += 1;
      playerPanelGesture = { pointerId:event.pointerId, axis:'pending', startX:event.clientX, startY:event.clientY,
        origin:state.playerView, startTarget:event.target, expanded:state.playerExpanded, canCollapse:event.clientY <= pager.getBoundingClientRect().top + 64, outerScroll:$('#player-scroll').scrollTop, innerScroll:$('#lyrics-lines').scrollTop, offsetX:current.x, x:current.x, width:pager.clientWidth,
        samples:[{x:event.clientX,y:event.clientY,t:performance.now()}] };
    });
    pager.addEventListener('pointermove', event => {
      const gesture = playerPanelGesture;
      if (!gesture || gesture.pointerId !== event.pointerId) return;
      const dx = event.clientX - gesture.startX; const dy = event.clientY - gesture.startY;
      if (gesture.axis === 'pending') {
        if (Math.max(Math.abs(dx),Math.abs(dy)) < 9) return;
        if (Math.abs(dy) > Math.abs(dx) * 1.15) {
          gesture.axis = 'vertical';
          lyricsManualUntil = performance.now() + 4000;
          if (!gesture.expanded || gesture.origin === 'lyrics') {
            clearTimeout(playerScrollSettleTimer);
            $('#player-scroll').style.scrollSnapType = 'none';
            if (pager.setPointerCapture) pager.setPointerCapture(event.pointerId);
          } else return;
        }
        if (gesture.axis === 'pending' && Math.abs(dx) <= Math.abs(dy) * 1.15) return;
        if (gesture.axis === 'pending') gesture.axis = 'horizontal';
        if (pager.setPointerCapture) pager.setPointerCapture(event.pointerId);
        if (gesture.axis === 'horizontal') pager.classList.add('is-moving','is-dragging');
        // Both real panels remain mounted; scroll positions and virtual rows survive.
        $('#lyrics-panel').inert = false; $('#queue-panel').inert = false;
        renderQueueWindow($('#queue-list'), false, lastQueueSignature, false);
      }
      if (gesture.axis === 'vertical') {
        lyricsManualUntil = performance.now() + 4000;
        if (!gesture.expanded || gesture.origin === 'lyrics') {
          event.preventDefault();
          sampleGesture(gesture,event.clientX,event.clientY);
          if (!gesture.expanded) $('#player-scroll').scrollTop = Math.max(0,Math.min(playerExpandedTop(),gesture.outerScroll - dy));
          else if (gesture.canCollapse) {
            // Reversing a pull restores the outer page before scrolling lyrics.
            $('#player-scroll').scrollTop = Math.max(0,Math.min(playerExpandedTop(),gesture.outerScroll - Math.max(0,dy)));
            $('#lyrics-lines').scrollTop = gesture.innerScroll - Math.min(0,dy);
          } else $('#lyrics-lines').scrollTop = gesture.innerScroll - dy;
        }
        return;
      }
      event.preventDefault();
      const raw = gesture.offsetX + dx;
      const edge = raw < -gesture.width ? -gesture.width : 0;
      const excess = raw - edge;
      gesture.x = raw < -gesture.width || raw > 0
        ? edge + (excess * gesture.width * .3) / (gesture.width + .3 * Math.abs(excess)) : raw;
      sampleGesture(gesture,event.clientX,event.clientY);
      setMotionPosition(track,gesture.x,0);
    }, { passive:false });
    const end = (event,cancelled) => {
      const gesture = playerPanelGesture;
      if (!gesture || gesture.pointerId !== event.pointerId) return;
      playerPanelGesture = null;
      if (pager.hasPointerCapture && pager.hasPointerCapture(event.pointerId)) pager.releasePointerCapture(event.pointerId);
      if (gesture.axis !== 'horizontal') {
        setPlayerView(gesture.origin);
        if (gesture.axis === 'vertical') {
          if (!gesture.expanded || gesture.origin === 'lyrics') {
            playerPanelClickUntil = performance.now() + 400; playerPanelClickTarget = gesture.startTarget;
            const velocity = gestureVelocity(gesture.samples,'y');
            const pulled = gesture.outerScroll - $('#player-scroll').scrollTop;
            if (!gesture.expanded) settlePlayerOverview(!cancelled && (-pulled > 72 || (-pulled > 8 && velocity < -650)) ? playerExpandedTop() : 0);
            else if (gesture.canCollapse && !cancelled && (pulled > 72 || (pulled > 8 && velocity > 650))) settlePlayerOverview(0);
            else {
              settlePlayerOverview(playerExpandedTop());
              if (!cancelled && Math.abs(velocity) > 50 && (!gesture.canCollapse || velocity < 0)) $('#lyrics-lines').scrollTo({top:$('#lyrics-lines').scrollTop - projectedTravel(velocity),behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});
            }
          }
          resumeLyricsLater();
        }
        return;
      }
      playerPanelClickUntil = performance.now() + 400;
      playerPanelClickTarget = gesture.startTarget;
      const velocity = performance.now() - gesture.samples[gesture.samples.length-1].t < 110
        ? gestureVelocity(gesture.samples,'x') : 0;
      const projected = gesture.x + projectedTravel(velocity);
      const next = cancelled ? gesture.origin : projected < -gesture.width / 2 ? 'queue' : 'lyrics';
      setPlayerView(next,{velocity:cancelled ? 0 : velocity});
    };
    pager.addEventListener('pointerup',event => end(event,false));
    pager.addEventListener('pointercancel',event => end(event,true));
    pager.addEventListener('lostpointercapture',event => { if (event.target === pager && playerPanelGesture) end(event,true); });
    pager.addEventListener('click',event => {
      if (performance.now() < playerPanelClickUntil && playerPanelClickTarget && (event.target === playerPanelClickTarget || playerPanelClickTarget.contains(event.target))) { event.preventDefault(); event.stopImmediatePropagation(); }
    },true);
    $('#lyrics-lines').addEventListener('wheel',() => {
      lyricsManualUntil = performance.now() + 4000; resumeLyricsLater();
    },{passive:true});
    if (window.ResizeObserver) new ResizeObserver(() => { resetPlayerPanels(); updateLyricsActive(true); }).observe(pager);
    window.addEventListener('blur',resetPlayerPanels);
  }

  function normalizeMediaState(raw) {
    const value = raw && typeof raw === 'object' ? raw : {};
    const track = normalizeTrack(value.track, value.track && value.track.provider);
    const queue = mediaQueue(value);
    return {
      track, queue, index: Number.isFinite(Number(value.index)) ? Number(value.index) : -1,
      playing: !!value.playing, buffering: !!value.buffering,
      position: Math.max(0,Number(value.position)||0), duration: Math.max(0,Number(value.duration)||0),
      source: String(value.source||''), shuffle: !!value.shuffle, repeat: String(value.repeat||'off'),
      error: value.error ? true : null, updatedAt: Number(value.updatedAt) || 0
    };
  }

  function mediaQueue(value) {
    // Saved or preview state still embeds the queue.
    if (Array.isArray(value.queue)) return value.queue.map(item => normalizeTrack(item,item && item.provider)).filter(Boolean);
    if (value.queueVersion === undefined || value.queueVersion === mediaQueueCache.version) return mediaQueueCache.queue;
    let payload = null;
    try { payload = typeof window.Native.getQueue === 'function' ? parseNativeObject(window.Native.getQueue(), null) : null; } catch (_) { payload = null; }
    if (!payload || !Array.isArray(payload.queue)) return mediaQueueCache.queue;
    mediaQueueCache = { version: payload.version, queue: payload.queue.map(item => normalizeTrack(item,item && item.provider)).filter(Boolean) };
    return mediaQueueCache.queue;
  }

  let lastProcessedMediaJson = null;

  function pollMediaState() {
    if (!nativeReady() || !window.Native || typeof window.Native.getState !== 'function') return;
    try {
      const rawState = window.Native.getState();
      if (typeof rawState === 'string' && rawState === lastProcessedMediaJson && !pendingPlayerModes) {
        if (state.media.playing) { updateProgress(); updateLyricsActive(); }
        return;
      }
      const next = normalizeMediaState(parseNativeObject(rawState,{}));
      applyPendingPlayerModes(next);
      const oldTrack = state.media.track;
      const trackKey = next.track && `${next.source}:${next.track.id}:${next.track.provider}`;
      const oldKey = oldTrack && `${state.media.source}:${oldTrack.id}:${oldTrack.provider}`;
      const newSuccessfulTrack = !!next.playing && (!state.media.playing || trackKey !== oldKey);
      const miniTransition = animateMiniTrackHandoff(trackKey);
      state.media = next;
      if (trackKey !== oldKey) fetchLyricsForCurrent();
      updatePlayerChrome();
      finishMiniTrackHandoff(miniTransition);
      lastProcessedMediaJson = typeof rawState === 'string' ? rawState : null;
      if (newSuccessfulTrack && state.tab === 'library' && !state.playlist && !state.playlistLoading && !state.playlistError) loadRecentHistory(true, 20);
    } catch (_) { /* Device bridge may be between activity transitions. */ }
  }

  function openSettingsFromPlayer() { openAccount(); }

  function onSeek() {
    const slider = $('#seek-slider');
    slider.addEventListener('input', () => {
      seekDragging = true;
      const duration = Math.max(1,state.media.duration || state.media.track && state.media.track.duration || 1);
      const value = Number(slider.value); slider.style.setProperty('--progress',`${value/duration*100}%`);
      $('#elapsed-time').textContent = formatTime(value);
      $('#remaining-time').textContent = `−${formatTime(Math.max(0,duration-value))}`;
    });
    slider.addEventListener('change', () => {
      seekDragging = false;
      const position = Number(slider.value);
      sendCommand({type:'seek',position});
      state.media = Object.assign({}, state.media, { position, updatedAt: Date.now() });
    });
  }

  function installSheetGesture() {
    const handle = $('#player-grab-area'); const sheet = $('#player-sheet');
    let gesture = null;
    handle.addEventListener('pointerdown', event => {
      if (event.button !== undefined && event.button !== 0) return;
      if (event.target && typeof event.target.closest === 'function' && event.target.closest('button')) return;
      if (!state.fullPlayer) return;
      const current = cancelSpring(sheet);
      gesture = { pointerId:event.pointerId, axis:'pending', startX:event.clientX, startY:event.clientY, offsetY:current.y, y:current.y, samples:[{x:event.clientX,y:event.clientY,t:performance.now()}] };
    });
    handle.addEventListener('pointermove', event => {
      if (!gesture || gesture.pointerId !== event.pointerId) return;
      const dx = event.clientX - gesture.startX; const dy = event.clientY - gesture.startY;
      if (gesture.axis === 'pending') {
        if (Math.max(Math.abs(dx),Math.abs(dy)) < 10) return;
        if (Math.abs(dy) > Math.abs(dx) * 1.12) {
          gesture.axis = 'vertical';
          if (handle.setPointerCapture) handle.setPointerCapture(event.pointerId);
        } else if (Math.abs(dx) > Math.abs(dy) * 1.12) {
          gesture = null;
          const current = cancelSpring(sheet);
          springTo(sheet,0,0,current.vx,current.vy);
          return;
        } else return;
      }
      if (gesture.axis !== 'vertical') return;
      event.preventDefault();
      gesture.y = Math.max(0,gesture.offsetY + dy);
      sampleGesture(gesture,event.clientX,event.clientY);
      setMotionPosition(sheet,0,gesture.y);
      $('#player-scrim').style.opacity = String(Math.max(0,1-gesture.y/Math.max(1,sheet.offsetHeight)));
    },{passive:false});
    const end = (event,cancelled) => {
      if (!gesture || gesture.pointerId !== event.pointerId) return;
      const current = gesture; gesture = null;
      if (current.axis !== 'vertical') {
        const position = cancelSpring(sheet);
        springTo(sheet,0,0,position.vx,position.vy,() => { $('#player-scrim').style.opacity = ''; });
        return;
      }
      const velocity = gestureVelocity(current.samples,'y');
      const projected = current.y + Math.max(0,velocity)*.16;
      const threshold = Math.min(170,window.innerHeight*.21);
      if (!cancelled && (projected > threshold || velocity > 900)) {
        closePlayer(velocity);
      } else {
        springTo(sheet,0,0,0,Math.min(0,velocity),() => { $('#player-scrim').style.opacity = ''; });
      }
    };
    handle.addEventListener('pointerup',event => end(event,false));
    handle.addEventListener('pointercancel',event => end(event,true));
  }

  function installSheetDrag(handle, sheet, scrim, isOpen, close) {
    let gesture = null;
    handle.addEventListener('pointerdown',event => {
      if (event.button !== undefined && event.button !== 0) return;
      if (!isOpen() || event.target.closest('button')) return;
      const current = cancelSpring(sheet);
      gesture = { pointerId:event.pointerId, axis:'pending', startX:event.clientX, startY:event.clientY, offsetY:current.y, y:current.y, samples:[{x:event.clientX,y:event.clientY,t:performance.now()}] };
    });
    handle.addEventListener('pointermove',event => {
      if (!gesture || gesture.pointerId !== event.pointerId) return;
      const dx = event.clientX-gesture.startX; const dy = event.clientY-gesture.startY;
      if (gesture.axis === 'pending') {
        if (Math.max(Math.abs(dx),Math.abs(dy)) < 10) return;
        if (Math.abs(dy) > Math.abs(dx)*1.12) {
          gesture.axis = 'vertical';
          if (handle.setPointerCapture) handle.setPointerCapture(event.pointerId);
        } else if (Math.abs(dx) > Math.abs(dy)*1.12) {
          gesture = null;
          const current = cancelSpring(sheet);
          springTo(sheet,0,0,current.vx,current.vy);
          return;
        } else return;
      }
      if (gesture.axis !== 'vertical') return;
      event.preventDefault();
      gesture.y = Math.max(0,gesture.offsetY+dy);
      sampleGesture(gesture,event.clientX,event.clientY);
      setMotionPosition(sheet,0,gesture.y);
      scrim.style.opacity = String(Math.max(0,1-gesture.y/Math.max(1,sheet.offsetHeight)));
    },{passive:false});
    const end = (event,cancelled) => {
      if (!gesture || gesture.pointerId !== event.pointerId) return;
      const currentGesture = gesture; gesture = null;
      if (currentGesture.axis !== 'vertical') {
        const position = cancelSpring(sheet);
        springTo(sheet,0,0,position.vx,position.vy,() => { scrim.style.opacity = ''; });
        return;
      }
      const velocity = gestureVelocity(currentGesture.samples,'y');
      const projected = currentGesture.y + Math.max(0,velocity)*.16;
      const threshold = Math.min(170,window.innerHeight*.21);
      if (!cancelled && (projected > threshold || velocity > 900)) close(velocity);
      else springTo(sheet,0,0,0,Math.min(0,velocity),() => { scrim.style.opacity = ''; });
    };
    handle.addEventListener('pointerup',event => end(event,false));
    handle.addEventListener('pointercancel',event => end(event,true));
  }

  function installTabSwipe() {
    const content = $('#tab-content');
    const surface = $('#app');
    let suppressClickUntil = 0;
    let suppressClickTarget = null;
    const blockedTarget = target => !!(target && target.closest && target.closest(
      'input,textarea,select,[contenteditable="true"],.card-rail,.recent-track-rail,.search-tabs,.search-source-row,.mini-player,.player-overlay,.sheet-overlay,.tab-bar,.topbar,[data-swipe-exclude]'
    ));
    const inGestureArea = event => {
      const rect = content.getBoundingClientRect();
      const shell = surface.getBoundingClientRect();
      let bottom = rect.bottom;
      ['#mini-player','.tab-bar'].forEach(selector => {
        const overlay = $(selector);
        if (!overlay || overlay.hidden || getComputedStyle(overlay).display === 'none') return;
        bottom = Math.min(bottom,overlay.getBoundingClientRect().top);
      });
      return event.clientX >= shell.left && event.clientX <= shell.right && event.clientY >= rect.top && event.clientY < bottom;
    };
    const settleHome = (velocityX) => {
      const current = motionPosition(content);
      springTo(content, 0, 0, tabSpringVelocity(velocityX,current.x,0), 0, () => {
        content.style.opacity = '1';
        content.style.willChange = '';
        tabTransitionInFlight = false;
      });
    };
    surface.addEventListener('pointerdown', event => {
      if (event.button !== undefined && event.button !== 0) return;
      if (event.isPrimary === false || state.fullPlayer || state.queueSheetOpen || $('#account-overlay').classList.contains('is-open')) return;
      if (state.playlist || state.playlistLoading || state.playlistError || $('#app').classList.contains('keyboard-open')) return;
      if (nativeKeyboardVisible !== false && document.activeElement && document.activeElement.matches('input,textarea,select,[contenteditable="true"]')) return;
      if (blockedTarget(event.target) || !inGestureArea(event)) return;
      tabTransitionGeneration += 1;
      tabTransitionInFlight = false;
      removeTabGhosts();
      const current = cancelSpring(content);
      content.style.opacity = '1';
      tabGesture = {
        pointerId:event.pointerId, axis:'pending', startX:event.clientX, startY:event.clientY,
        offsetX:current.x, x:current.x, y:0, startTarget:event.target, samples:[{x:event.clientX,y:event.clientY,t:performance.now()}]
      };
      content.style.willChange = 'transform, opacity';
    });
    surface.addEventListener('pointermove', event => {
      if (!tabGesture || tabGesture.pointerId !== event.pointerId) return;
      const dx = event.clientX - tabGesture.startX; const dy = event.clientY - tabGesture.startY;
      if (tabGesture.axis === 'pending') {
        if (Math.max(Math.abs(dx),Math.abs(dy)) < 10) return;
        if (Math.abs(dx) > Math.abs(dy) * 1.12) {
          tabGesture.axis = 'horizontal';
          if (surface.setPointerCapture) surface.setPointerCapture(event.pointerId);
        } else if (Math.abs(dy) > Math.abs(dx) * 1.12) {
          tabGesture = null;
          settleHome(0);
          return;
        } else return;
      }
      if (tabGesture.axis !== 'horizontal') return;
      event.preventDefault();
      const width = Math.max(content.clientWidth, window.innerWidth || 1);
      const index = tabOrder.indexOf(state.tab);
      let x = tabGesture.offsetX + dx;
      const atBoundary = (x > 0 && index === 0) || (x < 0 && index === tabOrder.length - 1);
      if (atBoundary) {
        const sign = Math.sign(x); const distance = Math.abs(x);
        x = sign * (width * 0.18) * (1 - Math.exp(-distance / (width * 0.18)));
      } else x = Math.max(-width * 1.08,Math.min(width * 1.08,x));
      tabGesture.x = x;
      sampleGesture(tabGesture,event.clientX,event.clientY);
      setMotionPosition(content,x,0);
      content.style.opacity = String(1 - Math.min(0.09,Math.abs(x) / width * 0.09));
    }, { passive:false });
    const finish = (event, cancelled) => {
      if (!tabGesture || tabGesture.pointerId !== event.pointerId) return;
      const gesture = tabGesture; tabGesture = null;
      const dx = event.clientX - gesture.startX;
      const velocityX = gestureVelocity(gesture.samples,'x');
      if (cancelled || gesture.axis !== 'horizontal') {
        settleHome(0);
        return;
      }
      if (Math.abs(dx) > 10) {
        suppressClickUntil = Date.now() + 450;
        suppressClickTarget = gesture.startTarget;
      }
      const index = tabOrder.indexOf(state.tab);
      const width = Math.max(content.clientWidth, window.innerWidth || 1);
      // The in-flight page offset is visual continuity, not user input.
      const projected = dx + projectedTravel(velocityX,0.994);
      const threshold = Math.max(64,width * 0.23);
      const direction = projected < -threshold ? 1 : projected > threshold ? -1 : 0;
      const destinationIndex = index + direction;
      if (direction && destinationIndex >= 0 && destinationIndex < tabOrder.length) {
        navigateTab(tabOrder[destinationIndex],direction,velocityX);
      } else settleHome(velocityX);
      setTimeout(() => {
        if (Date.now() >= suppressClickUntil) {
          suppressClickUntil = 0;
          suppressClickTarget = null;
        }
      },470);
      event.preventDefault();
    };
    surface.addEventListener('pointerup', event => finish(event,false));
    surface.addEventListener('pointercancel', event => finish(event,true));
    content.addEventListener('scroll', () => {
      if (!tabTransitionInFlight && !state.playlist && !state.playlistLoading && !state.playlistError) state.tabScrollTop[state.tab] = content.scrollTop;
    }, { passive:true });
    surface.addEventListener('click', event => {
      if (Date.now() >= suppressClickUntil) {
        suppressClickUntil = 0;
        suppressClickTarget = null;
        return;
      }
      const target = event.target;
      const start = suppressClickTarget;
      const targetGroup = target && target.closest ? (target.closest('button,a,.song-row') || target) : target;
      const startGroup = start && start.closest ? (start.closest('button,a,.song-row') || start) : start;
      if (target !== surface && targetGroup !== startGroup) return;
      suppressClickUntil = 0;
      suppressClickTarget = null;
      event.preventDefault(); event.stopImmediatePropagation();
    }, true);
  }

  function removeMiniGhosts() {
    $$('.mini-track-content--ghost',$('#mini-track-viewport')).forEach(node => {
      cancelSpring(node);
      cancelMiniOpacity(node,1);
      node.remove();
    });
  }

  function springMiniHome(content,position) {
    if (!content) return;
    const generation = miniHandoffGeneration;
    const body = document.body;
    const masked = body.classList.contains('mini-track-in-motion') || Math.abs(position.x) > .5 || Math.abs(position.vx) > 10;
    if (masked) body.classList.add('mini-track-in-motion');
    springTo(content,0,0,position.vx,position.vy,() => {
      if (generation !== miniHandoffGeneration) return;
      content.style.opacity = '1';
      if (masked) body.classList.remove('mini-track-in-motion');
    });
  }

  function cancelMiniHandoff(restore) {
    const pendingHandoff = pendingMiniHandoff;
    if (pendingHandoff && pendingHandoff.timeout) clearTimeout(pendingHandoff.timeout);
    pendingMiniHandoff = null;
    miniHandoffGeneration += 1;
    removeMiniGhosts();
    const content = $('#mini-track-content');
    const current = cancelSpring(content);
    if (content) {
      cancelMiniOpacity(content,1);
      if (restore) springMiniHome(content,current);
      else document.body.classList.remove('mini-track-in-motion');
    }
  }

  function beginMiniTrackHandoff(direction,velocityX) {
    const content = $('#mini-track-content'); const viewport = $('#mini-track-viewport');
    if (!content || !viewport || !state.media.track) return;
    cancelMiniHandoff(false);
    const oldTrack = state.media.track;
    const oldKey = `${state.media.source}:${oldTrack.id}:${oldTrack.provider}`;
    const generation = ++miniHandoffGeneration;
    const pendingHandoff = { oldKey, direction, velocityX:Number(velocityX)||0, timeout:0 };
    pendingMiniHandoff = pendingHandoff;
    document.body.classList.add('mini-track-in-motion');
    const visualVelocity = Math.max(-500,Math.min(500,(Number(velocityX)||0)*MINI_TRACK_DRAG_FACTOR));
    springTo(content,direction*MINI_TRACK_TRAVEL_LIMIT,0,visualVelocity,0);
    pendingHandoff.timeout = setTimeout(() => {
      if (pendingMiniHandoff !== pendingHandoff) return;
      pendingMiniHandoff = null;
      removeMiniGhosts();
      const position = cancelSpring(content);
      cancelMiniOpacity(content,1);
      springMiniHome(content,{...position,vy:0});
    },1800);
    sendCommand(direction < 0 ? {type:'next'} : {type:'previous',skipRestart:true});
    requestAnimationFrame(pollMediaState);
    setTimeout(pollMediaState,55);
    setTimeout(pollMediaState,125);
  }

  function cloneMiniGhost(position, opacity) {
    const viewport = $('#mini-track-viewport'); const content = $('#mini-track-content');
    if (!viewport || !content) return null;
    const ghost = content.cloneNode(true);
    ghost.removeAttribute('id');
    ghost.querySelectorAll('[id]').forEach(node => node.removeAttribute('id'));
    ghost.classList.add('mini-track-content--ghost');
    ghost.setAttribute('aria-hidden','true');
    ghost.style.opacity = String(opacity);
    ghost.querySelectorAll('img').forEach(image => { image.alt=''; image.draggable=false; });
    viewport.append(ghost);
    setMotionPosition(ghost,position.x,position.y);
    return ghost;
  }

  function animateMiniTrackHandoff(nextTrackKey) {
    const pendingHandoff = pendingMiniHandoff;
    if (!pendingHandoff || !nextTrackKey || nextTrackKey === pendingHandoff.oldKey) return null;
    clearTimeout(pendingHandoff.timeout);
    pendingMiniHandoff = null;
    const generation = ++miniHandoffGeneration;
    const content = $('#mini-track-content');
    const oldPosition = cancelSpring(content);
    const oldOpacity = cancelMiniOpacity(content);
    const ghost = cloneMiniGhost(oldPosition,oldOpacity);
    setMotionPosition(content,-pendingHandoff.direction*MINI_TRACK_HANDOFF_DISTANCE,0);
    content.style.opacity='0';
    return { ghost, oldPosition, pendingHandoff, generation };
  }

  function finishMiniTrackHandoff(transition) {
    if (!transition || transition.generation !== miniHandoffGeneration) return;
    const {ghost,oldPosition,pendingHandoff,generation} = transition;
    const content = $('#mini-track-content');
    const incomingVelocity = Math.max(-500,Math.min(500,pendingHandoff.velocityX*MINI_TRACK_DRAG_FACTOR));
    springTo(content,0,0,incomingVelocity,0,() => {
      if (generation !== miniHandoffGeneration) return;
      content.style.opacity='1';
      document.body.classList.remove('mini-track-in-motion');
    });
    fadeMiniOpacity(content,1,generation);
    if (ghost) {
      const outgoingVelocity = Math.max(-500,Math.min(500,oldPosition.vx*MINI_TRACK_DRAG_FACTOR));
      springTo(ghost,pendingHandoff.direction*MINI_TRACK_TRAVEL_LIMIT,0,outgoingVelocity,0);
      fadeMiniOpacity(ghost,0,generation,() => {
        if (generation !== miniHandoffGeneration) return;
        cancelSpring(ghost);
        ghost.remove();
      });
    }
  }

  function installMiniSwipe() {
    const container = $('#mini-player'); const button = $('#mini-open'); const content = $('#mini-track-content');
    let gesture = null;
    let suppressClickUntil = 0;
    container.addEventListener('pointerdown',event => {
      if (event.button !== undefined && event.button !== 0) return;
      if (event.isPrimary === false) return;
      if (!state.media.track) return;
      // A prior horizontal gesture may not synthesize click after pointer capture.
      // A fresh down means any late click from that gesture is no longer relevant.
      suppressClickUntil = 0;
      cancelMiniHandoff(true);
      const current = cancelSpring(content);
      const startOffset = Math.max(-MINI_TRACK_TRAVEL_LIMIT,Math.min(MINI_TRACK_TRAVEL_LIMIT,current.x));
      gesture = {pointerId:event.pointerId,axis:'pending',startX:event.clientX,startY:event.clientY,offsetX:startOffset,x:startOffset,samples:[{x:event.clientX,y:event.clientY,t:performance.now()}]};
    });
    container.addEventListener('pointermove',event => {
      if (!gesture || gesture.pointerId !== event.pointerId) return;
      const dx = event.clientX-gesture.startX; const dy = event.clientY-gesture.startY;
      if (gesture.axis === 'pending') {
        if (Math.max(Math.abs(dx),Math.abs(dy)) < 10) return;
        if (Math.abs(dx) > Math.abs(dy)*1.12) {
          gesture.axis = 'horizontal';
          document.body.classList.add('mini-swipe-active');
          document.body.classList.add('mini-track-in-motion');
          if (container.setPointerCapture) container.setPointerCapture(event.pointerId);
        } else if (Math.abs(dy) > Math.abs(dx)*1.12) {
          gesture = null;
          const current = cancelSpring(content);
          springMiniHome(content,current);
          return;
        } else return;
      }
      if (gesture.axis !== 'horizontal') return;
      event.preventDefault();
      gesture.x = Math.max(-MINI_TRACK_TRAVEL_LIMIT,Math.min(MINI_TRACK_TRAVEL_LIMIT,gesture.offsetX+dx*MINI_TRACK_DRAG_FACTOR));
      sampleGesture(gesture,event.clientX,event.clientY);
      setMotionPosition(content,gesture.x,0);
    },{passive:false});
    const end = (event,cancelled) => {
      if (!gesture || gesture.pointerId !== event.pointerId) return;
      const currentGesture = gesture; gesture = null;
      document.body.classList.remove('mini-swipe-active');
      if (currentGesture.axis !== 'horizontal') {
        const current = cancelSpring(content);
        cancelMiniOpacity(content,1);
        springMiniHome(content,current);
        return;
      }
      const dx = event.clientX-currentGesture.startX;
      const velocityX = gestureVelocity(currentGesture.samples,'x');
      if (Math.abs(dx) > 10) {
        suppressClickUntil = Date.now()+480;
        setTimeout(() => { if (Date.now() >= suppressClickUntil) suppressClickUntil = 0; },500);
      }
      if (cancelled) {
        const current = cancelSpring(content);
        cancelMiniOpacity(content,1);
        springMiniHome(content,current);
        return;
      }
      const width = Math.max(1,$('#mini-track-viewport').clientWidth);
      // Preserve the in-flight offset for the visual drag, but decide direction
      // from this gesture alone so interrupting an entrance never biases intent.
      const projected = dx+projectedTravel(velocityX,.992);
      const threshold = Math.max(48,width*.24);
      const direction = projected < -threshold ? -1 : projected > threshold ? 1 : 0;
      if (direction) beginMiniTrackHandoff(direction,velocityX);
      else {
        const current = cancelSpring(content);
        cancelMiniOpacity(content,1);
        springMiniHome(content,current);
      }
      event.preventDefault();
    };
    container.addEventListener('pointerup',event => end(event,false));
    container.addEventListener('pointercancel',event => end(event,true));
    container.addEventListener('click',event => {
      if (Date.now() >= suppressClickUntil) return;
      suppressClickUntil = 0;
      event.preventDefault(); event.stopImmediatePropagation();
    },true);
    button.addEventListener('click',event => {
      openPlayer();
    });
  }

  function bindEvents() {
    $$('.tab-item').forEach(button => button.addEventListener('click',() => navigateTab(button.dataset.tab,undefined,undefined,true)));
    $('#account-open').addEventListener('click',() => openAccount());
    $('#account-close').addEventListener('click',closeAccount); $('#account-scrim').addEventListener('click',closeAccount);
    $('#netease-login').addEventListener('click',() => { $('#netease-login-panel').hidden = !$('#netease-login-panel').hidden; });
    $('#netease-official-login').addEventListener('click',() => launchLogin('netease'));
    $('#qq-login').addEventListener('click',() => launchLogin('qq'));
    $('#qq-logout').addEventListener('click',() => logoutProvider('qq','qqLogout'));
    $('#send-captcha').addEventListener('click',sendNeteaseCaptcha);
    $('#netease-sms-form').addEventListener('submit',submitNeteaseSmsLogin);
    $('#netease-logout').addEventListener('click',() => logoutProvider('netease','logout'));
    $('#clear-cache').addEventListener('click',clearCache);
    $$('.source-option').forEach(button => button.addEventListener('click',() => saveSettings({sourcePreference:button.dataset.source})));
    $$('.theme-picker button').forEach(button => button.addEventListener('click',() => saveSettings({theme:button.dataset.themeChoice})));
    $('#status-lyrics').addEventListener('click',() => saveSettings({statusLyrics:state.settings.statusLyrics === false}));
    installMiniSwipe();
    installTabSwipe();
    installQueueVirtualization();
    installPlayerPanelSwipe();
    installPlayerExpansion();
    installSheetDrag($('#queue-sheet-handle'), $('#queue-sheet'), $('#queue-scrim'), () => state.queueSheetOpen, closeQueueSheet);
    installSheetDrag($('#track-actions-handle'), $('#track-actions-sheet'), $('#track-actions-scrim'), () => !!trackActions, closeTrackActions);
    $('#track-actions-close').addEventListener('click',() => closeTrackActions());
    $('#track-actions-scrim').addEventListener('click',() => closeTrackActions());
    $('#mini-like').addEventListener('click',toggleFavorite);
    $('#mini-toggle').addEventListener('click',() => sendCommand({type:'toggle'}));
    $('#mini-queue').addEventListener('click',openQueueSheet);
    $('#queue-sheet-close').addEventListener('click',() => closeQueueSheet());
    $('#queue-scrim').addEventListener('click',() => closeQueueSheet());
    $('#player-close').addEventListener('click',closePlayer); $('#player-scrim').addEventListener('click',closePlayer);
    $('#player-settings').addEventListener('click',openSettingsFromPlayer);
    $('#player-toggle').addEventListener('click',() => sendCommand({type:'toggle'}));
    $('#next-button').addEventListener('click',() => sendCommand({type:'next'}));
    $('#previous-button').addEventListener('click',() => sendCommand({type:'previous'}));
    $('#shuffle-button').addEventListener('click',() => setShuffle(!state.media.shuffle));
    $('#repeat-button').addEventListener('click',() => {
      const current = state.media.repeat || 'off';
      setRepeat(current === 'off' ? 'all' : current === 'all' ? 'one' : 'off');
    });
    $$('.view-switch').forEach(button => button.addEventListener('click',() => setPlayerView(button.dataset.playerView)));
    $('#tab-content').addEventListener('click',event => {
      const search = event.target.closest('[data-search-term]');
      if (search) { state.searchQuery=search.dataset.searchTerm; navigateTab('search'); runSearch(state.searchQuery); }
    });
    onSeek(); installSheetGesture();
    document.addEventListener('visibilitychange',() => {
      if (!document.hidden) { pollMediaState(); refreshAuth(); }
    });
    window.addEventListener('focus',() => { pollMediaState(); refreshAuth(); });
    window.addEventListener('native-resume',() => { pollMediaState(); refreshAuth(); });
    if (window.visualViewport) window.visualViewport.addEventListener('resize',updateKeyboard);
    window.addEventListener('resize',updateKeyboard);
    document.addEventListener('focusin',updateKeyboard);
    document.addEventListener('focusout',() => setTimeout(updateKeyboard,0));
    document.addEventListener('keydown',event => {
      if (event.key === 'Escape') handleBack();
    });
    const colorPreference = window.matchMedia('(prefers-color-scheme: dark)');
    const onColorPreference = () => { if (state.settings.theme === 'system') syncSystemChrome(); };
    if (colorPreference.addEventListener) colorPreference.addEventListener('change',onColorPreference);
    else if (colorPreference.addListener) colorPreference.addListener(onColorPreference);
  }

  function setupIcons() {
    $$('.tab-icon').forEach(node => setIcon(node,node.dataset.icon));
    setIcon($('#player-close'),'close'); setIcon($('#player-settings'),'more'); setIcon($('#account-close'),'close');
    setIcon($('#mini-toggle'),'play'); setIcon($('#mini-like'),'heart'); setIcon($('#mini-queue'),'queue'); setIcon($('#player-toggle'),'play');
    setIcon($('#queue-sheet-close'),'close'); setIcon($('#track-actions-close'),'close');
    updateMiniFavoriteButton();
    setIcon($('#avatar-mark'),'music'); setIcon($('#mini-art'),'music'); setIcon($('#player-artwork'),'music'); setIcon($('#account-avatar'),'music');
    setIcon($('#previous-button'),'previous'); setIcon($('#next-button'),'next'); setIcon($('#shuffle-button'),'shuffle'); setIcon($('#repeat-button'),'repeat');
  }

  function boot() {
    accountMotion = window.YPMSettingsMotion.create({overlay:$('#account-overlay'),sheet:$('.settings-sheet'),scrim:$('#account-scrim'),onDismiss:closeAccount});
    setupIcons(); bindEvents(); readSettings(); renderSettingsAccount(); renderTab(); setPlayerView(state.playerView);
    if (!nativeReady()) setMessage('请在 YesPlayMusic Android 应用中使用。浏览器预览不会伪造账号或播放状态。',false);
    else {
      refreshAuth().then(() => { loadHome(); if (state.tab === 'library') loadLibrary(); });
      pollMediaState();
      if (!state.polling) { state.polling=true; setInterval(pollMediaState,300); }
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded',boot,{once:true});
  else boot();
})();
