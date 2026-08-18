import type { AnyRoute } from '@tanstack/react-router';
import { pendingRoute, type RootRoute } from '../../router.js';

/** 결재 — the inbox is the home route, because it is what people open. */
export function approvalRoutes(parent: RootRoute): AnyRoute[] {
  return [pendingRoute(parent, '/', 'nav.inbox')];
}
