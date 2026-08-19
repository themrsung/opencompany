package com.coreintra.accounting.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * The correction trail. Append-only: there is no update and no delete, because a trail that can be
 * edited is not a trail.
 */
public interface JournalEntryRevisionRepository
        extends Repository<JournalEntryRevisionRow, String> {

    <S extends JournalEntryRevisionRow> S save(S row);

    List<JournalEntryRevisionRow> findByEntryIdOrderByNumberAsc(String entryId);

    List<JournalEntryRevisionRow> findByEntryIdInOrderByEntryIdAscNumberAsc(
            Collection<String> entryIds);
}
