// ASMR Admin: Weboberfläche für die Verwaltung. Spricht dieselbe API wie die App.
// Bewusst ohne Build-Schritt und ohne Framework; Texte vom Server landen nur per textContent im DOM.

const root = document.getElementById('app');
const state = {
  token: localStorage.getItem('asmr.token'),
  me: null,
  catalog: null,
};

// ---------------------------------------------------------------------------------------------
// Hilfsfunktionen

/** Baut ein Element; Strings und Zahlen werden als Text eingefügt (kein HTML). */
function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key === 'style' && typeof value === 'object') Object.assign(el.style, value);
    else if (key.startsWith('on')) el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key in el && key !== 'list') el[key] = value;
    else el.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

function clear(el, ...children) {
  el.replaceChildren(...children.flat(Infinity).filter((c) => c !== null && c !== undefined && c !== false));
  return el;
}

async function api(path, { method = 'GET', body } = {}) {
  const headers = { 'X-Asmr-Web': '1' };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  let payload;
  if (body instanceof Blob) {
    payload = body;
    headers['Content-Type'] = 'application/octet-stream';
  } else if (body !== undefined) {
    payload = JSON.stringify(body);
    headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(`/api${path}`, { method, headers, body: payload });
  if (res.status === 401 && state.token) {
    logout();
    throw new Error('Sitzung abgelaufen');
  }
  if (!res.ok) {
    let message = `Fehler ${res.status}`;
    try { message = (await res.json()).error || message; } catch { /* kein JSON */ }
    throw new Error(message);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

let toastTimer;
function toast(message, error = false) {
  document.querySelector('.toast')?.remove();
  const el = h('div', { class: error ? 'toast error' : 'toast' }, message);
  document.body.append(el);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.remove(), error ? 6000 : 2500);
}

/** Führt eine Aktion aus, meldet Erfolg/Fehler und gibt das Ergebnis zurück. */
async function run(action, success) {
  try {
    const result = await action();
    if (success) toast(success);
    return result;
  } catch (e) {
    toast(e.message, true);
    return undefined;
  }
}

const enc = encodeURIComponent;
const formatDuration = (seconds) => {
  if (seconds == null) return '';
  const s = Math.floor(seconds);
  const hh = Math.floor(s / 3600);
  const mm = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, '0');
  return hh > 0 ? `${hh}:${String(mm).padStart(2, '0')}:${ss}` : `${mm}:${ss}`;
};
const folderName = (path) => path.split('/').pop() || 'Bibliothek';

// Icons wie in der App (Material Icons, Apache 2.0).
const ICONS = {
  headphones: 'M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7c0,-4.97 -4.03,-9 -9,-9z',
  music: 'M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z',
  mic: 'M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z',
  ear: 'M17,20c-0.29,0 -0.56,-0.06 -0.76,-0.15 -0.71,-0.37 -1.21,-0.88 -1.71,-2.38 -0.51,-1.56 -1.47,-2.29 -2.39,-3 -0.79,-0.61 -1.61,-1.24 -2.32,-2.53C9.29,10.98 9,9.93 9,9c0,-2.8 2.2,-5 5,-5s5,2.2 5,5h2c0,-3.93 -3.07,-7 -7,-7S7,5.07 7,9c0,1.26 0.38,2.65 1.07,3.9 0.91,1.65 1.98,2.48 2.85,3.15 0.81,0.62 1.39,1.07 1.71,2.05 0.6,1.82 1.37,2.84 2.73,3.55 0.51,0.23 1.07,0.35 1.64,0.35 2.21,0 4,-1.79 4,-4h-2c0,1.1 -0.9,2 -2,2zM7.64,2.64L6.22,1.22C4.23,3.21 3,5.96 3,9s1.23,5.79 3.22,7.78l1.41,-1.41C6.01,13.74 5,11.49 5,9s1.01,-4.74 2.64,-6.36zM11.5,9c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5 -1.12,-2.5 -2.5,-2.5 -2.5,1.12 -2.5,2.5z',
  speaker: 'M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z',
  equalizer: 'M7,18h2V6H7v12zM11,22h2V2h-2v20zM3,14h2v-4H3v4zM15,18h2V6h-2v12zM19,10v4h2v-4h-2z',
  bars: 'M3,11h2v2H3zM7,8h2v8H7zM11,4h2v16h-2zM15,9h2v6h-2zM19,6h2v12h-2z',
  sine: 'M1,12Q4.5,4 8,12T15,12T22,12',
  pulse: 'M2,12h4l2,-6l4,12l3,-9l2,3h5',
  ripple: 'M6,9.5a3,3 0 0 1 0,5M10,6.5a7,7 0 0 1 0,11M14,3.5a11,11 0 0 1 0,17M2.5,12h0.01',
  leaf: 'M6.05,8.05c-2.73,2.73 -2.73,7.15 -0.02,9.88c1.47,-3.4 4.09,-6.24 7.36,-7.93c-2.77,2.34 -4.71,5.61 -5.39,9.32c2.6,1.23 5.8,0.78 7.95,-1.37C19.43,14.47 20,4 20,4S9.53,4.57 6.05,8.05z',
  tree: 'M17,12h2L12,2 5.05,12H7l-3.9,6h6.92v4h3.95v-4H21z',
  water: 'M12,2c-5.33,4.55 -8,8.48 -8,11.8c0,4.98 3.8,8.2 8,8.2s8,-3.22 8,-8.2C20,10.48 17.33,6.55 12,2z',
  cloud: 'M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z',
  fire: 'M13.5,0.67s0.74,2.65 0.74,4.8c0,2.06 -1.35,3.73 -3.41,3.73 -2.07,0 -3.63,-1.67 -3.63,-3.73l0.03,-0.36C5.21,7.51 4,10.62 4,14c0,4.42 3.58,8 8,8s8,-3.58 8,-8C20,8.61 17.41,3.8 13.5,0.67zM11.71,19c-1.78,0 -3.22,-1.4 -3.22,-3.14 0,-1.62 1.05,-2.76 2.81,-3.12 1.77,-0.36 3.6,-1.21 4.62,-2.58 0.39,1.29 0.59,2.65 0.59,4.04 0,2.65 -2.15,4.8 -4.8,4.8z',
  bolt: 'M7,2v11h3v9l7,-12h-4l4,-8z',
  sun: 'M6.76,4.84l-1.8,-1.79 -1.41,1.41 1.79,1.79 1.42,-1.41zM4,10.5H1v2h3v-2zM13,0.55h-2V3.5h2V0.55zM20.45,4.46l-1.41,-1.41 -1.79,1.79 1.41,1.41 1.79,-1.79zM17.24,18.16l1.79,1.8 1.41,-1.41 -1.8,-1.79 -1.4,1.4zM20,10.5v2h3v-2h-3zM12,5.5c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6zM11,22.45h2V19.5h-2v2.95zM3.55,18.54l1.41,1.41 1.79,-1.8 -1.41,-1.41 -1.79,1.8z',
  moon: 'M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10 3.71,0 6.93,-2.02 8.66,-5.02 -7.51,-0.25 -12.09,-8.43 -8.32,-14.96z',
  star: 'M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z',
  heart: 'M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z',
  smile: 'M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM15.5,11c0.83,0 1.5,-0.67 1.5,-1.5S16.33,8 15.5,8 14,8.67 14,9.5s0.67,1.5 1.5,1.5zM8.5,11c0.83,0 1.5,-0.67 1.5,-1.5S9.33,8 8.5,8 7,8.67 7,9.5 7.67,11 8.5,11zM12,17.5c2.33,0 4.31,-1.46 5.11,-3.5H6.89c0.8,2.04 2.78,3.5 5.11,3.5z',
  meditation: 'M12,2c1.1,0 2,0.9 2,2s-0.9,2 -2,2 -2,-0.9 -2,-2 0.9,-2 2,-2zM21,16v-2c-2.24,0 -4.16,-0.96 -5.6,-2.68l-1.34,-1.6C13.68,9.26 13.12,9 12.53,9h-1.05c-0.59,0 -1.15,0.26 -1.53,0.72l-1.34,1.6C7.16,13.04 5.24,14 3,14v2c2.77,0 5.19,-1.17 7,-3.25V15l-3.88,1.55C5.45,16.82 5,17.48 5,18.21C5,19.2 5.8,20 6.79,20H9v-0.5c0,-1.38 1.12,-2.5 2.5,-2.5h3c0.28,0 0.5,0.22 0.5,0.5S14.78,18 14.5,18h-3c-0.83,0 -1.5,0.67 -1.5,1.5V20h7.21C18.2,20 19,19.2 19,18.21c0,-0.73 -0.45,-1.39 -1.12,-1.66L14,15v-2.25C15.81,14.83 18.23,16 21,16z',
  bed: 'M7,13c1.66,0 3,-1.34 3,-3S8.66,7 7,7s-3,1.34 -3,3 1.34,3 3,3zM19,7h-8v7H3V5H1v15h2v-3h18v3h2v-9c0,-2.21 -1.79,-4 -4,-4z',
  home: 'M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z',
  coffee: 'M20,3H4v10c0,2.21 1.79,4 4,4h6c2.21,0 4,-1.79 4,-4v-3h2c1.11,0 2,-0.9 2,-2V5c0,-1.11 -0.89,-2 -2,-2zM20,8h-2V5h2v3zM2,21h18v-2H2v2z',
  book: 'M18,2H6c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM6,4h5v8l-2.5,-1.5L6,12V4z',
  brush: 'M7,14c-1.66,0 -3,1.34 -3,3 0,1.31 -1.16,2 -2,2 0.92,1.22 2.49,2 4,2 2.21,0 4,-1.79 4,-4 0,-1.66 -1.34,-3 -3,-3zM20.71,4.63l-1.34,-1.34c-0.39,-0.39 -1.02,-0.39 -1.41,0L9,12.25 11.75,15l8.96,-8.96c0.39,-0.39 0.39,-1.02 0,-1.41z',
};
/** Als Linie gezeichnet statt gefüllt. */
const STROKE_ICONS = new Set(['sine','pulse','ripple']);
function icon(key) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', 'icon');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', ICONS[key] || ICONS.headphones);
  if (STROKE_ICONS.has(key)) {
    Object.entries({ fill: 'none', stroke: 'currentColor', 'stroke-width': 2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })
      .forEach(([name, value]) => path.setAttribute(name, value));
  }
  svg.append(path);
  return svg;
}

