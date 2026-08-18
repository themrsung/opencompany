import { businessInstant, type BusinessInstant } from '@coreintra/business-time';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { BusinessInstantField, BusinessInstantText, formatClockFace, parseClockFace } from '../src/index.js';

const labels = {
  legend: 'Business time',
  businessDate: 'Business day',
  clock: 'Time',
  hint: 'A day runs from -24:00 to +48:00',
  outsideCalendarDay: (resolved: string) => `resolves to ${resolved}`,
  invalid: 'not a valid business time',
};

describe('the clock face parser', () => {
  it('accepts an ordinary time', () => {
    expect(parseClockFace('09:30')).toBe(9 * 3600 + 30 * 60);
  });

  it('accepts a shift that ended after midnight, which is the whole point', () => {
    expect(parseClockFace('27:00')).toBe(27 * 3600);
  });

  it('accepts a pre-shift briefing on the previous evening', () => {
    expect(parseClockFace('-02:00')).toBe(-2 * 3600);
  });

  it('accepts the two window edges exactly', () => {
    expect(parseClockFace('-24:00')).toBe(-86_400);
    expect(parseClockFace('48:00')).toBe(172_800);
  });

  it('refuses anything outside the 72-hour window', () => {
    expect(parseClockFace('-24:01')).toBeNull();
    expect(parseClockFace('48:01')).toBeNull();
  });

  it('refuses nonsense rather than guessing', () => {
    expect(parseClockFace('')).toBeNull();
    expect(parseClockFace('9')).toBeNull();
    expect(parseClockFace('09:60')).toBeNull();
    expect(parseClockFace('09:30Z')).toBeNull();
  });

  it('round-trips through the formatter', () => {
    for (const text of ['-24:00', '-02:00', '00:00', '09:30', '27:00', '48:00']) {
      expect(formatClockFace(parseClockFace(text) as number)).toBe(text);
    }
  });

  it('keeps seconds only when they carry information', () => {
    expect(formatClockFace(27 * 3600)).toBe('27:00');
    expect(formatClockFace(27 * 3600 + 30)).toBe('27:00:30');
  });
});

function Harness({ initial }: { initial: BusinessInstant | null }): React.ReactNode {
  const [value, setValue] = useState<BusinessInstant | null>(initial);
  return (
    <>
      <BusinessInstantField value={value} onChange={setValue} labels={labels} />
      <output data-testid="wire">
        {value === null ? 'null' : `${value.businessDate}/${value.offsetSeconds}`}
      </output>
    </>
  );
}

describe('<BusinessInstantField>', () => {
  it('lets a 03:00 shift end be recorded as 27:00 on the day it started', async () => {
    const user = userEvent.setup();
    render(<Harness initial={businessInstant('2026-08-30', 0)} />);

    const clock = screen.getByLabelText('Time');
    await user.clear(clock);
    await user.type(clock, '27:00');

    expect(screen.getByTestId('wire').textContent).toBe(`2026-08-30/${27 * 3600}`);
  });

  it('shows the resolved wall-clock moment once the offset leaves the calendar day', async () => {
    const user = userEvent.setup();
    render(<Harness initial={businessInstant('2026-08-30', 0)} />);

    await user.clear(screen.getByLabelText('Time'));
    await user.type(screen.getByLabelText('Time'), '27:00');

    expect(screen.getByRole('note').textContent).toBe('resolves to 2026-08-31T03:00:00');
  });

  it('says nothing extra for an ordinary time', () => {
    render(<Harness initial={businessInstant('2026-08-30', 9 * 3600)} />);
    expect(screen.queryByRole('note')).toBeNull();
  });

  it('accepts a negative offset for a briefing the evening before', async () => {
    const user = userEvent.setup();
    render(<Harness initial={businessInstant('2026-08-31', 0)} />);

    await user.clear(screen.getByLabelText('Time'));
    await user.type(screen.getByLabelText('Time'), '-02:00');

    expect(screen.getByTestId('wire').textContent).toBe(`2026-08-31/${-2 * 3600}`);
  });
});

describe('<BusinessInstantText>', () => {
  it('renders the business day and the clock face, not a wall-clock guess', () => {
    render(<BusinessInstantText value={businessInstant('2026-08-30', 26 * 3600 + 60)} />);
    expect(screen.getByText('2026-08-30 26:01')).toBeDefined();
  });
});
