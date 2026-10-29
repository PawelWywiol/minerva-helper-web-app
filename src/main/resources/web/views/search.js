import * as api from '../api.js';
import { badge, button, count, el, icon, loadOnScroll } from '../dom.js';
import { games } from './games.js';

/** Long enough that a two-letter prefix does not sweep the whole 2.4M-row index on every keystroke. */
const DEBOUNCE_MS = 200;
const EXAMPLES = ['nes mario', 'amiga cd32', 'psx final fantasy', 'genesis sonic', 'arcade'];

/**
 * The archive searched by name, by system, or both: "nes mario" keeps to roms/nes, "amigacd"
 * offers the systems it could mean. The query box lives in the page header, shared by every view.
 */
export function search(mount, { onError, setQuery }) {
  const list = games();
  const heading = el('h1', {}, 'Search');
  const more = button('Show more', () => run(true), 'ghost wide');
  more.hidden = true;
  loadOnScroll(more);
  const status = el('p', { className: 'muted' });
  const systems = el('div', { className: 'chips' });
  const hint = el('div', { className: 'hint' }, [
    el('p', {}, 'Type a game, a system, or both.'),
    el(
      'div',
      { className: 'chips' },
      EXAMPLES.map((example) => button(example, () => setQuery(example), 'chip')),
    ),
  ]);

  let query = '';
  let anywhere = false;
  let offset = 0;
  let timer = null;
  let generation = 0;

  function systemChip(system, active) {
    const parts = [
      badge(system.folder, system.family),
      el('span', {}, system.label),
      el('span', { className: 'n' }, count(system.games)),
    ];
    if (!active) return button(parts, () => setQuery(system.folder), 'chip system');
    const clear = button(
      [icon('close'), 'Search all systems'],
      () => {
        anywhere = true;
        run(false);
      },
      'link',
    );
    return el('div', { className: 'chip system on' }, [...parts, clear]);
  }

  async function run(append) {
    // Results arriving out of order would leave an older query's rows on screen; only the newest
    // request is allowed to paint.
    const mine = ++generation;
    if (!append) offset = 0;
    more.disabled = true;
    try {
      const result = await api.search(query, offset, anywhere);
      if (mine !== generation) return;
      offset += result.roms.length;
      if (append) list.append(result.roms);
      else list.set(result.roms);
      systems.replaceChildren(
        ...(result.system ? [systemChip(result.system, true)] : result.systems.map((s) => systemChip(s, false))),
      );
      status.textContent =
        list.size === 0
          ? result.systems.length > 0
            ? 'No game has that name. Did you mean one of these systems?'
            : `Nothing matches “${query}”. Try fewer words or another spelling.`
          : `${count(list.size)}${result.more ? '+' : ''} games`;
      more.hidden = !result.more;
    } catch (error) {
      if (mine !== generation) return;
      onError(error, () => {
        view.open();
        run(false);
      });
    } finally {
      if (mine === generation) more.disabled = false;
    }
  }

  function paintEmpty() {
    generation++;
    status.textContent = 'Search 2.4 million files across every collection.';
    systems.replaceChildren();
    list.set([]);
    more.hidden = true;
  }

  const view = {
    // Same nodes every time, so coming back keeps the query, its results and the game picked.
    open() {
      mount.replaceChildren(heading, status, systems, hint, list.node, more);
      hint.hidden = Boolean(query);
      if (!query) paintEmpty();
    },
    query(next) {
      clearTimeout(timer);
      const changed = next.trim() !== query;
      query = next.trim();
      if (!changed) return;
      anywhere = false;
      hint.hidden = Boolean(query);
      if (!query) {
        paintEmpty();
        return;
      }
      status.textContent = 'Searching…';
      timer = setTimeout(() => run(false), DEBOUNCE_MS);
    },
  };
  return view;
}
