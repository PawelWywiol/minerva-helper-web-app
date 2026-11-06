import * as api from '../api.js';
import { el, spinner } from '../dom.js';
import { t } from '../i18n.js';
import { games } from './games.js';

/** Games marked as favourites, kept on the server so every browser sees the same list. */
export function favourites(mount, { onError }) {
  const list = games();
  const status = el('p', { className: 'muted' });

  async function show() {
    mount.replaceChildren(spinner(t('favourites.loading')));
    try {
      const { roms } = await api.favourites();
      list.set(roms);
      status.textContent = roms.length === 0 ? t('favourites.empty') : t('favourites.saved', { count: roms.length });
      mount.replaceChildren(el('h1', {}, t('nav.favourites')), status, list.node);
    } catch (error) {
      onError(error, show);
    }
  }

  return { open: show };
}
