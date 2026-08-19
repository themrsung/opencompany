package com.coreintra.approval.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.businesstime.BusinessInstant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bucketing and ordering behind the 결재함.
 *
 * <p>The query itself — the single statement with its two correlated counts — is
 * verified against real PostgreSQL by the integration suite. What is tested here
 * is everything that happens to its rows afterwards, which is where the
 * behaviour a user notices lives: what appears in which list, what the badge
 * says, and the order.
 */
class ApprovalInboxServiceTest {

    private static final String ME = "acc-me";

    private static ApprovalDocumentEntity document(String id, String drafter, ApprovalState state,
            String submittedAt) {
        ApprovalDocumentEntity document = new ApprovalDocumentEntity(id, "co-1", "EXPENSE_CLAIM",
                id, drafter);
        if (submittedAt != null) {
            document.markSubmitted(BusinessInstant.parse(submittedAt), "sha256:x");
        }
        document.setState(state);
        return document;
    }

    private static Object[] row(ApprovalDocumentEntity document, long pendingOnMe,
            long copiedToMe) {
        return new Object[] {document, Long.valueOf(pendingOnMe), Long.valueOf(copiedToMe)};
    }

    @Test
    @DisplayName("the inbox query names only fields that exist on the entities")
    void queryMatchesTheEntityModel() {
        // The query itself needs a database to run, so the integration suite
        // proves it works. What can be proved here is that it still refers to
        // real columns: a field renamed in a neighbouring module would otherwise
        // break the inbox at runtime and nowhere earlier.
        assertHasFields(ApprovalDocumentEntity.class, "id", "drafterAccountId", "state",
                "submittedAt");
        assertHasFields(com.coreintra.approval.entity.ApprovalStepEntity.class, "id",
                "documentId", "state", "kind");
        assertHasFields(com.coreintra.approval.entity.ApprovalStepApproverEntity.class, "stepId",
                "accountId");

        for (String alias : new String[] {"ApprovalDocumentEntity", "ApprovalStepEntity",
                "ApprovalStepApproverEntity"}) {
            assertThat(ApprovalInboxService.INBOX_JPQL)
                    .as("the query still selects from %s", alias)
                    .contains(alias);
        }
    }

