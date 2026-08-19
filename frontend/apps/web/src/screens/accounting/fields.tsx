/**
 * A select and a date field, built the way `<TextField>` in `@coreintra/ui` is
 * built: a real `<label for>` pointing at the control, the hint and the error
 * wired through `aria-describedby`.
 *
 * They live here rather than in the shared package because the package does not
 * have them and this area is not the place to decide what the product's select
 * looks like. If a second area needs one, it should move — reported rather than
 * quietly generalised.
 */
import { useId, type ReactNode } from 'react';

interface FieldShellProps {
  readonly label: string;
  readonly hint?: string;
  readonly error?: string;
  readonly className?: string;
}

export interface SelectProps extends FieldShellProps {
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly children: ReactNode;
  readonly disabled?: boolean;
}

export function Select({ label, hint, error, className, value, onChange, children, disabled }: SelectProps): ReactNode {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const described = [hint === undefined ? null : hintId, error === undefined ? null : errorId]
    .filter((part): part is string => part !== null)
    .join(' ');

  return (
    <div className={['ci-field', error === undefined ? '' : 'ci-field--invalid', className ?? ''].filter(Boolean).join(' ')}>
      <label className="ci-field__label" htmlFor={id}>
        {label}
      </label>
      <select
        id={id}
        className="ci-field__input"
        value={value}
        disabled={disabled === true}
        aria-invalid={error === undefined ? undefined : true}
        aria-describedby={described === '' ? undefined : described}
        onChange={(event) => onChange(event.target.value)}
      >
        {children}
      </select>
      {hint === undefined ? null : (
        <p className="ci-field__hint" id={hintId}>
          {hint}
        </p>
      )}
      {error === undefined ? null : (
        <p className="ci-field__error" id={errorId} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

export interface CheckboxProps {
  readonly label: string;
  /** Read out with the control rather than folded into its name. */
  readonly hint?: string;
  readonly checked: boolean;
  readonly onChange: (checked: boolean) => void;
  readonly className?: string;
}

/**
 * A checkbox whose label is a real `<label for>` and whose hint is a separate
 * description. Nesting the hint inside the label would fold it into the
 * control's accessible name, which is how a switch ends up announced as a
 * paragraph.
 */
export function Checkbox({ label, hint, checked, onChange, className }: CheckboxProps): ReactNode {
  const id = useId();
  const hintId = `${id}-hint`;

  return (
    <span className={['acc-toggle', className ?? ''].filter(Boolean).join(' ')}>
      <input
        id={id}
        type="checkbox"
        checked={checked}
        aria-describedby={hint === undefined ? undefined : hintId}
        onChange={(event) => onChange(event.target.checked)}
      />
      <label htmlFor={id}>{label}</label>
      {hint === undefined ? null : (
        <span id={hintId} className="acc-muted">
          {hint}
        </span>
      )}
    </span>
  );
}

export interface DateFieldProps extends FieldShellProps {
  readonly value: string;
  readonly onChange: (value: string) => void;
}

/**
 * A plain calendar date, not a business instant.
 *
 * Report boundaries are calendar dates — `asOf`, `from`, `to` — while anything
 * that records when something *happened* is a `BusinessInstant` and is edited
 * with `<BusinessInstantField>`. Keeping the two visibly different is the point.
 */
export function DateField({ label, hint, error, className, value, onChange }: DateFieldProps): ReactNode {
  const id = useId();
  const hintId = `${id}-hint`;

  return (
    <div className={['ci-field', className ?? ''].filter(Boolean).join(' ')}>
      <label className="ci-field__label" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        type="date"
        className="ci-field__input ci-numeric"
        value={value}
        aria-describedby={hint === undefined ? undefined : hintId}
        onChange={(event) => onChange(event.target.value)}
      />
      {hint === undefined ? null : (
        <p className="ci-field__hint" id={hintId}>
          {hint}
        </p>
      )}
      {error === undefined ? null : (
        <p className="ci-field__error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
