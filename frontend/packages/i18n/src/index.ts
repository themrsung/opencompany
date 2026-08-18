import i18next, { type i18n as I18nInstance } from 'i18next';
import { initReactI18next } from 'react-i18next';
import { en } from './en.js';
import { ko } from './ko.js';

export { ko } from './ko.js';
export { en } from './en.js';
export type { Resources } from './ko.js';
export type { Translated } from './en.js';

/** The locales this product ships. Both are first class; neither is a retrofit. */
export const SUPPORTED_LOCALES = ['ko', 'en'] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];

/** Korean is the default on a fresh install. */
export const DEFAULT_LOCALE: Locale = 'ko';

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

/**
 * Builds the i18next instance.
 *
 * Note the fallback is Korean, not English. The usual `fallbackLng: 'en'` would
 * mean a missing Korean string silently shows English to a Korean user, which
 * is exactly the "English-first retrofit" the brief rules out — and it hides the
 * gap instead of surfacing it. Falling back to the source language means a
 * missing *English* string shows Korean, which is visible and gets fixed.
 */
export function createI18n(locale: Locale = DEFAULT_LOCALE): I18nInstance {
  const instance = i18next.createInstance();
  void instance.use(initReactI18next).init({
    lng: locale,
    fallbackLng: DEFAULT_LOCALE,
    supportedLngs: SUPPORTED_LOCALES as unknown as string[],
    defaultNS: 'translation',
    resources: {
      ko: { translation: ko },
      en: { translation: en },
    },
    interpolation: {
      // React escapes for us. Double-escaping turns a 사원 name with an
      // apostrophe into &#39; on screen.
      escapeValue: false,
    },
    returnNull: false,
  });
  return instance;
}