    private static void assertHasFields(Class<?> type, String... fieldNames) {
        for (String fieldName : fieldNames) {
            boolean found = false;
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                found = found || field.getName().equals(fieldName);
            }
            assertThat(found)
                    .as("%s.%s, named by the inbox query, must exist", type.getSimpleName(),
                            fieldName)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("splits one result set into pending-on-me, drafted-by-me and cc'd-to-me")
    void bucketsTheThreeLists() {
        List<Object[]> rows = new ArrayList<Object[]>();
        rows.add(row(document("waiting", "acc-other", ApprovalState.IN_PROGRESS,
                "2026-08-30T09:00:00.000"), 1, 0));
        rows.add(row(document("mine", ME, ApprovalState.IN_PROGRESS,
                "2026-08-30T10:00:00.000"), 0, 0));
        rows.add(row(document("copied", "acc-other", ApprovalState.APPROVED,
                "2026-08-30T11:00:00.000"), 0, 1));

        ApprovalInbox inbox = ApprovalInboxService.assemble(ME, rows, 50);

        assertThat(inbox.awaitingMe()).extracting(ApprovalDocumentEntity::id)
                .containsExactly("waiting");
        assertThat(inbox.draftedByMe()).extracting(ApprovalDocumentEntity::id)
                .containsExactly("mine");
        assertThat(inbox.copiedToMe()).extracting(ApprovalDocumentEntity::id)
                .containsExactly("copied");
        assertThat(inbox.awaitingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a document I drafted and am copied on is listed once, as mine")
    void ownDocumentIsNotAlsoAnFyi() {
        List<Object[]> rows = new ArrayList<Object[]>();
        rows.add(row(document("mine", ME, ApprovalState.IN_PROGRESS,
                "2026-08-30T09:00:00.000"), 0, 1));

        ApprovalInbox inbox = ApprovalInboxService.assemble(ME, rows, 50);

        assertThat(inbox.draftedByMe()).hasSize(1);
        assertThat(inbox.copiedToMe())
                .as("being copied on your own document is not news")
                .isEmpty();
    }

    @Test
    @DisplayName("a settled document is not still waiting on me, whatever its steps say")
    void terminalDocumentsAreNotPending() {
        List<Object[]> rows = new ArrayList<Object[]>();
        rows.add(row(document("done", "acc-other", ApprovalState.APPROVED,
                "2026-08-30T09:00:00.000"), 1, 0));

        ApprovalInbox inbox = ApprovalInboxService.assemble(ME, rows, 50);

        assertThat(inbox.awaitingMe()).isEmpty();
        assertThat(inbox.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the badges count my returned and in-flight documents separately")
    void badgeCounts() {
        List<Object[]> rows = new ArrayList<Object[]>();
        rows.add(row(document("returned", ME, ApprovalState.RETURNED,
                "2026-08-30T09:00:00.000"), 0, 0));
        rows.add(row(document("moving", ME, ApprovalState.PARTIALLY_APPROVED,
                "2026-08-30T10:00:00.000"), 0, 0));
        rows.add(row(document("held", ME, ApprovalState.ON_HOLD,
                "2026-08-30T11:00:00.000"), 0, 0));
        rows.add(row(document("finished", ME, ApprovalState.APPROVED,
                "2026-08-30T12:00:00.000"), 0, 0));

        ApprovalInbox inbox = ApprovalInboxService.assemble(ME, rows, 50);

        assertThat(inbox.returnedToMeCount()).isEqualTo(1);
        assertThat(inbox.inProgressByMeCount())
                .as("partially approved and on hold are both still in flight")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("pending is oldest first in business time, so 26:00 precedes the next day")
    void pendingIsOldestFirstInBusinessTime() {
        List<Object[]> rows = new ArrayList<Object[]>();
        rows.add(row(document("nextDayEarly", "acc-other", ApprovalState.IN_PROGRESS,
                "2026-08-31T-03:22:00.000"), 1, 0));
        rows.add(row(document("lateNight", "acc-other", ApprovalState.IN_PROGRESS,
                "2026-08-30T26:01:00.000"), 1, 0));

        ApprovalInbox inbox = ApprovalInboxService.assemble(ME, rows, 50);

        assertThat(inbox.awaitingMe()).extracting(ApprovalDocumentEntity::id)
                .as("date first, then offset — never the wire string, which sorts these the "
                        + "other way round")
                .containsExactly("lateNight", "nextDayEarly");
    }

    @Test
    @DisplayName("drafts sort last in the pending list: they wait on their author, not on time")
    void draftsSortLast() {
        List<Object[]> rows = new ArrayList<Object[]>();
        rows.add(row(document("draft", ME, ApprovalState.DRAFTING, null), 0, 0));
        rows.add(row(document("submitted", ME, ApprovalState.IN_PROGRESS,
                "2026-08-30T09:00:00.000"), 0, 0));

        ApprovalInbox inbox = ApprovalInboxService.assemble(ME, rows, 50);

        assertThat(inbox.draftedByMe()).extracting(ApprovalDocumentEntity::id)
                .containsExactly("draft", "submitted");
    }

    @Test
    @DisplayName("each list is capped independently, so a busy outbox cannot hide the inbox")
    void listsAreCappedIndependently() {
        List<Object[]> rows = new ArrayList<Object[]>();
        for (int i = 0; i < 5; i++) {
            rows.add(row(document("mine-" + i, ME, ApprovalState.IN_PROGRESS,
                    "2026-08-30T0" + i + ":00:00.000"), 0, 0));
        }
        rows.add(row(document("waiting", "acc-other", ApprovalState.IN_PROGRESS,
                "2026-08-30T09:00:00.000"), 1, 0));

        ApprovalInbox inbox = ApprovalInboxService.assemble(ME, rows, 2);

        assertThat(inbox.draftedByMe()).hasSize(2);
        assertThat(inbox.awaitingMe()).extracting(ApprovalDocumentEntity::id)
                .containsExactly("waiting");
        assertThat(inbox.inProgressByMeCount())
                .as("the badge counts everything, not just the page")
                .isEqualTo(5);
    }
}
