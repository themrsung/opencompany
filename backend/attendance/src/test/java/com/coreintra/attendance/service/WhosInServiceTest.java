package com.coreintra.attendance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.attendance.domain.StatusBehaviour;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 근무 현황 — the who's-in board.
 *
 * <p>Two of these tests are about cost rather than correctness. The board is one
 * of the two screens the brief says decide whether people like the product, and
 * a claim that it costs a fixed number of queries and one permission check
 * however big the team is only means something if something checks.
 */
class WhosInServiceTest {

    private AttendanceTestWorld world;
    private WhosInService service;
    private AttendanceRecordService records;
    private OrgUnit team;
    private PermissionPrincipal viewer;
    private PermissionPrincipal colleague;

    @BeforeEach
    void setUp() {
        world = new AttendanceTestWorld();
        team = world.unit("DEV");
        viewer = world.person("김사원", team);
        colleague = world.person("박사원", team);

        world.status("WORKING", AttendanceTestWorld.working());
        world.status("SICK", StatusBehaviour.builder().visibleToPeers(false).build());
        world.grants.grant(viewer, AttendancePermissions.ATTENDANCE_READ, PermissionScope.ORG_UNIT);
        world.grants.grant(viewer, AttendancePermissions.ATTENDANCE_WRITE, PermissionScope.SELF);
        world.grants.grant(colleague, AttendancePermissions.ATTENDANCE_WRITE,
                PermissionScope.SELF);

        service = world.whosInService();
        records = world.recordService();
    }

    private static BusinessInstant at(int hour) {
        return BusinessInstant.of(AttendanceTestWorld.DAY, hour, 0, 0);
    }

    private void start(PermissionPrincipal who, String statusCode) {
        records.startStatus(who, AttendanceTestWorld.COMPANY, who.employeeId(), statusCode,
                at(9), null, null);
    }

    @Test
    @DisplayName("shows the team's current statuses for the business day")
    void showsTheTeam() {
        start(viewer, "WORKING");
        start(colleague, "WORKING");

        List<WhoIsInEntry> board = service.forUnit(viewer, AttendanceTestWorld.COMPANY,
                team.id(), AttendanceTestWorld.DAY);

        assertThat(board).hasSize(2);
        assertThat(board).allMatch(WhoIsInEntry::isVisible);
        assertThat(board).allMatch(WhoIsInEntry::isCurrent);
        assertThat(board.get(0).statusCode()).isEqualTo("WORKING");
        assertThat(board.get(0).labelKo()).isEqualTo("WORKING");
        assertThat(board.get(0).countsAsWorking()).isTrue();
    }

    @Test
    @DisplayName("a status that is not visibleToPeers is masked, not omitted")
    void privateStatusIsMaskedNotHidden() {
        start(viewer, "WORKING");
        start(colleague, "SICK");

        List<WhoIsInEntry> board = service.forUnit(viewer, AttendanceTestWorld.COMPANY,
                team.id(), AttendanceTestWorld.DAY);

        WhoIsInEntry theirs = entryFor(board, colleague.employeeId());
        assertThat(theirs.isVisible()).isFalse();
        assertThat(theirs.statusCode()).isNull();
        assertThat(theirs.interval())
                .as("not even the times, which would say how long they have been out")
                .isNull();
        assertThat(theirs.labelKo()).isEqualTo("비공개");
        assertThat(board)
                .as("dropping them from the board would say 'not at work today', which is louder "
                        + "than the status they were keeping private")
                .hasSize(2);
    }

    @Test
    @DisplayName("but you always see your own private status")
    void ownPrivateStatusIsVisible() {
        start(viewer, "SICK");

        List<WhoIsInEntry> board = service.forUnit(viewer, AttendanceTestWorld.COMPANY,
                team.id(), AttendanceTestWorld.DAY);

        WhoIsInEntry mine = entryFor(board, viewer.employeeId());
        assertThat(mine.isVisible()).isTrue();
        assertThat(mine.statusCode()).isEqualTo("SICK");
    }

    @Test
    @DisplayName("someone with no record for the day still appears")
    void unrecordedPeopleAppear() {
        start(viewer, "WORKING");

        List<WhoIsInEntry> board = service.forUnit(viewer, AttendanceTestWorld.COMPANY,
                team.id(), AttendanceTestWorld.DAY);

        WhoIsInEntry theirs = entryFor(board, colleague.employeeId());
        assertThat(theirs.statusCode()).isNull();
        assertThat(theirs.isVisible())
                .as("nothing is being withheld — there is simply nothing recorded")
                .isTrue();
        assertThat(theirs.isCurrent()).isFalse();
    }

