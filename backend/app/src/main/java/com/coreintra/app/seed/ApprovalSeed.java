package com.coreintra.app.seed;

import com.coreintra.approval.domain.ApprovalStep;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.entity.ApprovalStepApproverEntity;
import com.coreintra.approval.entity.ApprovalStepEntity;
import com.coreintra.approval.service.ApprovalActionService;
import com.coreintra.approval.service.ApprovalDocumentService;
import com.coreintra.approval.service.ApprovalDocumentView;
import com.coreintra.approval.service.DraftRequest;
import com.coreintra.approval.service.EmploymentRulesService;
import com.coreintra.app.config.ApprovalWiring;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.runtime.crypto.Digests;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A 결재함 with something in it.
 *
 * <p>An empty inbox demos nothing, and an inbox full of documents in one state demos almost as
 * little. What is staged here is the set of states a person actually meets on a Monday morning:
 * something waiting on them, something they wrote that is still moving, something that came back
 * to them, something finished, and — the one worth the trouble — a document stopped at 공동대표
 * with one of the two required signatures on it, which is the state most likely to be broken by
 * a change and the hardest to picture from a schema.
 *
 * <p>Every document is drafted, submitted and signed by the person whose name is on it, through
 * the same two services the REST layer calls. Nothing is stamped into {@code approval_step} by
 * the seed; the 결재선 is resolved by {@code ApprovalDocumentService} out of the org chart as it
 * stood on the document's own business date.
 */
@Component
@Profile(DemoSeedRunner.SEED_PROFILE)
class ApprovalSeed {

    /** Labels the seed uses to find its own documents again. Not stored on the row. */
    static final String EXPENSE_JULY = "expense-july";
    static final String LEAVE_REQUEST = "leave-request";
    static final String EXPENSE_LARGE = "expense-large";
    static final String PURCHASE_WAITING = "purchase-waiting";
    static final String EXPENSE_RETURNED = "expense-returned";
    static final String RULES_DRAFT = "rules-draft";
    static final String DEMO_ACCOUNT_DRAFT = "demo-account-draft";
    static final String SUBSIDIARY_EXPENSE = "subsidiary-expense";

    private final ApprovalDocumentService documents;
    private final ApprovalActionService actions;
    private final EmploymentRulesService employmentRules;

    ApprovalSeed(ApprovalDocumentService documents, ApprovalActionService actions,
            EmploymentRulesService employmentRules) {
        this.documents = documents;
        this.actions = actions;
        this.employmentRules = employmentRules;
    }

    void run(SeedWorld world) {
        String hq = world.companyId(DemoCompany.HQ_CODE);
        PermissionPrincipal drafter = world.principal(DemoCompany.PROMOTED_EMPLOYEE_NUMBER);
        PermissionPrincipal teamLead = world.principal(DemoCompany.DEMO_EMPLOYEE_NUMBER);
        PermissionPrincipal director = world.principal(DemoCompany.SALES_DIRECTOR_EMPLOYEE_NUMBER);
        PermissionPrincipal junior = world.principal(DemoCompany.JUNIOR_SALES_EMPLOYEE_NUMBER);
        PermissionPrincipal hrLead = world.principal(DemoCompany.HR_LEAD_EMPLOYEE_NUMBER);
        PermissionPrincipal representative = world.principal(DemoCompany.MASTER_EMPLOYEE_NUMBER);

        completedExpense(world, hq, drafter, teamLead, director);
        approvedLeave(world, hq, drafter, teamLead, hrLead);
        partiallyApprovedByRepresentatives(world, hq, drafter, teamLead, director, representative);
        waitingOnTeamLead(world, hq, junior);
        halfWrittenByTheDemoAccount(world, hq, teamLead);
        returnedToDrafter(world, hq, junior, teamLead);
        employmentRulesDraft(world, hq, hrLead);
        subsidiaryExpense(world);
    }

    /** 완결: a small expense, reviewed by the 부장 and approved by the 이사. */
    private void completedExpense(SeedWorld world, String companyId, PermissionPrincipal drafter,
            PermissionPrincipal teamLead, PermissionPrincipal director) {
        LocalDate on = LocalDate.of(2026, 7, 22);
        String documentId = draft(drafter, companyId, DemoCompany.DOC_TYPE_EXPENSE,
                "7월 국내영업팀 거래처 접대비 정산", new BigDecimal("860000"), on);
        ApprovalDocumentView view = submit(drafter, documentId, BusinessInstant.of(on, 18, 40, 0),
                "7월 접대비 12건, 합계 860,000원");
        view = approve(teamLead, view, BusinessInstant.of(on, 19, 5, 0), "확인하였습니다.");
        approve(director, view, BusinessInstant.of(on, 20, 30, 0), "승인합니다.");
        world.putDocument(EXPENSE_JULY, documentId);
    }

