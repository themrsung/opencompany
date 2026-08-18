import { describe, expect, it } from 'vitest';
import { DEFAULT_LOCALE, createI18n, en, ko, screensEn, screensKo } from '../src/index.js';

type Leaves = Record<string, string>;

function flatten(source: unknown, prefix = ''): Leaves {
  const out: Leaves = {};
  for (const [key, value] of Object.entries(source as Record<string, unknown>)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') {
      out[path] = value;
    } else {
      Object.assign(out, flatten(value, path));
    }
  }
  return out;
}

// The screen modules are part of the same namespace at runtime, so they are
// part of the same parity check here. Splitting the files must not split the
// guarantee.
const koLeaves = flatten({ ...ko, ...screensKo });
const enLeaves = flatten({ ...en, ...screensEn });

describe('the two catalogues describe the same product', () => {
  it('has no key in one language and not the other', () => {
    expect(Object.keys(enLeaves).sort()).toEqual(Object.keys(koLeaves).sort());
  });

  it('has no empty string in either language', () => {
    const empty = Object.entries({ ...koLeaves, ...enLeaves }).filter(([, v]) => v.trim() === '');
    expect(empty).toEqual([]);
  });

  it('uses the same interpolation placeholders in both languages', () => {
    const placeholders = (text: string): string[] =>
      [...text.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1] as string).sort();

    const mismatched = Object.keys(koLeaves).filter((key) => {
      const left = placeholders(koLeaves[key] as string);
      const right = placeholders(enLeaves[key] as string);
      return left.join('|') !== right.join('|');
    });
    expect(mismatched).toEqual([]);
  });
});

describe('the screen modules merge cleanly over the shared catalogue', () => {
  it('has no screen key that silently replaces a shared one', () => {
    // The merge is one level deep, so a screen registering under a name the
    // shared catalogue already owns replaces it wholesale rather than
    // extending it — and every string under the shared key disappears with no
    // error anywhere. One agent building the permission explainer hit exactly
    // this and worked around it by registering as `explainer`; the next person
    // should be told, not left to notice.
    const shared = Object.keys(ko);
    const screens = Object.keys(screensKo);
    expect(screens.filter((key) => shared.includes(key))).toEqual([]);
  });
});

describe('Korean register', () => {
  // 해요체 endings are the usual way a machine translation gives itself away
  // here. 대표이사s read these screens; the register has to hold.
  const politeInformal = /(해요|이에요|예요|어요\b|아요\b)/;

  it('never drops into 해요체', () => {
    const offenders = Object.entries(koLeaves).filter(([, v]) => politeInformal.test(v));
    expect(offenders).toEqual([]);
  });
});

describe('the instance', () => {
  it('defaults to Korean on a fresh install', () => {
    const i18n = createI18n();
    expect(i18n.language).toBe('ko');
    expect(i18n.t('nav.inbox')).toBe('결재함');
  });

  it('falls back to Korean rather than English, so a missing translation is visible', () => {
    const i18n = createI18n('en');
    // i18next normalises fallbackLng to an array.
    expect(i18n.options.fallbackLng).toEqual([DEFAULT_LOCALE]);
    expect(i18n.t('nav.inbox')).toBe('Approvals');
  });

  it('interpolates without escaping, because React escapes', () => {
    const i18n = createI18n('en');
    expect(i18n.t('app.signedInAs', { name: "O'Brien" })).toBe("Signed in as O'Brien");
  });
});
