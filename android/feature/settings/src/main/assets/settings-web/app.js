/* Vesper settings page. Built entirely from GET /api/schema: every SettingSpec a feature registers
 * on the Echo shows up here with a control for its type. Saves as you go (PUT /api/settings) and
 * stays in sync with changes made on the Echo (SSE /api/events). No libraries, no build step. */
'use strict';

const $ = (s, el = document) => el.querySelector(s);
const SWATCHES = ['#FFFFFF', '#FF453A', '#FF9F0A', '#FFD60A', '#30D158', '#64D2FF', '#0A84FF', '#BF5AF2', '#FF375F'];
const GEOCODE = 'https://geocoding-api.open-meteo.com/v1/search';

/** h('div', {class: 'x', onclick: f}, child, 'text', …) */
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat()) if (c != null && c !== false) el.append(c.nodeType ? c : String(c));
  return el;
}
const svg = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstChild; };
const CHECK = '<svg viewBox="0 0 14 14" aria-hidden="true"><path d="M2.5 7.5l3 3 6-7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const CHEV = '<svg class="chev" viewBox="0 0 9 14" aria-hidden="true"><path d="M1.5 1.5L7 7l-5.5 5.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

const store = {
  get(k, d) { try { return localStorage.getItem(k) ?? d; } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } },
};

async function api(method, path, body) {
  const opts = { method, credentials: 'same-origin', headers: {} };
  if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  let res;
  try { res = await fetch(path, opts); } catch (e) { throw Object.assign(new Error('Can’t reach the Echo. Is it on?'), { status: 0 }); }
  let data = {};
  try { data = await res.json(); } catch (e) { /* empty body */ }
  if (!res.ok) throw Object.assign(new Error(data.error || `Error ${res.status}`), { status: res.status, data });
  return data;
}

// ---------- toast ----------
let toastTimer;
function toast(msg, bad) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.toggle('bad', !!bad);
  t.hidden = false;
  requestAnimationFrame(() => t.classList.add('show'));
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.classList.remove('show'); setTimeout(() => { t.hidden = true; }, 300); }, 2600);
}

// ---------- theme ----------
function applyTheme(mode) {
  document.documentElement.dataset.theme = mode;
  for (const b of document.querySelectorAll('[data-theme-set]')) b.setAttribute('aria-checked', b.dataset.themeSet === mode);
  const dark = mode === 'dark' || (mode === 'auto' && !matchMedia('(prefers-color-scheme: light)').matches);
  $('meta[name="theme-color"]').content = dark ? '#000000' : '#EEF0F6';
}

function setName(name) {
  for (const el of document.querySelectorAll('[data-name]')) el.textContent = name;
  document.title = name;
}

// ---------- pairing ----------
let pairId = null;

function showPair() {
  closeEvents();
  $('#app').hidden = true;
  $('#pair').hidden = false;
  $('#pair-start').hidden = false;
  $('#pair-code').hidden = true;
}

async function startPair() {
  const btn = $('#pair-btn');
  btn.disabled = true;
  try {
    const r = await api('POST', '/api/pair/start', {});
    pairId = r.pairId;
    $('#pair-start').hidden = true;
    $('#pair-code').hidden = false;
    pairMsg('');
    const code = $('#code');
    code.value = '';
    code.focus();
  } catch (e) {
    toast(e.message, true);
  } finally {
    btn.disabled = false;
  }
}

function pairMsg(text, bad) {
  const m = $('#pair-msg');
  m.textContent = text;
  m.classList.toggle('bad', !!bad);
}

async function submitCode() {
  const input = $('#code');
  const code = input.value.replace(/\D/g, '');
  if (code.length !== 6) return;
  input.disabled = true;
  try {
    await api('POST', '/api/pair', { pairId, code });
    pairMsg('Paired');
    await loadApp();
  } catch (e) {
    pairMsg(e.message, true);
    if (e.status === 410 || e.status === 429) { $('#pair-start').hidden = false; $('#pair-code').hidden = true; toast(e.message, true); }
    input.value = '';
  } finally {
    input.disabled = false;
    if (!$('#pair-code').hidden) input.focus();
  }
}

