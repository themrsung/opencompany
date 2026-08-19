package com.coreintra.core.service;

import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seniority ladder, entirely as the client defines it.
 *
 * <p>No Korean title is special-cased anywhere in here. 사원 / 대리 / 과장 / 차장 /
 * 부장 ships as seed data, and a client who calls their rungs something else, or
 * has eleven of them, or has three, is not working around anything. The only
 * thing the system reads is {@link Rank#seniority}, and the only thing that reads
 * <em>that</em> is a client-written approval rule of the form "the 상급자 of the
 * drafter's unit".
 *
 * <p>{@link Rank#isRepresentative} is likewise a flag, not a title match: whether
 * a rung counts as 대표이사 for representation purposes is a decision a client
 * makes about their own ladder, and a company may have several.
 */
@Service
public class RankService {

    private final RankCatalogRepository ranks;
    private final PositionAssignmentRepository positions;
    private final PermissionEvaluator evaluator;

    /**
     * The gap left between rungs by a reorder.
     *
     * <p>Seniority is deliberately not contiguous. Leaving room means the next
     * "we need a 수석 between 과장 and 차장" is one insert rather than a renumber of
     * everyone, and a renumber of everyone is exactly the operation that has to be
     * atomic to be safe.
     */
    static final int SENIORITY_STEP = 10;

    public RankService(RankCatalogRepository ranks, PositionAssignmentRepository positions,
            PermissionEvaluator evaluator) {
        if (ranks == null) {
            throw new NullPointerException("ranks");
        }
        if (positions == null) {
            throw new NullPointerException("positions");
        }
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        this.ranks = ranks;
        this.positions = positions;
        this.evaluator = evaluator;
    }

    /** The ladder, most senior first. */
    @Transactional(readOnly = true)
    public List<Rank> list(PermissionPrincipal caller, String companyId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        String company = Arguments.required(companyId, "companyId");
        evaluator.check(caller, OrgPermissions.RANK_READ, companyTarget(company, businessDate)).orThrow();
        return Immutables.copyOf(ranks.findByCompanyIdOrderBySeniorityDesc(company));
    }

    @Transactional
    public Rank create(PermissionPrincipal caller, String companyId, String code, String labelKo, String labelEn,
            int seniority, boolean representative, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        String company = Arguments.required(companyId, "companyId");
        evaluator.check(caller, OrgPermissions.RANK_CREATE, companyTarget(company, businessDate)).orThrow();

        String cleanCode = Arguments.required(code, "code");
        String cleanLabel = Arguments.required(labelKo, "labelKo");
        for (Rank existing : ranks.findByCompanyIdOrderBySeniorityDesc(company)) {
            if (existing.code().equals(cleanCode)) {
                throw new IllegalArgumentException("rank code is already in use in this company: " + cleanCode);
            }
        }

        Rank rank = new Rank(UUID.randomUUID().toString(), company, cleanCode, cleanLabel, seniority);
        rank.relabel(cleanLabel, Arguments.optional(labelEn));
        rank.setRepresentative(representative);
        return ranks.save(rank);
    }

    @Transactional
    public Rank relabel(PermissionPrincipal caller, String rankId, String labelKo, String labelEn,
            LocalDate businessDate) {
        Rank rank = forUpdate(caller, rankId, businessDate);
        rank.relabel(Arguments.required(labelKo, "labelKo"), Arguments.optional(labelEn));
        return ranks.save(rank);
    }

    /**
     * Marks a rung as carrying 대표이사 authority, or stops it doing so.
     *
     * <p>How many representative approvals a document then needs is the
     * representation mode's business, and that lives in the approval module. This
     * only records which rungs count as one.
     */
    @Transactional
    public Rank setRepresentative(PermissionPrincipal caller, String rankId, boolean representative,
            LocalDate businessDate) {
        Rank rank = forUpdate(caller, rankId, businessDate);
        rank.setRepresentative(representative);
        return ranks.save(rank);
    }

    /**
     * Reorders the whole ladder in one decision.
     *
     * <p>The argument is every rung of the company, most senior first. A partial
     * list is refused rather than interpreted, because there is no honest reading
     * of one: "move 과장 above 차장" leaves the rungs it did not mention in an order
     * the caller did not choose, and seniority is a total order or it is nothing -
     * the approval rule "the 상급자 of this unit" resolves against it on every
     * document.
     *
     * <p>Nothing is written until the whole ordering is known to be valid, and
     * nothing is mutated until then either: the seniorities are computed first,
     * applied in one pass, and saved in one call inside one transaction. There is
     * therefore no committed state in which half the ladder has been renumbered,
     * and no in-memory state either - which matters under JPA, where a mutated
     * managed entity is already a write whether or not {@code save} was called.
     */
    @Transactional
    public List<Rank> reorder(PermissionPrincipal caller, String companyId, List<String> rankIdsMostSeniorFirst,
            LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        String company = Arguments.required(companyId, "companyId");
        evaluator.check(caller, OrgPermissions.RANK_REORDER, companyTarget(company, businessDate)).orThrow();

        if (rankIdsMostSeniorFirst == null) {
            throw new NullPointerException("rankIdsMostSeniorFirst");
        }
        List<Rank> ladder = ranks.findByCompanyIdOrderBySeniorityDesc(company);
        Map<String, Rank> byId = new LinkedHashMap<String, Rank>();
        for (Rank rank : ladder) {
            byId.put(rank.id(), rank);
        }

        List<Rank> ordered = new ArrayList<Rank>(rankIdsMostSeniorFirst.size());
        Set<String> seen = new HashSet<String>();
        for (String rankId : rankIdsMostSeniorFirst) {
            String id = Arguments.required(rankId, "rankId");
            if (!seen.add(id)) {
                throw new IllegalArgumentException("rank " + id + " appears twice in the requested order");
            }
            Rank rank = byId.get(id);
            if (rank == null) {
                throw new IllegalArgumentException("rank " + id + " does not belong to company " + company);
            }
            ordered.add(rank);
        }
        if (ordered.size() != ladder.size()) {
            List<String> missing = new ArrayList<String>();
            for (Rank rank : ladder) {
                if (!seen.contains(rank.id())) {
                    missing.add(rank.labelKo());
                }
            }
            throw new IllegalArgumentException("a reorder must list every rank of the company; missing: " + missing);
        }

        int seniority = ordered.size() * SENIORITY_STEP;
        for (Rank rank : ordered) {
            rank.setSeniority(seniority);
            seniority -= SENIORITY_STEP;
        }
        ranks.saveAll(ordered);
        return Immutables.copyOf(ordered);
    }

    /**
     * Takes a rung out of use.
     *
     * <p>Refused while anyone is standing on it. Every grant attached to a rank
     * reaches its holders through their position, so retiring a rank people hold
     * revokes authority without any request having mentioned permissions.
     */
    @Transactional
    public Rank retire(PermissionPrincipal caller, String rankId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Rank rank = require(rankId);
        evaluator.check(caller, OrgPermissions.RANK_RETIRE,
                companyTarget(rank.companyId(), businessDate)).orThrow();

        List<Position> held = positions.findLiveWithRank(rank.id(), businessDate);
        if (!held.isEmpty()) {
            throw new IllegalArgumentException("rank " + rank.labelKo() + " is held by " + held.size()
                    + " live position(s) on " + businessDate + "; reassign them first");
        }
        rank.deactivate();
        return ranks.save(rank);
    }

    private Rank forUpdate(PermissionPrincipal caller, String rankId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Rank rank = require(rankId);
        evaluator.check(caller, OrgPermissions.RANK_UPDATE,
                companyTarget(rank.companyId(), businessDate)).orThrow();
        return rank;
    }

    private Rank require(String rankId) {
        Optional<Rank> found = ranks.findById(Arguments.required(rankId, "rankId"));
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("rank", rankId);
        }
        return found.get();
    }

    /**
     * The ladder belongs to the company, not to a unit.
     *
     * <p>So the target names the company and no org unit: an ORG_UNIT-scoped grant
     * does not reach it, which is right - a 팀장 does not get to rename the rungs
     * of the company because they run a 팀.
     */
    private static PermissionTarget companyTarget(String companyId, LocalDate businessDate) {
        return OrgTargets.company(companyId, businessDate, "rank ladder of company " + companyId);
    }
}