    /**
     * 휴가신청서, reviewed by the 부장 and waiting on 인사팀, and the source document for the 휴가
     * attendance record — the attendance module refuses a status that requires approval unless
     * one is named.
     *
     * <p>It stops one step short of complete on purpose, and not for variety. Final approval of
     * a leave request fires the deduction adapter in {@code ApprovalWiring}, which reads the
     * requested days off the document body; the seeded 휴가신청서 body template is §6.8 and is not
     * built yet, so a completed one refuses with "휴가신청서에 본문이 연결되어 있지 않아". Faking a body
     * would be the seed asserting something about the product that is not true.
     */
    private void approvedLeave(SeedWorld world, String companyId, PermissionPrincipal drafter,
            PermissionPrincipal teamLead, PermissionPrincipal hrLead) {
        LocalDate on = LocalDate.of(2026, 7, 20);
        String documentId = draft(drafter, companyId,
                ApprovalWiring.LeaveRequestFields.DOCUMENT_TYPE, "연차 사용 신청 (7월 24일)", null,
                on);
        ApprovalDocumentView view = submit(drafter, documentId, BusinessInstant.of(on, 9, 20, 0),
                "연차 1일, 사유: 개인 사정");
        approve(teamLead, view, BusinessInstant.of(on, 11, 0, 0), "다녀오십시오.");
        world.putDocument(LEAVE_REQUEST, documentId);
    }

    /**
     * The document the demo exists for: 8,000,000원 crosses the template's threshold, so the line
     * picks up a 공동대표 step requiring two of the three registered representatives. One signs.
     * The document sits there, visibly one signature short, which is a state that cannot be
     * reached at all in a company configured 각자대표.
     */
    private void partiallyApprovedByRepresentatives(SeedWorld world, String companyId,
            PermissionPrincipal drafter, PermissionPrincipal teamLead,
            PermissionPrincipal director, PermissionPrincipal representative) {
        LocalDate on = LocalDate.of(2026, 8, 10);
        String documentId = draft(drafter, companyId, DemoCompany.DOC_TYPE_EXPENSE,
                "하반기 영업 전산장비 구매 대금 지급", new BigDecimal("8000000"), on);
        ApprovalDocumentView view = submit(drafter, documentId, BusinessInstant.of(on, 10, 15, 0),
                "노트북 8대 및 도킹스테이션, 합계 8,000,000원");
        view = approve(teamLead, view, BusinessInstant.of(on, 13, 0, 0), "필요한 지출입니다.");
        view = approve(director, view, BusinessInstant.of(on, 16, 45, 0), "본부 예산 내입니다.");
        approve(representative, view, BusinessInstant.of(on, 18, 20, 0),
                "공동대표 1인 승인하였습니다.");
        world.putDocument(EXPENSE_LARGE, documentId);
    }

    /** Waiting on the demo account, so that signing in as 박지훈 lands on something to do. */
    private void waitingOnTeamLead(SeedWorld world, String companyId, PermissionPrincipal junior) {
        LocalDate on = LocalDate.of(2026, 8, 14);
        String documentId = draft(junior, companyId, DemoCompany.DOC_TYPE_PURCHASE,
                "국내영업팀 노트북 5대 구매 품의", new BigDecimal("3200000"), on);
        submit(junior, documentId, BusinessInstant.of(on, 9, 5, 0),
                "영업용 노트북 5대, 대당 640,000원");
        world.putDocument(PURCHASE_WAITING, documentId);
    }

    /**
     * Something of the demo account's own, still being written.
     *
     * <p>Left unsubmitted deliberately. 박지훈 is the 부장 his own team's line reviews at, so a
     * document he submitted would resolve its first step to himself — legal in a small company,
     * confusing on a demo screen, and not what anybody wants to explain in the first five
     * minutes. Unsubmitted, it shows the 기안함 for what it is.
     */
    private void halfWrittenByTheDemoAccount(SeedWorld world, String companyId,
            PermissionPrincipal teamLead) {
        String documentId = draft(teamLead, companyId, DemoCompany.DOC_TYPE_PURCHASE,
                "9월 국내영업팀 판촉물 제작 품의 (작성 중)", new BigDecimal("1750000"),
                LocalDate.of(2026, 8, 17));
        world.putDocument(DEMO_ACCOUNT_DRAFT, documentId);
    }

