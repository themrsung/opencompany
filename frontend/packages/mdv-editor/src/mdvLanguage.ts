/**
 * The mdv language, as an extension of CodeMirror's markdown mode.
 *
 * mdv is a CommonMark superset, so this extends the existing grammar rather
 * than replacing it. Everything CommonMark already handles — emphasis, lists,
 * tables, code fences, link references — keeps working, and the additions below
 * are only the parts mdv adds.
 */

import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { Tag, tags } from '@lezer/highlight';
import type { Extension } from '@codemirror/state';

/**
 * Highlight tags for mdv's own constructs.
 *
 * Defined as distinct tags rather than reusing markdown's so a theme can style
 * an mdv chart block differently from a plain code fence — which matters,
 * because they look identical in source and behave nothing alike.
 */
export const mdvTags = {
  /** The ```mdv fence marker itself. */
  blockFence: Tag.define(),
  /** A key inside a chart or dataset block's header. */
  specKey: Tag.define(),
  /** A `:::mdv-page{break=avoid}` style directive. */
  directive: Tag.define(),
  /** A `---` front-matter delimiter and its contents. */
  frontMatter: Tag.define(),
} as const;

/** The mdv block types the editor knows how to insert and highlight. */
export const MDV_BLOCK_TYPES = [
  'bar',
  'line',
  'area',
  'pie',
  'donut',
  'scatter',
  'bubble',
  'histogram',
  'box',
  'heatmap',
  'metric',
  // The financial types. Used in the seeded 이사회안건 and 회의록 templates so
  // the format demonstrates itself on first run rather than being described.
  'ohlc',
  'ohlcv',
  'candlestick',
] as const;

export type MdvBlockType = (typeof MDV_BLOCK_TYPES)[number];

/**
 * Recognises an mdv block opening fence.
 *
 * Kept as a plain predicate rather than folded into the grammar so the block
 * palette, the linter bridge and the preview can all agree on what counts as an
 * mdv block without three subtly different regexes.
 */
export function isMdvFence(line: string): boolean {
  return /^\s*```mdv(\s|$)/.test(line);
}

/** The block type named on a fence line, if any. */
export function mdvBlockTypeOf(line: string): string | null {
  const match = /^\s*```mdv\s+([a-z][a-z0-9-]*)/i.exec(line);
  return match?.[1]?.toLowerCase() ?? null;
}

/**
 * A syntax highlight style that keeps mdv blocks visually distinct.
 *
 * Colours are CSS custom properties rather than literals so the editor follows
 * the app's own light and dark themes; a hardcoded palette here would be the
 * one surface that ignores the user's theme choice.
 */
export const mdvHighlightStyle = HighlightStyle.define([
  { tag: tags.heading1, fontWeight: '700', color: 'var(--ci-syntax-heading)' },
  { tag: tags.heading2, fontWeight: '700', color: 'var(--ci-syntax-heading)' },
  { tag: tags.heading3, fontWeight: '600', color: 'var(--ci-syntax-heading)' },
  { tag: tags.strong, fontWeight: '700' },
  { tag: tags.emphasis, fontStyle: 'italic' },
  { tag: tags.strikethrough, textDecoration: 'line-through' },
  { tag: tags.link, color: 'var(--ci-syntax-link)', textDecoration: 'underline' },
  { tag: tags.monospace, fontFamily: 'var(--ci-font-mono)' },
  { tag: tags.meta, color: 'var(--ci-syntax-meta)' },
  { tag: mdvTags.blockFence, color: 'var(--ci-syntax-mdv)', fontWeight: '600' },
  { tag: mdvTags.specKey, color: 'var(--ci-syntax-key)' },
  { tag: mdvTags.directive, color: 'var(--ci-syntax-directive)', fontStyle: 'italic' },
  { tag: mdvTags.frontMatter, color: 'var(--ci-syntax-meta)' },
]);

/**
 * The complete language extension.
 *
 * `codeLanguages` is deliberately not wired to a loader that fetches grammars on
 * demand: an on-prem box may have no outbound internet, and an editor that
 * silently loses syntax highlighting when offline is a support ticket nobody can
 * reproduce.
 */
export function mdvLanguage(): Extension {
  return [
    markdown({
      base: markdownLanguage,
      addKeymap: true,
    }),
    syntaxHighlighting(mdvHighlightStyle),
  ];
}
