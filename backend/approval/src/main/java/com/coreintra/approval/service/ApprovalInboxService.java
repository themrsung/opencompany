package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.ApprovalStep;
import com.coreintra.approval.domain.ApprovalStepKind;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The 결재함 query. One of the two screens the brief says decide whether people
 * like this product, so it is one query.
 *
 * <h2>What it costs</h2>
 *
 * <p><b>One statement</b>, whatever the size of the inbox: a single select over
 * {@code approval_document} restricted to documents that touch this account,
 * carrying two correlated counts that say <em>how</em> each one touches them —
 * pending on me, and copied to me. Bucketing, per-list ordering and the badge
 * counts are then done in memory over one person's inbox, which is tens of rows,
 * not a table scan. The database orders too, newest first, because the fetch is
 * capped and an uncapped-looking cap is a bug waiting for a busy user.
 *
 * <p>The obvious alternative — list the pending documents, then for each one
 * load its steps to find out whether it is mine, then load its approvers to find
 * out whether I am copied — is 1 + 2N statements and gets slower exactly as a
 * person gets busier. That is the shape this class exists to avoid.
 *
 * <p><b>The index that makes it fast.</b> The correlated subqueries and the
 * {@code exists} filter all start from {@code approval_step_approver} by
 * {@code account_id}, which is the <em>second</em> column of that table's
 * primary key {@code (step_id, account_id)} and therefore not usable as a
 * prefix. Until an index on {@code approval_step_approver (account_id)} exists,
 * this is a sequential scan of that table per inbox load. It is a one-line
 * migration and it belongs with the schema, not here.
 *
 * <h2>Reading someone else's inbox</h2>
 *
 * <p>Your own needs no grant. Anyone else's is a sensitive read — it says what
 * that person is being asked to authorise — and goes through the evaluator.
 */
@Service
public class ApprovalInboxService {

    /** A sane page size: nobody triages a thousand documents in one sitting. */
    private static final int DEFAULT_LIMIT = 200;

    /** Package-private so a test can hold it to the entity model it names. */
    static final String INBOX_JPQL =
            "select d, "
            + "  (select count(a1) from ApprovalStepApproverEntity a1, ApprovalStepEntity s1 "
            + "     where a1.stepId = s1.id and s1.documentId = d.id "
            + "       and a1.accountId = :accountId and s1.state = :pending "
            + "       and s1.kind <> :cc), "
            + "  (select count(a2) from ApprovalStepApproverEntity a2, ApprovalStepEntity s2 "
            + "     where a2.stepId = s2.id and s2.documentId = d.id "
            + "       and a2.accountId = :accountId and s2.kind = :cc) "
            + "from ApprovalDocumentEntity d "
            + "where d.drafterAccountId = :accountId "
            + "   or exists (select 1 from ApprovalStepApproverEntity a3, ApprovalStepEntity s3 "
            + "              where a3.stepId = s3.id and s3.documentId = d.id "
            + "                and a3.accountId = :accountId) "
            // Ordered in the database because the fetch is capped. Without an
            // ORDER BY, which rows survive setMaxResults is whatever the plan
            // happens to produce, and the pending list the cap exists to
            // protect could be the part that gets dropped. Newest first, so the
            // cap keeps the documents a person is currently dealing with;
            // business ordering (ADR 0002), never the derived absolute
            // timestamp. Drafts have no submission instant and sort last under
            // NULLS LAST, which is where they belong.
            + "order by d.submittedAt.businessDate desc nulls last, "
            + "         d.submittedAt.offsetSeconds desc nulls last, d.id asc";

    private final PermissionEvaluator permissions;
    private final EntityManager entityManager;

    /**
     * Takes the {@link EntityManager} directly rather than a repository.
     *
     * <p>The query below spans three tables and exists to be one statement;
     * expressing it as derived repository methods would be the N+1 this class is
     * here to avoid, and adding it as a {@code @Query} would put a JPQL string
     * of this size somewhere nobody reads it beside the reasoning for its shape.
     */
    public ApprovalInboxService(PermissionEvaluator permissions, EntityManager entityManager) {
        this.permissions = permissions;
        this.entityManager = entityManager;
    }

    /** This account's inbox, capped at {@value #DEFAULT_LIMIT} documents per list. */
    @Transactional(readOnly = true)
    public ApprovalInbox load(PermissionPrincipal actor, String companyId, String accountId) {
        return load(actor, companyId, accountId, DEFAULT_LIMIT);
    }