    /** 반려: the state a drafter's own inbox has to be able to show. */
    private void returnedToDrafter(SeedWorld world, String companyId, PermissionPrincipal junior,
            PermissionPrincipal teamLead) {
        LocalDate on = LocalDate.of(2026, 8, 12);
        String documentId = draft(junior, companyId, DemoCompany.DOC_TYPE_EXPENSE,
                "8월 전시회 출장 경비 정산", new BigDecimal("1240000"), on);
        ApprovalDocumentView view = submit(junior, documentId, BusinessInstant.of(on, 17, 30, 0),
                "출장 경비 9건, 합계 1,240,000원");
        ApprovalStepEntity pending = pendingStepFor(view, teamLead.accountId());
        actions.returnToDrafter(teamLead, documentId, pending.id(), BusinessInstant.of(on, 18, 0, 0),
                "숙박비 영수증이 빠져 있습니다. 첨부하여 다시 올려 주십시오.");
        world.putDocument(EXPENSE_RETURNED, documentId);
    }

    /**
     * A 취업규칙 개정 left in draft, through the service that owns 취업규칙 rather than as a
     * generic document — so the demo's outbox contains the one document type that cannot be
     * published without representative approval.
     */
    private void employmentRulesDraft(SeedWorld world, String companyId,
            PermissionPrincipal hrLead) {
        ApprovalDocumentEntity proposal = employmentRules.proposeChange(hrLead, companyId,
                "취업규칙 개정(안) — 유연근무제 도입", LocalDate.of(2026, 8, 17));
        world.putDocument(RULES_DRAFT, proposal.id());
    }

    /** The subsidiary is 각자대표: one representative completes the same shape of line alone. */
    private void subsidiaryExpense(SeedWorld world) {
        String companyId = world.companyId(DemoCompany.SUBSIDIARY_CODE);
        PermissionPrincipal drafter = world.principal("21000003");
        PermissionPrincipal representative = world.principal("21000001");
        LocalDate on = LocalDate.of(2026, 8, 11);
        String documentId = draft(drafter, companyId, DemoCompany.DOC_TYPE_EXPENSE,
                "생산1팀 안전장비 구매 대금", new BigDecimal("2400000"), on);
        ApprovalDocumentView view = submit(drafter, documentId, BusinessInstant.of(on, 11, 0, 0),
                "안전화 20켤레 및 보호구, 합계 2,400,000원");
        approve(representative, view, BusinessInstant.of(on, 15, 30, 0), "승인합니다.");
        world.putDocument(SUBSIDIARY_EXPENSE, documentId);
    }

    private String draft(PermissionPrincipal actor, String companyId, String documentType,
            String title, BigDecimal amount, LocalDate businessDate) {
        DraftRequest request = new DraftRequest(companyId, documentType, title, amount,
                amount == null ? null : DemoCompany.BASE_CURRENCY_CODE, businessDate);
        return documents.draft(actor, request).id();
    }

    private ApprovalDocumentView submit(PermissionPrincipal actor, String documentId,
            BusinessInstant at, String body) {
        // The body digest is the documents module's business; until a body exists, a digest of
        // the summary line is honest about what was signed for and keeps the snapshot hash
        // meaningful rather than null.
        return documents.submit(actor, documentId, at, Digests.sha256Hex(body));
    }

    private ApprovalDocumentView approve(PermissionPrincipal actor, ApprovalDocumentView view,
            BusinessInstant at, String comment) {
        ApprovalStepEntity pending = pendingStepFor(view, actor.accountId());
        return actions.approve(actor, view.document().id(), pending.id(), at, comment);
    }

    /**
     * The step this person is being asked to act on.
     *
     * <p>Resolved from the document rather than assumed, because the line is built by the
     * approval module out of the org chart: if a role stops resolving to the person the demo
     * expects, this throws with their name in it instead of the seed silently signing on the
     * wrong step.
     */
    private ApprovalStepEntity pendingStepFor(ApprovalDocumentView view, String accountId) {
        for (ApprovalStepEntity step : view.steps()) {
            if (step.state() != ApprovalStep.StepState.PENDING) {
                continue;
            }
            for (ApprovalStepApproverEntity approver : view.approversOf(step.id())) {
                if (accountId.equals(approver.accountId())) {
                    return step;
                }
            }
        }
        throw new IllegalStateException("문서 " + view.document().title() + "의 결재선에서 계정 "
                + accountId + "이(가) 결재할 단계를 찾지 못하였습니다. 결재선 서식과 조직도를 확인해 주십시오.");
    }
}
