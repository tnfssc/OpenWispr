const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
if (params.has('presentation')) document.documentElement.classList.add('presentation');
if (params.get('theme') === 'light') document.documentElement.dataset.theme = 'light';
const pieces = [...document.querySelectorAll('.wave-piece')];
for (const piece of pieces) {
  piece.style.transformOrigin = `${piece.dataset.x}px ${piece.dataset.anchor || 188}px`;
}
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
// A longer delay can be used in recordings to review several processing cycles.
const processingDelay = Math.min(10000, Math.max(100, Number(params.get('processingDelay')) || 1600));
let state = 'idle', strength = 1.5, speed = 1, startTime = 0, envelope = 0, previousTime = 0;
let processingStarted = 0, processingMix = 0;
let timers = [], simulated = true;
let speechHistory = [];
const voiceAudio = new Audio('./voice-sample.wav');
function delayedSpeech(time, delay) {
  const target = time - delay;
  for (let i = speechHistory.length - 2; i >= 0; i--) {
    const [olderTime, older] = speechHistory[i];
    const [newerTime, newer] = speechHistory[i + 1];
    if (olderTime <= target) return older + (newer - older) * Math.min(1, (target - olderTime) / (newerTime - olderTime || 1));
  }
  return 0;
}
let noteBefore = '';
const sample = 'Remind me to pick up coffee on the way home.';
const partialTimeline = [
  [1300, 'Remind me'],
  [2100, 'Remind me to'],
  [2800, 'Remind me to pick up'],
  [3600, 'Remind me to pick up a coffee'],
  [4500, 'Remind me to pick up a coffee on my'],
  [5700, 'Remind me to pick up a coffee on my way home'],
];
function clearTimers() { timers.forEach(clearTimeout); timers = []; }
function later(fn, delay) { timers.push(setTimeout(fn, delay)); }
function renderDraft(text) {
  const words = text.split(' ');
  const existing = [...$('draft-text').children];
  let common = 0;
  while (common < Math.min(words.length, existing.length) && existing[common].textContent.trim() === words[common]) common++;
  existing.slice(common).forEach((word) => word.remove());
  for (const word of words.slice(common)) {
    const span = document.createElement('span');
    span.className = 'preview-word is-new';
    span.textContent = `${word} `;
    $('draft-text').append(span);
  }
}
function setState(next) {
  const showPreview = ['waiting', 'listening', 'transcribing'].includes(next);
  if (showPreview && state === 'idle') {
    noteBefore = $('note').value;
    $('saved-note').textContent = noteBefore ? `${noteBefore}\n\n` : '';
    $('draft-text').replaceChildren();
  }
  $('note').hidden = showPreview;
  $('live-note').hidden = !showPreview;
  if (!simulated && ['listening', 'transcribing'].includes(next)) renderDraft(partialTimeline.at(-1)[1]);
  if (next === 'transcribing' && state !== next) processingStarted = performance.now();
  state = next;
  document.querySelector('.keyboard').dataset.state = next;
  $('voice').setAttribute('aria-busy', String(next === 'transcribing'));
  $('state-picker').value = next;
  const idle = next === 'idle';
  $('keys').hidden = !idle;
  $('voice').hidden = idle;
  $('start').hidden = !idle;
  $('cancel').hidden = idle;
  $('finish').disabled = ['transcribing', 'success', 'disabled'].includes(next);
  $('cancel').disabled = next === 'success';
  $('finish').classList.remove('is-hover', 'is-focus', 'is-active');
  const messages = {
    waiting: ['Listening…', 'Finish dictation'],
    listening: ['Listening…', 'Finish dictation'],
    transcribing: ['Transcribing…', 'Transcribing'],
    success: ['', 'Transcription complete'],
    error: ['Try again', 'Retry dictation'],
    disabled: ['Microphone unavailable', 'Microphone unavailable'],
  };
  if (messages[next]) {
    $('status').textContent = messages[next][0];
    $('status').hidden = !messages[next][0];
    $('finish').setAttribute('aria-label', messages[next][1]);
  }
}
function start() {
  clearTimers(); simulated = true; startTime = performance.now(); envelope = 0; speechHistory = []; setState('waiting');
  voiceAudio.currentTime = 0;
  if (params.has('sampleAudio')) voiceAudio.play().catch(() => {});
  later(() => setState('listening'), 1100);
  partialTimeline.forEach(([delay, text]) => later(() => renderDraft(text), delay));
}
function finish() {
  if (state === 'error') { start(); return; }
  if (!['waiting', 'listening'].includes(state)) return;
  clearTimers(); voiceAudio.pause(); setState('transcribing');
  later(() => {
    $('note').value = (noteBefore ? `${noteBefore}\n\n` : '') + sample;
    setState('idle');
    $('start').focus({ preventScroll: true });
  }, processingDelay);
}
function cancel() { clearTimers(); voiceAudio.pause(); setState('idle'); $('start').focus({ preventScroll: true }); }
$('start').addEventListener('click', start);
$('finish').addEventListener('click', finish);
$('cancel').addEventListener('click', cancel);
document.addEventListener('keydown', (event) => { if (event.key === 'Escape' && state !== 'idle') cancel(); });
$('strength').addEventListener('input', (event) => { strength = Number(event.target.value); $('strength-value').value = strength <= .8 ? 'Subtle' : 'Expressive'; });
$('speed').addEventListener('input', (event) => { speed = Number(event.target.value); $('speed-value').value = `${speed}×`; });
$('theme').addEventListener('change', (event) => { document.documentElement.dataset.theme = event.target.value; });
$('state-picker').addEventListener('change', (event) => { clearTimers(); simulated = false; startTime = performance.now(); setState(event.target.value); });
$('reset').addEventListener('click', () => { cancel(); $('note').value = ''; });
$('tune').addEventListener('click', () => $('review').scrollIntoView({ behavior: reducedMotion.matches ? 'instant' : 'smooth' }));
$('replay').addEventListener('click', () => { $('note').value = ''; start(); later(finish, 10500); });
function animate(time) {
  const dt = Math.min((time - previousTime) / 1000 || .016, .1); previousTime = time;
  const elapsed = (time - startTime) / 1000;
  let target = 0;
  if (state === 'listening') {
    const sampleTime = simulated ? elapsed : elapsed % voiceFixture.duration;
    target = voiceFixture.levels[Math.floor(sampleTime / voiceFixture.interval)] || 0;
  }
  envelope += (target - envelope) * (1 - Math.exp(-dt / (target > envelope ? .025 : .12)));
  speechHistory.push([time / 1000, envelope]);
  if (speechHistory.length > 32) speechHistory.shift();
  processingMix += ((state === 'transcribing' ? 1 : 0) - processingMix) * (1 - Math.exp(-dt / .12));
  pieces.forEach((piece) => {
    if (reducedMotion.matches || !['waiting', 'listening', 'transcribing'].includes(state)) {
      piece.style.transform = ''; piece.style.opacity = ''; return;
    }
    const x = Number(piece.dataset.x);
    const gain = Number(piece.dataset.gain);
    const delay = Math.max(0, Math.min(1, (x - 83) / 241)) * .14;
    const speechAmount = delayedSpeech(time / 1000, delay) * strength;
    // Processing has a steady left-to-right chase, rather than speech bursts.
    // The narrow crest lifts each piece in turn; a smooth mix preserves the
    // current pose when recording ends instead of snapping into another loop.
    const processingPhase = (time - processingStarted) / 1400 * Math.PI * 2 * speed - (x - 83) / 241 * Math.PI * 1.6;
    const pulse = ((Math.sin(processingPhase) + 1) / 2) ** 3;
    const processingAmount = (pulse - .3) * strength * .85;
    const amount = speechAmount * (1 - processingMix) + processingAmount * processingMix;
    const energy = amount * gain;
    // Each vector piece follows real speech energy with a short delay. Strokes keep a constant
    // thickness; dots retain their shape and travel, while the end dash widens.
    if (piece.dataset.motion === 'dot') {
      const direction = Number(piece.dataset.direction || 1);
      const follow = Number(piece.dataset.followSpan || 0) * Number(piece.dataset.followGain || 0) * .27 * amount;
      piece.style.transform = `translateY(${follow + direction * energy * 16}px)`;
    } else if (piece.dataset.motion === 'dash') {
      piece.style.transform = `translateY(${energy * 8}px) scaleX(${1 + energy * .28})`;
    } else {
      piece.style.transform = `scaleY(${1 + energy * .27})`;
    }
    piece.style.opacity = 1 - processingMix * .4 * (1 - pulse);
  });
  requestAnimationFrame(animate);
}
requestAnimationFrame(animate);
if (params.has('state')) {
  simulated = false; setState(params.get('state'));
  if (params.has('style')) $('finish').classList.add(`is-${params.get('style')}`);
}
