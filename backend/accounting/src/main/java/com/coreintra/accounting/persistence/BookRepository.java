package com.coreintra.accounting.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Books, and only the four things the module actually does with them.
 *
 * <p>Every repository here extends {@link Repository} rather than {@code JpaRepository}. The
 * module's whole surface is then visible in one screen per aggregate, and nothing can quietly
 * start using {@code deleteAll} on a ledger.
 */
public interface BookRepository extends Repository<BookRow, String> {

    <S extends BookRow> S save(S row);

    Optional<BookRow> findById(String id);

    List<BookRow> findByCompanyIdOrderByNameAsc(String companyId);
}
