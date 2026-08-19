import { createRoute, type AnyRoute } from '@tanstack/react-router';
import { DocumentScreen } from './DocumentScreen.js';
import { InboxScreen } from './InboxScreen.js';

/**
 * 결재 — the inbox is the home route, because it is what people open.
 *
 * The document sits at `/approvals/{id}` rather than under `/documents`: what
 * this screen shows is the approval — its line, its trail, its decisions — and
 * the document's body belongs to the documents module.
 */
export function approvalRoutes(parent: AnyRoute): AnyRoute[] {
  return [
    createRoute({ getParentRoute: () => parent, path: '/', component: InboxScreen }),
    createRoute({
      getParentRoute: () => parent,
      path: '/approvals/$documentId',
      component: DocumentScreen,
    }),
  ];
}
