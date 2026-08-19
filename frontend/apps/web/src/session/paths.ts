/**
 * The one route that renders outside the shell, kept here rather than in the
 * screen module so the router, the guard and the sign-in screen can all agree
 * on it without importing each other in a circle.
 */
export const SIGN_IN_PATH = '/signin';

export function isSignInPath(pathname: string): boolean {
  const trimmed =
    pathname.length > 1 && pathname.endsWith('/') ? pathname.slice(0, -1) : pathname;
  return trimmed === SIGN_IN_PATH;
}