// ---------------------------------------------------------------------------------------------
// Anmeldung (Passwort oder SSO mit PKCE)

function logout() {
  if (state.token) fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${state.token}` } });
  state.token = null;
  state.me = null;
  localStorage.removeItem('asmr.token');
  renderLogin();
}

const b64url = (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

async function startSso() {
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(48)));
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  sessionStorage.setItem('asmr.sso', verifier);
  location.href = `/api/auth/sso/start?code_challenge=${b64url(digest)}&target=web`;
}

/** Rückkehr vom SSO-Login: Code steht im Fragment (#sso_code=… bzw. #sso_error=…). */
async function finishSsoIfReturning() {
  const params = new URLSearchParams(location.hash.slice(1));
  const code = params.get('sso_code');
  const error = params.get('sso_error');
  if (!code && !error) return;
  history.replaceState(null, '', '/admin/');
  if (error) {
    toast(error, true);
    return;
  }
  const verifier = sessionStorage.getItem('asmr.sso');
  sessionStorage.removeItem('asmr.sso');
  const result = await run(() => api('/auth/sso/exchange', { method: 'POST', body: { code, codeVerifier: verifier } }));
  if (result) setSession(result.token);
}

function setSession(token) {
  state.token = token;
  localStorage.setItem('asmr.token', token);
}

async function renderLogin() {
  const config = await api('/auth/config').catch(() => ({ passwordLogin: true }));
  const username = h('input', { type: 'text', placeholder: 'Benutzername', autocomplete: 'username' });
  const password = h('input', { type: 'password', placeholder: 'Passwort', autocomplete: 'current-password' });
  const submit = async (event) => {
    event.preventDefault();
    const result = await run(() => api('/auth/login', { method: 'POST', body: { username: username.value, password: password.value } }));
    if (result) {
      setSession(result.token);
      boot();
    }
  };
  clear(root, h('div', { class: 'login card stack' },
    h('h1', {}, 'ASMR Admin'),
    config.sso && h('button', { class: 'primary', onclick: startSso, style: { width: '100%' } }, `Mit ${config.sso.providerName} anmelden`),
    h('form', { class: 'stack', onsubmit: submit }, username, password, h('button', { type: 'submit', style: { width: '100%' } }, 'Mit Passwort anmelden')),
  ));
}

// ---------------------------------------------------------------------------------------------
// Rahmen und Navigation

const PAGES = [
  ['dashboard', 'Übersicht', renderDashboard],
  ['tracks', 'Tracks', renderTracks],
  ['folders', 'Ordner', renderFolders],
  ['categories', 'Kategorien', renderCategories],
  ['matrix', 'Bewertungsmatrix', renderMatrix],
  ['creators', 'ASMRtists', renderCreators],
  ['users', 'Benutzer', renderUsers],
  ['imports', 'Importe', renderImports],
];

const main = h('main');

function renderShell() {
  const current = location.hash.replace(/^#\//, '').split('?')[0] || 'dashboard';
  const nav = h('nav', { class: 'sidebar' },
    h('div', { class: 'brand' }, 'ASMR Admin'),
    PAGES.map(([id, label]) => h('a', { href: `#/${id}`, class: id === current ? 'active' : '' }, label)),
    h('div', { class: 'who' }, state.me.displayName || state.me.username, ' · ', h('a', { href: '#', onclick: (e) => { e.preventDefault(); logout(); } }, 'Abmelden')),
  );
  clear(root, h('div', { class: 'layout' }, nav, main));
  const page = PAGES.find(([id]) => id === current) || PAGES[0];
  clear(main, h('p', { class: 'muted' }, 'Lädt …'));
  page[2](main).catch((e) => clear(main, h('p', { class: 'muted' }, e.message)));
}

