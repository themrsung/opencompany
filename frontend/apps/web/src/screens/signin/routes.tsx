import { createRoute, type AnyRoute } from '@tanstack/react-router';
import { SIGN_IN_PATH } from '../../session/paths.js';
import type { RootRoute } from '../../router.js';
import { SecurityScreen } from './SecurityScreen.js';
import { SignInScreen } from './SignInScreen.js';

/**
 * Sign-in and account security.
 *
 * Two routes, deliberately unalike: `/signin` is the only screen in the
 * product that renders outside the shell and without a session, and
 * `/security` is an ordinary guarded screen that happens to live next to it in
 * the source. The router tells them apart by path — see `isSignInPath`.
 */
export function signinRoutes(parent: RootRoute): AnyRoute[] {
  return [
    createRoute({ getParentRoute: () => parent, path: SIGN_IN_PATH, component: SignInScreen }),
    createRoute({ getParentRoute: () => parent, path: '/security', component: SecurityScreen }),
  ];
}
