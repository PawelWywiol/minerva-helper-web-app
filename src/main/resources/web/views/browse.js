import * as api from '../api.js';
import { badge, button, count, el, spinner } from '../dom.js';
import { games } from './games.js';

/**
 * Collections → platforms → games, the archive's own three levels.
 *
 * The game list keeps a window rather than everything: the largest platform holds 753k entries, and
 * a page that grows without limit eventually stops scrolling.
 */
const MAX_ROWS = 1000;

export function browse(mount, { onError }) {
  const render = (...nodes) => mount.replaceChildren(...nodes);

  const crumbs = (...parts) =>
    el(
      'nav',
      { className: 'crumbs', ariaLabel: 'Where you are' },
      parts.map((part) =>
        part.onClick ? button(part.label, part.onClick, 'link') : el('span', { ariaCurrent: 'page' }, part.label),
      ),
    );

  const card = (onClick, children) => button(children, onClick, 'card');

  async function showCollections() {
    render(spinner('Loading collections…'));
    try {
      const { collections } = await api.collections();
      render(
        el('h1', {}, 'Browse'),
        el('p', { className: 'muted' }, 'Each collection is a different preservation group’s set of dumps.'),
        el(
          'div',
          { className: 'cards' },
          collections.map((c) =>
            card(
              () => showPlatforms(c.name),
              [
                el('strong', {}, c.name),
                el('span', { className: 'muted' }, `${count(c.platformCount)} platforms`),
                el('span', { className: 'n' }, `${count(c.fileCount)} files`),
              ],
            ),
          ),
        ),
      );
    } catch (error) {
      onError(error, showCollections);
    }
  }

  async function showPlatforms(collection) {
    render(spinner('Loading platforms…'));
    try {
      const { platforms } = await api.platforms(collection);
      render(
        crumbs({ label: 'Browse', onClick: showCollections }, { label: collection }),
        el('h1', {}, collection),
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
                el('span', { className: 'n' }, `${count(p.fileCount)} files`),
              ],
            ),
          ),
        ),
      );
    } catch (error) {
      onError(error, () => showPlatforms(collection));
    }
  }

  async function showRoms(collection, platform) {
    const list = games();
    const rows = [];
    let cursor = null;
    let more = true;
    let loading = false;

    const status = el('p', { className: 'muted' }, 'Loading…');
    const moreButton = button('Show more', () => loadMore(), 'ghost wide');
    moreButton.hidden = true;

    render(
      crumbs(
        { label: 'Browse', onClick: showCollections },
        { label: collection, onClick: () => showPlatforms(collection) },
        { label: platform.label },
      ),
      el('h1', {}, platform.label),
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
        onError(error, () => showRoms(collection, platform));
      } finally {
        loading = false;
        moreButton.disabled = false;
      }
    }

    await loadMore();
  }

  let opened = false;
  return {
    // Leaving and coming back keeps the level, page and game the user had reached.
    open() {
      if (opened) return;
      opened = true;
      showCollections();
    },
  };
}
