import type { components } from '@coreintra/api-client';
import { isLocale } from '@coreintra/i18n';
import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useTranslation } from 'react-i18next';
import { api, onSessionExpired } from '../api/client.js';

export type SignedIn = components['schemas']['SignedIn'];
export type ActiveSession = components['schemas']['ActiveSession'];

/**
 * Who is signed in.
 *
 * `unknown` is a real state, not a loading detail to paper over: until the
 * first probe comes back the app must not render a screen that fires a dozen
 * authenticated queries, and it must not flash the sign-in form at somebody
 * who is already signed in.
 */
export type SessionStatus = 'unknown' | 'signed-in' | 'signed-out';

export interface SessionValue {
  readonly status: SessionStatus;
  /**
   * The signed-in person, as far as the client knows.
   *
   * Null while `unknown`, and null after a reload even when the status is
   * `signed-in`: the API has no "who am I" endpoint, so the profile is
   * remembered locally and re-read rather than re-fetched. See
   * `readProfile` for what that does and does not hold.
   */
  readonly identity: SignedIn | null;
  /** True when the last transition to signed-out was the session expiring under us. */
  readonly expired: boolean;
  /** Where the person was heading before the guard sent them to sign in. */
  readonly pendingPath: string | null;
  readonly signIn: (identity: SignedIn) => void;
  readonly signOut: () => Promise<void>;
  readonly rememberPath: (path: string) => void;
  readonly clearPendingPath: () => void;
}

const SessionContext = createContext<SessionValue | null>(null);

/**
 * The remembered profile.
 *
 * Nothing token-shaped goes in here and nothing needs to: the session lives in
 * HttpOnly cookies the script cannot read, which is the point of them. What is
 * stored is the display identity the header needs before the first request
 * comes back — a name, an account id, whether this account is a master. It is
 * a cache of something the server already decided, never the decision itself:
 * every permission is checked again server-side on every call, so tampering
 * with it buys a liar nothing but a wrong-looking menu on their own screen.
 */
const PROFILE_KEY = 'coreintra.profile';

function readProfile(): SignedIn | null {
  try {
    const raw = globalThis.localStorage?.getItem(PROFILE_KEY);
    if (raw === null || raw === undefined) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) {
      return null;
    }
    return parsed as SignedIn;
  } catch {
    return null;
  }
}

function writeProfile(identity: SignedIn | null): void {
  try {
    if (identity === null) {
      globalThis.localStorage?.removeItem(PROFILE_KEY);
      return;
    }
    globalThis.localStorage?.setItem(PROFILE_KEY, JSON.stringify(identity));
  } catch {
    // A browser with storage denied still signs in; it just forgets the name
    // on reload. Not a reason to fail to start.
  }
}

interface SessionState {
  readonly status: SessionStatus;
  readonly identity: SignedIn | null;
  readonly expired: boolean;
  readonly pendingPath: string | null;
}

export function SessionProvider({ children }: { readonly children: ReactNode }): ReactNode {
  const { i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [state, setState] = useState<SessionState>(() => ({
    status: 'unknown',
    identity: readProfile(),
    expired: false,
    pendingPath: null,
  }));

  // The client calls this when a refresh failed and the session is genuinely
  // over. There is one slot for the handler and no way to unregister, which is
  // correct here: there is exactly one provider for the life of the tab.
  useEffect(() => {
    onSessionExpired(() => {
      writeProfile(null);
      setState((previous) => ({
        status: 'signed-out',
        identity: null,
        // Only claim a session expired if we believed there was one. A first
        // visit also fails the boot probe, and telling a stranger their
        // session ran out is a small lie that makes the real message
        // meaningless when it matters.
        expired: previous.status === 'signed-in' || previous.identity !== null,
        pendingPath: previous.pendingPath,
      }));
    });
  }, []);

  // Boot probe. There is no `GET /auth/session`, so the cheapest honest
  // question is "list my devices": a read, no rotation, and a 401 if the
  // cookies are gone. A refresh POST would answer too, but it would rotate the
  // token on every page load for no reason.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        await api.get<ActiveSession[]>('/auth/session/active');
        if (!cancelled) {
          setState((previous) =>
            previous.status === 'signed-in'
              ? previous
              : { ...previous, status: 'signed-in', expired: false },
          );
        }
      } catch {
        if (!cancelled) {
          writeProfile(null);
          setState((previous) => ({
            status: 'signed-out',
            identity: null,
            expired: previous.expired,
            pendingPath: previous.pendingPath,
          }));
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(
    (identity: SignedIn) => {
      writeProfile(identity);
      setState((previous) => ({
        status: 'signed-in',
        identity,
        expired: false,
        pendingPath: previous.pendingPath,
      }));
      // The server knows this person's language; honour it from the first
      // paint of the next screen rather than after some later fetch.
      if (isLocale(identity.locale)) {
        try {
          globalThis.localStorage?.setItem('coreintra.locale', identity.locale);
        } catch {
          // Preference not remembered; the session still uses it.
        }
        void i18n.changeLanguage(identity.locale);
      }
    },
    [i18n],
  );

  const signOut = useCallback(async () => {
    try {
      await api.request('/auth/session', { method: 'DELETE' });
    } finally {
      writeProfile(null);
      // A shared desk is the normal case on an intranet. Whatever the last
      // person read must not still be in memory for the next one.
      queryClient.clear();
      setState({ status: 'signed-out', identity: null, expired: false, pendingPath: null });
    }
  }, [queryClient]);

  const rememberPath = useCallback((path: string) => {
    setState((previous) =>
      previous.pendingPath === path ? previous : { ...previous, pendingPath: path },
    );
  }, []);

  const clearPendingPath = useCallback(() => {
    setState((previous) => (previous.pendingPath === null ? previous : { ...previous, pendingPath: null }));
  }, []);

  const value = useMemo<SessionValue>(
    () => ({
      status: state.status,
      identity: state.identity,
      expired: state.expired,
      pendingPath: state.pendingPath,
      signIn,
      signOut,
      rememberPath,
      clearPendingPath,
    }),
    [state, signIn, signOut, rememberPath, clearPendingPath],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (value === null) {
    throw new Error('useSession outside SessionProvider');
  }
  return value;
}