window.addEventListener('hashchange', () => { if (state.me) renderShell(); });

async function boot() {
  await finishSsoIfReturning();
  if (!state.token) return renderLogin();
  try {
    state.me = await api('/me');
  } catch {
    return renderLogin();
  }
  if (!state.me.isAdmin) {
    clear(root, h('div', { class: 'login card stack' }, h('h1', {}, 'Nur für Admins'),
      h('p', { class: 'muted' }, 'Dieses Konto hat keine Admin-Rechte.'), h('button', { onclick: logout }, 'Abmelden')));
    return;
  }
  state.catalog = await api('/catalog');
  renderShell();
}

// ---------------------------------------------------------------------------------------------
// Übersicht

async function renderDashboard(el) {
  const [items, creators, categories, imports] = await Promise.all([
    api('/items?pageSize=1'), api('/creators'), api('/categories'), api('/imports'),
  ]);
  const running = imports.filter((j) => j.status === 'QUEUED' || j.status === 'RUNNING').length;
  clear(el,
    h('h1', {}, 'Übersicht'),
    h('div', { class: 'cards' },
      stat('Tracks', items.total), stat('ASMRtists', creators.length), stat('Kategorien', categories.length), stat('Importe laufen', running)),
    h('div', { class: 'card stack', style: { marginTop: '16px' } },
      h('h2', {}, 'Medienordner'),
      h('p', { class: 'muted' }, 'Neue Dateien auf dem NAS werden alle 30 Minuten übernommen, umbenannte Ordner beim Öffnen sofort.'),
      h('button', { onclick: () => run(() => api('/library/scan', { method: 'POST' }), 'Scan gestartet') }, 'Jetzt scannen')),
    importForm(() => renderDashboard(el)),
  );
}

const stat = (label, value) => h('div', { class: 'card' }, h('div', { class: 'muted small' }, label), h('div', { class: 'stat' }, value));

function importForm(after) {
  const url = h('input', { type: 'url', placeholder: 'https://www.youtube.com/watch?v=…', class: 'grow' });
  return h('form', {
    class: 'card stack', style: { marginTop: '16px' },
    onsubmit: async (e) => {
      e.preventDefault();
      if (await run(() => api('/imports', { method: 'POST', body: { url: url.value } }), 'Import gestartet') !== undefined) {
        url.value = '';
        after?.();
      }
    },
  }, h('h2', {}, 'Importieren'), h('div', { class: 'row' }, url, h('button', { class: 'primary', type: 'submit' }, 'Importieren')));
}

// ---------------------------------------------------------------------------------------------
// Tracks: Tabelle mit Filtern, Editor und Massenaktionen

const trackState = { q: '', tag: '', creator: '', sort: 'TITLE', page: 0, selected: new Set(), editing: null };

async function renderTracks(el) {
  const [tags, creators] = await Promise.all([api('/tags'), api('/creators')]);
  const q = h('input', { type: 'search', placeholder: 'Suchen', value: trackState.q, class: 'grow' });
  const tag = h('select', {}, h('option', { value: '' }, 'Alle Trigger'), tags.map((t) => h('option', { value: t.name, selected: t.name === trackState.tag }, `${t.name} (${t.count})`)));
  const creator = h('select', {}, h('option', { value: '' }, 'Alle ASMRtists'), creators.map((c) => h('option', { value: c.name, selected: c.name === trackState.creator }, `${c.name} (${c.itemCount})`)));
  const sort = h('select', {}, [['TITLE', 'Titel'], ['ADDED', 'Neueste']].map(([v, l]) => h('option', { value: v, selected: v === trackState.sort }, l)));
  const tableBox = h('div');
  const bulkBox = h('div');
  const panel = h('div', { class: 'panel' });

  let timer;
  const reload = () => {
    Object.assign(trackState, { q: q.value, tag: tag.value, creator: creator.value, sort: sort.value });
    loadTable();
  };
  q.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { trackState.page = 0; reload(); }, 300); });
  [tag, creator, sort].forEach((s) => s.addEventListener('change', () => { trackState.page = 0; reload(); }));

  async function loadTable() {
    const params = new URLSearchParams({ page: trackState.page, pageSize: 50, sort: trackState.sort });
    if (trackState.q) params.set('q', trackState.q);
    if (trackState.tag) params.set('tag', trackState.tag);
    if (trackState.creator) params.set('creator', trackState.creator);
    const page = await api(`/items?${params}`);
    const pages = Math.max(1, Math.ceil(page.total / page.pageSize));
    clear(tableBox,
      h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, h('input', {
          type: 'checkbox',
          checked: page.items.length > 0 && page.items.every((i) => trackState.selected.has(i.id)),
          onchange: (e) => { page.items.forEach((i) => e.target.checked ? trackState.selected.add(i.id) : trackState.selected.delete(i.id)); loadTable(); renderBulk(); },
        })), h('th', {}), h('th', {}, 'Titel'), h('th', {}, 'ASMRtist'), h('th', {}, 'Länge'), h('th', {}, 'Ordner'), h('th', {}, 'Trigger'))),
        h('tbody', {}, page.items.map((item) => h('tr', {
          class: `clickable${trackState.editing === item.id ? ' selected' : ''}`,
          onclick: (e) => { if (e.target.type !== 'checkbox') openEditor(item.id); },
        },
        h('td', {}, h('input', {
          type: 'checkbox', checked: trackState.selected.has(item.id),
          onchange: (e) => { e.target.checked ? trackState.selected.add(item.id) : trackState.selected.delete(item.id); renderBulk(); },
        })),
        h('td', {}, item.hasCover ? h('img', { class: 'thumb', src: `/api/items/${item.id}/cover`, loading: 'lazy' }) : h('div', { class: 'thumb' })),
        h('td', {}, h('div', { class: 'title' }, item.title)),
        h('td', {}, item.creator || ''),
        h('td', { class: 'muted' }, formatDuration(item.durationSeconds)),
        h('td', { class: 'muted small' }, item.folder || '–'),
        h('td', {}, Object.entries(item.levels).slice(0, 3).map(([t, l]) => h('span', { class: 'chip' }, `${t} ${l}`))),
        ))),
      ),
      h('div', { class: 'row', style: { marginTop: '10px' } },
        h('span', { class: 'muted grow' }, `${page.total} Tracks`),
        h('button', { disabled: trackState.page === 0, onclick: () => { trackState.page--; loadTable(); } }, '←'),
        h('span', { class: 'muted' }, `Seite ${trackState.page + 1} / ${pages}`),
        h('button', { disabled: trackState.page + 1 >= pages, onclick: () => { trackState.page++; loadTable(); } }, '→')),
    );
  }

  function renderBulk() {
    const ids = [...trackState.selected];
    if (!ids.length) return clear(bulkBox);
    clear(bulkBox, bulkActions(ids, async () => { await loadTable(); if (trackState.editing) openEditor(trackState.editing); }));
  }

  async function openEditor(id) {
    trackState.editing = id;
    loadTable();
    await trackEditor(panel, id, async () => { await loadTable(); }, () => { trackState.editing = null; clear(panel); loadTable(); });
  }

  clear(el,
    h('h1', {}, 'Tracks'),
    h('div', { class: 'row', style: { marginBottom: '12px' } }, q, tag, creator, sort),
    bulkBox,
    h('div', { class: 'split' }, tableBox, panel),
  );
  renderBulk();
  await loadTable();
  if (trackState.editing) openEditor(trackState.editing);
}

