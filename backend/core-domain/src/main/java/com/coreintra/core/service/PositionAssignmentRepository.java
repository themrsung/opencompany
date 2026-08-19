package com.coreintra.core.service;

import com.coreintra.core.org.Position;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The reads and writes an assignment needs that no existing repository offers.
 *
 * <p>{@code PositionRepository} answers "which positions were live on date D",
 * which is what a permission check needs and nothing more. Assigning a position
 * needs the opposite shape: <em>every</em> row for one employee, closed rows
 * included, because an overlap is decided against history rather than against
 * today, and a closed row is the audit trail that a promotion happened.
 *
 * <p>The 직무 links are written by native statement because
 * {@code position_job_function} is a join table with no entity of its own - it
 * carries no columns beyond the two ids, and giving it an entity would put a
 * lazy collection on {@link Position}, which is exactly the N+1 the org
 * directory was shaped to avoid.
 */
public interface PositionAssignmentRepository extends Repository<Position, String> {

    <S extends Position> S save(S position);

    Optional<Position> findById(String id);

    /** Full history for one employee, oldest first. Closed rows are kept and returned. */
    List<Position> findByEmployeeIdOrderByEffectiveFromAsc(String employeeId);

    /** Positions live in a unit on a date. Asked before a unit is retired. */
    @Query("select p from Position p where p.orgUnitId = :orgUnitId"
            + " and p.effectiveFrom <= :asOf"
            + " and (p.effectiveTo is null or p.effectiveTo > :asOf)")
    List<Position> findLiveInUnit(@Param("orgUnitId") String orgUnitId, @Param("asOf") LocalDate asOf);

    /**
     * Positions holding a rank on a date. Asked before a rank is retired.
     *
     * <p>Retiring a rung somebody is standing on is a quiet change of authority:
     * every grant attached to that rank stops reaching, and nothing in the
     * request that caused it mentions permissions.
     */
    @Query("select p from Position p where p.rankId = :rankId"
            + " and p.effectiveFrom <= :asOf"
            + " and (p.effectiveTo is null or p.effectiveTo > :asOf)")
    List<Position> findLiveWithRank(@Param("rankId") String rankId, @Param("asOf") LocalDate asOf);

    /** How many live positions perform a 직무. Asked before it is retired, for the same reason. */
    @Query(value = "select count(*) from position p"
            + " join position_job_function pj on pj.position_id = p.id"
            + " where pj.job_function_id = :jobFunctionId"
            + " and p.effective_from <= :asOf"
            + " and (p.effective_to is null or p.effective_to > :asOf)", nativeQuery = true)
    long countLiveWithJobFunction(@Param("jobFunctionId") String jobFunctionId,
            @Param("asOf") LocalDate asOf);

    @Modifying
    @Query(value = "insert into position_job_function (position_id, job_function_id)"
            + " values (:positionId, :jobFunctionId)", nativeQuery = true)
    void linkJobFunction(@Param("positionId") String positionId,
            @Param("jobFunctionId") String jobFunctionId);
}