// ---------- the settings page ----------
const rows = new Map(); // key -> {spec, set(value | secretState)}
let schema = null;
let advanced = store.get('advanced', '0') === '1';

async function loadApp() {
  schema = await api('GET', '/api/schema');
  $('#pair').hidden = true;
  $('#app').hidden = false;
  render();
  loadPairs();
  openEvents();
}

function render() {
  const keep = window.scrollY;
  rows.clear();
  setName(schema.device.name);
  const main = $('#sections');
  const chips = $('#chips');
  main.textContent = '';
  chips.textContent = '';
  for (const s of schema.sections) {
    const specs = s.settings.filter((x) => advanced || !x.advanced);
    if (!specs.length && s.id !== 'general') continue;
    const group = h('div', { class: 'group glass' }, specs.map((spec) => row(spec)));
    if (s.id === 'general') group.append(h('div', { id: 'browsers' }));
    main.append(h('section', { class: 'section', id: `s-${s.id}`, 'aria-labelledby': `h-${s.id}` },
      h('h2', { id: `h-${s.id}`, text: s.title }),
      group,
      s.help && h('p', { class: 'section-help', text: s.help })));
    chips.append(h('a', { class: 'glass', href: `#s-${s.id}`, text: s.title, onclick: (e) => {
      e.preventDefault();
      document.getElementById(`s-${s.id}`).scrollIntoView({ behavior: 'smooth', block: 'start' });
    } }));
  }
  if (lastPairs) renderPairs(lastPairs);
  window.scrollTo(0, keep);
}

/** One row: title, control, help, inline error, saved tick. */
function row(spec) {
  const saved = h('span', { class: 'saved', 'aria-hidden': 'true' }, svg(CHECK), 'Saved');
  const err = h('p', { class: 'err', role: 'alert', hidden: true });
  const titleId = `t-${spec.key.replace(/[^a-z0-9]/gi, '-')}`;
  const title = h('span', { class: 'row-title' }, h('span', { class: 't', id: titleId, text: spec.title }), saved);
  const el = h('div', { class: 'row', 'data-key': spec.key });
  const ctx = {
    spec, el, titleId,
    ok() { err.hidden = true; saved.classList.add('show'); clearTimeout(ctx.t); ctx.t = setTimeout(() => saved.classList.remove('show'), 1400); },
    fail(msg) { err.textContent = msg; err.hidden = false; },
    save: (value) => save(spec.key, value, ctx),
  };
  const r = (RENDER[spec.type] || RENDER.TEXT)(spec, ctx);
  el.append(h('div', { class: 'row-main' }, title, r.inline), r.below, spec.help && h('p', { class: 'help', text: spec.help }), err);
  rows.set(spec.key, { spec, set: r.set, busy: r.busy || (() => el.contains(document.activeElement)) });
  return el;
}

async function save(key, value, ctx) {
  try {
    const r = await api('PUT', '/api/settings', { [key]: value });
    const v = r.values && r.values[key];
    if (v) applyChange(v, true);
    ctx.ok();
    return true;
  } catch (e) {
    if (e.status === 401) return showPair();
    ctx.fail(e.message);
    const cur = currentSpec(key);
    if (cur) rows.get(key).set(cur.type === 'SECRET' ? cur.secret : cur.value);
    return false;
  }
}

function currentSpec(key) {
  for (const s of schema.sections) for (const x of s.settings) if (x.key === key) return x;
  return null;
}

/** A change from the Echo (SSE) or from our own save. */
function applyChange(c, force) {
  const spec = currentSpec(c.key);
  if (!spec) return;
  if ('secret' in c) spec.secret = c.secret; else spec.value = c.value;
  if (c.key === 'device.name') setName((c.value || '').trim() || 'Echo Show');
  const r = rows.get(c.key);
  if (r && (force || !r.busy())) r.set('secret' in c ? c.secret : c.value);
}

const fmtNum = (spec, v) => {
  const step = spec.step || (spec.type === 'INT' ? 1 : 0.01);
  const dec = Math.max(0, Math.min(3, (String(step).split('.')[1] || '').length));
  return Number(v).toFixed(spec.type === 'INT' ? 0 : dec);
};

