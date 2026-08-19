import { Banner } from '@coreintra/ui';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client.js';
import { presentError } from '../../api/errors.js';
import { Page } from '../../layout/AppShell.js';
import {
  CompanySelect,
  todayBusinessDate,
  useCompanyChoice,
} from '../../session/viewingContext.js';
import { AuditTable, type AuditEntry } from './AuditTable.js';

/**
 * The session report: everything a support session actually did.
 *
 * Built from `GET /audit/support-session/{grantId}`, which returns typed audit
 * entries, rather than from `GET /support/temporary-master/{grantId}/report`,
 * whose schema in the contract is empty — it generates as `unknown`, and
 * hand-writing a shape for it here would be inventing an API. See the report.
 */
export function SupportReportScreen(): ReactNode {
  const { t, i18n } = useTranslation();
  const params = useParams({ strict: false });
  const grantId = typeof params.grantId === 'string' ? params.grantId : '';
  const choice = useCompanyChoice(todayBusinessDate());
  const companyId = choice.companyId;

  const entries = useQuery({
    queryKey: ['audit', 'support-session', grantId, companyId],
    enabled: grantId !== '' && companyId !== null,
    queryFn: () =>
      api.get<AuditEntry[]>(`/audit/support-session/${encodeURIComponent(grantId)}`, {
        query: { companyId: companyId ?? '' },
      }),
  });

  return (
    <Page
      title={t('temporaryMaster.sessionReport')}
      actions={
        <Link to="/support" className="ci-button">
          {t('support.backToSupport')}
        </Link>
      }
    >
      <p style={{ color: 'var(--ci-fg-muted)', maxWidth: '48rem' }}>{t('support.reportIntro')}</p>
      <p className="ci-numeric">
        {t('support.reportGrant')}: {grantId}
      </p>
      <p style={{ color: 'var(--ci-fg-muted)', maxWidth: '48rem' }}>{t('audit.twoClocks')}</p>

      <div className="page__surface" style={{ padding: 'var(--ci-space-4)' }}>
        <CompanySelect choice={choice} language={i18n.language} />
      </div>

      {entries.error === null || entries.error === undefined ? null : (
        <Banner tone="danger" title={t('error.title')}>
          {presentError(entries.error, t).message}
        </Banner>
      )}

      <section className="page__surface" style={{ padding: 'var(--ci-space-4)', marginTop: 'var(--ci-space-4)' }}>
        <h2>{t('audit.supportSessionTitle')}</h2>
        <AuditTable rows={entries.data ?? []} emptyMessage={t('support.reportEmpty')} />
      </section>
    </Page>
  );
}
