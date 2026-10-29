import * as api from '../api.js';
import { button, el, icon, infiniteScroll, spinner } from '../dom.js';

/**
 * Download settings and the folder each platform lands in, inside the library mounted into the container.
 */
export function settings(mount, { onError }) {
  let editing = null;

  const apply = async (change) => {
    try {
      paint(await api.updateSettings(change));
    } catch (error) {
      onError(error, show);
    }
  };

  function concurrency(data) {
    const options = [];
    for (let n = data.minConcurrentDownloads; n <= data.maxConcurrentDownloads; n++) options.push(n);
    return el(
      'div',
      { className: 'actions' },
      options.map((n) => {
        const button = el(
          'button',
          {
            type: 'button',
            className: n === data.concurrentDownloads ? '' : 'ghost',
          },
          String(n),
        );
        button.addEventListener('click', () => apply({ concurrentDownloads: n }));
        return button;
      }),
    );
  }

  // kept outside paint, so a settings change does not wipe the last check
  const vpnResult = el('div', { className: 'vpn', role: 'status' });
  const vpnButton = button('Check VPN', () => checkVpn(), 'ghost');

  async function checkVpn() {
    vpnButton.disabled = true;
    vpnResult.replaceChildren(el('p', { className: 'muted' }, 'Asking the VPN container…'));
    try {
      const v = await api.vpn();
      const place = [v.city, v.region, v.country].filter(Boolean).filter((x, i, all) => all.indexOf(x) === i);
      const fact = (key, value) => (value ? [el('dt', {}, key), el('dd', {}, value)] : []);
      const up = v.reachable && v.status === 'running';
      vpnResult.replaceChildren(
        el('p', { className: up ? 'ok' : 'warn' }, [
          up ? icon('check') : null,
          up
            ? 'Downloads go through the VPN.'
            : v.reachable
              ? `The VPN is ${v.status || 'not running'}. Downloads are blocked until it reconnects.`
              : 'No VPN found. This container does not run behind gluetun, so downloads would use your own connection.',
        ]),
        el('dl', { className: 'facts' }, [
          ...fact('Public address', v.public_ip),
          ...fact('Location', place.join(', ')),
          ...fact('Provider', v.organization),
        ]),
      );
    } catch (error) {
      vpnResult.replaceChildren(el('p', { className: 'error' }, `The check failed: ${error.message}`));
    } finally {
      vpnButton.disabled = false;
    }
  }

  function scrolling() {
    const on = infiniteScroll.get();
    return el(
      'div',
      { className: 'actions' },
      [true, false].map((choice) => {
        const node = el(
          'button',
          { type: 'button', className: choice === on ? '' : 'ghost' },
          choice ? 'Load as I scroll' : 'Show more button',
        );
        node.addEventListener('click', () => {
          infiniteScroll.set(choice);
          node.parentElement.replaceWith(scrolling());
        });
        return node;
      }),
    );
  }

  function unpacking(data) {
    return el(
      'div',
      { className: 'actions' },
      [true, false].map((on) => {
        const button = el(
          'button',
          {
            type: 'button',
            className: on === data.unpackArchives ? '' : 'ghost',
          },
          on ? 'Unpack' : 'Keep as downloaded',
        );
        button.addEventListener('click', () => apply({ unpackArchives: on }));
        return button;
      }),
    );
  }

  function editor(platform) {
    const input = el('input', { type: 'text', value: platform.folder, id: 'folder' });
    const move = el('input', { type: 'checkbox', id: 'move', checked: true });
    const save = el('button', { type: 'button' }, 'Save');
    const cancel = el('button', { type: 'button', className: 'ghost' }, 'Cancel');
    save.addEventListener('click', () => {
      const folder = input.value.trim();
      if (!folder) return;
      editing = null;
      apply({
        collection: platform.collection,
        platformKey: platform.platformKey,
        folder,
        moveFiles: move.checked,
      });
    });
    cancel.addEventListener('click', () => {
      editing = null;
      show();
    });

    return el(
      'div',
      { className: 'editor' },
      [
        el('label', { htmlFor: 'folder' }, `Folder for ${platform.platformKey}`),
        input,
        platform.downloaded > 0
          ? el('label', { htmlFor: 'move', className: 'checkline' }, [
              move,
              ` Move the ${platform.downloaded} file(s) already downloaded into the new folder`,
            ])
          : null,
        el('div', { className: 'actions' }, [save, cancel]),
      ].filter(Boolean),
    );
  }

  function paint(data) {
    const rows = data.platforms.map((platform) => {
      if (editing === platform.platformKey) return editor(platform);
      const change = el('button', { type: 'button', className: 'ghost small' }, 'Change');
      change.addEventListener('click', () => {
        editing = platform.platformKey;
        paint(data);
      });
      return el('div', { className: 'row static' }, [
        el('span', { className: 'name' }, platform.platformKey),
        el(
          'span',
          { className: 'meta' },
          `${platform.folder} · ${platform.queued + platform.downloaded} ROMs` +
            (platform.mapped ? '' : ' · no standard folder'),
        ),
        el('span', { className: 'buttons' }, change),
      ]);
    });

    mount.replaceChildren(
      el('h1', {}, 'Settings'),
      el(
        'p',
        { className: 'muted' },
        'Downloads land in the library, one folder per platform (ES-DE names by default).',
      ),
      el('h2', {}, 'VPN'),
      el('p', { className: 'muted' }, 'See the address and place the archive sees when this app downloads.'),
      el('div', { className: 'actions' }, vpnButton),
      vpnResult,
      el('h2', {}, 'Downloads at once'),
      el('p', { className: 'muted' }, 'More is not always faster — each torrent has to find its own sharers.'),
      concurrency(data),
      el('h2', {}, 'Unpack when needed'),
      el(
        'p',
        { className: 'muted' },
        'Disc games arrive zipped but no emulator can open them that way. Left on, they are ' +
          'unpacked as they land and the zip is thrown away.',
      ),
      unpacking(data),
      el('h2', {}, 'Long lists'),
      el(
        'p',
        { className: 'muted' },
        'Load the next games automatically when you reach the bottom of a list. Remembered in this browser only.',
      ),
      scrolling(),
      el('h2', {}, 'Where each platform lands'),
      rows.length === 0
        ? el('p', { className: 'muted' }, 'Queue something to see where it will go.')
        : el('div', { className: 'list' }, rows),
    );
  }

  async function show() {
    mount.replaceChildren(spinner('Loading settings…'));
    try {
      paint(await api.settings());
    } catch (error) {
      onError(error, show);
    }
  }

  return {
    open() {
      editing = null;
      show();
    },
  };
}