const RENDER = {
  BOOLEAN(spec, ctx) {
    const sw = h('input', { type: 'checkbox', class: 'switch', role: 'switch', 'aria-labelledby': ctx.titleId,
      onchange: () => ctx.save(sw.checked) });
    const set = (v) => { sw.checked = v === true || v === 'true'; };
    set(spec.value);
    return { inline: sw, set, busy: () => false };
  },

  INT(spec, ctx) { return RENDER.FLOAT(spec, ctx); },
  FLOAT(spec, ctx) {
    const hasRange = spec.min != null && spec.max != null;
    const step = spec.step || (spec.type === 'INT' ? 1 : (hasRange ? (spec.max - spec.min) / 100 : 0.1));
    if (!hasRange) {
      const f = h('input', { class: 'field inline-field', type: 'text', inputmode: spec.type === 'INT' ? 'numeric' : 'decimal',
        'aria-labelledby': ctx.titleId, onchange: () => ctx.save(Number(f.value)) });
      const set = (v) => { f.value = v ?? ''; };
      set(spec.value);
      return { inline: f, set };
    }
    const steps = (spec.max - spec.min) / step;
    if (spec.type === 'INT' && steps <= 24) {
      let cur = Number(spec.value);
      const out = h('output', { 'aria-live': 'polite' });
      const bump = (d) => {
        const n = Math.min(spec.max, Math.max(spec.min, cur + d * step));
        if (n !== cur) { cur = n; out.textContent = fmtNum(spec, n); ctx.save(n); }
      };
      const box = h('span', { class: 'stepper', role: 'group', 'aria-labelledby': ctx.titleId },
        h('button', { type: 'button', 'aria-label': 'Less', onclick: () => bump(-1) }, '−'), out,
        h('button', { type: 'button', 'aria-label': 'More', onclick: () => bump(1) }, '+'));
      const set = (v) => { cur = Number(v); out.textContent = fmtNum(spec, cur); };
      set(spec.value);
      return { inline: box, set, busy: () => false };
    }
    const range = h('input', { type: 'range', min: spec.min, max: spec.max, step, 'aria-labelledby': ctx.titleId });
    const out = h('output');
    const paint = () => {
      range.style.setProperty('--p', `${((range.value - spec.min) / (spec.max - spec.min)) * 100}%`);
      out.textContent = fmtNum(spec, range.value);
    };
    range.addEventListener('input', paint);
    range.addEventListener('change', () => ctx.save(Number(range.value)));
    const set = (v) => { range.value = v; paint(); };
    set(spec.value);
    return { below: h('div', { class: 'slider-row' }, range, out), set, busy: () => false };
  },

  CHOICE(spec, ctx) {
    const opts = spec.options || [];
    const short = opts.length <= 4 && opts.every(([, l]) => l.length <= 10) && opts.reduce((n, [, l]) => n + l.length, 0) <= 28;
    if (short) {
      const seg = h('div', { class: `seg${opts.length > 2 ? ' wide' : ''}`, role: 'radiogroup', 'aria-labelledby': ctx.titleId },
        opts.map(([v, l]) => h('button', { type: 'button', role: 'radio', 'data-v': v, text: l, onclick: () => { set(v); ctx.save(v); } })));
      const set = (v) => { for (const b of seg.children) b.setAttribute('aria-checked', b.dataset.v === v); };
      set(spec.value);
      return opts.length > 2 ? { below: h('div', { class: 'stack' }, seg), set, busy: () => false } : { inline: seg, set, busy: () => false };
    }
    const label = h('span');
    const sel = h('select', { 'aria-labelledby': ctx.titleId, onchange: () => { set(sel.value); ctx.save(sel.value); } },
      opts.map(([v, l]) => h('option', { value: v, text: l })));
    const set = (v) => { sel.value = v; const o = opts.find(([x]) => x === v); label.textContent = o ? o[1] : v; };
    set(spec.value);
    return { inline: h('span', { class: 'select' }, label, svg(CHEV), sel), set, busy: () => false };
  },

  TEXT(spec, ctx) {
    const f = h('input', { class: 'field', type: 'text', 'aria-labelledby': ctx.titleId, autocomplete: 'off', placeholder: spec.default || '',
      onkeydown: (e) => { if (e.key === 'Enter') f.blur(); },
      onchange: () => ctx.save(f.value) });
    const set = (v) => { f.value = v ?? ''; };
    set(spec.value);
    return { below: h('div', { class: 'stack' }, f), set };
  },

  TIME(spec, ctx) {
    const f = h('input', { class: 'field time', type: 'time', 'aria-labelledby': ctx.titleId, onchange: () => f.value && ctx.save(f.value) });
    const set = (v) => { f.value = v || ''; };
    set(spec.value);
    return { inline: f, set };
  },

  COLOR(spec, ctx) {
    const custom = h('input', { type: 'color', 'aria-label': 'Custom colour' });
    const customBtn = h('span', { class: 'swatch custom', role: 'radio' }, custom);
    const box = h('div', { class: 'swatches', role: 'radiogroup', 'aria-labelledby': ctx.titleId },
      SWATCHES.map((c) => h('button', { type: 'button', class: 'swatch', role: 'radio', 'data-v': c, 'aria-label': c,
        style: `background:${c}`, onclick: () => { set(c); ctx.save(c); } })), customBtn);
    custom.addEventListener('change', () => { const v = custom.value.toUpperCase(); set(v); ctx.save(v); });
    const set = (v) => {
      v = (v || '').toUpperCase();
      let found = false;
      for (const b of box.querySelectorAll('button.swatch')) { const on = b.dataset.v === v; b.setAttribute('aria-checked', on); found ||= on; }
      customBtn.setAttribute('aria-checked', !found && !!v);
      if (!found && v) { customBtn.style.background = v; custom.value = v.toLowerCase(); } else customBtn.style.background = '';
    };
    set(spec.value);
    return { below: box, set, busy: () => false };
  },

  TEXT_LIST(spec, ctx) {
    const list = h('div');
    const collect = () => [...list.querySelectorAll('input')].map((i) => i.value.trim()).filter(Boolean);
    const item = (v) => {
      const f = h('input', { class: 'field', type: 'text', value: v, autocomplete: 'off', 'aria-label': spec.title,
        onkeydown: (e) => { if (e.key === 'Enter') f.blur(); }, onchange: () => ctx.save(collect()) });
      const wrap = h('div', { class: 'list-item' }, f,
        h('button', { type: 'button', class: 'round-btn', 'aria-label': 'Remove', onclick: () => { wrap.remove(); ctx.save(collect()); } }, '−'));
      return wrap;
    };
    const add = h('button', { type: 'button', class: 'add-btn', onclick: () => { const w = item(''); list.append(w); $('input', w).focus(); } }, 'Add');
    const set = (v) => { list.textContent = ''; for (const x of (Array.isArray(v) ? v : [])) list.append(item(x)); };
    set(spec.value);
    return { below: h('div', {}, list, add), set };
  },

  LOCATION(spec, ctx) {
    const now = h('span', { class: 'loc-now' });
    const q = h('input', { class: 'field', type: 'search', placeholder: 'Search for a town or city', autocomplete: 'off',
      'aria-label': `${spec.title}: search`, enterkeyhint: 'search' });
    const results = h('div', { class: 'results', hidden: true });
    const lat = h('input', { class: 'field', inputmode: 'decimal', placeholder: 'Latitude', 'aria-label': 'Latitude' });
    const lon = h('input', { class: 'field', inputmode: 'decimal', placeholder: 'Longitude', 'aria-label': 'Longitude' });
    const auto = h('button', { type: 'button', class: 'text-btn', onclick: () => ctx.save('') }, 'Use automatic location');
    let timer, seq = 0;
    q.addEventListener('input', () => {
      clearTimeout(timer);
      const term = q.value.trim();
      if (term.length < 2) { results.hidden = true; return; }
      timer = setTimeout(async () => {
        const my = ++seq;
        try {
          const r = await fetch(`${GEOCODE}?name=${encodeURIComponent(term)}&count=6&language=en&format=json`);
          const data = await r.json();
          if (my !== seq) return;
          results.textContent = '';
          const list = data.results || [];
          if (!list.length) results.append(h('button', { type: 'button', disabled: true, text: 'No places found' }));
          for (const p of list) {
            const sub = [p.admin1, p.country].filter(Boolean).join(', ');
            results.append(h('button', { type: 'button', onclick: async () => {
              const place = [p.name, p.admin1 || p.country].filter(Boolean).join(', ');
              if (await ctx.save({ lat: p.latitude, lon: p.longitude, place })) { q.value = ''; results.hidden = true; }
            } }, p.name, ' ', h('small', { text: sub })));
          }
          results.hidden = false;
        } catch (e) {
          results.hidden = false;
          results.textContent = '';
          results.append(h('button', { type: 'button', disabled: true, text: 'Place search needs internet on this device' }));
        }
      }, 350);
    });
    const set = (v) => {
      if (v && typeof v === 'object') {
        now.textContent = v.place || `${(+v.lat).toFixed(3)}, ${(+v.lon).toFixed(3)}`;
        lat.value = v.lat; lon.value = v.lon; auto.hidden = false;
      } else { now.textContent = 'Automatic'; lat.value = ''; lon.value = ''; auto.hidden = true; }
    };
    set(spec.value);
    const manual = h('details', {}, h('summary', {}, 'Enter coordinates'),
      h('div', { class: 'coords' }, lat, lon,
        h('button', { type: 'button', class: 'text-btn', onclick: () => ctx.save({ lat: Number(lat.value), lon: Number(lon.value), place: '' }) }, 'Save')));
    return { inline: now, below: h('div', { class: 'stack' }, q, results, h('div', { class: 'secret-actions' }, auto), manual), set };
  },

  SECRET(spec, ctx) {
    const hints = spec.hints || {};
    const multi = 'multiline' in hints;
    const pill = h('span', { class: 'pill' });
    const f = multi
      ? h('textarea', { class: 'field masked', rows: 3, placeholder: hints.multiline || '', autocomplete: 'off', autocapitalize: 'off',
          spellcheck: 'false', 'aria-labelledby': ctx.titleId })
      : h('input', { class: 'field', type: 'password', placeholder: hints.placeholder || 'Paste here', autocomplete: 'off',
          autocapitalize: 'off', spellcheck: 'false', 'data-1p-ignore': true, 'aria-labelledby': ctx.titleId,
          onkeydown: (e) => { if (e.key === 'Enter') f.blur(); } });
    const commit = async () => {
      const v = f.value.trim();
      if (!v) return;
      if (await ctx.save(f.value)) f.value = '';
    };
    f.addEventListener('change', commit);
    const show = h('button', { type: 'button', class: 'text-btn', onclick: () => {
      const hidden = multi ? f.classList.toggle('masked') : (f.type = f.type === 'password' ? 'text' : 'password') === 'password';
      show.textContent = hidden ? 'Show' : 'Hide';
    } }, 'Show');
    const clear = h('button', { type: 'button', class: 'text-btn danger', onclick: async () => {
      if (confirm(`Remove the ${spec.title} from the Echo?`)) ctx.save(null);
    } }, 'Clear');
    const set = (state) => {
      pill.textContent = state === 'set' ? 'Set' : state === 'builtin' ? 'Built in' : 'Not set';
      pill.classList.toggle('set', state === 'set' || state === 'builtin');
      clear.hidden = state !== 'set';
      f.placeholder = state === 'set' || state === 'builtin' ? 'Type a new value to replace it' : (multi ? hints.multiline : hints.placeholder) || 'Paste here';
    };
    set(spec.secret);
    return { inline: pill, below: h('div', { class: 'stack' }, f, h('div', { class: 'secret-actions' }, show, clear)), set };
  },

  ACTION(spec, ctx) {
    const btn = h('button', { type: 'button', class: 'action-btn', onclick: async () => {
      btn.disabled = true;
      try {
        const r = await api('POST', `/api/action/${encodeURIComponent(spec.key)}`);
        toast(r.message || 'Done');
        ctx.ok();
      } catch (e) {
        if (e.status === 401) return showPair();
        toast(e.message, true);
      } finally { btn.disabled = false; }
    } }, spec.title);
    // The button is the title.
    queueMicrotask(() => { const t = ctx.el.querySelector('.row-title .t'); if (t) t.replaceWith(btn); });
    return { set: () => {}, busy: () => false };
  },
};

