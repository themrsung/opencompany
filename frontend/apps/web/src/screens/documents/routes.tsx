import { createRoute, useNavigate, useParams, type AnyRoute } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { ReactNode } from 'react';
import { Page } from '../../layout/AppShell.js';
import { CompanyPicker, useCompanyChoice } from './companies.js';
import { DocumentScreen } from './DocumentScreen.js';
import { DocumentsScreen } from './DocumentsScreen.js';
import { ErrorNote } from './errors.js';
import { FontsScreen } from './FontsScreen.js';
import { TemplateScreen } from './TemplateScreen.js';
import { TemplatesScreen } from './TemplatesScreen.js';

/**
 * Documents, templates and the font manager.
 *
 * The screens themselves take a `companyId` and plain callbacks rather than
 * reaching for the router, which keeps them renderable in a test without a
 * route tree and keeps the navigation decisions in one file.
 *
 * `parent` is an `AnyRoute` rather than the app's own root route type so that
 * this tree can be mounted on a throwaway root in a test — which is the only
 * way to find out that the five routes still compose without importing every
 * other area's routes along with them.
 */
export function documentRoutes(parent: AnyRoute): AnyRoute[] {
  return [
    createRoute({ getParentRoute: () => parent, path: '/documents', component: DocumentsRoute }),
    createRoute({
      getParentRoute: () => parent,
      path: '/documents/$documentId',
      component: DocumentRoute,
    }),
    createRoute({ getParentRoute: () => parent, path: '/templates', component: TemplatesRoute }),
    createRoute({
      getParentRoute: () => parent,
      path: '/templates/$templateId',
      component: TemplateRoute,
    }),
    createRoute({ getParentRoute: () => parent, path: '/fonts', component: FontsRoute }),
  ];
}

/**
 * Waits for the company choice, then renders.
 *
 * Every endpoint in this area is company-scoped, so a screen rendered before
 * the choice resolves would fire a page of requests with an empty companyId and
 * show a page of 400s.
 */
function WithCompany({
  title,
  children,
}: {
  readonly title: string;
  readonly children: (companyId: string) => ReactNode;
}): ReactNode {
  const { t } = useTranslation();
  const choice = useCompanyChoice();

  return (
    <Page title={title}>
      <CompanyPicker choice={choice} />
      <ErrorNote error={choice.error} />
      {choice.companyId === null ? (
        <p>{choice.isPending ? t('app.loading') : t('docs.companyNone')}</p>
      ) : (
        children(choice.companyId)
      )}
    </Page>
  );
}

function DocumentsRoute(): ReactNode {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return (
    <WithCompany title={t('docs.listTitle')}>
      {(companyId) => (
        <DocumentsScreen
          companyId={companyId}
          onOpenDocument={(documentId) => {
            void navigate({ to: '/documents/$documentId', params: { documentId } });
          }}
        />
      )}
    </WithCompany>
  );
}

function DocumentRoute(): ReactNode {
  const { t } = useTranslation();
  const params = useParams({ strict: false });
  const documentId = typeof params.documentId === 'string' ? params.documentId : '';
  return (
    <WithCompany title={t('documents.title')}>
      {(companyId) => <DocumentScreen documentId={documentId} companyId={companyId} />}
    </WithCompany>
  );
}

function TemplatesRoute(): ReactNode {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return (
    <WithCompany title={t('docs.templatesTitle')}>
      {(companyId) => (
        <TemplatesScreen
          companyId={companyId}
          onOpenTemplate={(templateId) => {
            void navigate({ to: '/templates/$templateId', params: { templateId } });
          }}
        />
      )}
    </WithCompany>
  );
}

function TemplateRoute(): ReactNode {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const params = useParams({ strict: false });
  const templateId = typeof params.templateId === 'string' ? params.templateId : '';
  return (
    <WithCompany title={t('docs.templatesTitle')}>
      {() => (
        <TemplateScreen
          templateId={templateId}
          onOpenDocument={(documentId) => {
            void navigate({ to: '/documents/$documentId', params: { documentId } });
          }}
        />
      )}
    </WithCompany>
  );
}

function FontsRoute(): ReactNode {
  const { t } = useTranslation();
  return (
    <WithCompany title={t('fontManager.heading')}>
      {(companyId) => <FontsScreen companyId={companyId} />}
    </WithCompany>
  );
}
