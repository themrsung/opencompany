package com.coreintra.accounting.persistence;

import com.coreintra.accounting.domain.Entry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The journal.
 *
 * <h2>Ordering is business date first, then offset</h2>
 *
 * <p>Never {@code posted_absolute_ts}, which exists for range scans and deliberately disagrees:
 * an entry at 26:01 on the 30th belongs before one at -03:22 on the 31st, and the absolute
 * timestamps say the opposite (ADR 0002). The order is spelt out in every query here rather than
 * left to a default, because the default would be whatever the planner felt like.
 *
 * <h2>Nothing is deleted</h2>
 *
 * <p>A wrong entry is voided, which hides it from every report and leaves it in the journal. That
 * is why there is no delete method: the journal is the record of what was done, including what was
 * done wrongly.
 */
public interface JournalEntryRepository extends Repository<JournalEntryRow, String> {

    <S extends JournalEntryRow> S save(S row);

    Optional<JournalEntryRow> findById(String id);

    @Query("select e from JournalEntryRow e where e.bookId = :bookId "
            + "order by e.postedAt.businessDate asc, e.postedAt.offsetSeconds asc, e.id asc")
    List<JournalEntryRow> findJournal(@Param("bookId") String bookId);

    /**
     * The reportable set: posted entries only, drafts and voids excluded at the query rather than
     * in each report, so a report added later cannot forget.
     */
    @Query("select e from JournalEntryRow e where e.bookId = :bookId and e.status = :status "
            + "order by e.postedAt.businessDate asc, e.postedAt.offsetSeconds asc, e.id asc")
    List<JournalEntryRow> findByStatus(@Param("bookId") String bookId,
            @Param("status") Entry.EntryStatus status);

    @Query("select e from JournalEntryRow e where e.batchId = :batchId "
            + "order by e.postedAt.businessDate asc, e.postedAt.offsetSeconds asc, e.id asc")
    List<JournalEntryRow> findByBatchId(@Param("batchId") String batchId);
}