    /**
     * @param companyId the company whose inbox this is, so that a company- or
     *        unit-scoped grant has something to reach when the inbox is somebody
     *        else's. A target naming no company can only be reached by an
     *        installation-wide grant, which would make "may read this team's
     *        inboxes" unexpressible.
     */
    @Transactional(readOnly = true)
    public ApprovalInbox load(PermissionPrincipal actor, String companyId, String accountId,
            int limit) {
        if (!actor.accountId().equals(accountId)) {
            // Today's date, and honestly so: the question is about a person's
            // inbox rather than about any one document, so there is no document
            // date to use instead.
            permissions.check(actor, ApprovalPermissions.DOCUMENT_READ,
                    PermissionTarget.builder()
                            .companyId(companyId)
                            .asOfBusinessDate(LocalDate.now())
                            .description("approval inbox of " + accountId)
                            .build()).orThrow();
        }

        TypedQuery<Object[]> query = entityManager.createQuery(INBOX_JPQL, Object[].class);
        query.setParameter("accountId", accountId);
        query.setParameter("pending", ApprovalStep.StepState.PENDING);
        query.setParameter("cc", ApprovalStepKind.CC);
        // One fetch feeds three lists, so the cap is three pages' worth: a
        // person whose drafted list is full must still get their pending one.
        query.setMaxResults(limit * 3);
        return assemble(accountId, query.getResultList(), limit);
    }

    /**
     * Buckets one query's rows into the three lists and the badge counts.
     *
     * <p>Separated from the query so the rules — what counts as pending on me,
     * what counts as returned, whether a document I drafted and am also copied
     * on appears twice — are testable without a database. The JPQL above is
     * covered by the integration suite against real PostgreSQL; this is covered
     * here.
     *
     * @param rows {@code [document, pendingOnMeCount, copiedToMeCount]}
     */
    static ApprovalInbox assemble(String accountId, List<Object[]> rows, int limit) {
        List<ApprovalDocumentEntity> awaiting = new ArrayList<ApprovalDocumentEntity>();
        List<ApprovalDocumentEntity> drafted = new ArrayList<ApprovalDocumentEntity>();
        List<ApprovalDocumentEntity> copied = new ArrayList<ApprovalDocumentEntity>();
        int returned = 0;
        int inProgress = 0;

        for (Object[] row : rows) {
            ApprovalDocumentEntity document = (ApprovalDocumentEntity) row[0];
            boolean pendingOnMe = count(row[1]) > 0 && document.state().isActive();
            boolean copiedToMe = count(row[2]) > 0;
            boolean mine = accountId.equals(document.drafterAccountId());

            if (pendingOnMe) {
                awaiting.add(document);
            }
            if (mine) {
                drafted.add(document);
                if (document.state() == ApprovalState.RETURNED) {
                    returned++;
                } else if (document.state().isActive()) {
                    inProgress++;
                }
            } else if (copiedToMe) {
                // Being copied on your own document is not news.
                copied.add(document);
            }
        }

        Collections.sort(awaiting, OLDEST_FIRST);
        Collections.sort(drafted, NEWEST_FIRST);
        Collections.sort(copied, NEWEST_FIRST);
        return new ApprovalInbox(accountId, cap(awaiting, limit), cap(drafted, limit),
                cap(copied, limit), returned, inProgress);
    }

    private static List<ApprovalDocumentEntity> cap(List<ApprovalDocumentEntity> all, int limit) {
        return all.size() <= limit ? all : new ArrayList<ApprovalDocumentEntity>(
                all.subList(0, limit));
    }

    private static long count(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /**
     * Business ordering: date first, then offset (ADR 0002).
     *
     * <p>Never the derived absolute timestamp and never the wire string — a
     * {@code 26:00} submission belongs before the next day's {@code -02:00} one,
     * and both string and wall-clock ordering get that backwards. A draft has no
     * submission instant at all and sorts last, since it is waiting on its own
     * author rather than on time.
     */
    private static final Comparator<ApprovalDocumentEntity> OLDEST_FIRST =
            new Comparator<ApprovalDocumentEntity>() {
                @Override
                public int compare(ApprovalDocumentEntity left, ApprovalDocumentEntity right) {
                    BusinessInstant leftAt = left.submittedAt();
                    BusinessInstant rightAt = right.submittedAt();
                    if (leftAt == null || rightAt == null) {
                        return leftAt == rightAt ? 0 : (leftAt == null ? 1 : -1);
                    }
                    int byInstant = BusinessInstant.COMPARATOR.compare(leftAt, rightAt);
                    return byInstant != 0 ? byInstant : left.id().compareTo(right.id());
                }
            };

    private static final Comparator<ApprovalDocumentEntity> NEWEST_FIRST =
            new Comparator<ApprovalDocumentEntity>() {
                @Override
                public int compare(ApprovalDocumentEntity left, ApprovalDocumentEntity right) {
                    return OLDEST_FIRST.compare(right, left);
                }
            };
}
