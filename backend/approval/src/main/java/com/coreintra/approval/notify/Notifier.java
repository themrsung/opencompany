package com.coreintra.approval.notify;

/**
 * Delivers a notification. Pluggable, because installations differ.
 *
 * <p>In-app notification is always available. Mail is only present if the
 * client wired a mail system, and a client module may register its own channel
 * — Kakao Work, Slack, an SMS gateway — through this same interface.
 *
 * <p>{@link #isAvailable()} exists so callers hide a channel rather than
 * offering one that silently drops messages. An approval notification that
 * never arrives is worse than none, because the approver believes they would
 * have been told.
 */
public interface Notifier {

    /** Stable identifier: {@code in-app}, {@code email}, or a module's own. */
    String channel();

    /** False when this channel is not configured on this installation. */
    boolean isAvailable();

    /**
     * Delivers, best-effort.
     *
     * <p>Implementations must not throw for ordinary delivery failure: a
     * notification channel being down must never roll back the approval it was
     * reporting. Log and move on.
     */
    void notify(ApprovalNotification notification);
}