/** Lädt Kategorien samt Zuordnungen (für Editor und Massenaktionen). */
async function categoriesWithMembers() {
  const categories = await api('/categories');
  return Promise.all(categories.map(async (c) => ({ ...c, detail: await api(`/categories/${c.id}`) })));
}

async function trackEditor(panel, id, onSaved, onClose) {
  const [item, categories, folders] = await Promise.all([api(`/items/${id}`), categoriesWithMembers(), api('/folders/all')]);
  const title = h('input', { type: 'text', value: item.title, style: { width: '100%' } });
  const creator = h('input', { type: 'text', value: item.creator || '', style: { width: '100%' } });
  const folder = h('select', { style: { width: '100%' } },
    h('option', { value: '', selected: item.folder === '' }, 'Bibliothek (oberste Ebene)'),
    folders.map((f) => h('option', { value: f, selected: f === item.folder }, f)));
  const memberIds = new Set(categories.filter((c) => c.detail.itemIds.includes(id)).map((c) => c.id));
  const categoryBoxes = categories.map((c) => {
    const box = h('input', { type: 'checkbox', checked: memberIds.has(c.id) });
    const viaFolder = c.detail.folders.some((f) => item.folder === f || item.folder.startsWith(`${f}/`));
    return { c, box, label: h('label', { class: 'check' }, box, c.name, viaFolder && h('span', { class: 'muted small' }, '(über Ordner)')) };
  });
  const levels = { ...item.levels };
  const matrix = levelMatrix(levels);

  const save = async () => {
    const patch = {};
    if (title.value.trim() !== item.title) patch.title = title.value.trim();
    if (creator.value.trim() !== (item.creator || '')) patch.creator = creator.value.trim();
    const cleaned = Object.fromEntries(Object.entries(levels).filter(([, l]) => l > 0));
    if (JSON.stringify(cleaned) !== JSON.stringify(item.levels)) patch.levels = cleaned;
    if (folder.value !== item.folder) patch.folder = folder.value;
    if (Object.keys(patch).length) await api(`/items/${id}`, { method: 'PATCH', body: patch });
    for (const { c, box } of categoryBoxes) {
      if (box.checked !== memberIds.has(c.id)) await api(`/categories/${c.id}/items/${id}`, { method: box.checked ? 'PUT' : 'DELETE' });
    }
  };

  clear(panel, h('div', { class: 'card stack' },
    h('div', { class: 'row' }, h('h2', { class: 'grow' }, 'Track bearbeiten'), h('button', { onclick: onClose }, 'Schließen')),
    item.hasCover && h('img', { src: `/api/items/${id}/cover`, style: { width: '100%', borderRadius: '10px' } }),
    h('audio', { controls: true, preload: 'none', src: `/api/items/${id}/audio` }),
    h('label', { class: 'muted small' }, 'Titel'), title,
    h('label', { class: 'muted small' }, 'ASMRtist'), creator,
    h('label', { class: 'muted small' }, 'Ordner (verschiebt die Datei auf dem NAS)'), folder,
    categories.length > 0 && h('div', {}, h('h3', {}, 'Kategorien'), h('div', { class: 'stack' }, categoryBoxes.map((b) => b.label))),
    h('h3', {}, 'Trigger-Matrix'), matrix,
    item.sourceUrl && h('p', { class: 'small' }, 'Quelle: ', h('a', { href: item.sourceUrl, target: '_blank', rel: 'noreferrer' }, item.sourceUrl)),
    h('div', { class: 'row' },
      h('button', { class: 'primary', onclick: async () => { if (await run(save, 'Gespeichert') !== undefined) { await onSaved(); trackEditor(panel, id, onSaved, onClose); } } }, 'Speichern'),
      h('span', { class: 'grow' }),
      h('button', {
        class: 'danger',
        onclick: async () => {
          if (!confirm(`"${item.title}" mit Datei, eigenem Bild und Metadaten vom NAS löschen?`)) return;
          if (await run(() => api(`/items/${id}`, { method: 'DELETE' }), 'Gelöscht') !== undefined) { onClose(); }
        },
      }, 'Löschen')),
  ));
}

/** Schieberegler 0..10 je Trigger (Katalog plus eigene); schreibt direkt in [levels]. */
function levelMatrix(levels) {
  const known = state.catalog.triggers.flatMap((g) => g.triggers);
  const custom = Object.keys(levels).filter((k) => !known.some((t) => t.toLowerCase() === k.toLowerCase()));
  const rows = (name) => {
    const key = Object.keys(levels).find((k) => k.toLowerCase() === name.toLowerCase()) || name;
    const value = h('span', { class: (levels[key] || 0) === 0 ? 'zero' : '' }, levels[key] || '–');
    const range = h('input', {
      type: 'range', min: 0, max: 10, step: 1, value: levels[key] || 0,
      oninput: (e) => {
        const v = Number(e.target.value);
        levels[key] = v;
        value.textContent = v || '–';
        value.className = v === 0 ? 'zero' : '';
      },
    });
    return [h('span', { class: (levels[key] || 0) === 0 ? 'muted' : '' }, name), range, value];
  };
  const box = h('div', { class: 'stack' },
    state.catalog.triggers.map((g) => h('div', {}, h('div', { class: 'muted small' }, g.name), h('div', { class: 'matrix' }, g.triggers.map(rows)))),
    custom.length > 0 && h('div', {}, h('div', { class: 'muted small' }, 'Eigene'), h('div', { class: 'matrix' }, custom.map(rows))));
  const input = h('input', { type: 'text', placeholder: 'Eigener Trigger', class: 'grow' });
  box.append(h('div', { class: 'row' }, input, h('button', {
    onclick: () => {
      const name = input.value.trim();
      if (!name) return;
      levels[name] = 5;
      box.replaceWith(levelMatrix(levels));
    },
  }, 'Hinzufügen')));
  return box;
}

