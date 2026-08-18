import { createRoute, type AnyRoute } from '@tanstack/react-router';
import { LeaveScreen } from './LeaveScreen.js';
import { WhosInScreen } from './WhosInScreen.js';

/** 근태 — the who's-in board and leave. */
export function attendanceRoutes(parent: AnyRoute): AnyRoute[] {
  return [
    createRoute({ getParentRoute: () => parent, path: '/whos-in', component: WhosInScreen }),
    createRoute({ getParentRoute: () => parent, path: '/leave', component: LeaveScreen }),
  ];
}
