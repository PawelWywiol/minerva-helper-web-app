import * as api from '../api.js';
import { badge, button, count, el, icon, loadOnScroll } from '../dom.js';
import { games } from './games.js';

/** Long enough that a two-letter prefix does not sweep the whole 2.4M-row index on every keystroke. */
const DEBOUNCE_MS = 200;
const EXAMPLES = ['nes mario', 'amiga cd32', 'psx final fantasy', 'genesis sonic', 'arcade'];
const MAX_LINES = 300;
const PER_LINE = 3;
const MORE_VERSIONS = 5;
const REGIONS = [
  ['usa', 'USA'],
  ['europe', 'Europe'],
  ['world', 'World'],
  ['japan', 'Japan'],
  ['other', 'Other regions'],
];
const REGIONS_KEY = 'minerva.hiddenRegions';

// a per-browser preference, like the scrolling choice
function loadHidden() {
  try {
    return new Set(JSON.parse(localStorage.getItem(REGIONS_KEY) || '[]'));
  } catch {
    return new Set();
  }
}

function saveHidden(hidden) {
  try {
    localStorage.setItem(REGIONS_KEY, JSON.stringify([...hidden]));
  } catch {
    // private windows may refuse storage; the filter still works for this visit
  }
}
const PARALLEL = 4;

/**
 * The archive searched by name, by system, or both: "nes mario" keeps to roms/nes, "amigacd"
 * offers the systems it could mean. The query box lives in the page header, shared by every view.
 * A pasted list searches every line on its own and shows each line's best matches under it.
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
    el('p', {}, [
      'Have a list? ',
      button('Search a whole list', () => showList(''), 'link'),
      ' or paste it into the search box.',
    ]),
  ]);

  const lines = el('textarea', {
    rows: 8,
    spellcheck: false,
    placeholder: 'One game per line, for example\nNES – Super Mario Bros. 3\nSonic the Hedgehog 2 (Mega Drive)',
    ariaLabel: 'Games to search, one per line',
  });
  const listBox = el('div', { className: 'panel listbox' }, [
    lines,
    el('div', { className: 'actions' }, [
      button('Search list', () => runList(), 'primary'),
      button(
        'Close',
        () => {
          listBox.hidden = true;
          paintEmpty();
        },
        'ghost',
      ),
    ]),
  ]);
  listBox.hidden = true;

  const hidden = loadHidden();
  const regionChips = el('div', { className: 'chips regions', role: 'group', ariaLabel: 'Release regions to show' });
  function paintRegions() {
    regionChips.replaceChildren(
      el('span', { className: 'muted' }, 'Show'),
      ...REGIONS.map(([key, label]) => {
        const chip = button(
          [hidden.has(key) ? null : icon('check'), label],
          () => {
            if (hidden.has(key)) hidden.delete(key);
            else hidden.add(key);
            saveHidden(hidden);
            paintRegions();
            if (!listBox.hidden) runList();
            else if (query) run(false);
          },
          'chip region',
        );
        chip.setAttribute('aria-pressed', String(!hidden.has(key)));
        return chip;
      }),
    );
  }
  paintRegions();
  const hide = () => [...hidden];

  let query = '';
  let anywhere = false;
  let offset = 0;
  let timer = null;
  let generation = 0;

  const withMatch = (result) =>
    result.roms.map((rom) => ({ ...rom, match: { terms: result.terms, prefix: result.prefix } }));

  function showList(text) {
    lines.value = text;
    listBox.hidden = false;
    hint.hidden = true;
    lines.focus();
    if (text.trim()) runList();
  }

  async function runList() {
    const mine = ++generation;
    const wanted = lines.value
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean)
      .slice(0, MAX_LINES);
    list.set([]);
    systems.replaceChildren();
    more.hidden = true;
    if (wanted.length === 0) {
      status.textContent = 'Paste or type one game per line.';
      return;
    }
    // a few requests at a time, started and painted in the list's own order
    let free = PARALLEL;
    const waiting = [];
    const acquire = () => (free > 0 ? (free--, Promise.resolve()) : new Promise((resolve) => waiting.push(resolve)));
    const release = () => (waiting.length > 0 ? waiting.shift()() : free++);
    const results = wanted.map(async (line) => {
      await acquire();
      try {
        // a newer search makes the rest of this list moot
        return mine === generation ? await api.search(line, { limit: PER_LINE, hide: hide() }) : null;
      } catch {
        return null;
      } finally {
        release();
      }
    });
    let found = 0;
    for (const [index, pending] of results.entries()) {
      status.textContent = `Searching ${count(index + 1)} of ${count(wanted.length)}…`;
      const result = await pending;
      if (mine !== generation) return;
      const roms = result ? withMatch(result) : [];
      if (roms.length > 0) found++;
      const where = result?.system ? ` in roms/${result.system.folder}` : '';
      let shown = roms.length;
      const line = wanted[index];
      const moreVersions = async () => {
        const next = await api.search(line, { offset: shown, limit: MORE_VERSIONS, hide: hide() });
        shown += next.roms.length;
        return { roms: withMatch(next), more: next.more };
      };
      list.appendGroup(
        line,
        roms.length > 0 ? `best matches${where}` : 'no match',
        roms,
        result?.more ? moreVersions : null,
      );
    }
    const missing = wanted.length - found;
    status.textContent = `${count(found)} of ${count(wanted.length)} found${missing ? `, ${count(missing)} without a match` : ''}`;
  }

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
      const result = await api.search(query, { offset, anywhere, hide: hide() });
      if (mine !== generation) return;
      offset += result.roms.length;
      if (append) list.append(withMatch(result));
      else list.set(withMatch(result));
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
      mount.replaceChildren(heading, listBox, regionChips, status, systems, hint, list.node, more);
      hint.hidden = Boolean(query) || !listBox.hidden;
      if (!query && listBox.hidden) paintEmpty();
    },
    list: showList,
    query(next) {
      clearTimeout(timer);
      const changed = next.trim() !== query;
      query = next.trim();
      if (!changed) return;
      anywhere = false;
      listBox.hidden = true;
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
