/** The whole view layer: build nodes, format numbers. No framework, because six screens do not need one. */

export function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === 'dataset') Object.assign(node.dataset, value);
    else if (key === 'role' || key.startsWith('aria')) node.setAttribute(attribute(key), value);
    else node[key] = value;
  }
  for (const child of [].concat(children)) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(child));
  }
  return node;
}

const attribute = (key) => (key === 'role' ? key : `aria-${key.slice(4).toLowerCase()}`);

const circle = (x, y, r) => `M${x - r} ${y}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`;

const ICONS = {
  home: 'M4 10.5 12 4l8 6.5V20h-5v-5.5H9V20H4z',
  search: `${circle(11, 11, 6.5)}M20 20l-4.2-4.2`,
  browse: 'M4 4h6.5v6.5H4zM13.5 4H20v6.5h-6.5zM4 13.5h6.5V20H4zM13.5 13.5H20V20h-6.5z',
  heart: 'M12 19.5s-7.5-4.6-7.5-10.1A4.2 4.2 0 0 1 12 7a4.2 4.2 0 0 1 7.5 2.4c0 5.5-7.5 10.1-7.5 10.1z',
  download: 'M12 4v11M7.5 10.5 12 15l4.5-4.5M5 19.5h14',
  check: 'm5 12.5 4.5 4.5L19 7.5',
  clock: `${circle(12, 12, 8)}M12 8v4.2l2.8 1.8`,
  queue: 'M4 6.5h16M4 12h16M4 17.5h9',
  settings: `M4 7h9M17 7h3M4 17h3M11 17h9${circle(15, 7, 2)}${circle(9, 17, 2)}`,
  close: 'M6.5 6.5l11 11M17.5 6.5l-11 11',
  back: 'M14.5 6 8.5 12l6 6',
};

export function icon(name) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.classList.add('icon');
  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', ICONS[name]);
  svg.append(path);
  return svg;
}

/** "Super Mario Bros. (World) [!].zip" reads as the title with its release tags set apart. */
export function splitName(name) {
  const base = name.replace(/\.[a-z0-9]{1,4}$/i, '');
  const at = base.search(/\s[([]/);
  return at > 0 ? [base.slice(0, at), base.slice(at + 1)] : [base, ''];
}

export const count = (value) => new Intl.NumberFormat().format(value);

/** Matches the app's own formatSize: decimal units, one decimal place. */
export function size(bytes) {
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(1)} MB`;
  if (bytes >= 1e3) return `${Math.floor(bytes / 1e3)} kB`;
  return `${bytes} B`;
}

export function button(label, onClick, className = '') {
  const node = el('button', { type: 'button', className }, label);
  node.addEventListener('click', onClick);
  return node;
}

export const spinner = (label = 'Loading…') => el('p', { className: 'muted' }, label);

/** The ES-DE folder a game lands in, coloured by maker so a mixed list reads at a glance. */
export const badge = (folder, family = 'other') =>
  el('span', { className: `badge f-${family}`, title: `Lands in roms/${folder}` }, folder);

/** A destructive button arms itself first: nothing here is undoable by pressing again. */
export function confirmable(label, armedLabel, onConfirm) {
  const node = el('button', { type: 'button', className: 'ghost small' }, label);
  node.addEventListener('click', () => {
    if (node.dataset.armed) {
      onConfirm();
      return;
    }
    node.dataset.armed = '1';
    node.textContent = armedLabel;
    setTimeout(() => {
      delete node.dataset.armed;
      node.textContent = label;
    }, 4000);
  });
  return node;
}

const SCROLL_KEY = 'minerva.infiniteScroll';

/** A per-browser choice, so it lives in this browser rather than in the server's settings. */
export const infiniteScroll = {
  get() {
    try {
      return localStorage.getItem(SCROLL_KEY) === '1';
    } catch {
      return false;
    }
  },
  set(on) {
    try {
      localStorage.setItem(SCROLL_KEY, on ? '1' : '0');
    } catch {
      // private windows may refuse storage; the button keeps working
    }
  },
};

/**
 * Presses a "Show more" button whenever it scrolls near the screen, if the user chose so. Watching
 * again after each press catches a short page whose button is still in view once it has loaded.
 */
export function loadOnScroll(node) {
  const observer = new IntersectionObserver(
    (entries) => {
      if (!entries.some((entry) => entry.isIntersecting) || !infiniteScroll.get()) return;
      if (!node.hidden && !node.disabled) node.click();
      setTimeout(() => {
        observer.unobserve(node);
        observer.observe(node);
      }, 400);
    },
    { rootMargin: '600px 0px' },
  );
  observer.observe(node);
}

let toastTimer = null;

export function toast(message) {
  let node = document.getElementById('toast');
  if (!node) {
    node = el('div', { id: 'toast', className: 'toast', role: 'status' });
    document.body.append(node);
  }
  node.textContent = message;
  node.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove('on'), 2600);
}
