package com.coreintra.attendance.service;

import com.coreintra.attendance.domain.LeaveLedger;
import java.util.List;

/**
 * Append-only persistence for the leave ledger.
 *
 * <p>A port because {@code leave_transaction} (V5) has no JPA entity yet. Its
 * shape is mirrored exactly by {@link LeaveLedger.Transaction}, so an adapter is
 * a mapping.
 *
 * <h2>There is no update and no delete</h2>
 *
 * <p>Deliberately absent from this interface. A mistake in someone's balance is
 * corrected by posting its reverse — an {@code ADJUSTMENT} or a
 * {@code CANCELLATION} with a reason — so the record shows both the error and
 * the fix. Leave is money to the person holding it, and when they dispute the
 * number, and they will, only the rows can say where it came from.
 *
 * <p>The balance itself is never stored: it is
 * {@link LeaveLedger#balanceOn(java.time.LocalDate)} over these rows, so it
 * cannot drift from the transactions that explain it.
 */
public interface LeaveLedgerStore {

    /** Every transaction for one employee under one policy. */
    List<LeaveLedger.Transaction> transactionsFor(String employeeId, String policyId);

    /**
     * Transactions written on the authority of one 결재 document.
     *
     * <p>The idempotency key for {@link LeaveService#recordApprovedLeave}: an
     * approval that is retried after a rolled-back transaction must not deduct
     * twice.
     */
    List<LeaveLedger.Transaction> findBySourceDocumentId(String sourceDocumentId);

    /** Appends one row. Never called with an id that already exists. */
    void append(String employeeId, String policyId, LeaveLedger.Transaction transaction);
}
