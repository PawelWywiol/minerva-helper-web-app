const HEADER = 'X-Minerva';

/** Thrown when the server has no catalogue yet; the overview offers to download it. */
export class NoCatalogue extends Error {}

async function call(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { ...options.headers, [HEADER]: '1' },
    credentials: 'same-origin',
    cache: 'no-store',
  });
  if (response.status === 503) throw new NoCatalogue();
  if (!response.ok) throw new Error(`${options.method || 'GET'} ${path} → ${response.status}`);
  return response.status === 204 ? null : response.json();
}

export const get = (path) => call(path);

export const send = (path, body) =>
  call(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  });

export const del = (path) => call(path, { method: 'DELETE' });

export const state = () => get('/api/state');

export const collections = () => get('/api/collections');

export const platforms = (collection) => get(`/api/collections/${encodeURIComponent(collection)}/platforms`);

export function roms(platformId, cursor) {
  const query = cursor ? `?afterName=${encodeURIComponent(cursor.name)}&afterId=${cursor.id}` : '';
  return get(`/api/platforms/${platformId}/roms${query}`);
}

export const search = (query, offset = 0, anywhere = false, limit = 50) =>
  get(`/api/search?q=${encodeURIComponent(query)}&offset=${offset}&limit=${limit}${anywhere ? '&anywhere=true' : ''}`);

export const vpn = () => get('/api/vpn');

export const rom = (id) => get(`/api/roms/${id}`);

export const favourites = () => get('/api/favourites');

export const toggleFavourite = (romId) => send('/api/favourites', { romId });

export const settings = () => get('/api/settings');

export const updateSettings = (change) => send('/api/settings', change);

export const queue = () => get('/api/queue');

export const queueRoms = () => get('/api/queue/roms');

/** Answers with the ROM's marks as they now stand, which is what the caller repaints its row from. */
export const toggleQueue = (romId) => send('/api/queue', { romId });

export const pauseGroup = (torrentPath, paused) => send('/api/queue/pause', { torrentPath, paused });

export const moveGroup = (from, to) => send('/api/queue/move', { from, to });

export const setRunning = (running) => send('/api/queue/run', { running });

export const retryFailed = () => send('/api/queue/retry');

export const removeQueued = (id) => del(`/api/queue/${id}`);

export const forgetDownloaded = (id) => del(`/api/downloaded/${id}`);

export const catalog = () => get('/api/catalog');

export const catalogUpdate = () => get('/api/catalog/update');

export const syncCatalog = (force = false) => send(`/api/catalog/sync${force ? '?force=true' : ''}`);
