(function () {
  'use strict';
  if (window.__rexDoramaFrameReady) return;
  window.__rexDoramaFrameReady = true;

  var resumeAt = Number(window.__rexResumeSec) || 0;
  var seekDone = resumeAt <= 0;
  var lastResumeSeekAttempt = 0;
  var userPaused = false;
  var lastAutoplayAttempt = 0;
  var styleText = 'video::-webkit-media-controls,video::-webkit-media-controls-enclosure,' +
    '.jw-controls,.jw-controlbar,.jw-display-controls,.jw-title,.jw-logo,.jw-dock,' +
    '.vjs-control-bar,.vjs-big-play-button,.plyr__controls,.plyr__control--overlaid,' +
    '#checkresume_div,#checkresume_div_n,.checkresume,.jw-resume,.jw-resume-dialog,' +
    '[class*="resume-dialog"],[class*="resume-modal"]' +
    '{display:none!important;visibility:hidden!important;pointer-events:none!important}';

  function player() {
    try {
      if (window.jwplayer) {
        var jw = window.jwplayer('vplayer') || window.jwplayer();
        if (jw && typeof jw.getState === 'function') return jw;
      }
    } catch (ignored) {}
    return null;
  }

  function video() {
    var videos = document.querySelectorAll('video');
    for (var i = 0; i < videos.length; i++) {
      if (!videos[i].paused) return videos[i];
    }
    return videos.length ? videos[0] : null;
  }

  function hideProviderUi() {
    try {
      if (!document.documentElement) return;
      var style = document.getElementById('rex-dorama-frame-style');
      if (!style) {
        style = document.createElement('style');
        style.id = 'rex-dorama-frame-style';
        document.documentElement.appendChild(style);
      }
      if (style.textContent !== styleText) style.textContent = styleText;
      document.querySelectorAll('video').forEach(function (v) {
        v.controls = false;
        v.removeAttribute('controls');
        v.setAttribute('playsinline', '');
      });
      var jw = player();
      if (jw && jw.setControls) jw.setControls(false);
      var confirm = document.getElementById('yesplease');
      if (confirm) confirm.click();
    } catch (ignored) {}
  }

  function seek(seconds) {
    var v = video();
    var jw = player();
    var done = false;
    try {
      if (v && isFinite(v.duration) && v.duration > 0 && seconds < v.duration) {
        v.currentTime = seconds;
        done = Math.abs(v.currentTime - seconds) < 3;
      }
      if (jw && jw.seek && jw.getDuration && jw.getDuration() > seconds) {
        jw.seek(seconds);
        done = true;
      }
    } catch (ignored) {}
    return done;
  }

  function play() {
    var v = video();
    var jw = player();
    try {
      if (jw && jw.play) jw.play(true);
      if (v && v.paused) {
        v.muted = true;
        var result = v.play();
        if (result && result.then) result.then(function () { v.muted = false; }).catch(function () {});
      }
    } catch (ignored) {}
  }

  function pause() {
    try {
      var jw = player();
      if (jw && jw.pause) jw.pause(true);
      var v = video();
      if (v) v.pause();
    } catch (ignored) {}
  }

  function ensureAudible(playback) {
    if (playback.paused || playback.duration < 300 || playback.currentTime <= 0) return;
    try {
      var jw = player();
      if (jw) {
        if (jw.setMute) jw.setMute(false);
        if (jw.setVolume) jw.setVolume(100);
      }
      var v = video();
      if (v) {
        v.muted = false;
        v.volume = 1;
      }
    } catch (ignored) {}
  }

  function state() {
    var v = video();
    var jw = player();
    var duration = v && isFinite(v.duration) ? v.duration : 0;
    var currentTime = v && isFinite(v.currentTime) ? v.currentTime : 0;
    var paused = v ? v.paused : true;
    if (jw) {
      try {
        if ((!duration || duration <= 0) && jw.getDuration) duration = jw.getDuration() || 0;
        if (!v && jw.getPosition) currentTime = jw.getPosition() || 0;
        if (!v) paused = jw.getState() !== 'playing';
      } catch (ignored) {}
    }
    return {hasMedia: !!v || !!jw, duration: duration, currentTime: currentTime,
      remaining: duration > 0 ? Math.max(0, duration - currentTime) : 999999,
      paused: paused, ended: !!(v && v.ended), restored: seekDone};
  }

  function tick() {
    hideProviderUi();
    var s = state();
    if (!s.hasMedia) return;
    if (!seekDone && s.duration > resumeAt) {
      if (lastResumeSeekAttempt && Math.abs(s.currentTime - resumeAt) < 3) {
        seekDone = true;
      } else if (Date.now() - lastResumeSeekAttempt > 1500) {
        lastResumeSeekAttempt = Date.now();
        seek(resumeAt);
      }
    }
    if (!userPaused && s.paused && !s.ended && Date.now() - lastAutoplayAttempt > 1800) {
      lastAutoplayAttempt = Date.now();
      play();
    }
    ensureAudible(state());
    try { window.RexDoramaFrame.postMessage(JSON.stringify(state())); } catch (ignored) {}
  }

  try {
    window.RexDoramaFrame.onmessage = function (event) {
      try {
        var command = JSON.parse(event.data);
        if (command.type === 'toggle') {
          userPaused = !state().paused;
          if (userPaused) pause(); else play();
        } else if (command.type === 'pause') {
          userPaused = true;
          pause();
        } else if (command.type === 'seek') {
          seekDone = seek(Number(command.seconds) || 0);
        } else if (command.type === 'skip') {
          seek(Math.max(0, state().currentTime + (Number(command.seconds) || 0)));
        }
        tick();
      } catch (ignored) {}
    };
  } catch (ignored) {}
  new MutationObserver(hideProviderUi).observe(document, {childList: true, subtree: true});
  setInterval(tick, 1000);
  document.addEventListener('DOMContentLoaded', tick);
})();
