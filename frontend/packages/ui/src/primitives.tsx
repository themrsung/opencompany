import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react';
import { useId } from 'react';

function cx(...parts: Array<string | false | undefined>): string {
  return parts.filter((part): part is string => Boolean(part)).join(' ');
}

/* ------------------------------------------------------------------ Button */

export type ButtonTone = 'default' | 'primary' | 'danger' | 'support';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  readonly tone?: ButtonTone;
  /**
   * Blocks the click and shows a busy state. Separate from `disabled` because a
   * disabled button is unreachable by keyboard and gives no reason; a busy one
   * stays focusable and can announce itself.
   */
  readonly busy?: boolean;
}

export function Button({ tone = 'default', busy = false, className, children, ...rest }: ButtonProps): ReactNode {
  return (
    <button
      type="button"
      {...rest}
      className={cx('ci-button', `ci-button--${tone}`, busy && 'ci-button--busy', className)}
      aria-busy={busy || undefined}
      onClick={busy ? undefined : rest.onClick}
    >
      {children}
    </button>
  );
}

/* ------------------------------------------------------------------ Banner */

export type BannerTone = 'info' | 'positive' | 'warning' | 'danger' | 'support';

export interface BannerProps {
  readonly tone?: BannerTone;
  readonly title?: string;
  readonly children: ReactNode;
  readonly actions?: ReactNode;
  /**
   * Omit for a banner that must not be dismissible — the live temporary-master
   * banner is required to be non-dismissible, so the absence of this prop is
   * the mechanism rather than a convention.
   */
  readonly onDismiss?: () => void;
  readonly dismissLabel?: string;
}

export function Banner({
  tone = 'info',
  title,
  children,
  actions,
  onDismiss,
  dismissLabel,
}: BannerProps): ReactNode {
  return (
    <div
      className={cx('ci-banner', `ci-banner--${tone}`)}
      role={tone === 'danger' || tone === 'warning' ? 'alert' : 'status'}
    >
      <div className="ci-banner__body">
        {title ? <strong className="ci-banner__title">{title}</strong> : null}
        <div className="ci-banner__message">{children}</div>
      </div>
      {actions ? <div className="ci-banner__actions">{actions}</div> : null}
      {onDismiss ? (
        <button type="button" className="ci-banner__dismiss" onClick={onDismiss} aria-label={dismissLabel}>
          <span aria-hidden="true">×</span>
        </button>
      ) : null}
    </div>
  );
}

/* --------------------------------------------------------------- TextField */

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  readonly label: string;
  readonly hint?: string;
  /** Present means invalid. The message says what happened and what to do. */
  readonly error?: string;
  readonly id?: string;
}

export function TextField({ label, hint, error, id, className, ...rest }: TextFieldProps): ReactNode {
  const generated = useId();
  const fieldId = id ?? generated;
  const hintId = `${fieldId}-hint`;
  const errorId = `${fieldId}-error`;
  const described = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');

  return (
    <div className={cx('ci-field', error && 'ci-field--invalid', className)}>
      <label className="ci-field__label" htmlFor={fieldId}>
        {label}
      </label>
      <input
        {...rest}
        id={fieldId}
        className="ci-field__input"
        aria-invalid={error ? true : undefined}
        aria-describedby={described === '' ? undefined : described}
      />
      {hint ? (
        <p className="ci-field__hint" id={hintId}>
          {hint}
        </p>
      ) : null}
      {error ? (
        <p className="ci-field__error" id={errorId} role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/* -------------------------------------------------------------- EmptyState */

export function EmptyState({
  message,
  action,
}: {
  readonly message: string;
  readonly action?: ReactNode;
}): ReactNode {
  return (
    <div className="ci-empty" role="status">
      <p className="ci-empty__message">{message}</p>
      {action ? <div className="ci-empty__action">{action}</div> : null}
    </div>
  );
}

/* ------------------------------------------------------------------- Badge */

export function Badge({
  tone = 'default',
  children,
}: {
  readonly tone?: 'default' | 'positive' | 'warning' | 'danger' | 'accent' | 'support';
  readonly children: ReactNode;
}): ReactNode {
  return <span className={cx('ci-badge', `ci-badge--${tone}`)}>{children}</span>;
}

/* --------------------------------------------------------------- DataTable */

export interface Column<Row> {
  readonly key: string;
  readonly header: string;
  readonly render: (row: Row) => ReactNode;
  /** Applies tabular figures and right alignment. Every money or count column wants this. */
  readonly numeric?: boolean;
  readonly width?: string;
}

export interface DataTableProps<Row> {
  readonly caption: string;
  readonly columns: ReadonlyArray<Column<Row>>;
  readonly rows: readonly Row[];
  readonly rowKey: (row: Row) => string;
  readonly emptyMessage: string;
  readonly onRowActivate?: (row: Row) => void;
}

/**
 * A dense table.
 *
 * Rows are activated with Enter as well as a click: this is a product people
 * drive from the keyboard for eight hours, and a click-only row is a row they
 * cannot reach. The caption is real rather than visually hidden decoration,
 * because a screen reader landing in a table needs to know which one it is.
 */
export function DataTable<Row>({
  caption,
  columns,
  rows,
  rowKey,
  emptyMessage,
  onRowActivate,
}: DataTableProps<Row>): ReactNode {
  if (rows.length === 0) {
    return <EmptyState message={emptyMessage} />;
  }

  return (
    <table className="ci-table">
      <caption className="ci-visually-hidden">{caption}</caption>
      <thead>
        <tr>
          {columns.map((column) => (
            <th
              key={column.key}
              scope="col"
              className={cx(column.numeric && 'ci-numeric')}
              style={column.width === undefined ? undefined : { width: column.width }}
            >
              {column.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={rowKey(row)}
            className={cx(onRowActivate && 'ci-table__row--activatable')}
            tabIndex={onRowActivate ? 0 : undefined}
            onClick={onRowActivate ? () => onRowActivate(row) : undefined}
            onKeyDown={
              onRowActivate
                ? (event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      onRowActivate(row);
                    }
                  }
                : undefined
            }
          >
            {columns.map((column) => (
              <td key={column.key} className={cx(column.numeric && 'ci-numeric')}>
                {column.render(row)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
