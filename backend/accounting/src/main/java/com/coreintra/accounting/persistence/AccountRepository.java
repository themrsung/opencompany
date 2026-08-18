package com.coreintra.accounting.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * The chart of accounts.
 *
 * <p>There is no delete. An account that is no longer used is retired, which hides it from future
 * postings and leaves every historical figure exactly where it was. Deleting one would rewrite
 * prior-period reports, which is the single thing a ledger must never do.
 */
public interface AccountRepository extends Repository<AccountRow, AccountRow.Key> {

    <S extends AccountRow> S save(S row);

    Optional<AccountRow> findById(AccountRow.Key key);

    /** Ordered by code, which is the order an accountant reads a chart in. */
    List<AccountRow> findByBookIdOrderByIdAsc(String bookId);

    List<AccountRow> findByBookIdAndParentId(String bookId, String parentId);
}
