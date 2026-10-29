import * as api from '../api.js';
import { button, count, el, size, toast } from '../dom.js';
import { games } from './games.js';

/**
 * The queue, refreshed from the server once a second.
 *
 * Two rules shape this view. Polling stops the moment the tab is hidden or the user leaves the
 * view — a forgotten tab must not keep polling the server. And the lists are only rebuilt when
 * their shape changes: a button that re-flows under a finger every second is how the wrong row gets
 * pressed, so the same nodes are kept and only their text is refreshed.
 */
const POLL_MS = 1000;

const STATES = {
  queued: { text: 'Waiting', kind: '' },
  downloading: { text: 'Downloading', kind: 'busy' },
  verifying: { text: 'Checking', kind: 'busy' },
  paused: { text: 'Paused', kind: '' },
  failed: { text: 'Failed', kind: 'bad' },
};

export function queue(mount, { onError }) {
  let timer = null;
  let shape = '';
  const updaters = new Map();
  // a downloaded row forgets the record only; its file stays in the library
  const list = games({
    onRemove: async (rom) => {
      try {
        if (rom.downloadedId) {
          await api.forgetDownloaded(rom.downloadedId);
          toast(`Forgotten. The file stays in roms/${rom.folder}`);
        } else {
          await api.removeQueued(rom.queueId);
          toast('Removed from the queue');
        }
        await poll();
      } catch (error) {
        toast(`Could not change the queue: ${error.message}`);
      }
    },
  });

  const summary = el('p', { className: 'muted' });
  const free = el('p', { className: 'muted' });
  const hint = el('p', { className: 'muted' });
  const runButton = button('', () => act(() => api.setRunning(!running)), 'ghost');
  const retryButton = button('Retry failed', () => act(() => api.retryFailed()), 'ghost');
  const actions = el('div', { className: 'actions' }, [runButton, retryButton]);
  const torrents = el('div');

  let running = false;

  async function act(request) {
    try {
      await request();
      await poll();
    } catch (error) {
      onError(error, open);
    }
  }

  function buildTorrent(group, index, total) {
    const progress = el('span', { className: 'muted small' });
    const fill = el('span');
    const bar = el('div', { className: 'meter' }, fill);
    const pause = el('button', { type: 'button', className: 'ghost small' });
    const up = button('↑', () => act(() => api.moveGroup(index, index - 1)), 'ghost small');
    const down = button('↓', () => act(() => api.moveGroup(index, index + 1)), 'ghost small');
    up.disabled = index === 0;
    down.disabled = index === total - 1;
    up.title = 'Fetch sooner';
    down.title = 'Fetch later';
    pause.addEventListener('click', () => act(() => api.pauseGroup(group.torrentPath, pause.dataset.paused !== '1')));

    updaters.set(group.torrentPath, (fresh) => {
      pause.dataset.paused = fresh.paused ? '1' : '0';
      pause.textContent = fresh.paused ? 'Resume' : 'Pause';
      const active = fresh.active;
      bar.hidden = !active;
      progress.textContent = active
        ? `${active.percent}%, ${size(active.rate)}/s from ${active.peers} peers`
        : `${count(fresh.items.length)} ${fresh.items.length === 1 ? 'game' : 'games'}`;
      if (active) fill.style.width = `${active.percent}%`;
    });

    return el('div', { className: 'row' }, [
      el('span', { className: 'name' }, [group.label.replace(/\.torrent$/, ''), el('br'), progress, bar]),
      el('span', {}, ''),
      el('span', { className: 'buttons' }, [up, down, pause]),
    ]);
  }

  const shapeOf = (data) =>
    [
      data.groups.map((g) => `${g.torrentPath}=${g.items.map((i) => i.id).join(',')}`).join('|'),
      data.downloaded.map((d) => d.id).join(','),
    ].join('#');

  async function rebuild(data) {
    updaters.clear();
    torrents.replaceChildren(
      ...(data.groups.length > 0
        ? [
            el('h2', {}, 'Torrents'),
            el(
              'div',
              { className: 'list' },
              data.groups.map((g, i) => buildTorrent(g, i, data.groups.length)),
            ),
          ]
        : []),
    );
    const { roms, downloaded } = await api.queueRoms();
    list.set([]);
    if (roms.length > 0) {
      list.appendGroup(
        'In the queue',
        '',
        roms.map((rom) => ({ ...rom, key: `q${rom.queueId}` })),
      );
    }
    if (downloaded.length > 0) {
      const rest = data.downloadedTotal - downloaded.length;
      list.appendGroup(
        'Recently downloaded',
        rest > 0 ? `${count(rest)} more are marked in the lists` : '',
        downloaded.map((rom) => ({ ...rom, key: `d${rom.downloadedId}`, removeLabel: 'Forget, keep the file' })),
      );
    }
  }

  function paint(data) {
    data.groups.forEach((group) => {
      updaters.get(group.torrentPath)?.(group);
      group.items.forEach((item) => {
        const state = STATES[item.state] || STATES.queued;
        const percent = item.state === 'downloading' && group.active ? ` ${group.active.percent}%` : '';
        const text = item.error ? `${state.text}: ${item.error}` : state.text + percent;
        list.patch(`q${item.id}`, { status: { text, kind: state.kind } });
      });
    });

    running = data.working;
    summary.textContent = `${count(data.count)} ${data.count === 1 ? 'game' : 'games'}, ${size(data.bytes)} in ${count(data.groups.length)} ${data.groups.length === 1 ? 'torrent' : 'torrents'}`;
    free.textContent =
      data.bytes > data.freeBytes
        ? `Will not fit: only ${size(data.freeBytes)} free in the library`
        : `${size(data.freeBytes)} free in the library`;
    free.className = data.bytes > data.freeBytes ? 'warn' : 'muted';
    hint.textContent =
      data.count === 0
        ? 'Nothing is waiting. Find a game in Search or Browse and press its download button.'
        : data.working
          ? 'Downloading. Move a torrent up to have it fetched sooner.'
          : 'Paused. Press Resume to continue; progress made so far is kept.';
    runButton.hidden = data.count === 0;
    runButton.textContent = data.working ? 'Pause all' : 'Resume';
    retryButton.hidden = !data.failed;
  }

  async function poll() {
    try {
      const data = await api.queue();
      const next = shapeOf(data);
      if (next !== shape) {
        shape = next;
        await rebuild(data);
      }
      paint(data);
    } catch (error) {
      stop();
      onError(error, open);
    }
  }

  const tick = async () => {
    if (document.hidden) return;
    await poll();
  };

  function start() {
    stop();
    timer = setInterval(tick, POLL_MS);
    document.addEventListener('visibilitychange', wake);
    poll();
  }

  function stop() {
    clearInterval(timer);
    timer = null;
    document.removeEventListener('visibilitychange', wake);
  }

  /** A tab brought back to the front should not show a second-old queue. */
  const wake = () => {
    if (!document.hidden) poll();
  };

  function open() {
    shape = '';
    summary.textContent = 'Loading queue…';
    mount.replaceChildren(el('h1', {}, 'Queue'), summary, free, hint, actions, list.node, torrents);
    start();
  }

  return { open, close: stop };
}
