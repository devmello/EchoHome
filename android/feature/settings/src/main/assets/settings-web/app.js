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
  stopRemote();
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
  try { alarmsData = await api('GET', '/api/alarms'); } catch (e) { alarmsData = null; }
  try { peopleData = await api('GET', '/api/people'); } catch (e) { peopleData = null; }
  try { diagData = await api('GET', '/api/diagnostics'); } catch (e) { diagData = null; }
  $('#pair').hidden = true;
  $('#app').hidden = false;
  render();
  loadPairs();
  openEvents();
  setTab(tab);
}

function render() {
  const keep = window.scrollY;
  rows.clear();
  setName(schema.device.name);
  const main = $('#sections');
  const chips = $('#chips');
  main.textContent = '';
  chips.textContent = '';
  let alarmsPlaced = false;
  const placeAlarms = () => {
    if (alarmsPlaced || !alarmsData) return;
    alarmsPlaced = true;
    main.append(h('section', { class: 'section', id: 's-alarms', 'aria-labelledby': 'h-alarms' },
      h('h2', { id: 'h-alarms', text: 'Alarms' }), h('div', { class: 'group glass', id: 'alarm-list' })));
    chips.append(chip('alarms', 'Alarms'));
  };
  let peoplePlaced = false;
  const placePeople = () => {
    if (peoplePlaced || !peopleData) return;
    peoplePlaced = true;
    main.append(h('section', { class: 'section', id: 's-people', 'aria-labelledby': 'h-people' },
      h('h2', { id: 'h-people', text: 'People' }), h('div', { class: 'group glass', id: 'people-list' }),
      h('p', { class: 'section-help', text: 'Each person’s own calendars, shown when the Echo recognises them. Links are never shown again once saved.' })));
    chips.append(chip('people', 'People'));
  };
  for (const s of schema.sections) {
    if (s.order >= 90 && s.id !== 'general') placePeople();
    if (s.id === 'alarm' || (s.order >= 40 && s.id !== 'general')) placeAlarms();
    const specs = s.settings.filter((x) => advanced || !x.advanced);
    if (!specs.length && s.id !== 'general') continue;
    const group = h('div', { class: 'group glass' }, specs.map((spec) => row(spec)));
    if (s.id === 'general') group.append(h('div', { id: 'browsers' }));
    main.append(h('section', { class: 'section', id: `s-${s.id}`, 'aria-labelledby': `h-${s.id}` },
      h('h2', { id: `h-${s.id}`, text: s.title }),
      group,
      s.help && h('p', { class: 'section-help', text: s.help })));
    chips.append(chip(s.id, s.title));
  }
  placeAlarms();
  placePeople();
  if (diagData) {
    main.append(h('section', { class: 'section', id: 's-diag', 'aria-labelledby': 'h-diag' },
      h('h2', { id: 'h-diag', text: 'Diagnostics' }), h('div', { class: 'group glass', id: 'diag-list' }),
      h('p', { class: 'section-help', text: 'Crashes and hangs of the Echo app since the log was last cleared.' })));
    chips.append(chip('diag', 'Diagnostics'));
  }
  if (lastPairs) renderPairs(lastPairs);
  renderAlarms();
  renderPeople();
  renderDiag();
  window.scrollTo(0, keep);
}