    @Test
    @DisplayName("the latest record of the day is the one shown")
    void latestRecordWins() {
        world.status("FIELD", AttendanceTestWorld.working());
        start(viewer, "WORKING");
        records.startStatus(viewer, AttendanceTestWorld.COMPANY, viewer.employeeId(), "FIELD",
                at(14), null, null);

        List<WhoIsInEntry> board = service.forUnit(viewer, AttendanceTestWorld.COMPANY,
                team.id(), AttendanceTestWorld.DAY);

        assertThat(entryFor(board, viewer.employeeId()).statusCode()).isEqualTo("FIELD");
    }

    @Test
    @DisplayName("costs a fixed number of queries and one permission check, whatever the headcount")
    void costIsFlat() {
        List<String> employeeIds = new ArrayList<String>();
        for (int i = 0; i < 25; i++) {
            PermissionPrincipal person = world.person("사원" + i, team);
            world.grants.grant(person, AttendancePermissions.ATTENDANCE_WRITE,
                    PermissionScope.SELF);
            start(person, "WORKING");
            employeeIds.add(person.employeeId());
        }
        world.records.queries = 0;
        world.statusTypes.queries = 0;
        world.positions.queries = 0;
        world.permissions.checks = 0;

        List<WhoIsInEntry> board = service.forEmployees(viewer, AttendanceTestWorld.COMPANY,
                team.id(), Immutables.copyOf(employeeIds), AttendanceTestWorld.DAY);

        assertThat(board).hasSize(25);
        assertThat(world.records.queries)
                .as("one query for the day's records across the whole team")
                .isEqualTo(1);
        assertThat(world.statusTypes.queries)
                .as("one query for the company's status types")
                .isEqualTo(1);
        assertThat(world.permissions.checks)
                .as("the board is one view of one team, authorised once — not once per person")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("filtering by unit costs exactly one query more")
    void unitFilterCostsOneMore() {
        start(viewer, "WORKING");
        world.records.queries = 0;
        world.statusTypes.queries = 0;
        world.positions.queries = 0;

        service.forUnit(viewer, AttendanceTestWorld.COMPANY, team.id(),
                AttendanceTestWorld.DAY);

        assertThat(world.positions.queries).isEqualTo(1);
        assertThat(world.records.queries + world.statusTypes.queries).isEqualTo(2);
    }

    @Test
    @DisplayName("the company board covers every unit, and needs a company-scoped grant")
    void companyWideBoard() {
        OrgUnit sales = world.unit("SALES");
        PermissionPrincipal salesperson = world.person("영업사원", sales);
        world.grants.grant(salesperson, AttendancePermissions.ATTENDANCE_WRITE,
                PermissionScope.SELF);
        start(viewer, "WORKING");
        start(salesperson, "WORKING");

        assertThatThrownBy(() -> service.forCompany(viewer, AttendanceTestWorld.COMPANY,
                AttendanceTestWorld.DAY))
                .as("a unit-scoped grant does not reach a company-wide board")
                .isInstanceOf(PermissionDeniedException.class);

        world.grants.grant(viewer, AttendancePermissions.ATTENDANCE_READ, PermissionScope.COMPANY);
        assertThat(service.forCompany(viewer, AttendanceTestWorld.COMPANY,
                AttendanceTestWorld.DAY))
                .extracting(WhoIsInEntry::employeeId)
                .contains(viewer.employeeId(), colleague.employeeId(),
                        salesperson.employeeId());
    }

    @Test
    @DisplayName("a viewer with no reach into the unit is refused")
    void needsReachIntoTheUnit() {
        OrgUnit otherTeam = world.unit("SALES");
        PermissionPrincipal outsider = world.person("영업사원", otherTeam);

        assertThatThrownBy(() -> service.forUnit(outsider, AttendanceTestWorld.COMPANY,
                team.id(), AttendanceTestWorld.DAY))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("hr.attendance:read");
    }

    @Test
    @DisplayName("an empty team is an empty board, not an error")
    void emptyTeam() {
        OrgUnit emptyUnit = world.unit("NEW");
        world.grants.grant(viewer, AttendancePermissions.ATTENDANCE_READ,
                PermissionScope.COMPANY);

        assertThat(service.forUnit(viewer, AttendanceTestWorld.COMPANY, emptyUnit.id(),
                AttendanceTestWorld.DAY)).isEmpty();
    }

    @Test
    @DisplayName("permission is checked before the board is empty, not after")
    void authorisationPrecedesEmptiness() {
        OrgUnit otherTeam = world.unit("SALES");

        assertThatThrownBy(() -> service.forUnit(viewer, AttendanceTestWorld.COMPANY,
                otherTeam.id(), AttendanceTestWorld.DAY))
                .as("learning that a unit is empty is still learning something about it")
                .isInstanceOf(PermissionDeniedException.class);
    }

    private static WhoIsInEntry entryFor(List<WhoIsInEntry> board, String employeeId) {
        for (WhoIsInEntry entry : board) {
            if (entry.employeeId().equals(employeeId)) {
                return entry;
            }
        }
        throw new AssertionError("no entry for " + employeeId);
    }
}