// ---------- paired browsers ----------
let lastPairs = null;
async function loadPairs() {
  try { renderPairs(lastPairs = await api('GET', '/api/pairs')); } catch (e) { if (e.status === 401) showPair(); }
}

function renderPairs(data) {
  const box = document.getElementById('browsers');
  if (!box) return;
  box.textContent = '';
  box.append(h('div', { class: 'subhead', text: 'Paired browsers' }));
  const fmt = (t) => new Date(t).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  for (const b of data.browsers) {
    box.append(h('div', { class: 'row' }, h('div', { class: 'row-main' },
      h('span', { class: 'row-title' }, h('span', {},
        h('span', { class: 't', text: b.name }), ' ', b.current && h('span', { class: 'badge', text: 'This browser' }),
        h('br'), h('span', { class: 'browser-meta', text: `Paired ${fmt(b.createdAt)} · last used ${fmt(b.lastSeenAt)}` }))),
      h('button', { type: 'button', class: 'text-btn danger', onclick: async () => {
        const msg = b.current ? 'Forget this browser? You’ll need a new code from the Echo to come back.' : `Forget ${b.name}?`;
        if (!confirm(msg)) return;
        try {
          const r = await api('DELETE', `/api/pairs/${encodeURIComponent(b.id)}`);
          if (r.self) return showPair();
          loadPairs();
        } catch (e) { toast(e.message, true); }
      } }, 'Forget'))));
  }
}