/** Aktionen für mehrere markierte Tracks; nutzt dieselben Endpunkte wie der Editor. */
function bulkActions(ids, after) {
  const category = h('select', {}, h('option', { value: '' }, 'Kategorie …'));
  const folder = h('select', {}, h('option', { value: '__' }, 'Verschieben nach …'));
  const trigger = h('select', {}, h('option', { value: '' }, 'Trigger …'), state.catalog.triggers.flatMap((g) => g.triggers).map((t) => h('option', { value: t }, t)));
  const level = h('input', { type: 'range', min: 0, max: 10, value: 5, style: { width: '110px' } });
  const levelLabel = h('span', {}, '5');
  level.addEventListener('input', () => { levelLabel.textContent = level.value; });
  api('/categories').then((cs) => cs.forEach((c) => category.append(h('option', { value: c.id }, c.name))));
  api('/folders/all').then((fs) => { folder.append(h('option', { value: '' }, 'Bibliothek (oberste Ebene)')); fs.forEach((f) => folder.append(h('option', { value: f }, f))); });

  const each = (fn, done) => run(async () => { for (const id of ids) await fn(id); await after(); }, done);
  return h('div', { class: 'bulk stack', style: { marginBottom: '12px' } },
    h('div', { class: 'row' }, h('strong', {}, `${ids.length} markiert`), h('span', { class: 'grow' }),
      h('button', { onclick: () => { trackState.selected.clear(); after(); } }, 'Auswahl aufheben')),
    h('div', { class: 'row' },
      category,
      h('button', { onclick: () => category.value && each((id) => api(`/categories/${category.value}/items/${id}`, { method: 'PUT' }), 'Zugeordnet') }, 'Zuordnen'),
      h('button', { onclick: () => category.value && each((id) => api(`/categories/${category.value}/items/${id}`, { method: 'DELETE' }), 'Entfernt') }, 'Entfernen')),
    h('div', { class: 'row' },
      trigger, level, levelLabel,
      h('button', {
        onclick: () => trigger.value && each(async (id) => {
          const item = await api(`/items/${id}`);
          const levels = { ...item.levels, [trigger.value]: Number(level.value) };
          await api(`/items/${id}`, { method: 'PATCH', body: { levels: Object.fromEntries(Object.entries(levels).filter(([, l]) => l > 0)) } });
        }, 'Trigger gesetzt'),
      }, 'Trigger setzen'),
      folder,
      h('button', { onclick: () => folder.value !== '__' && each((id) => api(`/items/${id}`, { method: 'PATCH', body: { folder: folder.value } }), 'Verschoben') }, 'Verschieben')),
  );
}

// ---------------------------------------------------------------------------------------------
// Ordner: Baum mit Zugriff und Kategorien

async function renderFolders(el) {
  const treeBox = h('div', { class: 'card' });
  const panel = h('div', { class: 'panel' });
  let selected = null;

  async function branch(path) {
    const listing = await api(`/folders?path=${enc(path)}`);
    return h('ul', { class: 'tree' }, listing.folders.map((f) => {
      const holder = h('li');
      let open = false;
      const childBox = h('div');
      const toggle = h('span', { class: 'toggle' }, '▸');
      const label = h('span', { class: 'node', onclick: () => { selected = f; document.querySelectorAll('.tree .node').forEach((n) => n.classList.remove('active')); label.classList.add('active'); folderPanel(panel, f, refresh); } },
        f.name, ' ', h('span', { class: 'muted small' }, `(${f.itemCount})`),
        f.restricted && h('span', { class: 'badge', style: { marginLeft: '6px' } }, 'gesperrt'));
      toggle.addEventListener('click', async () => {
        open = !open;
        toggle.textContent = open ? '▾' : '▸';
        clear(childBox, open ? await branch(f.path) : null);
      });
      holder.append(toggle, label, childBox);
      return holder;
    }));
  }

  async function refresh() {
    clear(treeBox, h('div', { class: 'row' }, h('strong', { class: 'grow' }, 'Bibliothek'),
      h('button', { onclick: () => newFolder('', refresh) }, 'Neuer Ordner')), await branch(''));
    if (selected) folderPanel(panel, selected, refresh);
  }

  clear(el, h('h1', {}, 'Ordner'), h('div', { class: 'split' }, treeBox, panel));
  await refresh();
}

async function newFolder(parent, after) {
  const name = prompt(`Neuer Ordner in ${parent || 'Bibliothek'}:`);
  if (!name || !name.trim()) return;
  if (await run(() => api('/folders', { method: 'POST', body: { path: parent ? `${parent}/${name.trim()}` : name.trim() } }), 'Ordner angelegt') !== undefined) after();
}

async function folderPanel(panel, folder, refresh) {
  const [access, categories] = await Promise.all([api('/admin/access'), categoriesWithMembers()]);
  const rule = access.rules.find((r) => r.path === folder.path);
  const restricted = h('input', { type: 'checkbox', checked: !!rule });
  const groups = new Set(rule?.groups || []);
  const users = new Set(rule?.userIds || []);
  const groupBox = h('div', { class: 'stack' });
  const renderGroups = () => clear(groupBox, [...new Set([...access.groups, ...groups])].sort().map((g) => h('label', { class: 'check' },
    h('input', { type: 'checkbox', checked: groups.has(g), onchange: (e) => (e.target.checked ? groups.add(g) : groups.delete(g)) }), g)));
  renderGroups();
  const newGroup = h('input', { type: 'text', placeholder: 'Gruppe hinzufügen', class: 'grow' });
  const inherited = access.rules.filter((r) => folder.path.startsWith(`${r.path}/`));
  const categoryBoxes = categories.map((c) => ({ c, box: h('input', { type: 'checkbox', checked: c.detail.folders.includes(folder.path) }) }));

  const save = async () => {
    if (restricted.checked) {
      await api('/admin/access', { method: 'PUT', body: { path: folder.path, groups: [...groups], userIds: [...users] } });
    } else if (rule) {
      await api(`/admin/access?path=${enc(folder.path)}`, { method: 'DELETE' });
    }
    for (const { c, box } of categoryBoxes) {
      const was = c.detail.folders.includes(folder.path);
      if (box.checked !== was) await api(`/categories/${c.id}/folders?path=${enc(folder.path)}`, { method: box.checked ? 'PUT' : 'DELETE' });
    }
  };

  clear(panel, h('div', { class: 'card stack' },
    h('h2', {}, folder.name),
    h('p', { class: 'muted small' }, folder.path, ` · ${folder.itemCount} Tracks`),
    folder.hasCover && h('img', { src: `/api/folders/cover?path=${enc(folder.path)}`, style: { width: '160px', borderRadius: '10px' } }),
    categories.length > 0 && h('div', {}, h('h3', {}, 'Kategorien (samt Unterordnern)'), h('div', { class: 'stack' }, categoryBoxes.map(({ c, box }) => h('label', { class: 'check' }, box, c.name)))),
    h('h3', {}, 'Zugriff'),
    inherited.length > 0 && h('p', { class: 'muted small' }, 'Zusätzlich gelten die Einschränkungen von: ', inherited.map((r) => r.path).join(', ')),
    h('label', { class: 'check' }, restricted, 'Eingeschränkt (Admins sehen immer alles)'),
    h('div', { class: 'muted small' }, access.ssoEnabled ? 'SSO-Gruppen' : 'SSO-Gruppen (greifen erst, wenn sich Benutzer per SSO anmelden)'),
    groupBox,
    h('div', { class: 'row' }, newGroup, h('button', { onclick: () => { if (newGroup.value.trim()) { groups.add(newGroup.value.trim()); newGroup.value = ''; renderGroups(); } } }, '+')),
    h('div', { class: 'muted small' }, 'Benutzer'),
    h('div', { class: 'stack' }, access.users.filter((u) => !u.isAdmin).map((u) => h('label', { class: 'check' },
      h('input', { type: 'checkbox', checked: users.has(u.id), onchange: (e) => (e.target.checked ? users.add(u.id) : users.delete(u.id)) }),
      u.displayName || u.username))),
    h('div', { class: 'row' },
      h('button', { class: 'primary', onclick: async () => { if (await run(save, 'Gespeichert') !== undefined) refresh(); } }, 'Speichern'),
      h('button', { onclick: () => newFolder(folder.path, refresh) }, 'Unterordner anlegen')),
  ));
}

