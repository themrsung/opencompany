package com.coreintra.approval.service;

import com.coreintra.core.permission.PermissionKey;

/**
 * The permission keys this module checks.
 *
 * <h2>There is deliberately no {@code approval.document:act}</h2>
 *
 * <p>Who may approve a step is decided by the line, not by a grant. The role
 * expressions were resolved to people at submission and snapshotted onto the
 * document; that resolved set <em>is</em> the authorisation, and
 * {@link com.coreintra.approval.domain.ApprovalStep#mayAct(String)} is the gate.
 *
 * <p>A separate permission would be wrong in both directions. Granting it to
 * someone outside the resolved set would let them sign a step they were never
 * routed — which is the corruption the snapshot exists to prevent. Requiring it
 * <em>in addition</em> would let an administrator who forgot a grant silently
 * lock out an approver the line legitimately routed to, and the document would
 * stall with no honest explanation to give the user.
 *
 * <p>Reading a document is a different question, and that one is a permission:
 * an approver may act on what reached them, but only a reader with
 * {@link #DOCUMENT_READ} may browse someone else's inbox.
 */
public final class ApprovalPermissions {

    /** Seeing a document, its line, and its trail. */
    public static final PermissionKey DOCUMENT_READ =
            PermissionKey.of("approval.document", "read");

    /** Drafting, editing a draft, submitting, and recalling. */
    public static final PermissionKey DOCUMENT_WRITE =
            PermissionKey.of("approval.document", "write");

    /** Reading 취업규칙. Employees need this for their own company. */
    public static final PermissionKey RULES_READ = PermissionKey.of("hr.rules", "read");

    /**
     * Proposing a change to 취업규칙.
     *
     * <p>This authorises <em>drafting</em> the change, never enacting it. The
     * enactment gate is 대표자 결재 and lives in
     * {@link com.coreintra.approval.rules.EmploymentRules#publish}, where no
     * grant and no master account can reach it.
     */
    public static final PermissionKey RULES_WRITE = PermissionKey.of("hr.rules", "write");

    private ApprovalPermissions() {
    }
}
