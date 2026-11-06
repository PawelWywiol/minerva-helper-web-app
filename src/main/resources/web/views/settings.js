import * as api from '../api.js';
import { button, el, icon, infiniteScroll, spinner } from '../dom.js';
import { browserLocale, language, LANGUAGES, t } from '../i18n.js';

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
  const vpnButton = button(t('settings.vpnCheck'), () => checkVpn(), 'ghost');

  async function checkVpn() {
    vpnButton.disabled = true;
    vpnResult.replaceChildren(el('p', { className: 'muted' }, t('settings.vpnAsking')));
    try {
      const v = await api.vpn();
      const place = [v.city, v.region, v.country].filter(Boolean).filter((x, i, all) => all.indexOf(x) === i);
      const fact = (key, value) => (value ? [el('dt', {}, key), el('dd', {}, value)] : []);
      const up = v.reachable && v.status === 'running';
      vpnResult.replaceChildren(
        el('p', { className: up ? 'ok' : 'warn' }, [
          up ? icon('check') : null,
          up
            ? t('settings.vpnUp')
            : v.reachable
              ? t('settings.vpnDown', { status: v.status || t('settings.vpnNotRunning') })
              : t('settings.vpnMissing'),
        ]),
        el('dl', { className: 'facts' }, [
          ...fact(t('settings.publicAddress'), v.public_ip),
          ...fact(t('settings.location'), place.join(', ')),
          ...fact(t('settings.provider'), v.organization),
        ]),
      );
    } catch (error) {
      vpnResult.replaceChildren(el('p', { className: 'error' }, t('settings.vpnFailed', { message: error.message })));
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
          t(choice ? 'settings.scrollOn' : 'settings.scrollOff'),
        );
        node.addEventListener('click', () => {
          infiniteScroll.set(choice);
          node.parentElement.replaceWith(scrolling());
        });
        return node;
      }),
    );
  }

  // the whole page is built in one language, so a new one takes a reload
  function languages() {
    const chosen = language.get();
    return el(
      'div',
      { className: 'actions' },
      ['', ...Object.keys(LANGUAGES)].map((code) =>
        button(
          code ? LANGUAGES[code] : t('settings.languageBrowser', { language: LANGUAGES[browserLocale] }),
          () => {
            language.set(code);
            location.reload();
          },
          code === chosen ? '' : 'ghost',
        ),
      ),
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
          t(on ? 'settings.unpackOn' : 'settings.unpackOff'),
        );
        button.addEventListener('click', () => apply({ unpackArchives: on }));
        return button;
      }),
    );
  }

  function editor(platform) {
    const input = el('input', { type: 'text', value: platform.folder, id: 'folder' });
    const move = el('input', { type: 'checkbox', id: 'move', checked: true });
    const save = el('button', { type: 'button' }, t('action.save'));
    const cancel = el('button', { type: 'button', className: 'ghost' }, t('action.cancel'));
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
        el('label', { htmlFor: 'folder' }, t('settings.folderFor', { platform: platform.platformKey })),
        input,
        platform.downloaded > 0
          ? el('label', { htmlFor: 'move', className: 'checkline' }, [
              move,
              ` ${t('settings.moveFiles', { count: platform.downloaded })}`,
            ])
          : null,
        el('div', { className: 'actions' }, [save, cancel]),
      ].filter(Boolean),
    );
  }

  function paint(data) {
    const rows = data.platforms.map((platform) => {
      if (editing === platform.platformKey) return editor(platform);
      const change = el('button', { type: 'button', className: 'ghost small' }, t('settings.change'));
      change.addEventListener('click', () => {
        editing = platform.platformKey;
        paint(data);
      });
      return el('div', { className: 'row static' }, [
        el('span', { className: 'name' }, platform.platformKey),
        el(
          'span',
          { className: 'meta' },
          [
            platform.folder,
            t('roms', { count: platform.queued + platform.downloaded }),
            platform.mapped ? null : t('settings.noStandardFolder'),
          ]
            .filter(Boolean)
            .join(' · '),
        ),
        el('span', { className: 'buttons' }, change),
      ]);
    });

    mount.replaceChildren(
      el('h1', {}, t('nav.settings')),
      el('p', { className: 'muted' }, t('settings.intro')),
      el('h2', {}, 'VPN'),
      el('p', { className: 'muted' }, t('settings.vpnIntro')),
      el('div', { className: 'actions' }, vpnButton),
      vpnResult,
      el('h2', {}, t('settings.concurrency')),
      el('p', { className: 'muted' }, t('settings.concurrencyIntro')),
      concurrency(data),
      el('h2', {}, t('settings.unpack')),
      el('p', { className: 'muted' }, t('settings.unpackIntro')),
      unpacking(data),
      el('h2', {}, t('settings.lists')),
      el('p', { className: 'muted' }, t('settings.listsIntro')),
      scrolling(),
      el('h2', {}, t('settings.language')),
      el('p', { className: 'muted' }, t('settings.languageIntro')),
      languages(),
      el('h2', {}, t('settings.folders')),
      rows.length === 0
        ? el('p', { className: 'muted' }, t('settings.foldersEmpty'))
        : el('div', { className: 'list' }, rows),
    );
  }

  async function show() {
    mount.replaceChildren(spinner(t('settings.loading')));
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
