(function (global) {
  'use strict';

  const CLOSED_TRANSFORM = 'translate3d(0, 102%, 0)';
  const OPEN_TRANSFORM = 'translate3d(0, 0, 0)';
  const OPEN_EASING = 'cubic-bezier(0.32, 0.72, 0, 1)';
  const CLOSE_EASING = 'cubic-bezier(0.23, 1, 0.32, 1)';
  const REDUCED_FADE_MS = 120;
  const DRAG_DISMISS_DISTANCE = 72;
  const DRAG_PROJECT_MS = 160;
  const DRAG_START_DISTANCE = 5;
  const INTERACTIVE_SELECTOR = 'button,input,textarea,select,option,a,label,[role="button"],[role="radio"],[contenteditable="true"]';

  function create(options) {
    const settings = options || {};
    const overlay = settings.overlay;
    const sheet = settings.sheet;
    const scrim = settings.scrim;
    if (!overlay || !sheet || !scrim) {
      throw new TypeError('YPMSettingsMotion requires overlay, sheet, and scrim elements');
    }

    const motionPreference = typeof global.matchMedia === 'function'
      ? global.matchMedia('(prefers-reduced-motion: reduce)')
      : { matches: false };
    let targetOpen = overlay.classList.contains('is-open') && overlay.getAttribute('aria-hidden') !== 'true';
    let revision = 0;
    let animations = [];
    let closeCallbacks = [];
    let gesture = null;

    function snapshot() {
      const sheetStyle = global.getComputedStyle(sheet);
      const scrimStyle = global.getComputedStyle(scrim);
      return {
        transform: sheetStyle.transform === 'none' ? OPEN_TRANSFORM : sheetStyle.transform,
        opacity: Number.isFinite(Number.parseFloat(scrimStyle.opacity))
          ? String(Math.max(0, Math.min(1, Number.parseFloat(scrimStyle.opacity))))
          : '0'
      };
    }

    function cancelAnimations() {
      revision += 1;
      const previous = animations;
      animations = [];
      previous.forEach(animation => {
        try { animation.cancel(); } catch (_) { /* Already finished or canceled. */ }
      });
    }

    function run(from, opening, reduced, finish) {
      const token = revision;
      const toTransform = opening ? OPEN_TRANSFORM : CLOSED_TRANSFORM;
      const toOpacity = opening ? '1' : '0';
      const canAnimate = typeof sheet.animate === 'function' && typeof scrim.animate === 'function';

      if (!canAnimate) {
        finish();
        return;
      }

      const created = [];
      try {
        if (!reduced) {
          created.push(sheet.animate([
            { transform: from.transform },
            { transform: toTransform }
          ], {
            duration: opening ? 280 : 220,
            easing: opening ? OPEN_EASING : CLOSE_EASING,
            fill: 'both'
          }));
        } else {
          // Honor reduced motion by snapping the sheet position and keeping only a brief fade.
          sheet.style.transform = toTransform;
        }
        created.push(scrim.animate([
          { opacity: from.opacity },
          { opacity: toOpacity }
        ], {
          duration: reduced ? REDUCED_FADE_MS : (opening ? 240 : 180),
          easing: OPEN_EASING,
          fill: 'both'
        }));
      } catch (_) {
        created.forEach(animation => {
          try { animation.cancel(); } catch (_) {}
        });
        animations = [];
        finish();
        return;
      }

      animations = created;
      Promise.all(created.map(animation => animation.finished.catch(() => null))).then(() => {
        if (token !== revision) return;
        animations = [];
        created.forEach(animation => {
          try { animation.cancel(); } catch (_) { /* Restore the matching CSS state. */ }
        });
        finish();
      });
    }

    function finishOpen() {
      sheet.style.removeProperty('transform');
      scrim.style.removeProperty('opacity');
      overlay.classList.add('is-open');
      overlay.setAttribute('aria-hidden', 'false');
    }

    function finishClose() {
      sheet.style.removeProperty('transform');
      scrim.style.removeProperty('opacity');
      overlay.classList.remove('is-open');
      overlay.setAttribute('aria-hidden', 'true');
      const callbacks = closeCallbacks;
      closeCallbacks = [];
      callbacks.forEach(callback => {
        try { callback(); } catch (_) { /* A consumer callback must not break state cleanup. */ }
      });
    }

    function open() {
      if (targetOpen) {
        if (gesture && gesture.dragging) settleOpen();
        else gesture = null;
        return;
      }
      gesture = null;
      const from = overlay.classList.contains('is-open')
        ? snapshot()
        : { transform: CLOSED_TRANSFORM, opacity: '0' };
      cancelAnimations();
      closeCallbacks = [];
      targetOpen = true;
      overlay.classList.add('is-open');
      overlay.setAttribute('aria-hidden', 'false');
      run(from, true, !!motionPreference.matches, finishOpen);
    }

    function close(onRest) {
      if (!targetOpen) {
        if (typeof onRest === 'function') {
          if (overlay.classList.contains('is-open')) closeCallbacks.push(onRest);
          else onRest();
        }
        return;
      }
      gesture = null;
      const from = snapshot();
      cancelAnimations();
      targetOpen = false;
      if (typeof onRest === 'function') closeCallbacks.push(onRest);
      run(from, false, !!motionPreference.matches, finishClose);
    }

    function settleOpen() {
      const from = snapshot();
      gesture = null;
      cancelAnimations();
      run(from, true, !!motionPreference.matches, finishOpen);
    }

    function isInteractive(target) {
      return !!(target && target.closest && target.closest(INTERACTIVE_SELECTOR));
    }

    function isHandleTarget(target) {
      if (!target || !target.closest) return false;
      return !!target.closest('.sheet-grabber, .settings-heading');
    }

    function currentOffset() {
      const sheetRect = sheet.getBoundingClientRect();
      const overlayRect = overlay.getBoundingClientRect();
      return Math.max(0, sheetRect.top - (overlayRect.bottom - sheetRect.height));
    }

    function startDrag(currentY, pointerId, kind, originY) {
      if (gesture && gesture.dragging) return;
      const from = snapshot();
      const offset = currentOffset();
      cancelAnimations();
      gesture.dragging = true;
      gesture.pointerId = pointerId;
      gesture.kind = kind;
      gesture.originY = Number.isFinite(originY) ? originY : currentY;
      gesture.originOffset = offset;
      gesture.scrimOpacity = Number.parseFloat(from.opacity) || 0;
      gesture.lastY = currentY;
      gesture.samples = [{ y: currentY, time: performance.now() }];
    }

    function moveDrag(currentY) {
      if (!gesture || !gesture.dragging) return;
      const now = performance.now();
      const offset = Math.max(0, gesture.originOffset + currentY - gesture.originY);
      gesture.samples.push({ y: currentY, time: now });
      while (gesture.samples.length > 5 || (gesture.samples.length > 2 && now - gesture.samples[0].time > 120)) {
        gesture.samples.shift();
      }
      const sheetHeight = Math.max(1, sheet.getBoundingClientRect().height);
      sheet.style.transform = `translate3d(0, ${offset}px, 0)`;
      scrim.style.opacity = String(gesture.scrimOpacity * Math.max(0, 1 - offset / sheetHeight));
    }

    function endDrag(cancelled) {
      if (!gesture) return;
      const activeGesture = gesture;
      gesture = null;
      if (!activeGesture.dragging) return;
      const offset = Math.max(0, activeGesture.originOffset + activeGesture.lastY - activeGesture.originY);
      const projected = offset + Math.max(0, gestureVelocityFrom(activeGesture.samples)) * DRAG_PROJECT_MS / 1000;
      if (!cancelled && projected >= DRAG_DISMISS_DISTANCE) {
        if (typeof settings.onDismiss === 'function') settings.onDismiss();
        else close();
      } else {
        const from = snapshot();
        cancelAnimations();
        run(from, true, !!motionPreference.matches, finishOpen);
      }
    }

    function gestureVelocityFrom(samples) {
      if (!samples || samples.length < 2) return 0;
      const first = samples[0];
      const last = samples[samples.length - 1];
      const elapsed = last.time - first.time;
      return elapsed > 0 ? (last.y - first.y) / elapsed * 1000 : 0;
    }

    function beginCandidate(currentY, currentX, kind, id, target) {
      if (target && isInteractive(target)) return null;
      return {
        kind,
        pointerId: id,
        startY: currentY,
        startX: currentX,
        lastY: currentY,
        startScrollTop: sheet.scrollTop,
        samples: [{ y: currentY, time: performance.now() }],
        dragging: false
      };
    }

    function updateCandidate(currentY, currentX, event) {
      if (!gesture || gesture.dragging) return;
      const dx = currentX - gesture.startX;
      const dy = currentY - gesture.startY;
      if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy)) {
        gesture = null;
        return;
      }
      if (dy < -DRAG_START_DISTANCE) {
        // Upward motion belongs to the settings sheet's native scrolling.
        gesture = null;
        return;
      }
      if (dy < DRAG_START_DISTANCE || (gesture.kind === 'touch' && sheet.scrollTop > 0)) return;
      if (event && event.cancelable) event.preventDefault();
      const originY = gesture.kind === 'touch' ? gesture.startY + gesture.startScrollTop : gesture.startY;
      startDrag(currentY, gesture.pointerId, gesture.kind, originY);
    }

    sheet.addEventListener('pointerdown', event => {
      if (!targetOpen || !event.isPrimary || (event.pointerType !== 'touch' && event.pointerType !== 'pen')) return;
      if (!isHandleTarget(event.target) || isInteractive(event.target)) return;
      gesture = beginCandidate(event.clientY, event.clientX, 'pointer', event.pointerId, event.target);
      if (!gesture) return;
      try { sheet.setPointerCapture(event.pointerId); } catch (_) {}
    });

    sheet.addEventListener('pointermove', event => {
      if (!gesture || gesture.kind !== 'pointer' || gesture.pointerId !== event.pointerId) return;
      updateCandidate(event.clientY, event.clientX, event);
      if (gesture && gesture.dragging) {
        if (event.cancelable) event.preventDefault();
        gesture.lastY = event.clientY;
        moveDrag(event.clientY);
      }
    });

    sheet.addEventListener('pointerup', event => {
      if (!gesture || gesture.kind !== 'pointer' || gesture.pointerId !== event.pointerId) return;
      if (gesture.dragging) {
        gesture.lastY = event.clientY;
        moveDrag(event.clientY);
      }
      endDrag(false);
    });

    sheet.addEventListener('pointercancel', event => {
      if (gesture && gesture.kind === 'pointer' && gesture.pointerId === event.pointerId) endDrag(true);
    });

    sheet.addEventListener('touchstart', event => {
      if (!targetOpen || !event.changedTouches || !event.changedTouches.length) return;
      if (event.touches && event.touches.length > 1) {
        if (gesture && gesture.dragging) endDrag(true);
        else gesture = null;
        return;
      }
      const touch = event.changedTouches[0];
      if (isHandleTarget(event.target)) return;
      gesture = beginCandidate(touch.clientY, touch.clientX, 'touch', touch.identifier, event.target);
    }, { passive: true });

    sheet.addEventListener('touchmove', event => {
      if (!gesture || gesture.kind !== 'touch') return;
      let touch = null;
      for (let i = 0; i < event.changedTouches.length; i += 1) {
        if (event.changedTouches[i].identifier === gesture.pointerId) { touch = event.changedTouches[i]; break; }
      }
      if (!touch) return;
      updateCandidate(touch.clientY, touch.clientX, event);
      if (gesture && gesture.dragging) {
        if (event.cancelable) event.preventDefault();
        gesture.lastY = touch.clientY;
        moveDrag(touch.clientY);
      }
    }, { passive: false });

    function finishTouch(event, cancelled) {
      if (!gesture || gesture.kind !== 'touch') return;
      let touch = null;
      for (let i = 0; i < event.changedTouches.length; i += 1) {
        if (event.changedTouches[i].identifier === gesture.pointerId) { touch = event.changedTouches[i]; break; }
      }
      if (!touch) return;
      if (gesture.dragging) {
        gesture.lastY = touch.clientY;
        moveDrag(touch.clientY);
      }
      endDrag(cancelled);
    }
    sheet.addEventListener('touchend', event => finishTouch(event, false), { passive: true });
    sheet.addEventListener('touchcancel', event => finishTouch(event, true), { passive: true });

    return {
      open,
      close,
      isOpen: () => targetOpen
    };
  }

  global.YPMSettingsMotion = { create };
})(window);
