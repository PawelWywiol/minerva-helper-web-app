import * as api from '../api.js';
import { badge, button, el, getState, icon, loadHidden, similarNote, size, splitName, toast, variant } from '../dom.js';
import { t } from '../i18n.js';

const FIRST_VERSIONS = 3;
const MORE_VERSIONS = 5;

/**
 * One game in full beside the list it was picked from: cover, where it lands, checksums, and on a
 * second tab the other files of the same game (regions, revisions) to pick instead. On a phone the
 * same panel slides over the list and closes back into it.
 */
export function detail({ toggleQueue, toggleFavourite, onClose }) {
  const node = el('aside', { className: 'detail', ariaLabel: t('detail.label') });
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
    node.replaceChildren(el('div', { className: 'empty' }, [icon('browse'), el('p', {}, t('detail.empty'))]));
  }

  function paint() {
    const rom = current;
    const [title] = splitName(rom.name);
    const close = el(
      'button',
      { type: 'button', className: 'icon-button close', ariaLabel: t('detail.close') },
      icon('close'),
    );
    close.addEventListener('click', onClose);

    const get = el('button', { type: 'button', className: 'primary' }, [
      icon(rom.queued ? 'close' : 'download'),
      t(rom.queued ? 'detail.unqueue' : rom.downloaded ? 'detail.downloadAgain' : 'rom.download'),
    ]);
    get.addEventListener('click', () => toggleQueue(rom));
    const favLabel = t(rom.favourite ? 'rom.unfavourite' : 'rom.favourite');
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
      ? el('img', { className: 'cover', src: full.cover, alt: t('detail.coverOf', { title: full.title || title }) })
      : el(
          'div',
          { className: `cover blank f-${rom.family}` },
          t(full || failed ? 'detail.noCover' : 'detail.lookingForCover'),
        );

    const parts = [
      close,
      cover,
      el('h2', {}, full?.title || title),
      el('p', { className: 'file' }, rom.name),
      failed ? el('p', { className: 'error' }, t('detail.failed', { message: failed })) : null,
      el('div', { className: 'lands' }, [
        badge(rom.folder, rom.family),
        el('div', {}, [
          el('strong', {}, `roms/${rom.folder}`),
          el('span', {}, t('detail.inEsde', { platform: rom.platform })),
        ]),
      ]),
      rom.downloaded && !rom.queued ? el('p', { className: 'ok' }, [icon('check'), t('rom.alreadyInLibrary')]) : null,
      similarNote(rom) ? el('p', { className: 'similar' }, t('detail.similar', { note: similarNote(rom) })) : null,
      el('div', { className: 'actions' }, [get, fav]),
      tabs(),
      tab === 'details'
        ? el('dl', { className: 'facts', role: 'tabpanel' }, [
            ...fact(t('detail.size'), size(rom.size)),
            ...fact(t('detail.path'), path(rom)),
            ...fact(t('detail.torrent'), full ? full.torrentPath.split('/').pop() : '…'),
            ...fact('MD5', full ? full.md5 || t('detail.notListed') : '…'),
            ...fact('SHA-1', full ? full.sha1 || t('detail.notListed') : '…'),
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
    const label = others?.loaded
      ? t('detail.versionsCount', { count: `${others.roms.length}${others.more ? '+' : ''}` })
      : t('detail.versions');
    return el(
      'div',
      { className: 'tabs', role: 'tablist' },
      [
        ['details', t('detail.details')],
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
      toast(t('detail.versionsFailed', { message: error.message }));
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
          version.downloaded ? el('span', { className: 'ok' }, t('rom.inLibrary')) : null,
        ]),
      ],
      () => show(version),
      'pick',
    );
    open.title = t('detail.showVersion');
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
    if (!others?.loaded) return el('p', { className: 'muted', role: 'tabpanel' }, t('detail.lookingForVersions'));
    if (others.roms.length === 0) {
      return el('p', { className: 'muted', role: 'tabpanel' }, t('detail.noVersions'));
    }
    const more = others.more
      ? button(t(others.loading ? 'loading' : 'action.moreVersions'), () => loadVersions(), 'link more-versions')
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
