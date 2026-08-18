package com.coreintra.attendance.repository;

import com.coreintra.attendance.entity.LeaveTransaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveTransactionRepository extends JpaRepository<LeaveTransaction, String> {

    /**
     * One employee's rows under one policy, in business order.
     *
     * <p>Business date then offset, never the wire string and never a derived
     * absolute timestamp: a transaction at 26:00 belongs before the next day's
     * -02:00 one, and sorting any other way makes the running balance wrong at
     * exactly the boundary the 72-hour day exists for.
     */
    List<LeaveTransaction> findByEmployeeIdAndPolicyIdOrderByOccurredBusinessDateAscOccurredOffsetSecondsAsc(
            String employeeId, String policyId);

    /**
     * Everything written on the authority of one 결재 document.
     *
     * <p>The idempotency key for an approval: a retried approval must find the
     * {@code USE} row it already wrote instead of writing a second one.
     */
    List<LeaveTransaction> findBySourceDocumentId(String sourceDocumentId);
}
