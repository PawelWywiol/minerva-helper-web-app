import * as api from '../api.js';
import { badge, button, el, highlight, icon, similarNote, size, splitName, toast } from '../dom.js';
import { detail } from './detail.js';

export function getState(rom) {
  if (rom.queued) return { icon: 'clock', label: 'Queued', hint: 'Remove from the queue' };
  if (rom.downloaded) return { icon: 'check', label: 'In library', hint: 'Already in your library' };
  return { icon: 'download', label: 'Download', hint: `Download to roms/${rom.folder}` };
}

/**
 * Games beside the details of the one picked. Pressing a row only picks it: downloading and
 * favourites are their own buttons, so a stray press never starts a download. Given onRemove, the
 * row's own button removes instead of downloading, which is all the queue needs. A row is known by
 * its key, so the same game may sit twice in one list (queued again after a download).
 */
export function games({ onRemove } = {}) {
  const rows = new Map();
  const list = el('div', { className: 'games', role: 'list' });
  const keyOf = (rom) => rom.key ?? rom.id;
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
    rows.get(keyOf(rom))?.paint();
    pane.refresh(rom);
  }

  function select(key) {
    rows.get(selected)?.node.classList.remove('on');
    selected = key;
    const row = rows.get(key);
    row?.node.classList.add('on');
    node.classList.toggle('open', row != null);
    if (row) pane.show(row.rom);
    else pane.clear();
  }

  function build(rom) {
    const [title, tags] = splitName(rom.name);
    const status = el('span', { className: 'status' });
    const similar = el('span', { className: 'similar' });
    const pick = el('button', { type: 'button', className: 'pick' }, [
      badge(rom.folder, rom.family),
      el('span', { className: 'title' }, [
        highlight(title, rom.match),
        tags ? el('span', { className: 'tags' }, highlight(` ${tags}`, rom.match)) : null,
      ]),
      el('span', { className: 'sub' }, [el('span', {}, rom.platform), el('span', {}, size(rom.size)), similar, status]),
    ]);
    pick.addEventListener('click', () => select(keyOf(rom)));

    const fav = el('button', { type: 'button', className: 'icon-button fav' }, icon('heart'));
    fav.addEventListener('click', () => toggleFavourite(rom));
    const get = el('button', { type: 'button', className: 'icon-button get' });
    get.addEventListener('click', () => (onRemove ? onRemove(rom) : toggleQueue(rom)));

    const row = el('div', { className: 'game', role: 'listitem' }, [pick, fav, get]);
    const paint = () => {
      const state = onRemove ? { icon: 'close', label: rom.removeLabel || 'Remove from the queue' } : getState(rom);
      fav.setAttribute('aria-pressed', String(Boolean(rom.favourite)));
      fav.title = rom.favourite ? 'Remove from favourites' : 'Add to favourites';
      fav.setAttribute('aria-label', fav.title);
      get.replaceChildren(icon(state.icon));
      get.title = state.hint || state.label;
      get.setAttribute('aria-label', `${state.label}: ${rom.name}`);
      get.disabled = !onRemove && Boolean(rom.downloaded && !rom.queued);
      get.dataset.state = state.icon;
      similar.textContent = similarNote(rom);
      status.textContent = rom.status?.text || '';
      status.className = `status ${rom.status?.kind || ''}`;
    };
    paint();
    rows.set(keyOf(rom), { rom, node: row, paint });
    if (keyOf(rom) === selected) row.classList.add('on');
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
    /**
     * A heading and its games. Given more(), a button below them fetches further versions and
     * slots them in above itself, so the group stays together.
     */
    appendGroup(title, note, roms, more) {
      const heading = el('div', { className: roms.length === 0 ? 'group-title none' : 'group-title' }, [
        el('strong', {}, title),
        el('span', {}, note),
      ]);
      const nodes = [heading, ...roms.map(build)];
      if (more) {
        const next = button(
          'More versions',
          async () => {
            next.disabled = true;
            try {
              const found = await more();
              next.before(...found.roms.map(build));
              next.hidden = !found.more;
            } catch (error) {
              toast(`Could not load more versions: ${error.message}`);
            } finally {
              next.disabled = false;
            }
          },
          'link more-versions',
        );
        nodes.push(next);
      }
      list.append(...nodes);
    },
    patch(key, fields) {
      const row = rows.get(key);
      if (!row) return;
      Object.assign(row.rom, fields);
      row.paint();
    },
    get size() {
      return rows.size;
    },
  };
}
