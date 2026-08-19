/**
 * The block insertion palette.
 *
 * mdv's value in this system is that charts and financial plots are plain text
 * a person can read and diff. That is only true if drafters can insert them
 * without memorising the syntax, so the palette emits complete, valid, minimal
 * blocks with real placeholder data rather than empty scaffolds.
 */

import type { MdvBlockType } from './mdvLanguage.js';

export interface BlockTemplate {
  readonly type: MdvBlockType;
  readonly labelKo: string;
  readonly labelEn: string;
  /** Which group it appears under in the palette. */
  readonly group: 'comparison' | 'trend' | 'distribution' | 'financial' | 'summary';
  /** A complete, valid block. Inserted as-is. */
  readonly snippet: string;
}

/**
 * Placeholder data is deliberately realistic, not `foo`/`bar`.
 *
 * A drafter who inserts a chart and sees plausible numbers immediately
 * understands the shape they need to supply. `a,1 / b,2` teaches nothing and
 * gets shipped into real documents by people in a hurry.
 */
export const BLOCK_TEMPLATES: readonly BlockTemplate[] = [
  {
    type: 'bar',
    labelKo: '막대 차트',
    labelEn: 'Bar chart',
    group: 'comparison',
    snippet: [
      '```mdv bar',
      'title: 부서별 예산 집행',
      'x: 부서',
      'y: 금액',
      '---',
      '부서,금액',
      '회계팀,12000000',
      '영업본부,34500000',
      '개발팀,28700000',
      '```',
    ].join('\n'),
  },
  {
    type: 'line',
    labelKo: '선 차트',
    labelEn: 'Line chart',
    group: 'trend',
    snippet: [
      '```mdv line',
      'title: 월별 매출',
      'x: 월',
      'y: 매출',
      '---',
      '월,매출',
      '2026-01,45000000',
      '2026-02,51200000',
      '2026-03,48900000',
      '```',
    ].join('\n'),
  },
  {
    type: 'pie',
    labelKo: '원형 차트',
    labelEn: 'Pie chart',
    group: 'comparison',
    snippet: [
      '```mdv pie',
      'title: 비용 구성',
      '---',
      '항목,비율',
      '인건비,52',
      '임차료,18',
      '기타,30',
      '```',
    ].join('\n'),
  },
  {
    type: 'metric',
    labelKo: '지표 타일',
    labelEn: 'Metric tile',
    group: 'summary',
    snippet: [
      '```mdv metric',
      'label: 당기순이익',
      'value: 128500000',
      'unit: KRW',
      'delta: +12.4%',
      '```',
    ].join('\n'),
  },
  {
    type: 'heatmap',
    labelKo: '히트맵',
    labelEn: 'Heatmap',
    group: 'distribution',
    snippet: [
      '```mdv heatmap',
      'title: 요일·시간대별 접속',
      '---',
      '요일,시간,값',
      '월,09,12',
      '월,14,31',
      '화,09,18',
      '```',
    ].join('\n'),
  },
  {
    // Used by the seeded 이사회안건 template so the format demonstrates itself
    // on first run rather than being described in documentation.
    type: 'candlestick',
    labelKo: '캔들 차트',
    labelEn: 'Candlestick',
    group: 'financial',
    snippet: [
      '```mdv candlestick',
      'title: 주가 추이',
      '---',
      'date,open,high,low,close,volume',
      '2026-08-26,74200,75100,73800,74900,1240000',
      '2026-08-27,74900,76300,74600,76100,1580000',
      '2026-08-28,76100,76400,75200,75500,1120000',
      '```',
    ].join('\n'),
  },
  {
    type: 'ohlcv',
    labelKo: 'OHLCV',
    labelEn: 'OHLCV',
    group: 'financial',
    snippet: [
      '```mdv ohlcv',
      'title: 거래 요약',
      'overlays: [sma20]',
      '---',
      'date,open,high,low,close,volume',
      '2026-08-26,74200,75100,73800,74900,1240000',
      '2026-08-27,74900,76300,74600,76100,1580000',
      '```',
    ].join('\n'),
  },
];

export function templatesByGroup(group: BlockTemplate['group']): readonly BlockTemplate[] {
  return BLOCK_TEMPLATES.filter((t) => t.group === group);
}

export function templateFor(type: string): BlockTemplate | undefined {
  return BLOCK_TEMPLATES.find((t) => t.type === type);
}

/**
 * Where to insert a block so it lands as its own top-level block.
 *
 * Blank lines are added only where they are missing. Always adding them
 * produces a widening gap every time a drafter inserts a chart, which looks
 * like the editor is drifting.
 */
export function insertionFor(
  doc: string,
  cursor: number,
  snippet: string,
): { from: number; to: number; insert: string; cursorAfter: number } {
  const before = doc.slice(0, cursor);
  const after = doc.slice(cursor);

  const needsLeadingBlank = before.length > 0 && !before.endsWith('\n\n');
  const needsTrailingBlank = after.length > 0 && !after.startsWith('\n\n');

  const lead = needsLeadingBlank ? (before.endsWith('\n') ? '\n' : '\n\n') : '';
  const trail = needsTrailingBlank ? (after.startsWith('\n') ? '\n' : '\n\n') : '';
  const insert = `${lead}${snippet}${trail}`;

  return {
    from: cursor,
    to: cursor,
    insert,
    // Land the caret just inside the block's data section, which is where the
    // drafter's next keystroke wants to go.
    cursorAfter: cursor + lead.length + snippet.indexOf('---') + 4,
  };
}