// ---------------------------------------------------------------------------------------------
// Kategorien

async function renderCategories(el, editId) {
  const categories = await categoriesWithMembers();
  const panel = h('div', { class: 'panel' });
  // Reihenfolge wie in der App; ▲/▼ tauscht mit dem Nachbarn.
  const move = async (index, delta, event) => {
    event.stopPropagation();
    const ids = categories.map((c) => c.id);
    const target = index + delta;
    if (target < 0 || target >= ids.length) return;
    [ids[index], ids[target]] = [ids[target], ids[index]];
    if (await run(() => api('/categories/order', { method: 'PUT', body: { ids } })) !== undefined) renderCategories(el, editId);
  };
  const list = h('table', {},
    h('thead', {}, h('tr', {}, h('th', {}, 'Reihenfolge'), h('th', {}), h('th', {}, 'Name'), h('th', {}, 'Inhalte'), h('th', {}, 'Ordner'), h('th', {}, 'Einzelne Tracks'))),
    h('tbody', {}, categories.map((c, index) => h('tr', { class: 'clickable', onclick: () => categoryForm(panel, c, () => renderCategories(el, c.id)) },
      h('td', {}, h('div', { class: 'row', style: { gap: '4px' } },
        h('button', { disabled: index === 0, title: 'Nach oben', onclick: (e) => move(index, -1, e) }, '▲'),
        h('button', { disabled: index === categories.length - 1, title: 'Nach unten', onclick: (e) => move(index, 1, e) }, '▼'))),
      h('td', {}, h('span', { class: 'icon-btn', style: { background: c.color, borderRadius: '8px', color: '#fff', display: 'inline-grid' } }, icon(c.icon))),
      h('td', {}, c.name, c.isAmbient && h('span', { class: 'badge', style: { marginLeft: '6px' } }, 'Ambiente')),
      h('td', { class: 'muted' }, c.itemCount),
      h('td', { class: 'small' }, c.detail.folders.join(', ') || '–'),
      h('td', { class: 'muted' }, c.detail.itemIds.length)))));
  clear(el,
    h('div', { class: 'row', style: { marginBottom: '12px' } }, h('h1', { class: 'grow' }, 'Kategorien'),
      h('button', { class: 'primary', onclick: () => categoryForm(panel, null, () => renderCategories(el)) }, 'Neue Kategorie')),
    h('div', { class: 'split' }, h('div', { class: 'card' }, categories.length ? list : h('p', { class: 'muted' }, 'Noch keine Kategorien.')), panel));
  const editing = categories.find((c) => c.id === editId);
  if (editing) categoryForm(panel, editing, () => renderCategories(el, editId));
}

function categoryForm(panel, category, after) {
  const name = h('input', { type: 'text', value: category?.name || '', style: { width: '100%' } });
  let iconKey = category?.icon || state.catalog.categoryIcons[0];
  let color = category?.color || state.catalog.categoryColors[0];
  const icons = h('div', { class: 'icons' });
  const swatches = h('div', { class: 'swatches' });
  const render = () => {
    clear(icons, state.catalog.categoryIcons.map((key) => h('button', { class: `icon-btn${key === iconKey ? ' on' : ''}`, title: key, onclick: () => { iconKey = key; render(); } }, icon(key))));
    clear(swatches, state.catalog.categoryColors.map((hex) => h('span', { class: `swatch${hex === color ? ' on' : ''}`, style: { background: hex }, onclick: () => { color = hex; render(); } })));
  };
  render();
  const ambient = h('input', { type: 'checkbox', checked: !!category?.isAmbient });
  const body = () => ({ name: name.value.trim(), icon: iconKey, color, isAmbient: ambient.checked });
  const save = () => category
    ? api(`/categories/${category.id}`, { method: 'PATCH', body: body() })
    : api('/categories', { method: 'POST', body: body() });

  clear(panel, h('div', { class: 'card stack' },
    h('h2', {}, category ? 'Kategorie bearbeiten' : 'Neue Kategorie'),
    h('label', { class: 'muted small' }, 'Name'), name,
    h('label', { class: 'muted small' }, 'Icon'), icons,
    h('label', { class: 'muted small' }, 'Farbe'), swatches,
    h('label', { class: 'check' }, ambient, 'Ambiente-Kategorie (eigene Reihe in der App, Sounds laufen als zweite Spur)'),
    category && h('div', {}, h('h3', {}, 'Zugeordnete Ordner'),
      category.detail.folders.length === 0 && h('p', { class: 'muted small' }, 'Keine. Ordner ordnest du unter "Ordner" zu, einzelne Tracks unter "Tracks".'),
      category.detail.folders.map((f) => h('div', { class: 'row' }, h('span', { class: 'grow' }, f),
        h('button', { onclick: async () => { if (await run(() => api(`/categories/${category.id}/folders?path=${enc(f)}`, { method: 'DELETE' }), 'Entfernt') !== undefined) after(); } }, 'Entfernen')))),
    h('div', { class: 'row' },
      h('button', { class: 'primary', onclick: async () => { if (!name.value.trim()) return toast('Name fehlt', true); if (await run(save, 'Gespeichert') !== undefined) after(); } }, 'Speichern'),
      h('span', { class: 'grow' }),
      category && h('button', {
        class: 'danger',
        onclick: async () => { if (confirm(`Kategorie "${category.name}" löschen? Tracks bleiben erhalten.`) && await run(() => api(`/categories/${category.id}`, { method: 'DELETE' }), 'Gelöscht') !== undefined) after(); },
      }, 'Löschen')),
  ));
}

