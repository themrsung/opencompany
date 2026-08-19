package com.coreintra.attendance.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.attendance.domain.LeaveLedger;
import com.coreintra.attendance.entity.LeaveTransaction;
import com.coreintra.attendance.repository.LeaveTransactionRepository;
import com.coreintra.businesstime.BusinessInstant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JpaLeaveLedgerStoreTest {

    private final FakeTransactions rows = new FakeTransactions();
    private final JpaLeaveLedgerStore store = new JpaLeaveLedgerStore(rows);

    @Test
    @DisplayName("a row survives the round trip with its business instant intact")
    void roundTripsARow() {
        // 26:00 is a real time on this business day. A row that came back as
        // 02:00 the next day would put the deduction on the wrong day and, for a
        // shift worker, in the wrong month.
        BusinessInstant lateShift = BusinessInstant.of(LocalDate.of(2026, 8, 18), 26 * 3600);
        store.append("emp-1", "pol-1", new LeaveLedger.Transaction("t1",
                LeaveLedger.TransactionKind.USE, new BigDecimal("1.5"), lateShift, null, null,
                "여름 휴가", "doc-1", "acc-1"));

        List<LeaveLedger.Transaction> found = store.transactionsFor("emp-1", "pol-1");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).occurredAt().businessDate()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(found.get(0).occurredAt().offsetSeconds()).isEqualTo(26 * 3600);
        assertThat(found.get(0).days()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(found.get(0).sourceDocumentId()).isEqualTo("doc-1");
    }

    @Test
    @DisplayName("rows are read back by the document that authorised them")
    void findsByTheAuthorisingDocument() {
        store.append("emp-1", "pol-1", use("t1", "doc-1"));
        store.append("emp-1", "pol-1", use("t2", "doc-2"));

        assertThat(store.findBySourceDocumentId("doc-2")).hasSize(1);
        assertThat(store.findBySourceDocumentId("doc-2").get(0).id()).isEqualTo("t2");
    }

    @Test
    @DisplayName("a null document id matches nothing, rather than every manual row")
    void nullDocumentIdMatchesNothing() {
        // Every grant, expiry and adjustment shares a null here. Matching on it
        // would make an approval look as though it had already been recorded,
        // and the deduction would silently never happen.
        store.append("emp-1", "pol-1", new LeaveLedger.Transaction("t1",
                LeaveLedger.TransactionKind.GRANT, new BigDecimal("15"), instant(), null, null,
                null, null, "acc-1"));

        assertThat(store.findBySourceDocumentId(null)).isEmpty();
    }

    @Test
    @DisplayName("one employee's ledger under one policy excludes another's")
    void ledgersAreScopedToEmployeeAndPolicy() {
        store.append("emp-1", "pol-1", use("t1", "doc-1"));
        store.append("emp-2", "pol-1", use("t2", "doc-2"));
        store.append("emp-1", "pol-2", use("t3", "doc-3"));

        assertThat(store.transactionsFor("emp-1", "pol-1")).hasSize(1);
        assertThat(store.transactionsFor("emp-1", "pol-1").get(0).id()).isEqualTo("t1");
    }

    private static LeaveLedger.Transaction use(String id, String documentId) {
        return new LeaveLedger.Transaction(id, LeaveLedger.TransactionKind.USE, BigDecimal.ONE,
                instant(), null, null, null, documentId, "acc-1");
    }

    private static BusinessInstant instant() {
        return BusinessInstant.of(LocalDate.of(2026, 8, 18), 9 * 3600);
    }

    private static final class FakeTransactions extends FakeRepository<LeaveTransaction, String>
            implements LeaveTransactionRepository {

        @Override
        String idOf(LeaveTransaction entity) {
            return entity.id();
        }

        @Override
        public List<LeaveTransaction>
                findByEmployeeIdAndPolicyIdOrderByOccurredBusinessDateAscOccurredOffsetSecondsAsc(
                        String employeeId, String policyId) {
            List<LeaveTransaction> found = new ArrayList<LeaveTransaction>();
            for (LeaveTransaction row : all()) {
                if (row.employeeId().equals(employeeId) && policyId.equals(row.policyId())) {
                    found.add(row);
                }
            }
            return found;
        }

        @Override
        public List<LeaveTransaction> findBySourceDocumentId(String sourceDocumentId) {
            List<LeaveTransaction> found = new ArrayList<LeaveTransaction>();
            for (LeaveTransaction row : all()) {
                if (sourceDocumentId != null && sourceDocumentId.equals(row.sourceDocumentId())) {
                    found.add(row);
                }
            }
            return found;
        }
    }
}
