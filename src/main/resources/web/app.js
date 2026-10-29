import * as api from './api.js';
import { count, el, size } from './dom.js';
import { browse } from './views/browse.js';
import { detail } from './views/detail.js';
import { favourites } from './views/favourites.js';
import { queue } from './views/queue.js';
import { search } from './views/search.js';
import { settings } from './views/settings.js';

const app = document.getElementById('app');
const POLL_MS = 1000;

const fact = (key, value) => el('li', {}, [el('div', { className: 'k' }, key), el('div', { className: 'v' }, value)]);

/** Server facts plus the catalogue: it has to be downloaded once (~1.7 GB) before anything can be browsed. */
function overview(mount) {
  let timer = null;
  const stop = () => {
    clearTimeout(timer);
    timer = null;
  };

  function catalogueCard(catalog, onChange) {
    const button = (label, action, ghost = false) => {
      const node = el('button', { type: 'button', className: ghost ? 'ghost' : '' }, label);
      node.addEventListener('click', async () => {
        node.disabled = true;
        try {
          await action();
        } finally {
          onChange();
        }
      });
      return node;
    };

    switch (catalog.state) {
      case 'missing':
        return [
          el(
            'p',
            { className: 'warn' },
            'The catalogue is not downloaded yet. It takes about 1.7 GB and a few minutes, once.',
          ),
          el(
            'div',
            { className: 'actions' },
            button('Download catalogue', () => api.syncCatalog()),
          ),
        ];
      case 'downloading':
      case 'importing': {
        const percent = catalog.total > 0 ? Math.floor((catalog.done * 100) / catalog.total) : 0;
        const detailText =
          catalog.state === 'downloading'
            ? `Downloading the archive index — ${size(catalog.done)} of ${size(catalog.total)}`
            : `Building the catalogue — ${count(catalog.done)} of ${count(catalog.total)} files`;
        return [
          el('p', { className: 'muted' }, `${detailText} (${percent}%)`),
          el('progress', { max: 100, value: percent }),
        ];
      }
      case 'failed':
        return [
          el('p', { className: 'error' }, `The catalogue could not be built: ${catalog.error || 'unknown error'}`),
          el(
            'div',
            { className: 'actions' },
            button('Try again', () => api.syncCatalog()),
          ),
        ];
      default: {
        const check = button(
          'Check for a newer catalogue',
          async () => {
            const { available } = await api.catalogUpdate();
            if (available) await api.syncCatalog(true);
          },
          true,
        );
        return [el('div', { className: 'actions' }, check)];
      }
    }
  }

  async function paint() {
    stop();
    try {
      const [status, catalog] = await Promise.all([api.state(), api.catalog()]);
      mount.replaceChildren(
        el('h1', {}, 'Overview'),
        el('p', { className: 'muted' }, `Version ${status.version}`),
        el('ul', { className: 'facts' }, [
          fact('Games in catalogue', count(status.romCount)),
          fact('In the queue', count(status.queued)),
          fact('Downloaded', count(status.downloaded)),
          fact('Downloads at once', count(status.concurrentDownloads)),
        ]),
        el('h2', {}, 'Catalogue'),
        ...catalogueCard(catalog, paint),
      );
      if (catalog.state === 'downloading' || catalog.state === 'importing') timer = setTimeout(paint, POLL_MS);
    } catch (error) {
      mount.replaceChildren(el('p', { className: 'error' }, error.message));
    }
  }

  return { open: paint, close: stop };
}

/**
 * One shell, one mount point. The tab is kept in the URL hash so a reload — and the browser's own
 * Back button — land where the user was.
 */
function shell() {
  const mount = el('section', { className: 'card' });
  const onError = (error, retry) => {
    const again = el('button', { type: 'button', className: 'ghost' }, 'Try again');
    again.addEventListener('click', retry);
    mount.replaceChildren(
      el(
        'p',
        { className: 'error' },
        error instanceof api.NoCatalogue
          ? 'There is no catalogue yet — download it from the Overview tab.'
          : error.message,
      ),
      again,
    );
  };

  // Detail is not a tab: it belongs to whichever list opened it, and hands control straight back
  // there so the list keeps the page and the scroll the user had reached.
  const pane = detail(mount, { onError });
  const onDetails = (rom, back) => pane.open(rom, back);

  const views = {
    overview: { label: 'Overview', ...overview(mount) },
    browse: { label: 'Browse', ...browse(mount, { onError, onDetails }) },
    search: { label: 'Search', ...search(mount, { onError, onDetails }) },
    favourites: { label: 'Favourites', ...favourites(mount, { onError, onDetails }) },
    queue: { label: 'Queue', ...queue(mount, { onError }) },
    settings: { label: 'Settings', ...settings(mount, { onError }) },
  };

  const tabs = el('nav', { className: 'tabs', ariaLabel: 'Sections' });
  const paintTabs = (active) => {
    tabs.replaceChildren(
      ...Object.entries(views).map(([key, view]) => {
        const button = el(
          'button',
          {
            type: 'button',
            className: key === active ? 'tab on' : 'tab',
            ariaCurrent: key === active ? 'page' : 'false',
          },
          view.label,
        );
        button.addEventListener('click', () => {
          location.hash = key;
        });
        return button;
      }),
    );
  };

  // A view that polls must be told it is no longer on screen.
  let current = null;
  const show = () => {
    const key = location.hash.replace('#', '') || 'overview';
    const view = views[key] || views.overview;
    current?.close?.();
    current = view;
    paintTabs(views[key] ? key : 'overview');
    view.open();
  };

  window.addEventListener('hashchange', show);
  app.replaceChildren(
    el('header', { className: 'bar' }, [el('span', { className: 'brand' }, 'Minerva Helper'), tabs]),
    mount,
  );
  show();
}

shell();
