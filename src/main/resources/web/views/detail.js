import * as api from '../api.js';
import { badge, el, icon, similarNote, size, splitName } from '../dom.js';

/**
 * One game in full beside the list it was picked from: cover, where it lands, checksums. On a
 * phone the same panel slides over the list and closes back into it.
 */
export function detail({ toggleQueue, toggleFavourite, onClose }) {
  const node = el('aside', { className: 'detail', ariaLabel: 'Game details' });
  let current = null;
  let full = null;
  let failed = null;
  let generation = 0;

  const fact = (key, value) => [el('dt', {}, key), el('dd', {}, value)];

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
      el('dl', { className: 'facts' }, [
        ...fact('Size', size(rom.size)),
        ...fact('Collection', rom.collection),
        ...fact('Torrent', full ? full.torrentPath.split('/').pop() : '…'),
        ...fact('MD5', full ? full.md5 || 'not listed' : '…'),
        ...fact('SHA-1', full ? full.sha1 || 'not listed' : '…'),
      ]),
    ];
    node.replaceChildren(...parts.filter(Boolean));
  }

  async function show(rom) {
    current = rom;
    full = null;
    failed = null;
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
