package com.coreintra.approval.service;

/**
 * A consequence of an approval that must happen <em>as part of</em> the approval.
 *
 * <h2>Why this is not a notification, and not an event bus</h2>
 *
 * <p>Approving a 휴가 request has to write the leave balance transaction. If
 * that write is a separate step it can fail on its own, and then the employee
 * has an approved leave request and an unchanged balance — a discrepancy nobody
 * will find until they dispute it months later. So this is not "tell interested
 * parties afterwards": a listener that throws takes the approval down with it.
 *
 * <p>Compare {@link com.coreintra.approval.notify.Notifier}, which is the exact
 * opposite: a notification channel being down must <em>never</em> roll back the
 * approval it was reporting.
 *
 * <h2>The two guarantees</h2>
 *
 * <ol>
 *   <li>Listeners run inside the transaction that records the action, so a
 *       failure rolls the whole approval back — nothing is written.</li>
 *   <li>Listeners run <em>before</em> the approval is persisted, so the same
 *       holds even where no transaction manager is in play. This is why a
 *       listener is handed an {@link ApprovalOutcome} rather than being invited
 *       to re-read the document: at the moment it runs, the database still says
 *       the document is in progress.</li>
 * </ol>
 *
 * <p>Implementations must be idempotent on {@link ApprovalOutcome#documentId()}.
 * A rolled-back transaction that is retried will call them again, and a leave
 * request that deducts twice is worse than one that fails loudly.
 *
 * <h2>Where the 휴가 implementation lives</h2>
 *
 * <p>Not here, and not in {@code attendance}: neither module depends on the
 * other, and neither should — approval has no business knowing what leave is,
 * and leave should not have to know how approval works. The adapter therefore
 * belongs in the module that already depends on both, and is about this long:
 *
 * <pre>{@code
 * @Component
 * class LeaveRequestEffect implements ApprovalOutcomeListener {
 *     private final LeaveService leave;
 *     private final LeaveRequestDetails details;   // reads the document body
 *
 *     public boolean appliesTo(String documentType) {
 *         return "LEAVE_REQUEST".equals(documentType);
 *     }
 *
 *     public void documentSettled(ApprovalOutcome outcome) {
 *         LeaveRequest request = details.of(outcome.documentId());
 *         if (outcome.state() == ApprovalState.APPROVED) {
 *             leave.recordApprovedLeave(new ApprovedLeave(request.employeeId(),
 *                     request.policyId(), request.days(), outcome.decidedAt(),
 *                     outcome.documentId(), outcome.actorAccountId(), request.reason()));
 *         } else {
 *             leave.cancelApprovedLeave(request.employeeId(), request.policyId(),
 *                     outcome.documentId(), outcome.decidedAt(), null,
 *                     outcome.actorAccountId());
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>Both calls it makes are idempotent, and both refuse rather than write a
 * half-answer, so the guarantees above hold through the adapter unchanged.
 */
public interface ApprovalOutcomeListener {

    /**
     * True when this listener has anything to do with this document type.
     *
     * <p>Asked first so the common case — a document type nobody has registered
     * an effect for — costs a string comparison.
     */
    boolean appliesTo(String documentType);

    /**
     * The document reached a terminal state.
     *
     * <p>Called for {@code APPROVED}, {@code RETURNED} and {@code RECALLED}: a
     * 휴가 request that is recalled after its balance was written has to give
     * the days back, and only the listener knows that.
     *
     * @throws RuntimeException to refuse the approval outright; the message
     *         reaches the approver, so make it say what to do
     */
    void documentSettled(ApprovalOutcome outcome);
}