// ---------------------------------------------------------------------------------------------
// Bewertungsmatrix: Gruppen und Regler verwalten

async function renderMatrix(el) {
  const groups = await api('/admin/triggers');
  const reload = async () => {
    state.catalog = await api('/catalog');
    await renderMatrix(el);
  };
  const act = async (action, success) => { if (await run(action, success) !== undefined) await reload(); };
  const swap = (list, index, delta) => {
    const ids = list.map((x) => x.id);
    const target = index + delta;
    if (target < 0 || target >= ids.length) return null;
    [ids[index], ids[target]] = [ids[target], ids[index]];
    return ids;
  };

  const newGroup = h('input', { type: 'text', placeholder: 'Neue Gruppe, z.B. "Rollenspiel"', class: 'grow' });
  const addGroup = (e) => {
    e.preventDefault();
    if (!newGroup.value.trim()) return;
    act(() => api('/admin/triggers/groups', { method: 'POST', body: { name: newGroup.value.trim() } }), 'Gruppe angelegt');
  };

  const groupCard = (group, groupIndex) => {
    const newTrigger = h('input', { type: 'text', placeholder: 'Neuer Regler', class: 'grow' });
    const addTrigger = (e) => {
      e.preventDefault();
      if (!newTrigger.value.trim()) return;
      act(() => api('/admin/triggers', { method: 'POST', body: { name: newTrigger.value.trim(), groupId: group.id } }), 'Regler angelegt');
    };
    return h('div', { class: 'card stack' },
      h('div', { class: 'row' },
        h('h2', { class: 'grow', style: { margin: 0 } }, group.name),
        h('button', { title: 'Nach oben', disabled: groupIndex === 0, onclick: () => { const ids = swap(groups, groupIndex, -1); if (ids) act(() => api('/admin/triggers/groups/order', { method: 'PUT', body: { ids } })); } }, '▲'),
        h('button', { title: 'Nach unten', disabled: groupIndex === groups.length - 1, onclick: () => { const ids = swap(groups, groupIndex, 1); if (ids) act(() => api('/admin/triggers/groups/order', { method: 'PUT', body: { ids } })); } }, '▼'),
        h('button', {
          onclick: () => {
            const name = prompt('Gruppe umbenennen:', group.name);
            if (name && name.trim() && name.trim() !== group.name) act(() => api(`/admin/triggers/groups/${group.id}`, { method: 'PATCH', body: { name: name.trim() } }), 'Umbenannt');
          },
        }, 'Umbenennen'),
        h('button', {
          class: 'danger',
          onclick: () => {
            if (confirm(`Gruppe "${group.name}" mit ${group.triggers.length} Reglern löschen? Werte an den Tracks bleiben als eigene Trigger erhalten.`)) {
              act(() => api(`/admin/triggers/groups/${group.id}`, { method: 'DELETE' }), 'Gruppe gelöscht');
            }
          },
        }, 'Löschen')),
      group.triggers.length === 0 && h('p', { class: 'muted small' }, 'Noch keine Regler.'),
      h('table', {}, h('tbody', {}, group.triggers.map((t, index) => h('tr', {},
        h('td', {}, t.name),
        h('td', { class: 'muted small' }, t.usage === 1 ? '1 Track' : `${t.usage} Tracks`),
        h('td', {}, h('div', { class: 'row', style: { gap: '4px', justifyContent: 'flex-end' } },
          h('button', { title: 'Nach oben', disabled: index === 0, onclick: () => { const ids = swap(group.triggers, index, -1); if (ids) act(() => api('/admin/triggers/order', { method: 'PUT', body: { ids } })); } }, '▲'),
          h('button', { title: 'Nach unten', disabled: index === group.triggers.length - 1, onclick: () => { const ids = swap(group.triggers, index, 1); if (ids) act(() => api('/admin/triggers/order', { method: 'PUT', body: { ids } })); } }, '▼'),
          h('select', {
            title: 'In andere Gruppe verschieben',
            onchange: (e) => act(() => api(`/admin/triggers/${t.id}`, { method: 'PATCH', body: { groupId: Number(e.target.value) } }), 'Verschoben'),
          }, groups.map((g) => h('option', { value: g.id, selected: g.id === group.id }, g.name))),
          h('button', {
            onclick: () => {
              const name = prompt(`"${t.name}" umbenennen (wird in allen ${t.usage} Tracks übernommen):`, t.name);
              if (name && name.trim() && name.trim() !== t.name) act(() => api(`/admin/triggers/${t.id}`, { method: 'PATCH', body: { name: name.trim() } }), 'Umbenannt');
            },
          }, 'Umbenennen'),
          h('button', {
            class: 'danger',
            onclick: () => {
              if (!confirm(`Regler "${t.name}" löschen?`)) return;
              const purge = t.usage > 0 && confirm(`Den Wert auch aus den ${t.usage} Tracks entfernen?\nOK = entfernen, Abbrechen = an den Tracks als eigenen Trigger behalten.`);
              act(() => api(`/admin/triggers/${t.id}${purge ? '?purge=true' : ''}`, { method: 'DELETE' }), 'Gelöscht');
            },
          }, 'Löschen'))))))),
      h('form', { class: 'row', onsubmit: addTrigger }, newTrigger, h('button', { type: 'submit' }, 'Regler anlegen')));
  };

  clear(el,
    h('h1', {}, 'Bewertungsmatrix'),
    h('p', { class: 'muted' }, 'Gruppen und Regler, wie sie im Track-Editor (App und Web) erscheinen. Umbenennen übernimmt die Werte in alle Tracks und deren Metadaten-Dateien.'),
    h('form', { class: 'row', style: { marginBottom: '16px' }, onsubmit: addGroup }, newGroup, h('button', { class: 'primary', type: 'submit' }, 'Gruppe anlegen')),
    h('div', { class: 'stack' }, groups.map(groupCard)));
}

// ---------------------------------------------------------------------------------------------
// ASMRtists

async function renderCreators(el, selectedName) {
  const creators = await api('/creators');
  const panel = h('div', { class: 'panel' });
  clear(el, h('h1', {}, 'ASMRtists'), h('div', { class: 'split' },
    h('div', { class: 'card' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}), h('th', {}, 'Name'), h('th', {}, 'Tracks'), h('th', {}, 'Bei ASMRtists'))),
      h('tbody', {}, creators.map((c) => h('tr', { class: 'clickable', onclick: () => creatorPanel(panel, c.name, () => renderCreators(el, c.name)) },
        h('td', {}, c.hasAvatar ? h('img', { class: 'thumb', style: { borderRadius: '50%' }, src: `/api/creators/${enc(c.name)}/avatar?t=${Date.now()}` }) : h('div', { class: 'thumb' })),
        h('td', {}, c.name),
        h('td', { class: 'muted' }, c.itemCount),
        h('td', {}, h('input', {
          type: 'checkbox', checked: c.isCreator, title: 'In der ASMRtists-Reihe der App anzeigen',
          onclick: (e) => e.stopPropagation(),
          onchange: async (e) => { await run(() => api(`/creators/${enc(c.name)}/marked`, { method: e.target.checked ? 'PUT' : 'DELETE' }), e.target.checked ? 'Als ASMRtist markiert' : 'Markierung entfernt'); },
        }))))))),
    panel));
  if (selectedName) creatorPanel(panel, selectedName, () => renderCreators(el, selectedName));
}

