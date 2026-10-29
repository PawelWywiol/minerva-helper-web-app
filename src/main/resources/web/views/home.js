import * as api from '../api.js';
import { button, count, el, size } from '../dom.js';

const POLL_MS = 1000;

/** What the library holds, plus the catalogue: it has to be downloaded once (~1.7 GB) before anything can be browsed. */
export function home(mount, { setQuery }) {
  let timer = null;
  const stop = () => {
    clearTimeout(timer);
    timer = null;
  };

  const act = (label, action, className = 'primary') =>
    button(
      label,
      async (event) => {
        event.currentTarget.disabled = true;
        try {
          await action();
        } finally {
          paint();
        }
      },
      className,
    );

  function catalogue(catalog) {
    switch (catalog.state) {
      case 'missing':
        return [
          el('p', {}, 'Download the catalogue first. It takes about 1.7 GB and a few minutes, once.'),
          act('Download catalogue', () => api.syncCatalog()),
        ];
      case 'downloading':
      case 'importing': {
        const percent = catalog.total > 0 ? Math.floor((catalog.done * 100) / catalog.total) : 0;
        const text =
          catalog.state === 'downloading'
            ? `Downloading the archive index: ${size(catalog.done)} of ${size(catalog.total)}`
            : `Building the catalogue: ${count(catalog.done)} of ${count(catalog.total)} files`;
        return [el('p', {}, `${text} (${percent}%)`), el('progress', { max: 100, value: percent })];
      }
      case 'failed':
        return [
          el('p', { className: 'error' }, `The catalogue could not be built: ${catalog.error || 'unknown error'}`),
          act('Try again', () => api.syncCatalog()),
        ];
      default:
        return [
          el('p', { className: 'muted' }, 'The catalogue is ready.'),
          act(
            'Check for a newer catalogue',
            async () => {
              const { available } = await api.catalogUpdate();
              if (available) await api.syncCatalog(true);
            },
            'ghost',
          ),
        ];
    }
  }

  const stat = (value, label, hash) => {
    const body = [el('strong', {}, value), el('span', {}, label)];
    return hash ? el('a', { className: 'stat', href: `#${hash}` }, body) : el('div', { className: 'stat' }, body);
  };

  async function paint() {
    stop();
    try {
      const [status, catalog] = await Promise.all([api.state(), api.catalog()]);
      const ready = catalog.state === 'ready';
      const parts = [
        el('h1', {}, 'Your retro library'),
        el(
          'p',
          { className: 'lead' },
          'Find a game in the MiNERVA archive and it lands in the ES-DE folder of its system, ready to play.',
        ),
        ready
          ? el('div', { className: 'chips' }, [
              el('span', { className: 'muted' }, 'Try'),
              ...['nes mario', 'amiga cd32', 'psx', 'snes zelda'].map((q) => button(q, () => setQuery(q), 'chip')),
            ])
          : null,
        el('div', { className: 'stats' }, [
          stat(count(status.romCount), 'games in the catalogue', ready ? 'browse' : null),
          stat(count(status.queued), 'waiting in the queue', 'queue'),
          stat(count(status.downloaded), 'in your library', 'queue'),
        ]),
        el('section', { className: 'panel' }, [el('h2', {}, 'Catalogue'), ...catalogue(catalog)]),
        el('p', { className: 'muted small' }, `Minerva Helper ${status.version}`),
      ];
      mount.replaceChildren(...parts.filter(Boolean));
      if (catalog.state === 'downloading' || catalog.state === 'importing') timer = setTimeout(paint, POLL_MS);
    } catch (error) {
      mount.replaceChildren(el('p', { className: 'error' }, error.message), button('Try again', paint, 'ghost'));
    }
  }

  return { open: paint, close: stop };
}
