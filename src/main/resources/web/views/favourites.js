import * as api from '../api.js';
import { count, el, spinner } from '../dom.js';
import { games } from './games.js';

/** Games marked as favourites, kept on the server so every browser sees the same list. */
export function favourites(mount, { onError }) {
  const list = games();
  const status = el('p', { className: 'muted' });

  async function show() {
    mount.replaceChildren(spinner('Loading favourites…'));
    try {
      const { roms } = await api.favourites();
      list.set(roms);
      status.textContent =
        roms.length === 0
          ? 'Nothing saved yet. Press the heart beside any game to keep it here. Favourites survive a catalogue rebuild.'
          : `${count(roms.length)} saved`;
      mount.replaceChildren(el('h1', {}, 'Favourites'), status, list.node);
    } catch (error) {
      onError(error, show);
    }
  }

  return { open: show };
}
