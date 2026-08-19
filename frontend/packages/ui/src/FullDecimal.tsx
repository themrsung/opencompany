import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

/**
 * The global full-decimal toggle (§9).
 *
 * The requirement is that the complete unrounded stored value is reachable from
 * **every** screen that shows a number. A per-screen implementation of that
 * would be forgotten on the fourth screen, so it lives in one context that the
 * shared `<Amount>` reads, and no screen has to remember anything.
 *
 * The preference is per-person and sticky: an accountant who turns it on wants
 * it on tomorrow too. It is stored locally rather than on the server because it
 * is a display preference, not a permission, and a round trip to read it would
 * make the first paint of every table wait on the network.
 */
export interface FullDecimalState {
  /** When true, every amount shows its exact stored value beside the rounded one. */
  readonly showExact: boolean;
  readonly setShowExact: (value: boolean) => void;
  readonly toggle: () => void;
}

const STORAGE_KEY = 'coreintra.showExactValues';

const FullDecimalContext = createContext<FullDecimalState | null>(null);

function readStoredPreference(): boolean {
  try {
    return globalThis.localStorage?.getItem(STORAGE_KEY) === 'true';
  } catch {
    // Private browsing, or a locked-down on-prem browser policy. Defaulting to
    // off is right: the rounded value is the one people read all day.
    return false;
  }
}

export function FullDecimalProvider({
  children,
  initial,
}: {
  children: ReactNode;
  initial?: boolean;
}): ReactNode {
  const [showExact, setShowExactState] = useState<boolean>(() => initial ?? readStoredPreference());

  const setShowExact = useCallback((value: boolean) => {
    setShowExactState(value);
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, String(value));
    } catch {
      // Not being able to remember the preference is survivable; not being able
      // to set it would not be.
    }
  }, []);

  const value = useMemo<FullDecimalState>(
    () => ({ showExact, setShowExact, toggle: () => setShowExact(!showExact) }),
    [showExact, setShowExact],
  );

  return <FullDecimalContext.Provider value={value}>{children}</FullDecimalContext.Provider>;
}

/**
 * Reads the toggle.
 *
 * Falls back to "off" rather than throwing when there is no provider, so a
 * component can be rendered in isolation in a test or a storybook without the
 * whole app around it. `<Amount>` still shows the exact value on demand in that
 * case, because the per-field expansion does not depend on the global toggle.
 */
export function useFullDecimal(): FullDecimalState {
  const context = useContext(FullDecimalContext);
  return (
    context ?? {
      showExact: false,
      setShowExact: () => undefined,
      toggle: () => undefined,
    }
  );
}
