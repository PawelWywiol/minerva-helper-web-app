import * as api from '../api.js';
import { button, count, el, size } from '../dom.js';
import { t } from '../i18n.js';

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
        return [el('p', {}, t('home.missing')), act(t('home.download'), () => api.syncCatalog())];
      case 'downloading':
      case 'importing': {
        const percent = catalog.total > 0 ? Math.floor((catalog.done * 100) / catalog.total) : 0;
        const text =
          catalog.state === 'downloading'
            ? t('home.downloading', { done: size(catalog.done), total: size(catalog.total), percent })
            : t('home.importing', { done: catalog.done, total: catalog.total, percent });
        return [el('p', {}, text), el('progress', { max: 100, value: percent })];
      }
      case 'failed':
        return [
          el('p', { className: 'error' }, t('home.failed', { message: catalog.error || t('home.unknownError') })),
          act(t('action.retry'), () => api.syncCatalog()),
        ];
      default:
        return [
          el('p', { className: 'muted' }, t('home.ready')),
          act(
            t('home.checkUpdate'),
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
        el('h1', {}, t('home.title')),
        el('p', { className: 'lead' }, t('home.lead')),
        ready
          ? el('div', { className: 'chips' }, [
              el('span', { className: 'muted' }, t('home.try')),
              ...['nes mario', 'amiga cd32', 'psx', 'snes zelda'].map((q) => button(q, () => setQuery(q), 'chip')),
            ])
          : null,
        el('div', { className: 'stats' }, [
          stat(count(status.romCount), t('home.inCatalogue', { count: status.romCount }), ready ? 'browse' : null),
          stat(count(status.queued), t('home.inQueue', { count: status.queued }), 'queue'),
          stat(count(status.downloaded), t('home.inLibrary'), 'queue'),
        ]),
        el('section', { className: 'panel' }, [el('h2', {}, t('home.catalogue')), ...catalogue(catalog)]),
        el('p', { className: 'muted small' }, `Minerva Helper ${status.version}`),
      ];
      mount.replaceChildren(...parts.filter(Boolean));
      if (catalog.state === 'downloading' || catalog.state === 'importing') timer = setTimeout(paint, POLL_MS);
    } catch (error) {
      mount.replaceChildren(el('p', { className: 'error' }, error.message), button(t('action.retry'), paint, 'ghost'));
    }
  }

  return { open: paint, close: stop };
}
