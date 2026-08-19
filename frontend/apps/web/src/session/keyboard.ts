import { useCallback, useEffect, type KeyboardEvent as ReactKeyboardEvent } from 'react';

/**
 * ⌘↵ / Ctrl+↵ runs the screen's primary action.
 *
 * §12 asks for one keyboard path through each screen. Enter alone already
 * submits a single-field form, so the shortcut earns its keep on the screens
 * where the primary action sits below a list of controls — the explainer and
 * the audit filters — and it means the same thing on both.
 *
 * The issuance wizard deliberately does not take it. Its primary action opens
 * a stranger's way into the company's data, and §8 wants that slow: a
 * keystroke that can fire it from anywhere on the page is the opposite of a
 * four-step confirmation.
 *
 * It lives in `session/` because three screen areas share it and the app has
 * no agreed home for cross-area hooks yet; say so rather than quietly minting
 * a `hooks/` directory two other agents are also about to invent.
 */
export function usePrimaryShortcut(enabled: boolean, run: () => void): void {
  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        run();
      }
    };
    globalThis.addEventListener('keydown', onKeyDown);
    return () => {
      globalThis.removeEventListener('keydown', onKeyDown);
    };
  }, [enabled, run]);
}

/**
 * Up and down arrows move between the rows of an activatable table.
 *
 * §12 asks for arrow-navigable lists. `DataTable` already makes each row
 * focusable and Enter-activatable when it takes `onRowActivate`; what it does
 * not do is move focus, and it belongs to another agent this week. So the
 * movement lives here as a handler you spread onto the element wrapping the
 * table: it works on the shipped primitive, and it disappears the day the
 * primitive grows the behaviour itself.
 *
 * Home and End go to the ends, because a long roster is the case that needs it.
 */
export function useRowArrowKeys(): (event: ReactKeyboardEvent<HTMLElement>) => void {
  return useCallback((event: ReactKeyboardEvent<HTMLElement>) => {
    const keys = ['ArrowDown', 'ArrowUp', 'Home', 'End'];
    if (!keys.includes(event.key)) {
      return;
    }
    const target = event.target as HTMLElement | null;
    const row = target?.closest?.('tr');
    const body = row?.closest('tbody');
    if (row === null || row === undefined || body === null || body === undefined) {
      return;
    }
    const rows = [...body.querySelectorAll<HTMLElement>('tr[tabindex]')];
    const here = rows.indexOf(row);
    if (here === -1) {
      return;
    }
    const last = rows.length - 1;
    let next = here;
    if (event.key === 'ArrowDown') {
      next = Math.min(here + 1, last);
    } else if (event.key === 'ArrowUp') {
      next = Math.max(here - 1, 0);
    } else if (event.key === 'Home') {
      next = 0;
    } else {
      next = last;
    }
    const moved = rows[next];
    if (moved !== undefined && moved !== row) {
      event.preventDefault();
      moved.focus();
    }
  }, []);
}
