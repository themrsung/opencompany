import { useEffect, useId, useRef, type ReactNode } from 'react';

/**
 * The controls these screens need that `@coreintra/ui` does not have yet.
 *
 * Reported rather than added to the package: a select, a checkbox, a tab strip,
 * a file field and a surfaced section are wanted by more than one area, and two
 * agents adding them at once is how a design system ends up with two of each.
 * They are written against the same `ci-` classes and the same tokens as the
 * primitives, so moving them into the package later is a file move.
 *
 * All of them are keyboard-first, because this is software people are in for
 * eight hours: the tab strip is arrow-navigable with a roving tabindex, every
 * field has a real `<label>` bound to its control, and the confirmation panel
 * takes focus when it opens and closes on Escape.
 */

/**
 * A screen-level keyboard shortcut for a primary action.
 *
 * Ignores anything typed into a field, which is the whole difficulty with
 * single-letter shortcuts: `n` for "new document" is a shortcut on the list and
 * a letter in the filter box, and a shortcut that eats a keystroke someone
 * meant for an input is worse than no shortcut.
 */
export function useShortcut(
  match: (event: KeyboardEvent) => boolean,
  run: () => void,
  enabled = true,
): void {
  const latest = useRef(run);
  latest.current = run;

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      const target = event.target as HTMLElement | null;
      const tag = target?.tagName ?? '';
      const typing =
        tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable === true;
      // A modified shortcut is safe inside a field; a bare letter is not.
      if (typing && !(event.metaKey || event.ctrlKey)) {
        return;
      }
      if (match(event)) {
        event.preventDefault();
        latest.current();
      }
    };
    globalThis.addEventListener('keydown', onKeyDown);
    return () => {
      globalThis.removeEventListener('keydown', onKeyDown);
    };
  }, [enabled, match]);
}

export function Panel({
  title,
  description,
  actions,
  children,
  id,
}: {
  readonly title: string;
  readonly description?: string;
  readonly actions?: ReactNode;
  readonly children: ReactNode;
  readonly id?: string;
}): ReactNode {
  return (
    <section
      className="page__surface"
      style={{ padding: 'var(--ci-space-4)', marginBottom: 'var(--ci-space-4)' }}
      aria-labelledby={id === undefined ? undefined : `${id}-heading`}
      {...(id === undefined ? {} : { id })}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          gap: 'var(--ci-space-3)',
          marginBottom: 'var(--ci-space-3)',
        }}
      >
        <h2
          id={id === undefined ? undefined : `${id}-heading`}
          style={{ margin: 0, fontSize: 'var(--ci-text-lg)' }}
        >
          {title}
        </h2>
        {actions ?? null}
      </div>
      {description === undefined ? null : (
        <p style={{ margin: '0 0 var(--ci-space-3)', color: 'var(--ci-fg-muted)', fontSize: 'var(--ci-text-sm)' }}>
          {description}
        </p>
      )}
      {children}
    </section>
  );
}

export interface SelectOption {
  readonly value: string;
  readonly label: string;
}

