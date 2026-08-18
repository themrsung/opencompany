package com.coreintra.accounting.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Postings, loaded per entry or per page of the journal.
 *
 * <p>{@link #findByEntryIdInOrderByEntryIdAscPositionAsc} is why loading a month of entries is two
 * queries rather than one per entry: reports read the whole period, and a query per entry turns a
 * trial balance into a thousand round trips.
 */
public interface JournalPostingRepository extends Repository<JournalPostingRow, String> {

    <S extends JournalPostingRow> S save(S row);

    List<JournalPostingRow> findByEntryIdOrderByPositionAsc(String entryId);

    List<JournalPostingRow> findByEntryIdInOrderByEntryIdAscPositionAsc(Collection<String> entryIds);

    /**
     * Whether an account has ever been posted to. Asked before an account is given a child: the
     * database refuses that outright, and this is how the refusal gets a sentence a person can act
     * on instead of a foreign-key violation.
     */
    long countByBookIdAndAccountId(String bookId, String accountId);

    /**
     * Removes the current lines of an entry so a correction can write the new ones.
     *
     * <p>The only delete in the module, and it is not a hard delete of history: the entry, its
     * numbered revision and the pre-state snapshot of exactly these lines all survive. What goes
     * is the superseded version of the current state, which the revision already describes.
     *
     * <p>Written as a bulk statement rather than a derived delete on purpose. A derived delete
     * marks entities for removal and Hibernate orders inserts before deletes when it flushes, so
     * the replacement lines would collide with the old ones on {@code (entry_id, position)} and a
     * correction would fail on a unique constraint that has nothing to do with the correction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from JournalPostingRow p where p.entryId = :entryId")
    void deleteByEntryId(@Param("entryId") String entryId);
}