// ---------- live sync ----------
let es = null;
let retryTimer;
function setStatus(state) {
  $('#dot').className = `dot ${state === 'on' ? 'on' : 'wait'}`;
  $('#status-text').textContent = state === 'on' ? 'Connected · changes apply right away' : 'Reconnecting to the Echo…';
}

function openEvents() {
  closeEvents();
  es = new EventSource('/api/events');
  es.onopen = () => setStatus('on');
  es.addEventListener('change', (e) => { try { applyChange(JSON.parse(e.data)); } catch (x) { /* ignore */ } });
  es.addEventListener('schema', async () => { schema = await api('GET', '/api/schema'); render(); });
  es.addEventListener('pairs', () => loadPairs());
  es.onerror = () => {
    setStatus('wait');
    // EventSource retries on its own; if pairing was revoked it never succeeds, so check.
    clearTimeout(retryTimer);
    retryTimer = setTimeout(async () => {
      try {
        const r = await api('GET', '/api/hello');
        if (!r.paired) return showPair();
        if (es && es.readyState === EventSource.CLOSED) await loadApp();
      } catch (x) { /* Echo offline: EventSource keeps retrying */ }
    }, 4000);
  };
}
function closeEvents() { if (es) { es.close(); es = null; } clearTimeout(retryTimer); }

