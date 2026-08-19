import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import {
  absoluteDateTime,
  compareBusinessInstants,
  formatBusinessInstant,
  isOutsideCalendarDay,
  parseBusinessInstant,
} from '../src/index.js';

/**
 * The shared conformance corpus, loaded from the same file the Java suite reads.
 * A behaviour change on either side fails the other side's build.
 */
const here = dirname(fileURLToPath(import.meta.url));
const vectorsPath = resolve(here, '../../../../spec/business-time-vectors.json');

interface ValidCase {
  wire: string;
  date: string;
  offsetSeconds: number;
  outsideCalendarDay: boolean;
  absolute: string;
}
interface InvalidCase {
  wire: string;
  reasonContains: string;
}
interface CanonCase {
  input: string;
  canonical: string;
  why: string;
}
interface Vectors {
  valid: ValidCase[];
  invalid: InvalidCase[];
  canonicalisation: CanonCase[];
  ordering: {
    ascending: string[];
    disagreements: Array<{ left: string; right: string; business: string; absolute: string }>;
  };
}

const vectors = JSON.parse(readFileSync(vectorsPath, 'utf8')) as Vectors;

describe('shared vectors: valid instants', () => {
  it.each(vectors.valid)('parses $wire', (testCase) => {
    const instant = parseBusinessInstant(testCase.wire);
    expect(instant.businessDate).toBe(testCase.date);
    expect(instant.offsetSeconds).toBe(testCase.offsetSeconds);
    expect(isOutsideCalendarDay(instant)).toBe(testCase.outsideCalendarDay);
    expect(absoluteDateTime(instant)).toBe(testCase.absolute);
    expect(formatBusinessInstant(instant)).toBe(testCase.wire);
  });
});

describe('shared vectors: rejections', () => {
  it.each(vectors.invalid)('rejects $wire', (testCase) => {
    expect(() => parseBusinessInstant(testCase.wire)).toThrowError(
      new RegExp(testCase.reasonContains.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
    );
  });
});

describe('shared vectors: canonicalisation', () => {
  it.each(vectors.canonicalisation)('$input -> $canonical ($why)', (testCase) => {
    expect(formatBusinessInstant(parseBusinessInstant(testCase.input))).toBe(testCase.canonical);
  });
});

describe('shared vectors: ordering', () => {
  it('sorts date first, then offset', () => {
    const shuffled = [...vectors.ordering.ascending].reverse().map(parseBusinessInstant);
    const sorted = [...shuffled].sort(compareBusinessInstants).map(formatBusinessInstant);
    expect(sorted).toEqual(vectors.ordering.ascending);
  });

  it.each(vectors.ordering.disagreements)(
    'business order and absolute order disagree on $left vs $right',
    (testCase) => {
      const left = parseBusinessInstant(testCase.left);
      const right = parseBusinessInstant(testCase.right);

      expect(compareBusinessInstants(left, right)).toBeLessThan(0);
      expect(testCase.business).toBe('left-first');

      // ...and the wall clock says the opposite, which is the whole point.
      expect(absoluteDateTime(left) > absoluteDateTime(right)).toBe(true);
      expect(testCase.absolute).toBe('right-first');
    },
  );

  it('lexical wire-string ordering is a third, always-wrong order', () => {
    // Find a real counterexample rather than asserting the rule.
    let found: [string, string] | null = null;
    for (let a = -86_400; a <= 172_800 && found === null; a += 137) {
      for (let b = a + 1; b <= 172_800; b += 4_099) {
        const left = formatBusinessInstant({ businessDate: '2026-08-30', offsetSeconds: a });
        const right = formatBusinessInstant({ businessDate: '2026-08-30', offsetSeconds: b });
        if (Math.sign(a - b) !== Math.sign(left < right ? -1 : left > right ? 1 : 0)) {
          found = [left, right];
          break;
        }
      }
    }
    expect(found).not.toBeNull();
  });
});
