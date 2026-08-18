import type { AnyRoute } from '@tanstack/react-router';
import { pendingRoute, type RootRoute } from '../../router.js';

/** 근태 — the who's-in board and leave. */
export function attendanceRoutes(parent: RootRoute): AnyRoute[] {
  return [
    pendingRoute(parent, '/whos-in', 'nav.whosIn'),
    pendingRoute(parent, '/leave', 'nav.leave'),
  ];
}