export function Select({
  label,
  value,
  onChange,
  options,
  hint,
  error,
  disabled,
  id,
}: {
  readonly label: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly options: readonly SelectOption[];
  readonly hint?: string;
  readonly error?: string;
  readonly disabled?: boolean;
  readonly id?: string;
}): ReactNode {
  const generated = useId();
  const fieldId = id ?? generated;
  const hintId = `${fieldId}-hint`;
  const errorId = `${fieldId}-error`;
  const describedBy = [hint === undefined ? null : hintId, error === undefined ? null : errorId]
    .filter((candidate): candidate is string => candidate !== null)
    .join(' ');

  return (
    <div className={`ci-field${error === undefined ? '' : ' ci-field--invalid'}`}>
      <label className="ci-field__label" htmlFor={fieldId}>
        {label}
      </label>
      <select
        id={fieldId}
        className="ci-field__input"
        value={value}
        disabled={disabled === true}
        aria-invalid={error === undefined ? undefined : true}
        aria-describedby={describedBy === '' ? undefined : describedBy}
        onChange={(event) => {
          onChange(event.currentTarget.value);
        }}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {hint === undefined ? null : (
        <p className="ci-field__hint" id={hintId}>
          {hint}
        </p>
      )}
      {error === undefined ? null : (
        <p className="ci-field__error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  );
}

/**
 * A checkbox whose label is a block of real words rather than a caption.
 *
 * §6.9's licence acknowledgement is the reason this takes a `ReactNode` label
 * and lays it out beside the box rather than truncating it: the wording has to
 * be readable in full, and a control that visually implies "fine print" is the
 * thing the brief rules out.
 */
export function Checkbox({
  checked,
  onChange,
  label,
  id,
  describedBy,
  disabled,
}: {
  readonly checked: boolean;
  readonly onChange: (checked: boolean) => void;
  readonly label: ReactNode;
  readonly id?: string;
  readonly describedBy?: string;
  readonly disabled?: boolean;
}): ReactNode {
  const generated = useId();
  const fieldId = id ?? generated;
  return (
    <div style={{ display: 'flex', gap: 'var(--ci-space-2)', alignItems: 'flex-start' }}>
      <input
        type="checkbox"
        id={fieldId}
        checked={checked}
        disabled={disabled === true}
        aria-describedby={describedBy}
        onChange={(event) => {
          onChange(event.currentTarget.checked);
        }}
        style={{ marginTop: '3px' }}
      />
      <label htmlFor={fieldId} style={{ fontSize: 'var(--ci-text-sm)', lineHeight: 1.5 }}>
        {label}
      </label>
    </div>
  );
}

export function FileField({
  label,
  onChange,
  accept,
  hint,
  error,
  id,
}: {
  readonly label: string;
  readonly onChange: (file: File | null) => void;
  readonly accept?: string;
  readonly hint?: string;
  readonly error?: string;
  readonly id?: string;
}): ReactNode {
  const generated = useId();
  const fieldId = id ?? generated;
  const hintId = `${fieldId}-hint`;
  const errorId = `${fieldId}-error`;
  return (
    <div className={`ci-field${error === undefined ? '' : ' ci-field--invalid'}`}>
      <label className="ci-field__label" htmlFor={fieldId}>
        {label}
      </label>
      <input
        type="file"
        id={fieldId}
        className="ci-field__input"
        {...(accept === undefined ? {} : { accept })}
        aria-invalid={error === undefined ? undefined : true}
        aria-describedby={error === undefined ? (hint === undefined ? undefined : hintId) : errorId}
        onChange={(event) => {
          onChange(event.currentTarget.files?.[0] ?? null);
        }}
      />
      {hint === undefined ? null : (
        <p className="ci-field__hint" id={hintId}>
          {hint}
        </p>
      )}
      {error === undefined ? null : (
        <p className="ci-field__error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  );
}

export interface TabDefinition {
  readonly id: string;
  readonly label: string;
}

/**
 * A tab strip with a roving tabindex.
 *
 * One stop in the tab order, arrows to move between tabs, Home and End to the
 * ends — the pattern a screen reader user expects and the one a keyboard user
 * gets for free. A row of buttons would put four stops in the tab order of a
 * screen that already has plenty.
 */
export function Tabs({
  tabs,
  active,
  onSelect,
  label,
}: {
  readonly tabs: readonly TabDefinition[];
  readonly active: string;
  readonly onSelect: (id: string) => void;
  readonly label: string;
}): ReactNode {
  const move = (delta: number): void => {
    const index = tabs.findIndex((tab) => tab.id === active);
    const next = tabs[(index + delta + tabs.length) % tabs.length];
    if (next !== undefined) {
      onSelect(next.id);
    }
  };

  return (
    <div
      role="tablist"
      aria-label={label}
      style={{
        display: 'flex',
        gap: 'var(--ci-space-1)',
        borderBottom: '1px solid var(--ci-border)',
        marginBottom: 'var(--ci-space-4)',
      }}
      onKeyDown={(event) => {
        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
          event.preventDefault();
          move(1);
        } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
          event.preventDefault();
          move(-1);
        } else if (event.key === 'Home') {
          event.preventDefault();
          const first = tabs[0];
          if (first !== undefined) {
            onSelect(first.id);
          }
        } else if (event.key === 'End') {
          event.preventDefault();
          const last = tabs[tabs.length - 1];
          if (last !== undefined) {
            onSelect(last.id);
          }
        }
      }}
    >
      {tabs.map((tab) => {
        const selected = tab.id === active;
        return (
          <button
            key={tab.id}
            type="button"
            role="tab"
            id={`tab-${tab.id}`}
            aria-selected={selected}
            // Only the selected panel is in the DOM, so only the selected tab
            // can honestly point at one.
            {...(selected ? { 'aria-controls': `tabpanel-${tab.id}` } : {})}
            tabIndex={selected ? 0 : -1}
            onClick={() => {
              onSelect(tab.id);
            }}
            style={{
              appearance: 'none',
              background: 'none',
              border: 'none',
              borderBottom: `2px solid ${selected ? 'var(--ci-accent)' : 'transparent'}`,
              color: selected ? 'var(--ci-accent)' : 'var(--ci-fg-muted)',
              font: 'inherit',
              fontWeight: selected ? 600 : 400,
              padding: 'var(--ci-space-2) var(--ci-space-3)',
              cursor: 'pointer',
            }}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
}

export function TabPanel({ id, children }: { readonly id: string; readonly children: ReactNode }): ReactNode {
  return (
    <div role="tabpanel" id={`tabpanel-${id}`} aria-labelledby={`tab-${id}`} tabIndex={-1}>
      {children}
    </div>
  );
}

/**
 * An inline confirmation, focused on open and dismissed with Escape.
 *
 * Inline rather than modal on purpose. What a person needs before removing a
 * font is the affected count and the sentence explaining it, both of which are
 * more readable in the flow of the page than in a box that hides the row being
 * acted on.
 */
export function ConfirmPanel({
  title,
  children,
  onCancel,
  id,
}: {
  readonly title: string;
  readonly children: ReactNode;
  readonly onCancel: () => void;
  readonly id?: string;
}): ReactNode {
  const container = useRef<HTMLDivElement | null>(null);
  const generated = useId();
  const headingId = `${id ?? generated}-heading`;

  useEffect(() => {
    container.current?.focus();
  }, []);

  return (
    <div
      ref={container}
      role="group"
      aria-labelledby={headingId}
      tabIndex={-1}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation();
          onCancel();
        }
      }}
      style={{
        border: '1px solid var(--ci-border)',
        borderLeft: '3px solid var(--ci-danger)',
        borderRadius: 'var(--ci-radius)',
        padding: 'var(--ci-space-3)',
        marginTop: 'var(--ci-space-3)',
        background: 'var(--ci-bg-subtle)',
      }}
    >
      <h3 id={headingId} style={{ margin: '0 0 var(--ci-space-2)', fontSize: 'var(--ci-text-sm)' }}>
        {title}
      </h3>
      {children}
    </div>
  );
}

/** A read-only label/value list, for metadata that is not a table. */
export function KeyValues({
  rows,
}: {
  readonly rows: readonly { readonly key: string; readonly label: string; readonly value: ReactNode }[];
}): ReactNode {
  return (
    <dl
      style={{
        display: 'grid',
        gridTemplateColumns: 'max-content 1fr',
        gap: 'var(--ci-space-1) var(--ci-space-4)',
        margin: 0,
        fontSize: 'var(--ci-text-sm)',
      }}
    >
      {rows.map((row) => (
        <div key={row.key} style={{ display: 'contents' }}>
          <dt style={{ color: 'var(--ci-fg-muted)' }}>{row.label}</dt>
          <dd style={{ margin: 0, minWidth: 0, overflowWrap: 'anywhere' }}>{row.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Monospace, for hashes and generated config lines that must be copied exactly. */
export function Mono({ children }: { readonly children: ReactNode }): ReactNode {
  return (
    <code style={{ fontFamily: 'var(--ci-font-mono)', fontSize: 'var(--ci-text-xs)', overflowWrap: 'anywhere' }}>
      {children}
    </code>
  );
}
