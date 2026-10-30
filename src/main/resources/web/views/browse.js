import * as api from '../api.js';
import { badge, button, count, el, icon, loadOnScroll, size, spinner, toast } from '../dom.js';
import { games } from './games.js';

/**
 * Collections → platforms → games, the archive's own three levels. Each level can be queued whole,
 * after a confirmation that names its file count and size. The level reached lives in the URL hash,
 * so a game's path in its details links straight to it.
 *
 * The game list keeps a window rather than everything: the largest platform holds 753k entries, and
 * a page that grows without limit eventually stops scrolling.
 */
const MAX_ROWS = 1000;

export function browse(mount, { onError }) {
  // only the newest navigation may paint; a slow earlier one must not land on top of it
  let generation = 0;
  const render = (...nodes) => mount.replaceChildren(...nodes);
  const remember = (collection, key) => {
    const params = new URLSearchParams();
    if (collection) params.set('c', collection);
    if (key) params.set('p', key);
    const hash = params.toString() ? `#browse?${params}` : '#browse';
    if (location.hash !== hash) history.replaceState(null, '', hash);
  };

  const crumbs = (...parts) =>
    el(
      'nav',
      { className: 'crumbs', ariaLabel: 'Where you are' },
      parts.map((part) =>
        part.onClick ? button(part.label, part.onClick, 'link') : el('span', { ariaCurrent: 'page' }, part.label),
      ),
    );

  const amount = (files, bytes) => `${count(files)} ${files === 1 ? 'file' : 'files'}, ${size(bytes)}`;

  // one question at a time, shown under the heading: queueing a folder can mean terabytes
  const confirmation = el('div', { className: 'panel confirm', role: 'alertdialog', hidden: true });

  function askToQueue(label, files, bytes, collection, platformId) {
    const yes = button(
      'Queue all',
      async () => {
        yes.disabled = true;
        try {
          const answer = await api.queueAll(collection, platformId);
          confirmation.hidden = true;
          toast(
            answer.fits
              ? `Queued ${count(answer.added)} ${answer.added === 1 ? 'file' : 'files'} from ${label}`
              : `Will not fit: ${size(answer.bytes)} needed, ${size(answer.freeBytes)} free after the queue`,
          );
        } catch (error) {
          toast(`Could not queue ${label}: ${error.message}`);
        } finally {
          yes.disabled = false;
        }
      },
      'primary',
    );
    confirmation.replaceChildren(
      el('p', {}, [el('strong', {}, `Queue all of ${label}?`), ` ${amount(files, bytes)}.`]),
      el('p', { className: 'muted' }, 'Files already in your library are skipped.'),
      el('div', { className: 'actions' }, [yes, button('Cancel', () => (confirmation.hidden = true), 'ghost')]),
    );
    confirmation.hidden = false;
    confirmation.scrollIntoView({ block: 'nearest' });
    yes.focus({ preventScroll: true });
  }

  const queueButton = (label, files, bytes, collection, platformId) => {
    const node = button(
      icon('download'),
      () => askToQueue(label, files, bytes, collection, platformId),
      'icon-button get',
    );
    node.title = `Queue all of ${label}`;
    node.setAttribute('aria-label', `Queue all of ${label}, ${amount(files, bytes)}`);
    return node;
  };

  const card = (onClick, children, queue) =>
    el('div', { className: 'card-wrap' }, [button(children, onClick, 'card'), queue]);

  async function showCollections() {
    const mine = ++generation;
    remember();
    confirmation.hidden = true;
    render(spinner('Loading collections…'));
    try {
      const { collections } = await api.collections();
      if (mine !== generation) return;
      render(
        el('h1', {}, 'Browse'),
        el('p', { className: 'muted' }, 'Each collection is a different preservation group’s set of dumps.'),
        confirmation,
        el(
          'div',
          { className: 'cards' },
          collections.map((c) =>
            card(
              () => showPlatforms(c.name),
              [
                el('strong', {}, c.name),
                el(
                  'span',
                  { className: 'muted' },
                  `${count(c.platformCount)} ${c.platformCount === 1 ? 'platform' : 'platforms'}`,
                ),
                el('span', { className: 'n' }, amount(c.fileCount, c.bytes)),
              ],
              queueButton(c.name, c.fileCount, c.bytes, c.name),
            ),
          ),
        ),
      );
    } catch (error) {
      if (mine === generation) onError(error, showCollections);
    }
  }

  async function showPlatforms(collection) {
    const mine = ++generation;
    remember(collection);
    confirmation.hidden = true;
    render(spinner('Loading platforms…'));
    try {
      const { platforms } = await api.platforms(collection);
      if (mine !== generation) return;
      const files = platforms.reduce((sum, p) => sum + p.fileCount, 0);
      const bytes = platforms.reduce((sum, p) => sum + p.bytes, 0);
      render(
        crumbs({ label: 'Browse', onClick: showCollections }, { label: collection }),
        el('div', { className: 'heading' }, [
          el('h1', {}, collection),
          button(`Queue all, ${amount(files, bytes)}`, () => askToQueue(collection, files, bytes, collection), 'ghost'),
        ]),
        confirmation,
        el(
          'div',
          { className: 'cards' },
          platforms.map((p) =>
            card(
              () => showRoms(collection, p),
              [
                badge(p.folder, p.family),
                el('strong', {}, p.label),
                p.label === p.key ? null : el('span', { className: 'muted' }, p.key),
                el('span', { className: 'n' }, amount(p.fileCount, p.bytes)),
              ],
              queueButton(p.label, p.fileCount, p.bytes, collection, p.id),
            ),
          ),
        ),
      );
    } catch (error) {
      if (mine === generation) onError(error, () => showPlatforms(collection));
    }
  }

  async function showPlatform(collection, key) {
    const mine = ++generation;
    render(spinner('Loading platform…'));
    try {
      const { platforms } = await api.platforms(collection);
      if (mine !== generation) return;
      const platform = platforms.find((p) => p.key === key);
      if (platform) showRoms(collection, platform);
      else showPlatforms(collection);
    } catch (error) {
      if (mine === generation) onError(error, () => showPlatform(collection, key));
    }
  }

  async function showRoms(collection, platform) {
    const mine = ++generation;
    remember(collection, platform.key);
    confirmation.hidden = true;
    const list = games();
    const rows = [];
    let cursor = null;
    let more = true;
    let loading = false;

    const status = el('p', { className: 'muted' }, 'Loading…');
    const moreButton = button('Show more', () => loadMore(), 'ghost wide');
    moreButton.hidden = true;
    loadOnScroll(moreButton);

    render(
      crumbs(
        { label: 'Browse', onClick: showCollections },
        { label: collection, onClick: () => showPlatforms(collection) },
        { label: platform.label },
      ),
      el('div', { className: 'heading' }, [
        el('h1', {}, platform.label),
        button(
          `Queue all, ${amount(platform.fileCount, platform.bytes)}`,
          () => askToQueue(platform.label, platform.fileCount, platform.bytes, collection, platform.id),
          'ghost',
        ),
      ]),
      confirmation,
      status,
      list.node,
      moreButton,
    );

    async function loadMore() {
      if (loading || !more) return;
      loading = true;
      moreButton.disabled = true;
      try {
        const page = await api.roms(platform.id, cursor);
        if (mine !== generation) return;
        rows.push(...page.roms);
        cursor = page.last;
        more = page.more;
        if (rows.length > MAX_ROWS) {
          rows.splice(0, rows.length - MAX_ROWS);
          list.set(rows);
        } else {
          list.append(page.roms);
        }
        status.textContent = `${count(rows.length)} of ${count(platform.fileCount)} shown, landing in roms/${platform.folder}`;
        moreButton.hidden = !more;
      } catch (error) {
        if (mine === generation) onError(error, () => showRoms(collection, platform));
      } finally {
        loading = false;
        moreButton.disabled = false;
      }
    }

    await loadMore();
  }

  function go() {
    const params = new URLSearchParams(location.hash.split('?')[1] ?? '');
    const collection = params.get('c');
    const key = params.get('p');
    if (!collection) showCollections();
    else if (!key) showPlatforms(collection);
    else showPlatform(collection, key);
  }

  let opened = false;
  return {
    // Leaving and coming back keeps the level, page and game the user had reached, unless the
    // link that brought the user here names a level of its own.
    open() {
      if (opened && !location.hash.includes('?')) return;
      opened = true;
      go();
    },
    go,
  };
}
