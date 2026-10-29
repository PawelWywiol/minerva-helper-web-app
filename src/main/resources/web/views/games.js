import * as api from '../api.js';
import { badge, el, icon, size, splitName, toast } from '../dom.js';
import { detail } from './detail.js';

export function getState(rom) {
  if (rom.queued) return { icon: 'clock', label: 'Queued', hint: 'Remove from the queue' };
  if (rom.downloaded) return { icon: 'check', label: 'In library', hint: 'Already in your library' };
  return { icon: 'download', label: 'Download', hint: `Download to roms/${rom.folder}` };
}

/**
 * Games beside the details of the one picked. Pressing a row only picks it: downloading and
 * favourites are their own buttons, so a stray press never starts a download.
 */
export function games() {
  const rows = new Map();
  const list = el('div', { className: 'games', role: 'list' });
  let selected = null;

  async function toggleQueue(rom) {
    try {
      Object.assign(rom, await api.toggleQueue(rom.id));
      changed(rom);
      toast(rom.queued ? `Queued for roms/${rom.folder}` : 'Removed from the queue');
    } catch (error) {
      toast(`Could not change the queue: ${error.message}`);
    }
  }

  async function toggleFavourite(rom) {
    try {
      Object.assign(rom, await api.toggleFavourite(rom.id));
      changed(rom);
      toast(rom.favourite ? 'Added to favourites' : 'Removed from favourites');
    } catch (error) {
      toast(`Could not change favourites: ${error.message}`);
    }
  }

  const pane = detail({ toggleQueue, toggleFavourite, onClose: () => select(null) });
  const node = el('div', { className: 'split' }, [list, pane.node]);

  function changed(rom) {
    rows.get(rom.id)?.paint();
    pane.refresh(rom);
  }

  function select(id) {
    rows.get(selected)?.node.classList.remove('on');
    selected = id;
    const row = rows.get(id);
    row?.node.classList.add('on');
    node.classList.toggle('open', row != null);
    if (row) pane.show(row.rom);
    else pane.clear();
  }

  function build(rom) {
    const [title, tags] = splitName(rom.name);
    const pick = el('button', { type: 'button', className: 'pick' }, [
      badge(rom.folder, rom.family),
      el('span', { className: 'title' }, [title, tags ? el('span', { className: 'tags' }, ` ${tags}`) : null]),
      el('span', { className: 'sub' }, [el('span', {}, rom.platform), el('span', {}, size(rom.size))]),
    ]);
    pick.addEventListener('click', () => select(rom.id));

    const fav = el('button', { type: 'button', className: 'icon-button fav' }, icon('heart'));
    fav.addEventListener('click', () => toggleFavourite(rom));
    const get = el('button', { type: 'button', className: 'get' });
    get.addEventListener('click', () => toggleQueue(rom));

    const row = el('div', { className: 'game', role: 'listitem' }, [pick, fav, get]);
    const paint = () => {
      const state = getState(rom);
      fav.setAttribute('aria-pressed', String(Boolean(rom.favourite)));
      fav.title = rom.favourite ? 'Remove from favourites' : 'Add to favourites';
      fav.setAttribute('aria-label', fav.title);
      get.replaceChildren(icon(state.icon), el('span', {}, state.label));
      get.title = state.hint;
      get.disabled = Boolean(rom.downloaded && !rom.queued);
      get.dataset.state = state.icon;
    };
    paint();
    rows.set(rom.id, { rom, node: row, paint });
    if (rom.id === selected) row.classList.add('on');
    return row;
  }

  list.addEventListener('keydown', (event) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    const picks = [...list.querySelectorAll('.pick')];
    const next = picks[picks.indexOf(document.activeElement) + (event.key === 'ArrowDown' ? 1 : -1)];
    if (!next) return;
    event.preventDefault();
    next.focus();
    next.click();
  });

  return {
    node,
    set(roms) {
      rows.clear();
      list.replaceChildren(...roms.map(build));
      if (!rows.has(selected)) select(null);
    },
    append(roms) {
      list.append(...roms.map(build));
    },
    get size() {
      return rows.size;
    },
  };
}
