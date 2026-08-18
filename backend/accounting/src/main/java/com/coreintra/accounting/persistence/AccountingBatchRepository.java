package com.coreintra.accounting.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Batches, which stay first-class after the transaction that wrote them.
 *
 * <p>{@link #findByBookIdAndKind} is what the income statement uses to find the closing batches it
 * must leave out. That lookup is a query rather than a flag copied onto each entry, so a batch
 * that turns out to have been the wrong kind is corrected in one row instead of ten thousand.
 */
public interface AccountingBatchRepository extends Repository<AccountingBatchRow, String> {

    <S extends AccountingBatchRow> S save(S row);

    Optional<AccountingBatchRow> findById(String id);

    List<AccountingBatchRow> findByBookIdOrderByCreatedAtDesc(String bookId);

    List<AccountingBatchRow> findByBookIdAndKind(String bookId, String kind);
}
