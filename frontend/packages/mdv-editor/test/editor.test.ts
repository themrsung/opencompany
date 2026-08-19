import { describe, expect, it } from 'vitest';
import {
  BLOCK_TEMPLATES,
  canSubmit,
  describeBlockers,
  insertionFor,
  isMdvFence,
  mdvBlockTypeOf,
  submissionBlockers,
  templateFor,
  templatesByGroup,
} from '../src/index.js';
import type { MdvDiagnostic } from '../src/index.js';

const error = (code: string, message: string): MdvDiagnostic => ({
  code,
  severity: 'error',
  message,
  from: 0,
  to: 1,
});
const warning = (code: string, message: string): MdvDiagnostic => ({
  code,
  severity: 'warning',
  message,
  from: 0,
  to: 1,
});

describe('mdv fence recognition', () => {
  it('recognises an mdv block and its type', () => {
    expect(isMdvFence('```mdv bar')).toBe(true);
    expect(isMdvFence('  ```mdv')).toBe(true);
    expect(mdvBlockTypeOf('```mdv candlestick')).toBe('candlestick');
  });

  it('does not mistake an ordinary code fence for an mdv block', () => {
    // They look identical in source and behave nothing alike, which is exactly
    // why the palette, the linter bridge and the preview share one predicate.
    expect(isMdvFence('```')).toBe(false);
    expect(isMdvFence('```javascript')).toBe(false);
    expect(isMdvFence('```mdvish')).toBe(false);
    expect(mdvBlockTypeOf('```javascript')).toBeNull();
  });
});

describe('submission gating', () => {
  it('errors block submission and warnings do not', () => {
    // Blocking on warnings would train drafters to route around the editor.
    expect(canSubmit([warning('MDV5100', 'missing glyphs for U+D55C')])).toBe(true);
    expect(canSubmit([error('MDV2001', 'unknown block type')])).toBe(false);
  });

  it('names every blocking problem at once', () => {
    // Three mistakes should not mean three round trips.
    const diagnostics = [
      error('MDV2001', 'unknown block type "piechart"'),
      warning('MDV5100', 'missing glyphs'),
      error('MDV3002', 'column "매출" not found in dataset'),
    ];
    expect(submissionBlockers(diagnostics)).toHaveLength(2);

    const korean = describeBlockers(diagnostics, 'ko');
    expect(korean).toContain('상신할 수 없습니다');
    expect(korean).toContain('MDV2001');
    expect(korean).toContain('MDV3002');
    expect(korean).not.toContain('MDV5100');

    expect(describeBlockers(diagnostics, 'en')).toContain('cannot be submitted');
  });

  it('says nothing when there is nothing blocking', () => {
    expect(describeBlockers([warning('MDV5100', 'x')], 'ko')).toBe('');
  });
});

describe('block palette', () => {
  it('every template is a complete, valid, closed block', () => {
    // A palette that emits a half-written block is worse than no palette: the
    // drafter gets a diagnostic for something they did not type.
    for (const template of BLOCK_TEMPLATES) {
      expect(isMdvFence(template.snippet.split('\n')[0] ?? ''), template.type).toBe(true);
      expect(mdvBlockTypeOf(template.snippet.split('\n')[0] ?? '')).toBe(template.type);
      expect(template.snippet.endsWith('```'), template.type).toBe(true);
      // Opening and closing fence, and nothing stray in between.
      expect((template.snippet.match(/^```/gm) ?? []).length, template.type).toBe(2);
    }
  });

  it('ships the financial types the seeded board-pack template uses', () => {
    expect(templatesByGroup('financial').map((t) => t.type)).toContain('candlestick');
    expect(templateFor('ohlcv')).toBeDefined();
  });

  it('placeholder data is realistic rather than foo/bar', () => {
    // Placeholders get shipped into real documents by people in a hurry, and
    // `a,1 / b,2` teaches a drafter nothing about the shape they need.
    const bar = templateFor('bar');
    expect(bar?.snippet).toContain('회계팀');
    expect(bar?.snippet).toMatch(/\d{6,}/);
  });

  it('every template carries both Korean and English labels', () => {
    for (const template of BLOCK_TEMPLATES) {
      expect(template.labelKo.length, template.type).toBeGreaterThan(0);
      expect(template.labelEn.length, template.type).toBeGreaterThan(0);
    }
  });
});

describe('block insertion', () => {
  it('separates the block from surrounding prose', () => {
    const doc = '문단입니다.';
    const result = insertionFor(doc, doc.length, '```mdv bar\n---\na,1\n```');
    expect(result.insert.startsWith('\n\n')).toBe(true);
  });

  it('does not add blank lines that are already there', () => {
    // Always adding them produces a widening gap on every insert, which reads
    // as the editor drifting.
    const doc = '문단입니다.\n\n';
    const result = insertionFor(doc, doc.length, '```mdv bar\n---\na,1\n```');
    expect(result.insert.startsWith('\n')).toBe(false);
  });

  it('inserts cleanly into an empty document', () => {
    const result = insertionFor('', 0, '```mdv bar\n---\na,1\n```');
    expect(result.insert).toBe('```mdv bar\n---\na,1\n```');
    expect(result.from).toBe(0);
    expect(result.to).toBe(0);
  });

  it('places the caret inside the data section', () => {
    const snippet = '```mdv bar\ntitle: x\n---\na,1\n```';
    const result = insertionFor('', 0, snippet);
    expect(result.cursorAfter).toBeGreaterThan(snippet.indexOf('---'));
  });
});
