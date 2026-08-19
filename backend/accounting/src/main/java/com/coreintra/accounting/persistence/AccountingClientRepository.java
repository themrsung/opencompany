package com.coreintra.accounting.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 거래처, the sub-ledger dimension that receivables ageing is grouped by. */
public interface AccountingClientRepository extends Repository<AccountingClientRow, String> {

    <S extends AccountingClientRow> S save(S row);

    Optional<AccountingClientRow> findById(String id);

    List<AccountingClientRow> findByBookIdOrderByNameAsc(String bookId);
}
