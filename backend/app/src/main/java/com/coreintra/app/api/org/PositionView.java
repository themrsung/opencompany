package com.coreintra.app.api.org;

import com.coreintra.core.org.Position;
import java.util.function.Function;

/**
 * One assignment of a person to a unit at a rank, for an interval.
 *
 * <p>The interval is what makes the permission model honest: a check on a
 * document dated last March resolves against the positions that were live in
 * March, so a promotion since then neither grants nor removes authority over
 * history (§3). {@code effectiveFrom} is inclusive and {@code effectiveTo} is
 * exclusive, which is why a reassignment on the 1st shows the old row ending on
 * the 1st and the new one starting the same day rather than a gap or an overlap.
 *
 * <p>The 직무 list is <em>not</em> here. {@code PositionService} writes the
 * links but exposes no read of them, and inventing a repository call in the API
 * layer to fill the field would put an unauthorised query where the evaluator
 * cannot see it. Recorded as a gap rather than papered over.
 */
public class PositionView {

    public static final Function<Position, PositionView> MAPPER = new Function<Position, PositionView>() {
        @Override
        public PositionView apply(Position position) {
            return from(position);
        }
    };

    /** Chronological: a career reads forwards. */
    public static final Pages.Keys<Position> KEYS = new Pages.Keys<Position>() {
        @Override
        public String sortKey(Position position) {
            return position.effectiveFrom().toString();
        }

        @Override
        public String id(Position position) {
            return position.id();
        }
    };

    private final String id;
    private final String employeeId;
    private final String orgUnitId;
    private final String rankId;
    private final String effectiveFrom;
    private final String effectiveTo;
    private final boolean primary;
    private final String createdAt;

    PositionView(String id, String employeeId, String orgUnitId, String rankId,
            String effectiveFrom, String effectiveTo, boolean primary, String createdAt) {
        this.id = id;
        this.employeeId = employeeId;
        this.orgUnitId = orgUnitId;
        this.rankId = rankId;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.primary = primary;
        this.createdAt = createdAt;
    }

    public static PositionView from(Position position) {
        return new PositionView(position.id(), position.employeeId(), position.orgUnitId(),
                position.rankId(),
                position.effectiveFrom() == null ? null : position.effectiveFrom().toString(),
                position.effectiveTo() == null ? null : position.effectiveTo().toString(),
                position.isPrimary(),
                position.createdAt() == null ? null : position.createdAt().toString());
    }

    public static String tagOf(Position position) {
        return OrgVersions.tag(position.id(), position.orgUnitId(), position.rankId(),
                position.effectiveFrom(), position.effectiveTo(),
                Boolean.valueOf(position.isPrimary()));
    }

    public String getId() {
        return id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getOrgUnitId() {
        return orgUnitId;
    }

    public String getRankId() {
        return rankId;
    }

    /** Inclusive. */
    public String getEffectiveFrom() {
        return effectiveFrom;
    }

    /** Exclusive, and null while the assignment is open. */
    public String getEffectiveTo() {
        return effectiveTo;
    }

    /** The assignment that answers "which team is this person on". */
    public boolean isPrimary() {
        return primary;
    }

    /** Real UTC. */
    public String getCreatedAt() {
        return createdAt;
    }
}
