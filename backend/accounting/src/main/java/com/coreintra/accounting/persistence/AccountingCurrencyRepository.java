package com.coreintra.accounting.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Units of account, scoped to a book. */
public interface AccountingCurrencyRepository extends Repository<AccountingCurrencyRow, String> {

    <S extends AccountingCurrencyRow> S save(S row);

    Optional<AccountingCurrencyRow> findByBookIdAndCode(String bookId, String code);

    /**
     * Retired currencies included: a report over old entries still has to name the unit those
     * amounts were in, and refusing to load it would leave the figures unlabelled.
     */
    List<AccountingCurrencyRow> findByBookIdOrderByCodeAsc(String bookId);
}
