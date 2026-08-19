import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Amount, FullDecimalProvider } from '../src/index.js';

const labels = {
  roundedNotice: (exact: string) => `rounded; stored value is ${exact}`,
};

describe('<Amount>', () => {
  it('shows a KRW figure at zero decimals, grouped', () => {
    render(<Amount value="1400000" displayDecimals={0} labels={labels} />);
    expect(screen.getByText('1,400,000')).toBeDefined();
  });

  it('pads a USD figure to two decimals so a column lines up', () => {
    render(<Amount value="7" displayDecimals={2} labels={labels} />);
    expect(screen.getByText('7.00')).toBeDefined();
  });

  it('marks an abbreviated figure and names the exact value for a screen reader', () => {
    render(<Amount value="1400000.25" displayDecimals={0} labels={labels} />);
    expect(screen.getByText('1,400,000')).toBeDefined();
    // The exact value is present in the accessible name, not only in a tooltip.
    expect(screen.getByRole('button').getAttribute('aria-label')).toBe(
      'rounded; stored value is 1,400,000.25',
    );
  });

  it('does not mark a figure that hides nothing', () => {
    render(<Amount value="1400000" displayDecimals={0} labels={labels} />);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('reveals the exact value from the keyboard, per field', async () => {
    const user = userEvent.setup();
    render(<Amount value="1400000.25" displayDecimals={0} labels={labels} />);

    await user.click(screen.getByRole('button'));

    expect(screen.getByText('1,400,000.25')).toBeDefined();
  });

  it('reveals every figure at once when the global toggle is on', () => {
    render(
      <FullDecimalProvider initial>
        <Amount value="1400000.25" displayDecimals={0} labels={labels} />
        <Amount value="0.0000000439" displayDecimals={2} labels={labels} />
      </FullDecimalProvider>,
    );

    expect(screen.getByText('1,400,000.25')).toBeDefined();
    expect(screen.getByText('0.0000000439')).toBeDefined();
  });

  it('keeps precision a double would lose', () => {
    render(<Amount value="123456789012345678.999" displayDecimals={2} labels={labels} />);
    expect(screen.getByText('123,456,789,012,345,679.00')).toBeDefined();
  });

  it('shows a refused value verbatim rather than NaN, because that is an upstream bug worth seeing', () => {
    render(<Amount value="1e3" displayDecimals={0} labels={labels} />);
    expect(screen.getByText('1e3')).toBeDefined();
  });

  it('renders the currency code beside the figure when asked', () => {
    render(<Amount value="1000" displayDecimals={0} currencyCode="KRW" labels={labels} />);
    expect(screen.getByText('KRW')).toBeDefined();
  });

  it('signs a positive movement when the column is a movement column', () => {
    render(<Amount value="250" displayDecimals={0} signed labels={labels} />);
    expect(screen.getByText('+250')).toBeDefined();
  });
});
