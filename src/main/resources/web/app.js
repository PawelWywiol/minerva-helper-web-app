import * as api from './api.js';
import { button, el, icon } from './dom.js';
import { browse } from './views/browse.js';
import { favourites } from './views/favourites.js';
import { home } from './views/home.js';
import { queue } from './views/queue.js';
import { search } from './views/search.js';
import { settings } from './views/settings.js';

const STATE_POLL_MS = 5000;

/**
 * One shell: navigation, the search box every view shares, and one mount per view. A view keeps
 * its own nodes while hidden, so coming back lands on the same page, scroll and game.
 * The view and the query live in the URL hash, so a reload lands where the user was.
 */
function shell() {
  const app = document.getElementById('app');
  const main = el('main', { className: 'content', id: 'content' });

  const input = el('input', {
    type: 'search',
    placeholder: 'Search games or systems, like “nes mario”',
    autocomplete: 'off',
    spellcheck: false,
    ariaLabel: 'Search games or systems',
  });

  const setQuery = (value) => {
    input.value = value;
    onType();
    input.focus();
  };

  const make = (factory, extra = {}) => {
    const mount = el('section', { className: 'view' });
    const onError = (error, retry) =>
      mount.replaceChildren(
        el(
          'p',
          { className: 'error' },
          error instanceof api.NoCatalogue
            ? 'The catalogue is not downloaded yet. Start it from Home.'
            : `Something went wrong: ${error.message}`,
        ),
        button('Try again', retry, 'ghost'),
      );
    return { mount, ...factory(mount, { onError, setQuery, ...extra }) };
  };

  const views = {
    home: { label: 'Home', icon: 'home', ...make(home) },
    search: { label: 'Search', icon: 'search', ...make(search) },
    browse: { label: 'Browse', icon: 'browse', ...make(browse) },
    favourites: { label: 'Favourites', icon: 'heart', ...make(favourites) },
    queue: { label: 'Queue', icon: 'queue', ...make(queue) },
    settings: { label: 'Settings', icon: 'settings', ...make(settings) },
  };

  const queued = el('span', { className: 'count', hidden: true });
  const links = Object.entries(views).map(([key, view]) =>
    el('a', { href: `#${key}`, dataset: { view: key } }, [
      icon(view.icon),
      el('span', {}, view.label),
      key === 'queue' ? queued : null,
    ]),
  );

  function route() {
    const [key, params] = location.hash.slice(1).split('?');
    return { key: views[key] ? key : 'home', query: new URLSearchParams(params).get('q') ?? '' };
  }

  function onType() {
    const query = input.value;
    const hash = query.trim() ? `#search?q=${encodeURIComponent(query.trim())}` : '#search';
    if (route().key === 'search') history.replaceState(null, '', hash);
    else location.hash = hash;
    views.search.query(query);
  }
  input.addEventListener('input', onType);
  // a one-line box would flatten a pasted list into one long query
  input.addEventListener('paste', (event) => {
    const text = event.clipboardData?.getData('text') ?? '';
    if (!/\r?\n/.test(text.trim())) return;
    event.preventDefault();
    input.value = '';
    if (route().key === 'search') history.replaceState(null, '', '#search');
    else location.hash = '#search';
    views.search.query('');
    views.search.list(text);
  });

  let current = null;
  function show() {
    const { key, query } = route();
    if (key === 'search' && query !== input.value.trim()) {
      input.value = query;
      views.search.query(query);
    }
    const view = views[key];
    if (view !== current) {
      // A view that polls must be told it is no longer on screen.
      current?.close?.();
      current = view;
      main.replaceChildren(view.mount);
      view.open();
    }
    for (const link of links) {
      if (link.dataset.view === key) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    }
  }

  async function pollState() {
    if (document.hidden) return;
    try {
      const status = await api.state();
      queued.hidden = status.queued === 0;
      queued.textContent = String(status.queued);
    } catch {
      queued.hidden = true;
    }
  }

  document.addEventListener('keydown', (event) => {
    if (event.key !== '/' || event.target instanceof HTMLInputElement) return;
    event.preventDefault();
    input.focus();
  });
  window.addEventListener('hashchange', show);

  app.replaceChildren(
    el('nav', { className: 'nav', ariaLabel: 'Sections' }, [
      el('a', { className: 'brand', href: '#home' }, [el('img', { src: '/favicon.svg', alt: '' }), 'Minerva']),
      ...links,
    ]),
    el('header', { className: 'top' }, el('label', { className: 'searchbox' }, [icon('search'), input])),
    main,
  );
  show();
  pollState();
  setInterval(pollState, STATE_POLL_MS);
}

shell();
