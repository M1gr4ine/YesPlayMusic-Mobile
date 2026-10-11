(function () {
  'use strict';

  function create(host) {
    const line = host && host.querySelector('.mini-subtitle-line');
    const text = line && line.querySelector('.mini-subtitle-text');
    if (!host || !line || !text) {
      return { update: function () {}, destroy: function () {} };
    }

    const body = document.body;
    const reducedQuery = window.matchMedia
      ? window.matchMedia('(prefers-reduced-motion: reduce)')
      : null;
    let reducedMotion = !!(reducedQuery && reducedQuery.matches);
    let currentKey = null;
    let currentText = null;
    let currentLyric = null;
    let playing = false;
    let marqueeAnimation = null;
    let entranceAnimation = null;
    let marqueeDistance = 0;
    let marqueeDuration = 0;
    let lastWidth = -1;
    let lastTextWidth = -1;
    let measureFrame = 0;
    let preserveProgressOnFrame = true;
    let destroyed = false;

    function hasSize() {
      return host.clientWidth > 0 && host.getClientRects().length > 0;
    }

    function gestureBlocksMotion() {
      return !!(body && (body.classList.contains('mini-swipe-active')
        || body.classList.contains('mini-track-in-motion')));
    }

    function shouldPauseMarquee() {
      return destroyed || reducedMotion || !playing || document.hidden || !hasSize()
        || gestureBlocksMotion();
    }

    function syncMarqueePlayback() {
      if (!marqueeAnimation) return;
      if (shouldPauseMarquee()) {
        if (marqueeAnimation.playState === 'running') marqueeAnimation.pause();
      } else if (marqueeAnimation.playState === 'paused') {
        marqueeAnimation.play();
      }
    }

    function stopMarquee() {
      if (marqueeAnimation) {
        const previous = marqueeAnimation;
        marqueeAnimation = null;
        previous.cancel();
      }
      marqueeDistance = 0;
      marqueeDuration = 0;
      text.classList.remove('is-marquee');
      text.style.willChange = '';
    }

    function cancelEntrance() {
      if (entranceAnimation) {
        const previous = entranceAnimation;
        entranceAnimation = null;
        previous.cancel();
      }
      line.style.opacity = '1';
      line.style.transform = 'translate3d(0,0,0)';
    }

    function animateEntrance() {
      cancelEntrance();
      if (reducedMotion || typeof line.animate !== 'function') return;
      const animation = line.animate([
        { opacity: 0, transform: 'translate3d(0,5px,0)' },
        { opacity: 1, transform: 'translate3d(0,0,0)' }
      ], {
        duration: 160,
        easing: 'cubic-bezier(.22,.75,.25,1)',
        fill: 'both'
      });
      entranceAnimation = animation;
      animation.onfinish = function () {
        if (entranceAnimation !== animation) return;
        entranceAnimation = null;
        line.style.opacity = '1';
        line.style.transform = 'translate3d(0,0,0)';
        animation.cancel();
      };
    }

    function currentMarqueeProgress() {
      if (!marqueeAnimation || marqueeDuration <= 0) return 0;
      const time = marqueeAnimation.currentTime;
      if (typeof time !== 'number') return 0;
      return ((time % marqueeDuration) + marqueeDuration) % marqueeDuration / marqueeDuration;
    }

    function startMarquee(distance, preserveProgress) {
      if (typeof text.animate !== 'function') return;
      const progress = preserveProgress ? currentMarqueeProgress() : 0;
      const travelMs = distance / 30 * 1000;
      const endpointPauseMs = 800;
      const duration = travelMs * 2 + endpointPauseMs * 2;
      const far = 'translate3d(' + (-distance) + 'px,0,0)';
      const outboundEnd = (endpointPauseMs + travelMs) / duration;
      const returnStart = (endpointPauseMs + travelMs + endpointPauseMs) / duration;

      stopMarquee();
      marqueeDistance = distance;
      marqueeDuration = duration;
      text.classList.add('is-marquee');
      text.style.willChange = 'transform';
      marqueeAnimation = text.animate([
        { transform: 'translate3d(0,0,0)', offset: 0 },
        { transform: 'translate3d(0,0,0)', offset: endpointPauseMs / duration },
        { transform: far, offset: outboundEnd },
        { transform: far, offset: returnStart },
        { transform: 'translate3d(0,0,0)', offset: 1 }
      ], {
        duration: duration,
        iterations: Infinity,
        easing: 'linear',
        fill: 'both'
      });
      if (progress > 0) marqueeAnimation.currentTime = progress * duration;
      syncMarqueePlayback();
    }

    function measureMarquee(preserveProgress) {
      if (destroyed) return;
      const width = host.clientWidth;
      const textWidth = text.scrollWidth;
      lastWidth = width;
      lastTextWidth = textWidth;
      if (!currentLyric || reducedMotion || width <= 0) {
        stopMarquee();
        syncMarqueePlayback();
        return;
      }

      const distance = Math.ceil(textWidth - width);
      if (distance < 8) {
        stopMarquee();
        return;
      }
      if (marqueeAnimation && Math.abs(distance - marqueeDistance) < 1) {
        syncMarqueePlayback();
        return;
      }
      startMarquee(distance, preserveProgress);
    }

    function scheduleMeasure(preserveProgress) {
      if (destroyed) return;
      if (!preserveProgress) preserveProgressOnFrame = false;
      else if (!measureFrame) preserveProgressOnFrame = true;
      if (measureFrame) return;
      measureFrame = requestAnimationFrame(function () {
        measureFrame = 0;
        const keepProgress = preserveProgressOnFrame;
        preserveProgressOnFrame = true;
        measureMarquee(keepProgress);
      });
    }

    function onResize() {
      const width = host.clientWidth;
      const textWidth = text.scrollWidth;
      if (width === lastWidth && textWidth === lastTextWidth) return;
      lastWidth = width;
      lastTextWidth = textWidth;
      scheduleMeasure(true);
    }

    function onVisibilityChange() {
      syncMarqueePlayback();
      if (!document.hidden) scheduleMeasure(true);
    }

    function onBodyClassChange() {
      syncMarqueePlayback();
    }

    function onReducedMotionChange(event) {
      reducedMotion = !!event.matches;
      if (reducedMotion) {
        stopMarquee();
        cancelEntrance();
      }
      else scheduleMeasure(false);
    }

    const resizeObserver = typeof ResizeObserver === 'function'
      ? new ResizeObserver(onResize)
      : null;
    if (resizeObserver) {
      resizeObserver.observe(host);
      resizeObserver.observe(text);
    } else {
      window.addEventListener('resize', onResize);
    }

    const bodyObserver = body && typeof MutationObserver === 'function'
      ? new MutationObserver(onBodyClassChange)
      : null;
    if (bodyObserver) bodyObserver.observe(body, { attributes: true, attributeFilter: ['class'] });
    document.addEventListener('visibilitychange', onVisibilityChange);
    if (reducedQuery) {
      if (typeof reducedQuery.addEventListener === 'function') reducedQuery.addEventListener('change', onReducedMotionChange);
      else if (typeof reducedQuery.addListener === 'function') reducedQuery.addListener(onReducedMotionChange);
    }

    return {
      update: function (model) {
        if (destroyed) return;
        const value = model && typeof model === 'object' ? model : {};
        const key = value.key == null ? '' : String(value.key);
        const nextText = value.text == null ? '' : String(value.text);
        const lyric = !!value.lyric;
        const changed = key !== currentKey || nextText !== currentText || lyric !== currentLyric;
        const nextPlaying = !!value.playing;
        const playingChanged = playing !== nextPlaying;

        if (changed) {
          currentKey = key;
          currentText = nextText;
          currentLyric = lyric;
          stopMarquee();
          cancelEntrance();
          text.textContent = nextText;
          host.classList.toggle('is-lyric', lyric);
          lastWidth = -1;
          lastTextWidth = -1;
          animateEntrance();
          scheduleMeasure(false);
        }

        if (playingChanged) {
          playing = nextPlaying;
          syncMarqueePlayback();
        }
      },
      destroy: function () {
        if (destroyed) return;
        destroyed = true;
        if (measureFrame) cancelAnimationFrame(measureFrame);
        measureFrame = 0;
        stopMarquee();
        cancelEntrance();
        if (resizeObserver) resizeObserver.disconnect();
        else window.removeEventListener('resize', onResize);
        if (bodyObserver) bodyObserver.disconnect();
        document.removeEventListener('visibilitychange', onVisibilityChange);
        if (reducedQuery) {
          if (typeof reducedQuery.removeEventListener === 'function') reducedQuery.removeEventListener('change', onReducedMotionChange);
          else if (typeof reducedQuery.removeListener === 'function') reducedQuery.removeListener(onReducedMotionChange);
        }
      }
    };
  }

  window.YPMMiniLyrics = Object.freeze({ create: create });
})();
