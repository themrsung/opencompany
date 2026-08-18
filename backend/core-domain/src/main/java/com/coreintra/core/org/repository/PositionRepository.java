package com.coreintra.core.org.repository;

import com.coreintra.core.org.Position;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PositionRepository extends JpaRepository<Position, String> {

    /**
     * Positions held on a date. {@code effectiveTo} is exclusive.
     *
     * <p>This is the as-of query the permission evaluator depends on: it must
     * return what was true on {@code asOf}, never what is true now.
     */
    @Query("select p from Position p "
            + "where p.employeeId = :employeeId "
            + "and p.effectiveFrom <= :asOf "
            + "and (p.effectiveTo is null or p.effectiveTo > :asOf)")
    List<Position> findActiveOn(@Param("employeeId") String employeeId, @Param("asOf") LocalDate asOf);

    @Query("select p from Position p "
            + "where p.orgUnitId = :orgUnitId "
            + "and p.effectiveFrom <= :asOf "
            + "and (p.effectiveTo is null or p.effectiveTo > :asOf)")
    List<Position> findInUnitOn(@Param("orgUnitId") String orgUnitId, @Param("asOf") LocalDate asOf);

    /**
     * 직무 attached to the given positions.
     *
     * <p>A native query against the join table rather than a JPA association.
     * An association would be a lazy collection on {@link Position}, and
     * initialising it during a permission check is the N+1 this whole read path
     * is shaped to avoid.
     */
    @Query(value = "select distinct job_function_id from position_job_function "
            + "where position_id in (:positionIds)", nativeQuery = true)
    List<String> findJobFunctionIds(@Param("positionIds") Collection<String> positionIds);
}
