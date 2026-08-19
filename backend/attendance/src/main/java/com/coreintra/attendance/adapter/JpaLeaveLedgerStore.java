package com.coreintra.attendance.adapter;

import com.coreintra.attendance.domain.LeaveLedger;
import com.coreintra.attendance.entity.LeaveTransaction;
import com.coreintra.attendance.repository.LeaveTransactionRepository;
import com.coreintra.attendance.service.LeaveLedgerStore;
import com.coreintra.compat.Immutables;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only persistence for {@code leave_transaction}.
 *
 * <p>There is no update and no delete here because there is none in the port
 * either: a mistake is corrected by posting its reverse. The balance is never
 * stored — {@link LeaveLedger} sums these rows — so it cannot drift from the
 * transactions that explain it.
 *
 * <p>{@link #append} does not check whether the balance can afford the row, and
 * must not: that decision needs the policy and the whole ledger, it belongs to
 * {@code LeaveService}, and it is tested there without a database. An adapter
 * that second-guessed it would be a second set of rules to keep in step.
 */
@Component
public class JpaLeaveLedgerStore implements LeaveLedgerStore {

    private final LeaveTransactionRepository transactions;

    public JpaLeaveLedgerStore(LeaveTransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaveLedger.Transaction> transactionsFor(String employeeId, String policyId) {
        return toDomain(transactions
                .findByEmployeeIdAndPolicyIdOrderByOccurredBusinessDateAscOccurredOffsetSecondsAsc(
                        employeeId, policyId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaveLedger.Transaction> findBySourceDocumentId(String sourceDocumentId) {
        if (sourceDocumentId == null) {
            // Every row written outside an approval shares a null here, so
            // matching on it would return an unrelated crowd and, worse, would
            // make an approval look already-recorded.
            return Immutables.listOf();
        }
        return toDomain(transactions.findBySourceDocumentId(sourceDocumentId));
    }

    @Override
    @Transactional
    public void append(String employeeId, String policyId, LeaveLedger.Transaction transaction) {
        transactions.save(new LeaveTransaction(employeeId, policyId, transaction));
    }

    private List<LeaveLedger.Transaction> toDomain(List<LeaveTransaction> rows) {
        List<LeaveLedger.Transaction> domain = new ArrayList<LeaveLedger.Transaction>();
        for (LeaveTransaction row : rows) {
            domain.add(row.toTransaction());
        }
        return Immutables.copyOf(domain);
    }
}
