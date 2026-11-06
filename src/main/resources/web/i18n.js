import en from './locales/en.js';
import pl from './locales/pl.js';

/** Each language is named in itself, so anyone can find their own. */
export const LANGUAGES = { en: 'English', pl: 'Polski' };

const MESSAGES = { en, pl };
const KEY = 'minerva.language';

/** A per-browser choice, like the scrolling one; empty means follow the browser. */
export const language = {
  get() {
    try {
      return localStorage.getItem(KEY) ?? '';
    } catch {
      return '';
    }
  },
  set(code) {
    try {
      if (code) localStorage.setItem(KEY, code);
      else localStorage.removeItem(KEY);
    } catch {
      // private windows may refuse storage; the browser's language stays in use
    }
  },
};

export const browserLocale =
  (navigator.languages ?? [navigator.language])
    .map((tag) => tag.split('-')[0].toLowerCase())
    .find((code) => Object.hasOwn(MESSAGES, code)) ?? 'en';

export const locale = Object.hasOwn(MESSAGES, language.get()) ? language.get() : browserLocale;
document.documentElement.lang = locale;

const plurals = new Intl.PluralRules(locale);
const numbers = new Intl.NumberFormat(locale);
const decimals = new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 });

export const number = (value) => numbers.format(value);

export const decimal = (value) => decimals.format(value);

/** A message with its {placeholders} filled; one given a count picks the plural form that count needs. */
export function t(key, params = {}) {
  const message = MESSAGES[locale][key] ?? en[key];
  const text = typeof message === 'object' ? (message[plurals.select(params.count)] ?? message.other) : message;
  return text.replace(/\{(\w+)\}/g, (_, name) => {
    const value = params[name];
    return typeof value === 'number' ? number(value) : (value ?? '');
  });
}
