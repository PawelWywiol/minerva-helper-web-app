import * as api from '../api.js';
import { badge, button, el, getState, icon, loadHidden, similarNote, size, splitName, toast, variant } from '../dom.js';

const FIRST_VERSIONS = 3;
const MORE_VERSIONS = 5;

/**
 * One game in full beside the list it was picked from: cover, where it lands, checksums, and on a
 * second tab the other files of the same game (regions, revisions) to pick instead. On a phone the
 * same panel slides over the list and closes back into it.
 */
export function detail({ toggleQueue, toggleFavourite, onClose }) {
  const node = el('aside', { className: 'detail', ariaLabel: 'Game details' });
  let current = null;
  let full = null;
  let failed = null;
  let generation = 0;
  let tab = 'details';
  let others = null;

  const fact = (key, value) => [el('dt', {}, key), el('dd', {}, value)];

  // where the file sits in the archive, each level a link into Browse
  const path = (rom) => {
    const collection = new URLSearchParams({ c: rom.collection });
    const platform = new URLSearchParams({ c: rom.collection, p: rom.platformKey });
    return el('span', { className: 'path' }, [
      el('a', { href: `#browse?${collection}` }, rom.collection),
      ' / ',
      el('a', { href: `#browse?${platform}` }, rom.platformKey),
    ]);
  };

  function clear() {
    current = null;
    full = null;
    generation++;
    node.replaceChildren(
      el('div', { className: 'empty' }, [
        icon('browse'),
        el('p', {}, 'Pick a game to see its cover, where it lands and its checksums.'),
      ]),
    );
  }

  function paint() {
    const rom = current;
    const [title] = splitName(rom.name);
    const close = el(
      'button',
      { type: 'button', className: 'icon-button close', ariaLabel: 'Close details' },
      icon('close'),
    );
    close.addEventListener('click', onClose);

    const get = el('button', { type: 'button', className: 'primary' }, [
      icon(rom.queued ? 'close' : 'download'),
      rom.queued ? 'Remove from queue' : rom.downloaded ? 'Download again' : 'Download',
    ]);
    get.addEventListener('click', () => toggleQueue(rom));
    const favLabel = rom.favourite ? 'Remove from favourites' : 'Add to favourites';
    const fav = el(
      'button',
      {
        type: 'button',
        className: 'ghost icon-button fav',
        title: favLabel,
        ariaLabel: favLabel,
        ariaPressed: String(Boolean(rom.favourite)),
      },
      icon('heart'),
    );
    fav.addEventListener('click', () => toggleFavourite(rom));

    const cover = full?.cover
      ? el('img', { className: 'cover', src: full.cover, alt: `Cover of ${full.title || title}` })
      : el(
          'div',
          { className: `cover blank f-${rom.family}` },
          full || failed ? 'No cover found' : 'Looking for a cover…',
        );

    const parts = [
      close,
      cover,
      el('h2', {}, full?.title || title),
      el('p', { className: 'file' }, rom.name),
      failed ? el('p', { className: 'error' }, `Details could not be loaded: ${failed}`) : null,
      el('div', { className: 'lands' }, [
        badge(rom.folder, rom.family),
        el('div', {}, [el('strong', {}, `roms/${rom.folder}`), el('span', {}, `${rom.platform} in ES-DE`)]),
      ]),
      rom.downloaded && !rom.queued ? el('p', { className: 'ok' }, [icon('check'), 'Already in your library']) : null,
      similarNote(rom)
        ? el('p', { className: 'similar' }, `${similarNote(rom)}: check before downloading this one too.`)
        : null,
      el('div', { className: 'actions' }, [get, fav]),
      tabs(),
      tab === 'details'
        ? el('dl', { className: 'facts', role: 'tabpanel' }, [
            ...fact('Size', size(rom.size)),
            ...fact('Path', path(rom)),
            ...fact('Torrent', full ? full.torrentPath.split('/').pop() : '…'),
            ...fact('MD5', full ? full.md5 || 'not listed' : '…'),
            ...fact('SHA-1', full ? full.sha1 || 'not listed' : '…'),
          ])
        : versionsPanel(),
    ];
    // a repaint after "More versions" must not throw the reader back to the top of the list
    const scrolled = node.querySelector('.versions')?.scrollTop ?? 0;
    node.replaceChildren(...parts.filter(Boolean));
    const versions = node.querySelector('.versions');
    if (versions) versions.scrollTop = scrolled;
  }

  function tabs() {
    const label = others?.loaded ? `Other versions (${others.roms.length}${others.more ? '+' : ''})` : 'Other versions';
    return el(
      'div',
      { className: 'tabs', role: 'tablist' },
      [
        ['details', 'Details'],
        ['versions', label],
      ].map(([key, text]) => {
        const node = button(
          text,
          () => {
            tab = key;
            paint();
            if (key === 'versions' && !others) loadVersions();
          },
          'tab',
        );
        node.setAttribute('role', 'tab');
        node.setAttribute('aria-selected', String(tab === key));
        return node;
      }),
    );
  }

  async function loadVersions() {
    const rom = current;
    const mine = generation;
    others ??= { roms: [], more: false, loaded: false, loading: false };
    others.loading = true;
    paint();
    try {
      const page = await api.versions(rom.id, {
        offset: others.roms.length,
        limit: others.loaded ? MORE_VERSIONS : FIRST_VERSIONS,
        hide: [...loadHidden()],
      });
      if (mine !== generation) return;
      others.roms.push(...page.roms);
      others.more = page.more;
      others.loaded = true;
    } catch (error) {
      if (mine !== generation) return;
      toast(`Could not load other versions: ${error.message}`);
    }
    others.loading = false;
    paint();
  }

  function versionRow(version) {
    const [title, tags] = splitName(version.name);
    const state = getState(version);
    const open = button(
      [
        el('span', { className: 'title' }, [title, tags ? el('span', { className: 'tags' }, ` ${tags}`) : null]),
        el('span', { className: 'sub' }, [
          el('span', {}, size(version.size)),
          el('span', {}, version.collection),
          variant(version) ? el('span', {}, variant(version)) : null,
          version.downloaded ? el('span', { className: 'ok' }, 'In library') : null,
        ]),
      ],
      () => show(version),
      'pick',
    );
    open.title = 'Show this version';
    const get = button(
      icon(state.icon),
      async () => {
        await toggleQueue(version);
        paint();
      },
      'icon-button get',
    );
    get.dataset.state = state.icon;
    get.title = state.hint;
    get.setAttribute('aria-label', `${state.label}: ${version.name}`);
    get.disabled = Boolean(version.downloaded && !version.queued);
    return el('div', { className: 'version' }, [open, get]);
  }

  function versionsPanel() {
    if (!others?.loaded) return el('p', { className: 'muted', role: 'tabpanel' }, 'Looking for other versions…');
    if (others.roms.length === 0) {
      return el('p', { className: 'muted', role: 'tabpanel' }, 'No other version of this game in this system.');
    }
    const more = others.more
      ? button(others.loading ? 'Loading…' : 'More versions', () => loadVersions(), 'link more-versions')
      : null;
    if (more) more.disabled = others.loading;
    return el('div', { className: 'versions', role: 'tabpanel' }, [...others.roms.map(versionRow), more]);
  }

  async function show(rom) {
    current = rom;
    full = null;
    failed = null;
    tab = 'details';
    others = null;
    const mine = ++generation;
    paint();
    node.scrollTop = 0;
    try {
      const answer = await api.rom(rom.id);
      if (mine !== generation) return;
      full = answer;
      paint();
    } catch (error) {
      if (mine !== generation) return;
      failed = error.message;
      paint();
    }
  }

  clear();
  return {
    node,
    show,
    clear,
    refresh(rom) {
      if (current?.id === rom.id) paint();
    },
  };
}