// Coming back to a backgrounded tab / home-screen app: resync everything.
document.addEventListener('visibilitychange', async () => {
  if (document.visibilityState !== 'visible' || $('#app').hidden) return;
  try { schema = await api('GET', '/api/schema'); render(); loadPairs(); if (!es || es.readyState === EventSource.CLOSED) openEvents(); }
  catch (e) { if (e.status === 401) showPair(); }
});

// ---------- boot ----------
async function boot() {
  applyTheme(store.get('theme', 'dark'));
  for (const b of document.querySelectorAll('[data-theme-set]')) {
    b.addEventListener('click', () => { store.set('theme', b.dataset.themeSet); applyTheme(b.dataset.themeSet); });
  }
  const adv = $('#advanced');
  adv.checked = advanced;
  adv.addEventListener('change', () => { advanced = adv.checked; store.set('advanced', advanced ? '1' : '0'); render(); });
  $('#pair-btn').addEventListener('click', startPair);
  $('#pair-again').addEventListener('click', () => { $('#pair-code').hidden = true; $('#pair-start').hidden = false; startPair(); });
  $('#code').addEventListener('input', (e) => {
    e.target.value = e.target.value.replace(/\D/g, '').slice(0, 6);
    if (e.target.value.length === 6) submitCode();
  });
  try {
    const hello = await api('GET', '/api/hello');
    setName(hello.name);
    if (hello.paired) await loadApp(); else showPair();
  } catch (e) {
    showPair();
    toast(e.message, true);
  }
}
boot();
