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
  leaf: 'M6.05,8.05c-2.73,2.73 -2.73,7.15 -0.02,9.88c1.47,-3.4 4.09,-6.24 7.36,-7.93c-2.77,2.34 -4.71,5.61 -5.39,9.32c2.6,1.23 5.8,0.78 7.95,-1.37C19.43,14.47 20,4 20,4S9.53,4.57 6.05,8.05z',
  tree: 'M17,12h2L12,2 5.05,12H7l-3.9,6h6.92v4h3.95v-4H21z',
  home: 'M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z',
  water: 'M12,2c-5.33,4.55 -8,8.48 -8,11.8c0,4.98 3.8,8.2 8,8.2s8,-3.22 8,-8.2C20,10.48 17.33,6.55 12,2z',
  fire: 'M13.5,0.67s0.74,2.65 0.74,4.8c0,2.06 -1.35,3.73 -3.41,3.73 -2.07,0 -3.63,-1.67 -3.63,-3.73l0.03,-0.36C5.21,7.51 4,10.62 4,14c0,4.42 3.58,8 8,8s8,-3.58 8,-8C20,8.61 17.41,3.8 13.5,0.67z',
  moon: 'M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10 3.71,0 6.93,-2.02 8.66,-5.02 -7.51,-0.25 -12.09,-8.43 -8.32,-14.96z',
  star: 'M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z',
  heart: 'M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z',
};
function icon(key) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', 'icon');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', ICONS[key] || ICONS.headphones);
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
  ['creators', 'Creator', renderCreators],
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
      stat('Tracks', items.total), stat('Creator', creators.length), stat('Kategorien', categories.length), stat('Importe laufen', running)),
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
  const creator = h('select', {}, h('option', { value: '' }, 'Alle Creator'), creators.map((c) => h('option', { value: c.name, selected: c.name === trackState.creator }, `${c.name} (${c.itemCount})`)));
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
        })), h('th', {}), h('th', {}, 'Titel'), h('th', {}, 'Creator'), h('th', {}, 'Länge'), h('th', {}, 'Ordner'), h('th', {}, 'Trigger'))),
        h('tbody', {}, page.items.map((item) => h('tr', {
          class: `clickable${trackState.editing === item.id ? ' selected' : ''}`,
          onclick: (e) => { if (e.target.type !== 'checkbox') openEditor(item.id); },
        },
        h('td', {}, h('input', {
          type: 'checkbox', checked: trackState.selected.has(item.id),
          onchange: (e) => { e.target.checked ? trackState.selected.add(item.id) : trackState.selected.delete(item.id); renderBulk(); },
        })),
        h('td', {}, item.hasCover ? h('img', { class: 'thumb', src: `/api/items/${item.id}/cover`, loading: 'lazy' }) : h('div', { class: 'thumb' })),
        h('td', {}, h('div', { class: 'title' }, item.title), item.isAmbient && h('span', { class: 'badge' }, 'Ambiente')),
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
  const ambient = h('input', { type: 'checkbox', checked: item.isAmbient });
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
    if (ambient.checked !== item.isAmbient) await api(`/items/${id}/ambient`, { method: ambient.checked ? 'PUT' : 'DELETE' });
    for (const { c, box } of categoryBoxes) {
      if (box.checked !== memberIds.has(c.id)) await api(`/categories/${c.id}/items/${id}`, { method: box.checked ? 'PUT' : 'DELETE' });
    }
  };

  clear(panel, h('div', { class: 'card stack' },
    h('div', { class: 'row' }, h('h2', { class: 'grow' }, 'Track bearbeiten'), h('button', { onclick: onClose }, 'Schließen')),
    item.hasCover && h('img', { src: `/api/items/${id}/cover`, style: { width: '100%', borderRadius: '10px' } }),
    h('audio', { controls: true, preload: 'none', src: `/api/items/${id}/audio` }),
    h('label', { class: 'muted small' }, 'Titel'), title,
    h('label', { class: 'muted small' }, 'Creator'), creator,
    h('label', { class: 'muted small' }, 'Ordner (verschiebt die Datei auf dem NAS)'), folder,
    h('label', { class: 'check' }, ambient, 'Ambiente'),
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
      h('button', { onclick: () => category.value && each((id) => api(`/categories/${category.value}/items/${id}`, { method: 'DELETE' }), 'Entfernt') }, 'Entfernen'),
      h('button', { onclick: () => each((id) => api(`/items/${id}/ambient`, { method: 'PUT' }), 'Als Ambiente markiert') }, 'Ambiente an'),
      h('button', { onclick: () => each((id) => api(`/items/${id}/ambient`, { method: 'DELETE' }), 'Ambiente entfernt') }, 'Ambiente aus')),
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
// Ordner: Baum mit Zugriff, Ambiente, Kategorien

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
        f.restricted && h('span', { class: 'badge', style: { marginLeft: '6px' } }, 'gesperrt'),
        f.isAmbient && h('span', { class: 'badge', style: { marginLeft: '6px' } }, 'Ambiente'));
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
  const ambient = h('input', { type: 'checkbox', checked: folder.isAmbient });
  const categoryBoxes = categories.map((c) => ({ c, box: h('input', { type: 'checkbox', checked: c.detail.folders.includes(folder.path) }) }));

  const save = async () => {
    if (restricted.checked) {
      await api('/admin/access', { method: 'PUT', body: { path: folder.path, groups: [...groups], userIds: [...users] } });
    } else if (rule) {
      await api(`/admin/access?path=${enc(folder.path)}`, { method: 'DELETE' });
    }
    if (ambient.checked !== folder.isAmbient) await api(`/folders/ambient?path=${enc(folder.path)}`, { method: ambient.checked ? 'PUT' : 'DELETE' });
    for (const { c, box } of categoryBoxes) {
      const was = c.detail.folders.includes(folder.path);
      if (box.checked !== was) await api(`/categories/${c.id}/folders?path=${enc(folder.path)}`, { method: box.checked ? 'PUT' : 'DELETE' });
    }
  };

  clear(panel, h('div', { class: 'card stack' },
    h('h2', {}, folder.name),
    h('p', { class: 'muted small' }, folder.path, ` · ${folder.itemCount} Tracks`),
    folder.hasCover && h('img', { src: `/api/folders/cover?path=${enc(folder.path)}`, style: { width: '160px', borderRadius: '10px' } }),
    h('label', { class: 'check' }, ambient, 'Alle Tracks darin als Ambiente'),
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
      h('td', {}, c.name),
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
  const save = () => category
    ? api(`/categories/${category.id}`, { method: 'PATCH', body: { name: name.value.trim(), icon: iconKey, color } })
    : api('/categories', { method: 'POST', body: { name: name.value.trim(), icon: iconKey, color } });

  clear(panel, h('div', { class: 'card stack' },
    h('h2', {}, category ? 'Kategorie bearbeiten' : 'Neue Kategorie'),
    h('label', { class: 'muted small' }, 'Name'), name,
    h('label', { class: 'muted small' }, 'Icon'), icons,
    h('label', { class: 'muted small' }, 'Farbe'), swatches,
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
// Creator

async function renderCreators(el, selectedName) {
  const creators = await api('/creators');
  const panel = h('div', { class: 'panel' });
  clear(el, h('h1', {}, 'Creator'), h('div', { class: 'split' },
    h('div', { class: 'card' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}), h('th', {}, 'Name'), h('th', {}, 'Tracks'), h('th', {}, 'Als Creator'))),
      h('tbody', {}, creators.map((c) => h('tr', { class: 'clickable', onclick: () => creatorPanel(panel, c.name, () => renderCreators(el, c.name)) },
        h('td', {}, c.hasAvatar ? h('img', { class: 'thumb', style: { borderRadius: '50%' }, src: `/api/creators/${enc(c.name)}/avatar?t=${Date.now()}` }) : h('div', { class: 'thumb' })),
        h('td', {}, c.name),
        h('td', { class: 'muted' }, c.itemCount),
        h('td', {}, h('input', {
          type: 'checkbox', checked: c.isCreator, title: 'In der Creator-Reihe der App anzeigen',
          onclick: (e) => e.stopPropagation(),
          onchange: async (e) => { await run(() => api(`/creators/${enc(c.name)}/marked`, { method: e.target.checked ? 'PUT' : 'DELETE' }), e.target.checked ? 'Als Creator markiert' : 'Markierung entfernt'); },
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
    }), 'Als Creator anzeigen (Creator-Reihe in der App)'),
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
