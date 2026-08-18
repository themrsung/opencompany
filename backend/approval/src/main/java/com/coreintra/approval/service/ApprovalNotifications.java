package com.coreintra.approval.service;

import com.coreintra.approval.notify.ApprovalNotification;
import com.coreintra.approval.notify.Notifier;
import com.coreintra.compat.Immutables;
import java.util.List;

/**
 * Fans a notification out over every configured channel.
 *
 * <h2>Delivery never fails an approval</h2>
 *
 * <p>The opposite of {@link ApprovalOutcomeListener}, and deliberately so. A
 * notification is a courtesy; the approval is the fact. If the mail server is
 * unreachable, the 부장's signature still stands, so a throwing notifier is
 * swallowed here rather than being allowed to roll the transaction back.
 *
 * <p>Unavailable channels are skipped instead of being handed a message they
 * will drop: an installation with no mail system wired should send no mail, not
 * pretend to. {@link Notifier#isAvailable()} exists for exactly that question.
 */
final class ApprovalNotifications {

    private final List<Notifier> notifiers;

    ApprovalNotifications(List<Notifier> notifiers) {
        this.notifiers = notifiers == null
                ? Immutables.<Notifier>listOf()
                : Immutables.copyOf(notifiers);
    }

    void send(ApprovalNotification notification) {
        if (notification == null || notification.recipientAccountId() == null) {
            return;
        }
        for (int i = 0; i < notifiers.size(); i++) {
            Notifier notifier = notifiers.get(i);
            try {
                if (notifier.isAvailable()) {
                    notifier.notify(notification);
                }
            } catch (RuntimeException delivery) {
                // The SPI contract says implementations must not throw for
                // ordinary delivery failure. This catch is for the ones that do
                // anyway: one badly behaved channel must not stop the others,
                // and must certainly not undo the approval being reported.
                continue;
            }
        }
    }
}