function chip(id, title) {
  return h('a', { class: 'glass', href: `#s-${id}`, text: title, onclick: (e) => {
    e.preventDefault();
    document.getElementById(`s-${id}`).scrollIntoView({ behavior: 'smooth', block: 'start' });
  } });
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
  el.append(...[h('div', { class: 'row-main' }, title, r.inline), r.below, spec.help && h('p', { class: 'help', text: spec.help }), err].filter(Boolean));
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
    const f = h('input', { class: 'field', type: 'text', 'aria-labelledby': ctx.titleId, autocomplete: 'off', placeholder: spec.placeholder || spec.default || '',
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
    // A colour whose default is empty (the feature's own palette) gets an Automatic swatch.
    const auto = !spec.default && h('button', { type: 'button', class: 'swatch auto', role: 'radio', 'data-v': '',
      'aria-label': 'Automatic', title: 'Automatic', onclick: () => { set(''); ctx.save(null); } });
    const box = h('div', { class: 'swatches', role: 'radiogroup', 'aria-labelledby': ctx.titleId }, auto,
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
    f.addEventListener('input', () => { show.hidden = !f.value; });
    const commit = async () => {
      const v = f.value.trim();
      if (!v) return;
      if (await ctx.save(f.value)) { f.value = ''; show.hidden = true; }
    };
    f.addEventListener('change', commit);
    const show = h('button', { type: 'button', class: 'text-btn', onclick: () => {
      const hidden = multi ? f.classList.toggle('masked') : (f.type = f.type === 'password' ? 'text' : 'password') === 'password';
      show.textContent = hidden ? 'Show' : 'Hide';
    } }, 'Show');
    show.hidden = true;
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

// ---------- alarms (iOS Clock style) ----------
let alarmsData = null;   // {alarms: [...], next: {id, at} | null}
let openAlarm = null;    // id of the alarm whose editor is open, or 'new'
// Days are ISO numbers (Monday = 1). 2024-01-01 was a Monday.
const DAY_NAMES = [1, 2, 3, 4, 5, 6, 7].map((d) => new Date(Date.UTC(2024, 0, d)).toLocaleDateString(undefined, { weekday: 'short', timeZone: 'UTC' }));
const DAY_LETTERS = [1, 2, 3, 4, 5, 6, 7].map((d) => new Date(Date.UTC(2024, 0, d)).toLocaleDateString(undefined, { weekday: 'narrow', timeZone: 'UTC' }));
const HOUR12 = new Intl.DateTimeFormat(undefined, { hour: 'numeric' }).resolvedOptions().hour12 !== false;

function timeParts(t) {
  const [hh, mm] = t.split(':').map(Number);
  if (!HOUR12) return [`${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`, ''];
  return [`${hh % 12 || 12}:${String(mm).padStart(2, '0')}`, hh < 12 ? 'AM' : 'PM'];
}

function repeatText(days) {
  if (!days.length) return '';
  const set = days.slice().sort().join('');
  if (set === '1234567') return 'every day';
  if (set === '12345') return 'weekdays';
  if (set === '67') return 'weekends';
  return days.slice().sort().map((d) => DAY_NAMES[d - 1]).join(', ');
}

function nextText(next) {
  if (!next) return 'No alarms on';
  const mins = Math.max(0, Math.round((next.at - Date.now()) / 60000));
  if (mins < 60) return `Next alarm in ${mins} min`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `Next alarm in ${hrs} hr ${mins % 60} min`;
  return `Next alarm ${new Date(next.at).toLocaleString(undefined, { weekday: 'long', hour: 'numeric', minute: '2-digit' })}`;
}

async function alarmCall(method, path, body) {
  try { return await api(method, path, body); } catch (e) {
    if (e.status === 401) showPair(); else toast(e.message, true);
    return null;
  }
}

function renderAlarms() {
  const box = document.getElementById('alarm-list');
  if (!box || !alarmsData) return;
  box.textContent = '';
  box.append(h('div', { class: 'row alarm-head' }, h('div', { class: 'row-main' },
    h('span', { class: 'row-title alarm-next' }, h('span', {},
      nextText(alarmsData.next), alarmsData.next && alarmsData.next.reason && h('small', { class: 'next-reason', style: 'display:block', text: alarmsData.next.reason }))),
    h('button', { type: 'button', class: 'add-alarm', 'aria-label': 'Add alarm', onclick: () => { openAlarm = 'new'; renderAlarms(); } }, '+'))));
  if (openAlarm === 'new') box.append(alarmEditor(null));
  if (!alarmsData.alarms.length && openAlarm !== 'new') box.append(h('div', { class: 'row empty', text: 'No alarms yet. Tap + to add one.' }));
  for (const a of alarmsData.alarms) {
    const [time, ampm] = timeParts(a.time);
    const sub = [a.label, repeatText(a.days)].filter(Boolean).join(', ');
    const sw = h('input', { type: 'checkbox', class: 'switch', role: 'switch', 'aria-label': `${a.label} ${a.time}`,
      onchange: async () => {
        const r = await alarmCall('PUT', `/api/alarms/${encodeURIComponent(a.id)}`, { enabled: sw.checked });
        if (r) { Object.assign(a, r.alarm); renderAlarms(); } else sw.checked = a.enabled;
      } });
    sw.checked = a.enabled;
    const open = openAlarm === a.id;
    const row = h('div', { class: `row alarm-row${a.enabled ? '' : ' off'}${open ? ' open' : ''}` },
      h('div', { class: 'row-main' },
        h('button', { type: 'button', class: 'alarm-face', 'aria-expanded': open, onclick: () => { openAlarm = open ? null : a.id; renderAlarms(); } },
          h('span', { class: 'alarm-time' }, time, ampm && h('span', { class: 'alarm-ampm', text: ampm })),
          h('span', { class: 'alarm-sub', text: sub })),
        sw));
    box.append(row);
    if (open) row.append(alarmEditor(a));
  }
}

/** Edits to an existing alarm apply as you go; a new one is added with the Add alarm button. */
function alarmEditor(a) {
  const isNew = !a;
  const draft = a ? { ...a, days: [...a.days] } : (() => {
    const d = new Date(Date.now() + 3600e3);
    return { time: `${String(d.getHours()).padStart(2, '0')}:00`, days: [], label: 'Alarm', enabled: true, sunrise: true, leaveBy: false, readyMinutes: 45, skipHolidays: false };
  })();
  const ok = h('span', { class: 'saved', 'aria-hidden': 'true' }, svg(CHECK), 'Saved');
  let okTimer;
  const patch = async (fields) => {
    Object.assign(draft, fields);
    if (isNew) return;
    const r = await alarmCall('PUT', `/api/alarms/${encodeURIComponent(a.id)}`, fields);
    if (r) {
      Object.assign(a, r.alarm);
      ok.classList.add('show'); clearTimeout(okTimer); okTimer = setTimeout(() => ok.classList.remove('show'), 1400);
    }
  };
  const time = h('input', { class: 'field alarm-time-input', type: 'time', value: draft.time, 'aria-label': 'Time',
    onchange: () => time.value && patch({ time: time.value }) });
  const days = h('div', { class: 'days', role: 'group', 'aria-label': 'Repeat' }, [1, 2, 3, 4, 5, 6, 7].map((d) => {
    const b = h('button', { type: 'button', class: 'day', 'aria-pressed': String(draft.days.includes(d)), 'aria-label': DAY_NAMES[d - 1], text: DAY_LETTERS[d - 1],
      onclick: () => {
        const on = !draft.days.includes(d);
        b.setAttribute('aria-pressed', on);
        patch({ days: on ? [...draft.days, d].sort() : draft.days.filter((x) => x !== d) });
      } });
    return b;
  }));
  const label = h('input', { class: 'field', type: 'text', value: draft.label, maxlength: 40, 'aria-label': 'Label', autocomplete: 'off',
    onkeydown: (e) => { if (e.key === 'Enter') label.blur(); }, onchange: () => patch({ label: label.value }) });
  const sunrise = h('input', { type: 'checkbox', class: 'switch', role: 'switch', 'aria-label': 'Sunrise light', onchange: () => patch({ sunrise: sunrise.checked }) });
  sunrise.checked = draft.sunrise;
  const leave = h('input', { type: 'checkbox', class: 'switch', role: 'switch', 'aria-label': 'Leave on time',
    onchange: () => { ready.hidden = !leave.checked; patch({ leaveBy: leave.checked }); } });
  leave.checked = !!draft.leaveBy;
  const readyMin = h('input', { class: 'field inline-field', type: 'text', inputmode: 'numeric', value: draft.readyMinutes ?? 45, 'aria-label': 'Minutes to get ready',
    style: 'width:84px;text-align:center', onchange: () => {
      const n = Math.round(Number(readyMin.value));
      if (!(n >= 0 && n <= 180)) { readyMin.value = draft.readyMinutes ?? 45; return toast('Getting ready: 0 to 180 minutes', true); }
      patch({ readyMinutes: n });
    } });
  const ready = h('div', { class: 'ed-row' }, h('span', { class: 'ed-label' }, 'Getting ready', h('small', { text: 'Minutes between the alarm and walking out' })), readyMin);
  ready.hidden = !draft.leaveBy;
  const holidays = h('input', { type: 'checkbox', class: 'switch', role: 'switch', 'aria-label': 'Skip holidays', onchange: () => patch({ skipHolidays: holidays.checked }) });
  holidays.checked = !!draft.skipHolidays;

  // The sound is one setting for every alarm (alarm.sound on the schema), so it's edited here too.
  const soundSpec = currentSpec('alarm.sound');
  let sound = null;
  if (soundSpec && soundSpec.type === 'CHOICE') {
    const lab = h('span');
    const sel = h('select', { 'aria-label': 'Sound' }, soundSpec.options.map(([v, l]) => h('option', { value: v, text: l })));
    const setLab = () => { const o = soundSpec.options.find(([v]) => v === sel.value); lab.textContent = o ? o[1] : sel.value; };
    sel.addEventListener('change', async () => {
      setLab();
      try {
        const r = await api('PUT', '/api/settings', { 'alarm.sound': sel.value });
        if (r.values && r.values['alarm.sound']) applyChange(r.values['alarm.sound'], true);
      } catch (e) { toast(e.message, true); }
    });
    sel.value = soundSpec.value;
    setLab();
    sound = h('div', { class: 'ed-row' }, h('span', { class: 'ed-label' }, 'Sound', h('small', { text: 'Same for every alarm' })),
      h('span', { class: 'select' }, lab, svg(CHEV), sel));
  }

  const actions = isNew
    ? h('div', { class: 'ed-actions' },
        h('button', { type: 'button', class: 'text-btn', onclick: () => { openAlarm = null; renderAlarms(); } }, 'Cancel'),
        h('button', { type: 'button', class: 'btn-primary small', onclick: async () => {
          const r = await alarmCall('POST', '/api/alarms', { time: time.value || draft.time, days: draft.days, label: label.value, sunrise: sunrise.checked, leaveBy: leave.checked, readyMinutes: Number(readyMin.value) || 45, skipHolidays: holidays.checked, enabled: true });
          if (!r) return;
          openAlarm = null;
          alarmsData = (await alarmCall('GET', '/api/alarms')) || alarmsData;
          renderAlarms();
          toast(`Alarm set for ${timeParts(r.alarm.time).join(' ').trim()}`);
        } }, 'Add alarm'))
    : h('div', { class: 'ed-actions' }, ok,
        h('button', { type: 'button', class: 'text-btn danger', onclick: async () => {
          if (!confirm(`Delete the ${a.label} alarm?`)) return;
          if (await alarmCall('DELETE', `/api/alarms/${encodeURIComponent(a.id)}`)) {
            openAlarm = null;
            alarmsData.alarms = alarmsData.alarms.filter((x) => x.id !== a.id);
            renderAlarms();
          }
        } }, 'Delete alarm'),
        h('button', { type: 'button', class: 'text-btn', onclick: () => { openAlarm = null; renderAlarms(); } }, 'Done'));

  return h('div', { class: `alarm-editor${isNew ? ' new' : ''}` },
    h('div', { class: 'ed-row' }, h('span', { class: 'ed-label', text: 'Time' }), time),
    h('div', { class: 'ed-col' }, h('span', { class: 'ed-label', text: 'Repeat' }), days),
    h('div', { class: 'ed-col' }, h('span', { class: 'ed-label', text: 'Label' }), label),
    h('div', { class: 'ed-row' }, h('span', { class: 'ed-label' }, 'Sunrise light', h('small', { text: 'The screen brightens before it rings' })), sunrise),
    h('div', { class: 'ed-row' }, h('span', { class: 'ed-label' }, 'Leave on time', h('small', { text: 'Rings earlier when your first event needs it (travel time included). Never later than set.' })), leave),
    ready,
    h('div', { class: 'ed-row' }, h('span', { class: 'ed-label' }, 'Skip holidays', h('small', { text: 'Stays quiet on public holidays and day-off events' })), holidays),
    sound,
    actions);
}


// ---------- remote (live state + controls) ----------
const ICON = {
  play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.2v13.6a1 1 0 0 0 1.5.86l11-6.8a1 1 0 0 0 0-1.72l-11-6.8A1 1 0 0 0 8 5.2z"/></svg>',
  pause: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="4.5" width="4.2" height="15" rx="1.2"/><rect x="13.8" y="4.5" width="4.2" height="15" rx="1.2"/></svg>',
  next: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5.6v12.8a.9.9 0 0 0 1.4.75l9-6.4a.9.9 0 0 0 0-1.5l-9-6.4A.9.9 0 0 0 4 5.6z"/><rect x="16.5" y="5" width="3.2" height="14" rx="1.1"/></svg>',
  prev: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 5.6v12.8a.9.9 0 0 1-1.4.75l-9-6.4a.9.9 0 0 1 0-1.5l9-6.4A.9.9 0 0 1 20 5.6z"/><rect x="4.3" y="5" width="3.2" height="14" rx="1.1"/></svg>',
};
const POLL_MS = 2000;
let remote = null;          // last /api/remote reply
let remoteAt = 0;           // Date.now() when it arrived (timers count down from here)
let remoteUi = null;        // element refs, built once
let pollTimer = null, tickTimer = null;
let tab = store.get('tab', 'remote');
const touched = { volume: 0, brightness: 0, night: 0, dnd: 0 }; // last local change: don't let a poll fight the finger

function fmtDur(ms) {
  const s = Math.max(0, Math.ceil(ms / 1000));
  const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
  return hh ? `${hh}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}` : `${mm}:${String(ss).padStart(2, '0')}`;
}

async function remoteCall(method, path, body) {
  try { return await api(method, path, body); } catch (e) {
    if (e.status === 401) showPair(); else toast(e.message, true);
    return null;
  }
}

function slider(label, key, onSend) {
  const range = h('input', { type: 'range', min: 0, max: 100, step: 1, 'aria-label': label });
  const out = h('output');
  let last = 0, pending = null;
  const paint = () => { range.style.setProperty('--p', `${range.value}%`); out.textContent = `${range.value}%`; };
  const send = () => { last = Date.now(); touched[key] = last; onSend(range.value / 100); pending = null; };
  range.addEventListener('input', () => {
    paint(); touched[key] = Date.now();
    if (Date.now() - last > 140) send(); else if (!pending) pending = setTimeout(send, 140);
  });
  range.addEventListener('change', () => { clearTimeout(pending); send(); });
  const set = (v) => { if (Date.now() - touched[key] < 2500) return; range.value = Math.round(v * 100); paint(); };
  return { el: h('div', {}, h('div', { class: 'row-main' }, h('span', { class: 'slider-label', text: label })),
    h('div', { class: 'slider-row' }, range, out)), set };
}

function buildRemote() {
  const u = {};
  const box = $('#remote');
  box.textContent = '';
  const card = (title, ...kids) => h('section', { class: 'section' }, title && h('h2', { text: title }), h('div', { class: 'group glass' }, ...kids));

  // Now playing
  u.npTitle = h('div', { class: 'np-title' });
  u.npSub = h('div', { class: 'np-sub' });
  u.npBar = h('i');
  const tbtn = (cls, icon, label, fn) => h('button', { type: 'button', class: cls, 'aria-label': label, onclick: fn }, svg(icon));
  const media = async (action) => { if (await remoteCall('POST', '/api/remote/media', { action })) setTimeout(poll, 350); };
  u.prev = tbtn('', ICON.prev, 'Previous', () => media('previous'));
  u.pp = tbtn('big', ICON.play, 'Play', () => media(remote && remote.media && remote.media.playing ? 'pause' : 'play'));
  u.next = tbtn('', ICON.next, 'Next', () => media('next'));
  u.np = h('div', { class: 'row' }, u.npTitle, u.npSub, h('div', { class: 'np-bar' }, u.npBar), h('div', { class: 'transport' }, u.prev, u.pp, u.next));
  box.append(card('Now playing', u.np));

  // Volume, brightness, DND, night
  u.vol = slider('Volume', 'volume', (v) => remoteCall('POST', '/api/remote/system', { volume: v }));
  u.bri = slider('Brightness', 'brightness', (v) => remoteCall('POST', '/api/remote/system', { brightness: v }));
  u.dnd = h('input', { type: 'checkbox', class: 'switch', role: 'switch', 'aria-label': 'Do Not Disturb',
    onchange: () => { touched.dnd = Date.now(); remoteCall('POST', '/api/remote/system', { dnd: u.dnd.checked }); } });
  const nightOpts = [['auto', 'Auto'], ['on', 'On'], ['off', 'Off']];
  const setNight = (v) => { for (const b of u.night.children) b.setAttribute('aria-checked', b.dataset.v === v); };
  u.night = h('div', { class: 'seg wide', role: 'radiogroup', 'aria-label': 'Night mode' },
    nightOpts.map(([v, l]) => h('button', { type: 'button', role: 'radio', 'data-v': v, text: l, onclick: () => {
      touched.night = Date.now(); setNight(v); remoteCall('POST', '/api/remote/system', { night: v });
    } })));
  u.setNight = setNight;
  box.append(card('Controls',
    h('div', { class: 'row' }, u.vol.el), h('div', { class: 'row' }, u.bri.el),
    h('div', { class: 'row' }, h('div', { class: 'row-main' }, h('span', { class: 'row-title', text: 'Do Not Disturb' }), u.dnd)),
    h('div', { class: 'row' }, h('div', { class: 'row-main' }, h('span', { class: 'row-title', text: 'Night mode' })), h('div', { class: 'stack' }, u.night))));

  // Timers
  u.timers = h('div');
  const start = async (seconds) => { if (await remoteCall('POST', '/api/remote/timers', { seconds })) poll(); };
  const presets = h('div', { class: 'presets' }, [1, 5, 10, 15].map((m) => h('button', { type: 'button', class: 'mini', text: `${m} min`, onclick: () => start(m * 60) })));
  u.cMin = h('input', { class: 'field mins', inputmode: 'numeric', placeholder: 'min', 'aria-label': 'Minutes', maxlength: 4 });
  u.cLabel = h('input', { class: 'field', type: 'text', placeholder: 'Label (optional)', maxlength: 40, 'aria-label': 'Timer label', autocomplete: 'off' });
  const go = h('button', { type: 'button', class: 'mini', text: 'Start', onclick: async () => {
    const m = Number(u.cMin.value);
    if (!(m >= 1 && m <= 1440)) return toast('Enter 1 to 1440 minutes', true);
    if (await remoteCall('POST', '/api/remote/timers', { seconds: Math.round(m * 60), label: u.cLabel.value.trim() })) { u.cMin.value = ''; u.cLabel.value = ''; poll(); }
  } });
  box.append(card('Timers', u.timers,
    h('div', { class: 'row' }, presets, h('div', { class: 'custom-timer' }, u.cMin, u.cLabel, go))));

  // Next alarm
  u.alarm = h('div', { class: 'row' });
  box.append(card('Next alarm', u.alarm));

  // Note
  u.note = h('textarea', { class: 'field', rows: 2, maxlength: 280, placeholder: 'Dinner is ready', 'aria-label': 'Note for the Echo' });
  u.noteCount = h('div', { class: 'note-count', text: '0 / 280' });
  u.noteBtn = h('button', { type: 'button', class: 'btn-primary small', text: 'Send', onclick: async () => {
    const text = u.note.value.trim();
    if (!text) return;
    u.noteBtn.disabled = true;
    try { if (await remoteCall('POST', '/api/remote/note', { text })) { u.note.value = ''; u.noteCount.textContent = '0 / 280'; toast('Sent to the Echo'); } }
    finally { u.noteBtn.disabled = false; }
  } });
  u.note.addEventListener('input', () => { u.noteCount.textContent = `${u.note.value.length} / 280`; });
  box.append(card('Send a note to the Echo', h('div', { class: 'row' }, h('div', { class: 'send-row' }, u.note, u.noteBtn), u.noteCount)));
  remoteUi = u;
  timersSig = null;
}

let timersSig = '';
function renderRemote() {
  if (!remoteUi || !remote) return;
  const u = remoteUi, m = remote.media, s = remote.system;
  if (m) {
    u.npTitle.textContent = m.title || 'Playing';
    u.npSub.textContent = [m.artist, m.source].filter(Boolean).join(' · ');
    u.prev.disabled = u.next.disabled = !m.canSkip;
    u.pp.disabled = !m.canPause && m.playing;
    u.pp.replaceChildren(svg(m.playing ? ICON.pause : ICON.play));
    u.pp.setAttribute('aria-label', m.playing ? 'Pause' : 'Play');
  } else {
    u.npTitle.textContent = 'Nothing playing';
    u.npSub.textContent = 'Play something from your phone with AirPlay or Bluetooth.';
    u.prev.disabled = u.next.disabled = u.pp.disabled = true;
    u.pp.replaceChildren(svg(ICON.play));
  }
  u.npBar.parentElement.hidden = !m || !m.durationMs;
  u.vol.set(s.volume); u.bri.set(s.brightness);
  if (Date.now() - touched.dnd > 2500) u.dnd.checked = s.dnd;
  if (Date.now() - touched.night > 2500) u.setNight(s.night);

  const sig = JSON.stringify(remote.timers.map((t) => [t.id, t.label, t.state]));
  if (sig !== timersSig) {
    timersSig = sig;
    u.timers.textContent = '';
    if (!remote.timers.length) u.timers.append(h('div', { class: 'empty-note', text: 'No timers running.' }));
    for (const t of remote.timers) {
      const big = h('div', { class: 'big', 'data-timer': t.id });
      const acts = [];
      const act = (action, extra) => async () => { if (await remoteCall('POST', `/api/remote/timers/${encodeURIComponent(t.id)}`, { action, ...extra })) poll(); };
      if (t.state === 'running') acts.push(h('button', { type: 'button', class: 'mini', text: 'Pause', onclick: act('pause') }));
      if (t.state === 'paused') acts.push(h('button', { type: 'button', class: 'mini', text: 'Resume', onclick: act('resume') }));
      if (t.state !== 'done') acts.push(h('button', { type: 'button', class: 'mini', text: '+1 min', onclick: act('add', { seconds: 60 }) }));
      acts.push(h('button', { type: 'button', class: 'mini danger', text: t.state === 'done' ? 'Dismiss' : 'Cancel', onclick: act(t.state === 'done' ? 'dismiss' : 'cancel') }));
      u.timers.append(h('div', { class: `row timer${t.state === 'done' ? ' done' : ''}` },
        h('div', { class: 'tleft' }, big, h('small', { text: t.state === 'done' ? `${t.label || 'Timer'} · done` : [t.label, t.state === 'paused' ? 'Paused' : ''].filter(Boolean).join(' · ') })),
        h('div', { style: 'display:flex;gap:6px;flex-wrap:wrap;justify-content:flex-end' }, acts)));
    }
  }
  tickRemote();

  const a = remote.nextAlarm;
  u.alarm.textContent = '';
  if (a) {
    const [tm, ap] = timeParts(a.time);
    u.alarm.append(h('div', { class: 'np-title', text: `${tm}${ap ? ' ' + ap : ''}${a.label && a.label !== 'Alarm' ? ' · ' + a.label : ''}` }),
      h('div', { class: 'np-sub', text: nextText({ at: a.at }) }));
    if (a.reason) u.alarm.append(h('div', { class: 'next-reason', text: a.reason }));
  } else u.alarm.append(h('div', { class: 'np-sub', text: 'No alarms on' }));
}

/** Once a second: timers count down from the last poll, the progress bar moves. */
function tickRemote() {
  if (!remote || !remoteUi) return;
  const el = Date.now() - remoteAt;
  for (const t of remote.timers) {
    const n = remoteUi.timers.querySelector(`[data-timer="${CSS.escape(t.id)}"]`);
    if (n) n.textContent = t.state === 'done' ? '0:00' : fmtDur(t.state === 'running' ? t.remainingMs - el : t.remainingMs);
  }
  const m = remote.media;
  if (m && m.durationMs) remoteUi.npBar.style.width = `${Math.min(100, ((m.positionMs + (m.playing ? el : 0)) / m.durationMs) * 100)}%`;
}

async function poll() {
  if (document.visibilityState !== 'visible' || tab !== 'remote' || $('#app').hidden) return;
  try {
    const r = await api('GET', '/api/remote');
    remote = r; remoteAt = Date.now();
    renderRemote();
  } catch (e) { if (e.status === 401) showPair(); }
}

function startRemote() {
  stopRemote();
  if (tab !== 'remote' || $('#app').hidden || document.visibilityState !== 'visible') return;
  if (!remoteUi) buildRemote();
  poll();
  pollTimer = setInterval(poll, POLL_MS);
  tickTimer = setInterval(tickRemote, 1000);
}
function stopRemote() { clearInterval(pollTimer); clearInterval(tickTimer); pollTimer = tickTimer = null; }

function setTab(t) {
  tab = t;
  store.set('tab', t);
  for (const b of document.querySelectorAll('[data-tab]')) b.setAttribute('aria-checked', b.dataset.tab === t);
  $('#remote').hidden = t !== 'remote';
  $('#sections').hidden = t !== 'settings';
  $('#chips').hidden = t !== 'settings';
  window.scrollTo(0, 0);
  if (t === 'remote') startRemote(); else stopRemote();
}

// ---------- people: each person's calendars (write-only) ----------
let peopleData = null;   // {people: [{id, name, color, calendar: 'set'|'unset'}]}
let diagData = null;     // {entries: [...]}

function renderPeople() {
  const box = document.getElementById('people-list');
  if (!box || !peopleData) return;
  box.textContent = '';
  if (!peopleData.people.length) box.append(h('div', { class: 'empty-note', text: 'No people yet. Add one on the Echo.' }));
  for (const p of peopleData.people) {
    const pill = h('span', { class: 'pill' });
    const f = h('textarea', { class: 'field masked', rows: 3, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', 'aria-label': `${p.name}’s calendar links` });
    const clear = h('button', { type: 'button', class: 'text-btn danger', text: 'Clear', onclick: async () => {
      if (!confirm(`Remove ${p.name}’s calendar links from the Echo?`)) return;
      const r = await remoteCall('PUT', `/api/people/${encodeURIComponent(p.id)}/calendar`, { urls: null });
      if (r) { p.calendar = r.calendar; renderPeople(); }
    } });
    const show = h('button', { type: 'button', class: 'text-btn', text: 'Show', hidden: true, onclick: () => {
      const hidden = f.classList.toggle('masked'); show.textContent = hidden ? 'Show' : 'Hide';
    } });
    const save = h('button', { type: 'button', class: 'text-btn', text: 'Save links', hidden: true, onclick: async () => {
      const r = await remoteCall('PUT', `/api/people/${encodeURIComponent(p.id)}/calendar`, { urls: f.value });
      if (r) { p.calendar = r.calendar; toast(`${p.name}’s calendars saved`); renderPeople(); }
    } });
    f.addEventListener('input', () => { show.hidden = save.hidden = !f.value.trim(); });
    const isSet = p.calendar === 'set';
    pill.textContent = isSet ? 'Set' : 'Not set';
    pill.classList.toggle('set', isSet);
    clear.hidden = !isSet;
    f.placeholder = isSet ? 'Paste new links to replace them' : 'One iCal link per line (https:// or webcal://)';
    box.append(h('div', { class: 'row person' },
      h('div', { class: 'row-main' }, h('span', { class: 'dot-c', style: `background:${p.color}` }), h('span', { class: 'row-title' }, h('span', { class: 't', text: p.name })), pill),
      h('div', { class: 'stack' }, f, h('div', { class: 'secret-actions' }, save, show, clear))));
  }
}

// ---------- diagnostics ----------
function renderDiag() {
  const box = document.getElementById('diag-list');
  if (!box || !diagData) return;
  box.textContent = '';
  const list = diagData.entries;
  if (!list.length) { box.append(h('div', { class: 'empty-note', text: 'No crashes or hangs recorded.' })); return; }
  for (const e of list) {
    const when = new Date(e.at).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
    box.append(h('details', { class: 'crash' },
      h('summary', {}, h('span', { class: `kind ${e.kind}`, text: e.kind === 'hang' ? 'Hang' : 'Crash' }), h('span', { class: 'when', text: `${when} · ${e.thread}` })),
      h('div', { class: 'sum', text: e.summary }),
      e.stack && h('pre', { text: e.stack })));
  }
  box.append(h('div', { class: 'row' }, h('button', { type: 'button', class: 'text-btn danger', text: 'Clear', onclick: async () => {
    if (!confirm('Clear the crash log on the Echo?')) return;
    const r = await remoteCall('POST', '/api/diagnostics/clear', {});
    if (r) { diagData = r; renderDiag(); }
  } })));
}

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
  const fmt = (t) => new Date(t).toLocaleDateString(undefined, new Date(t).getFullYear() === new Date().getFullYear() ? { month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric', year: 'numeric' });
  const day = 864e5;
  for (const b of data.browsers) {
    box.append(h('div', { class: 'row' }, h('div', { class: 'row-main' },
      h('span', { class: 'row-title' }, h('span', {},
        h('span', { class: 't', text: b.name }), ' ', b.current && h('span', { class: 'badge', text: 'This browser' }),
        h('br'), h('span', { class: 'browser-meta', text: `Paired ${fmt(b.createdAt)}${b.lastSeenAt - b.createdAt > day ? ` · used ${fmt(b.lastSeenAt)}` : ''}` }))),
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
  $('#status-text').textContent = state === 'on' ? 'Connected' : 'Reconnecting to the Echo…';
}

function openEvents() {
  closeEvents();
  es = new EventSource('/api/events');
  es.onopen = () => setStatus('on');
  es.addEventListener('change', (e) => { try { applyChange(JSON.parse(e.data)); } catch (x) { /* ignore */ } });
  es.addEventListener('schema', async () => { schema = await api('GET', '/api/schema'); render(); });
  es.addEventListener('pairs', () => loadPairs());
  es.addEventListener('people', (e) => {
    try { peopleData = JSON.parse(e.data); } catch (x) { return; }
    const box = document.getElementById('people-list');
    if (box && !box.contains(document.activeElement)) renderPeople();
  });
  es.addEventListener('alarms', (e) => {
    try { alarmsData = JSON.parse(e.data); } catch (x) { return; }
    const box = document.getElementById('alarm-list');
    if (!box) return render();
    if (!box.contains(document.activeElement)) renderAlarms();
  });
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
  if (document.visibilityState !== 'visible') { stopRemote(); return; }
  if ($('#app').hidden) return;
  startRemote();
  try { schema = await api('GET', '/api/schema'); alarmsData = await api('GET', '/api/alarms').catch(() => alarmsData); peopleData = await api('GET', '/api/people').catch(() => peopleData); diagData = await api('GET', '/api/diagnostics').catch(() => diagData); render(); loadPairs(); if (!es || es.readyState === EventSource.CLOSED) openEvents(); }
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
  for (const b of document.querySelectorAll('[data-tab]')) b.addEventListener('click', () => setTab(b.dataset.tab));
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
