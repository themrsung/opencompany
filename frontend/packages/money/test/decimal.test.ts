import { describe, expect, it } from 'vitest';
import {
  DecimalParseError,
  addDecimals,
  compareDecimals,
  displayAmount,
  formatGrouped,
  hasHiddenPrecision,
  isValidAmountInput,
  parseAmountInput,
  parseDecimal,
  roundForDisplay,
  subtractDecimals,
  toPlainString,
  withExactScale,
} from '../src/index.js';

describe('parsing the wire form', () => {
  it('accepts an exact decimal string and preserves its scale', () => {
    expect(toPlainString(parseDecimal('1400000.25'))).toBe('1400000.25');
    expect(toPlainString(parseDecimal('-0.0000000439'))).toBe('-0.0000000439');
    expect(toPlainString(parseDecimal('0'))).toBe('0');
  });

  it('preserves trailing zeros, because scale is information', () => {
    expect(toPlainString(parseDecimal('1.50'))).toBe('1.50');
    expect(toPlainString(parseDecimal('1.5'))).toBe('1.5');
  });

  it.each(['1e3', '1E3', '.5', '1.', '', ' ', '1,000', 'abc', '--1', '1.2.3', '+1'])(
    'rejects %j, because it means a Number got involved somewhere upstream',
    (input) => {
      expect(() => parseDecimal(input)).toThrow(DecimalParseError);
    },
  );

  it('survives more significant digits than a double can hold', () => {
    const huge = '123456789012345678901234567890.123456789';
    expect(toPlainString(parseDecimal(huge))).toBe(huge);
  });
});

describe('parsing what a person typed', () => {
  it('accepts grouping separators and surrounding space', () => {
    expect(toPlainString(parseAmountInput('  1,234,567.89 '))).toBe('1234567.89');
    expect(toPlainString(parseAmountInput('+42'))).toBe('42');
  });

  it('still refuses anything ambiguous', () => {
    expect(isValidAmountInput('1e3')).toBe(false);
    expect(isValidAmountInput('.5')).toBe(false);
    expect(isValidAmountInput('1.')).toBe(false);
    expect(isValidAmountInput('1,234.5')).toBe(true);
  });
});

describe('comparison is by quantity, not representation', () => {
  it('treats 1.50 and 1.5 as equal', () => {
    expect(compareDecimals(parseDecimal('1.50'), parseDecimal('1.5'))).toBe(0);
  });

  it('orders across differing scales', () => {
    expect(compareDecimals(parseDecimal('0.1'), parseDecimal('0.09999'))).toBe(1);
    expect(compareDecimals(parseDecimal('-2'), parseDecimal('-1.999'))).toBe(-1);
  });
});

describe('arithmetic stays exact where a float would not', () => {
  it('adds a tenth to two tenths and gets three tenths', () => {
    expect(toPlainString(addDecimals(parseDecimal('0.1'), parseDecimal('0.2')))).toBe('0.3');
  });

  it('subtracts without drift', () => {
    const total = subtractDecimals(parseDecimal('1000000.00'), parseDecimal('999999.99'));
    expect(toPlainString(total)).toBe('0.01');
  });
});

describe('display rounding', () => {
  it('rounds half away from zero', () => {
    expect(toPlainString(roundForDisplay(parseDecimal('2.5'), 0))).toBe('3');
    expect(toPlainString(roundForDisplay(parseDecimal('-2.5'), 0))).toBe('-3');
    expect(toPlainString(roundForDisplay(parseDecimal('2.4'), 0))).toBe('2');
  });

  it('never lengthens a value that is already shorter than the display scale', () => {
    expect(toPlainString(roundForDisplay(parseDecimal('7'), 4))).toBe('7');
  });

  it('pads to the display scale only when asked, for column alignment', () => {
    expect(toPlainString(withExactScale(parseDecimal('7'), 2))).toBe('7.00');
  });
});

describe('hidden precision', () => {
  it('is flagged when rounding changes the quantity', () => {
    expect(hasHiddenPrecision(parseDecimal('1400000.25'), 0)).toBe(true);
  });

  it('is not flagged for trailing zeros, which hide nothing a reader cares about', () => {
    expect(hasHiddenPrecision(parseDecimal('1.500'), 2)).toBe(false);
  });

  it('is not flagged when the value fits', () => {
    expect(hasHiddenPrecision(parseDecimal('1400000'), 0)).toBe(false);
  });
});

describe('grouping', () => {
  it('groups the integer part and leaves the fraction alone', () => {
    expect(formatGrouped(parseDecimal('1234567.8901'))).toBe('1,234,567.8901');
    expect(formatGrouped(parseDecimal('-1000'))).toBe('-1,000');
    expect(formatGrouped(parseDecimal('999'))).toBe('999');
  });
});

describe('displayAmount carries the abbreviation and the truth together', () => {
  it('shows KRW at zero decimals and still exposes the exact value', () => {
    const shown = displayAmount(parseDecimal('1400000.25'), 0);
    expect(shown.rounded).toBe('1,400,000');
    expect(shown.exact).toBe('1,400,000.25');
    expect(shown.abbreviated).toBe(true);
  });

  it('shows USD at two decimals', () => {
    const shown = displayAmount(parseDecimal('1400000.25'), 2);
    expect(shown.rounded).toBe('1,400,000.25');
    expect(shown.exact).toBe('1,400,000.25');
    expect(shown.abbreviated).toBe(false);
  });

  it('pads to the display scale so a column lines up', () => {
    expect(displayAmount(parseDecimal('7'), 2).rounded).toBe('7.00');
  });

  it('never loses digits beyond double precision', () => {
    const shown = displayAmount(parseDecimal('123456789012345678.999'), 2);
    expect(shown.exact).toBe('123,456,789,012,345,678.999');
    expect(shown.rounded).toBe('123,456,789,012,345,679.00');
  });
});