async function creatorPanel(panel, name, after) {
  const creator = await api(`/creators/${enc(name)}`);
  const links = h('textarea', { placeholder: 'Ein Link pro Zeile (YouTube, Patreon, Fansly …)' }, creator.links.map((l) => l.url).join('\n'));
  const file = h('input', { type: 'file', accept: 'image/*' });
  file.addEventListener('change', async () => {
    const image = file.files[0];
    if (image && await run(() => api(`/creators/${enc(name)}/avatar`, { method: 'PUT', body: image }), 'Profilbild gespeichert') !== undefined) after();
  });
  clear(panel, h('div', { class: 'card stack' },
    h('div', { class: 'row' },
      creator.hasAvatar ? h('img', { class: 'avatar', src: `/api/creators/${enc(name)}/avatar?t=${Date.now()}` }) : h('div', { class: 'avatar' }),
      h('div', {}, h('h2', {}, name), creator.links.map((l) => h('a', { href: l.url, target: '_blank', rel: 'noreferrer', class: 'chip on', style: { textDecoration: 'none' } }, l.label)))),
    h('label', { class: 'check' }, h('input', {
      type: 'checkbox', checked: creator.isCreator,
      onchange: async (e) => { if (await run(() => api(`/creators/${enc(name)}/marked`, { method: e.target.checked ? 'PUT' : 'DELETE' }), 'Gespeichert') !== undefined) after(); },
    }), 'Bei den ASMRtists anzeigen (Reihe in der App)'),
    h('h3', {}, 'Links'), links,
    h('button', { class: 'primary', onclick: async () => { if (await run(() => api(`/creators/${enc(name)}`, { method: 'PUT', body: { links: links.value.split('\n') } }), 'Gespeichert') !== undefined) after(); } }, 'Links speichern'),
    h('h3', {}, 'Profilbild'),
    h('p', { class: 'muted small' }, 'Dient als Cover für Tracks ohne eigenes Bild und als Kachel des gleichnamigen Ordners.'),
    file,
    h('button', {
      disabled: !creator.links.some((l) => l.label === 'YouTube'),
      onclick: async () => { if (await run(() => api(`/creators/${enc(name)}/avatar/fetch`, { method: 'POST' }), 'Profilbild geholt') !== undefined) after(); },
    }, 'Vom YouTube-Kanal holen'),
  ));
}

// ---------------------------------------------------------------------------------------------
// Benutzer

async function renderUsers(el) {
  const users = await api('/users');
  const username = h('input', { type: 'text', placeholder: 'Benutzername' });
  const password = h('input', { type: 'password', placeholder: 'Passwort (min. 10 Zeichen)' });
  const admin = h('input', { type: 'checkbox' });
  const create = async (e) => {
    e.preventDefault();
    if (await run(() => api('/users', { method: 'POST', body: { username: username.value, password: password.value, isAdmin: admin.checked } }), 'Konto angelegt') !== undefined) renderUsers(el);
  };
  const update = async (user, body, done) => { if (await run(() => api(`/users/${user.id}`, { method: 'PATCH', body }), done) !== undefined) renderUsers(el); };
  clear(el,
    h('h1', {}, 'Benutzer'),
    h('div', { class: 'card' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, 'Name'), h('th', {}, 'Rolle'), h('th', {}, 'Anmeldung'), h('th', {}, 'SSO-Gruppen'), h('th', {}))),
      h('tbody', {}, users.map((u) => h('tr', {},
        h('td', {}, h('div', { class: 'title' }, u.displayName || u.username), h('div', { class: 'muted small' }, `@${u.username}`)),
        h('td', {}, u.isAdmin ? h('span', { class: 'badge' }, 'Admin') : 'Benutzer'),
        h('td', { class: 'muted small' }, u.hasPassword ? 'Passwort' : 'nur SSO'),
        h('td', {}, u.groups.map((g) => h('span', { class: 'chip' }, g))),
        h('td', {}, u.id === state.me.id ? h('span', { class: 'muted small' }, 'du') : h('div', { class: 'row' },
          h('button', { onclick: () => update(u, { isAdmin: !u.isAdmin }, u.isAdmin ? 'Admin-Rolle entfernt' : 'Zum Admin gemacht') }, u.isAdmin ? 'Admin entfernen' : 'Zum Admin'),
          h('button', { onclick: () => { const pw = prompt(`Neues Passwort für ${u.username} (min. 10 Zeichen):`); if (pw) update(u, { password: pw }, 'Passwort gesetzt'); } }, 'Passwort setzen'),
          h('button', { class: 'danger', onclick: async () => { if (confirm(`Konto ${u.username} samt Favoriten und Playlists löschen?`) && await run(() => api(`/users/${u.id}`, { method: 'DELETE' }), 'Gelöscht') !== undefined) renderUsers(el); } }, 'Löschen'))),
      ))))),
    h('form', { class: 'card stack', style: { marginTop: '16px' }, onsubmit: create },
      h('h2', {}, 'Konto anlegen'),
      h('div', { class: 'row' }, username, password, h('label', { class: 'check' }, admin, 'Admin'), h('button', { class: 'primary', type: 'submit' }, 'Anlegen'))),
  );
}

// ---------------------------------------------------------------------------------------------
// Importe

async function renderImports(el) {
  const jobs = await api('/imports');
  const label = { QUEUED: 'Wartet', RUNNING: 'Lädt …', DONE: 'Fertig', FAILED: 'Fehler' };
  clear(el,
    h('h1', {}, 'Importe'),
    importForm(() => renderImports(el)),
    h('div', { class: 'card', style: { marginTop: '16px' } }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, 'URL'), h('th', {}, 'Status'), h('th', {}, 'Gestartet'))),
      h('tbody', {}, jobs.map((j) => h('tr', {},
        h('td', { class: 'small' }, h('a', { href: j.url, target: '_blank', rel: 'noreferrer' }, j.url)),
        h('td', { class: j.status === 'FAILED' ? 'small' : '' }, label[j.status], j.error && h('div', { class: 'small', style: { color: 'var(--danger)' } }, j.error)),
        h('td', { class: 'muted small' }, new Date(j.createdAt).toLocaleString('de-DE'))))))),
  );
  if (jobs.some((j) => j.status === 'QUEUED' || j.status === 'RUNNING')) {
    setTimeout(() => { if (location.hash.startsWith('#/imports')) renderImports(el); }, 3000);
  }
}

boot();
