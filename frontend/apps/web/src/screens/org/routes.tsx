import { createRoute, type AnyRoute } from '@tanstack/react-router';
import type { RootRoute } from '../../router.js';
import { OrgChartScreen } from './OrgChartScreen.js';
import { PeopleScreen } from './PeopleScreen.js';
import { PermissionExplainerScreen } from './PermissionExplainerScreen.js';

/**
 * The org chart and the effective-permissions explainer.
 *
 * The explainer is a sibling of the chart rather than a panel inside it: §4
 * requires it to be linkable, because the answer to "why can this person do
 * this?" is something one person pastes to another during an argument.
 */
export function orgRoutes(parent: RootRoute): AnyRoute[] {
  return [
    createRoute({ getParentRoute: () => parent, path: '/org', component: OrgChartScreen }),
    createRoute({ getParentRoute: () => parent, path: '/org/people', component: PeopleScreen }),
    createRoute({
      getParentRoute: () => parent,
      path: '/org/explainer',
      component: PermissionExplainerScreen,
    }),
  ];
}
