import * as api from '../api.js';
import { badge, count, el, highlight, icon, size, splitName, toast } from '../dom.js';
import { detail } from './detail.js';

export function getState(rom) {
  if (rom.queued) return { icon: 'clock', label: 'Queued', hint: 'Remove from the queue' };
  if (rom.downloaded) return { icon: 'check', label: 'In library', hint: 'Already in your library' };
  return { icon: 'download', label: 'Download', hint: `Download to roms/${rom.folder}` };
}

/**
 * Games beside the details of the one picked. Pressing a row only picks it: downloading and
 * favourites are their own buttons, so a stray press never starts a download. Given onRemove, the
 * row's own button removes instead of downloading, which is all the queue needs.
 */
export function games({ onRemove } = {}) {
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
    const status = el('span', { className: 'status' });
    const pick = el('button', { type: 'button', className: 'pick' }, [
      badge(rom.folder, rom.family),
      el('span', { className: 'title' }, [
        highlight(title, rom.match),
        tags ? el('span', { className: 'tags' }, highlight(` ${tags}`, rom.match)) : null,
      ]),
      el('span', { className: 'sub' }, [el('span', {}, rom.platform), el('span', {}, size(rom.size)), status]),
    ]);
    pick.addEventListener('click', () => select(rom.id));

    const fav = el('button', { type: 'button', className: 'icon-button fav' }, icon('heart'));
    fav.addEventListener('click', () => toggleFavourite(rom));
    const get = el('button', { type: 'button', className: 'icon-button get' });
    get.addEventListener('click', () => (onRemove ? onRemove(rom) : toggleQueue(rom)));

    const row = el('div', { className: 'game', role: 'listitem' }, [pick, fav, get]);
    const paint = () => {
      const state = onRemove ? { icon: 'close', label: 'Remove from the queue' } : getState(rom);
      fav.setAttribute('aria-pressed', String(Boolean(rom.favourite)));
      fav.title = rom.favourite ? 'Remove from favourites' : 'Add to favourites';
      fav.setAttribute('aria-label', fav.title);
      get.replaceChildren(icon(state.icon));
      get.title = state.hint || state.label;
      get.setAttribute('aria-label', `${state.label}: ${rom.name}`);
      get.disabled = !onRemove && Boolean(rom.downloaded && !rom.queued);
      get.dataset.state = state.icon;
      status.textContent = rom.status?.text || '';
      status.className = `status ${rom.status?.kind || ''}`;
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
    /** One pasted line and its best matches, under a heading that says what was asked. */
    appendGroup(query, roms, system) {
      const found =
        roms.length === 0 ? 'no match' : `${count(roms.length)} best ${roms.length === 1 ? 'match' : 'matches'}`;
      list.append(
        el('div', { className: roms.length === 0 ? 'group-title none' : 'group-title' }, [
          el('strong', {}, query),
          el('span', {}, system ? `${found} in roms/${system.folder}` : found),
        ]),
        ...roms.map(build),
      );
    },
    patch(id, fields) {
      const row = rows.get(id);
      if (!row) return;
      Object.assign(row.rom, fields);
      row.paint();
    },
    get size() {
      return rows.size;
    },
  };
}
