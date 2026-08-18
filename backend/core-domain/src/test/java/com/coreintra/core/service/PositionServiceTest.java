package com.coreintra.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Position;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Assignments, and the history they leave behind.
 *
 * <p>The history is not bookkeeping. It is what makes a permission check on a
 * past-dated document answer the question that document asks, so the last group
 * here is really a test of the whole layer: a promotion written through this
 * service must not change what the evaluator says about last quarter.
 */
class PositionServiceTest {

    private OrgFixture fixture;
    private PermissionPrincipal admin;

    @BeforeEach
    void setUp() {
        fixture = new OrgFixture();
        admin = fixture.administrator();
        fixture.rank("사원", "사원", 10);
        fixture.rank("과장", "과장", 30);
        fixture.rank("부장", "부장", 40);
        fixture.jobFunction("회계", "회계");
        fixture.jobFunction("영업", "영업");
        fixture.employee("emp-1", "김민준");
    }

    @Nested
    @DisplayName("one seat, one occupant")
    class Overlap {

        @Test
        @DisplayName("a second position in the same unit while the first is open is refused")
        void overlapInSameUnitIsRefused() {
            fixture.positions.assign(admin, "emp-1", "finance", "사원", null, OrgFixture.JANUARY, true,
                    OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.positions.assign(admin, "emp-1", "finance", "과장", null,
                    OrgFixture.JUNE, true, OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already holds a position in this unit");
        }

        @Test
        @DisplayName("the refused assignment leaves the existing row untouched")
        void refusedAssignmentWritesNothing() {
            fixture.positions.assign(admin, "emp-1", "finance", "사원", null, OrgFixture.JANUARY, true,
                    OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.positions.assign(admin, "emp-1", "finance", "과장", null,
                    OrgFixture.JUNE, true, OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class);

            List<Position> history = fixture.positions.history(admin, "emp-1", OrgFixture.TODAY);
            assertThat(history).hasSize(1);
            assertThat(history.get(0).rankId()).isEqualTo("사원");
            assertThat(history.get(0).effectiveTo()).isNull();
        }

        @Test
        @DisplayName("a position starting the day the previous one ends is allowed")
        void adjacentIntervalsAreAllowed() {
            Position first = fixture.positions.assign(admin, "emp-1", "finance", "사원", null, OrgFixture.JANUARY,
                    true, OrgFixture.TODAY);
            fixture.positions.close(admin, first.id(), OrgFixture.JUNE, OrgFixture.TODAY);

            Position second = fixture.positions.assign(admin, "emp-1", "finance", "과장", null, OrgFixture.JUNE,
                    true, OrgFixture.TODAY);

            assertThat(second.effectiveFrom()).isEqualTo(OrgFixture.JUNE);
            assertThat(fixture.positions.history(admin, "emp-1", OrgFixture.TODAY)).hasSize(2);
        }

        @Test
        @DisplayName("겸직 in a different unit is not an overlap")
        void concurrentPostsInDifferentUnitsAreAllowed() {
            fixture.positions.assign(admin, "emp-1", "finance", "과장", null, OrgFixture.JANUARY, true,
                    OrgFixture.TODAY);

            Position second = fixture.positions.assign(admin, "emp-1", "sales", "과장", null, OrgFixture.JUNE,
                    false, OrgFixture.TODAY);

            // Position itself says so: a primary flag exists because people hold
            // several posts at once, and the permission scope walks all of them.
            assertThat(second.isPrimary()).isFalse();
            assertThat(fixture.positions.history(admin, "emp-1", OrgFixture.TODAY)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("closing keeps the row")
    class Closing {

        @Test
        @DisplayName("a closed position is still in the history, with its dates intact")
        void closePreservesTheRow() {
            Position first = fixture.positions.assign(admin, "emp-1", "finance", "사원", null, OrgFixture.JANUARY,
                    true, OrgFixture.TODAY);

            fixture.positions.close(admin, first.id(), OrgFixture.JUNE, OrgFixture.TODAY);

            List<Position> history = fixture.positions.history(admin, "emp-1", OrgFixture.TODAY);
            assertThat(history).hasSize(1);
            assertThat(history.get(0).id()).isEqualTo(first.id());
            assertThat(history.get(0).effectiveFrom()).isEqualTo(OrgFixture.JANUARY);
            assertThat(history.get(0).effectiveTo()).isEqualTo(OrgFixture.JUNE);
        }

        @Test
        @DisplayName("ending a position before it started is refused")
        void cannotEndBeforeItStarted() {
            Position first = fixture.positions.assign(admin, "emp-1", "finance", "사원", null, OrgFixture.JUNE,
                    true, OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.positions.close(admin, first.id(), OrgFixture.JANUARY,
                    OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a reassignment closes the old row and opens a new one")
        void reassignClosesAndOpens() {
            Position first = fixture.positions.assign(admin, "emp-1", "finance", "과장",
                    Immutables.listOf("회계"), OrgFixture.JANUARY, true, OrgFixture.TODAY);

            Position second = fixture.positions.reassign(admin, first.id(), "sales", "부장",
                    Immutables.listOf("영업"), OrgFixture.JUNE, OrgFixture.TODAY);

            List<Position> history = fixture.positions.history(admin, "emp-1", OrgFixture.TODAY);
            assertThat(history).hasSize(2);
            assertThat(history.get(0).id()).isEqualTo(first.id());
            assertThat(history.get(0).effectiveTo()).isEqualTo(OrgFixture.JUNE);
            assertThat(history.get(0).rankId()).as("the old row keeps the old rank").isEqualTo("과장");
            assertThat(second.rankId()).isEqualTo("부장");
            assertThat(second.orgUnitId()).isEqualTo("sales");
            assertThat(fixture.book.functionsOf(first.id())).containsExactly("회계");
            assertThat(fixture.book.functionsOf(second.id())).containsExactly("영업");
        }

        @Test
        @DisplayName("a closed position cannot be reassigned")
        void closedPositionCannotBeReassigned() {
            Position first = fixture.positions.assign(admin, "emp-1", "finance", "과장", null, OrgFixture.JANUARY,
                    true, OrgFixture.TODAY);
            fixture.positions.close(admin, first.id(), OrgFixture.JUNE, OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.positions.reassign(admin, first.id(), "sales", "부장", null,
                    OrgFixture.JUNE, OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be reassigned");
        }
    }

    @Nested
    @DisplayName("history is what a past-dated check resolves against")
    class AsOf {

        @Test
        @DisplayName("promoting someone does not change last quarter's answer")
        void promotionDoesNotRewriteThePast() {
            // 과장 in 재경팀 may post journal entries in their own unit.
            fixture.grant(GrantSource.RANK, "과장", "accounting.entry:post", PermissionScope.ORG_UNIT);
            PermissionPrincipal accountant = fixture.person("emp-9", "박지훈");
            Position march = fixture.positions.assign(admin, "emp-9", "finance", "과장", null, OrgFixture.JANUARY,
                    true, OrgFixture.TODAY);

            assertThat(canPost(accountant, OrgFixture.LAST_MARCH)).isTrue();

            // Promoted out of 재경팀 into 영업팀 on 1 June.
            fixture.positions.reassign(admin, march.id(), "sales", "부장", null, OrgFixture.JUNE, OrgFixture.TODAY);

            assertThat(canPost(accountant, OrgFixture.TODAY))
                    .as("today they are in 영업팀 and no longer post to the ledger")
                    .isFalse();
            assertThat(canPost(accountant, OrgFixture.LAST_MARCH))
                    .as("in March they were a 과장 in 재경팀, and a promotion since then must not "
                            + "make March's entries unauditable")
                    .isTrue();
        }

        @Test
        @DisplayName("a leaver's closed position stops reaching the day it closes")
        void closedPositionStopsReaching() {
            fixture.grant(GrantSource.RANK, "과장", "accounting.entry:post", PermissionScope.ORG_UNIT);
            PermissionPrincipal accountant = fixture.person("emp-9", "박지훈");
            Position held = fixture.positions.assign(admin, "emp-9", "finance", "과장", null, OrgFixture.JANUARY,
                    true, OrgFixture.TODAY);

            fixture.positions.close(admin, held.id(), OrgFixture.JUNE, OrgFixture.TODAY);

            assertThat(canPost(accountant, OrgFixture.JUNE.minusDays(1))).isTrue();
            assertThat(canPost(accountant, OrgFixture.JUNE))
                    .as("effectiveTo is exclusive, so the seat is vacant on the day it closes")
                    .isFalse();
        }

        private boolean canPost(PermissionPrincipal principal, LocalDate businessDate) {
            PermissionTarget entry = PermissionTarget.builder()
                    .companyId(OrgFixture.COMPANY)
                    .orgUnitId("finance")
                    .asOfBusinessDate(businessDate)
                    .description("journal entry dated " + businessDate)
                    .build();
            return fixture.evaluator.check(principal, "accounting.entry", "post", entry).isAllowed();
        }
    }

    @Nested
    @DisplayName("both ends of an assignment are checked")
    class Authorisation {

        @Test
        @DisplayName("a caller with no grants cannot assign anyone")
        void assignNeedsPermission() {
            PermissionPrincipal nobody = fixture.person("emp-2", "이서준");

            assertThatThrownBy(() -> fixture.positions.assign(nobody, "emp-1", "finance", "사원", null,
                    OrgFixture.JANUARY, true, OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("authority over a team is not authority to staff it from another team")
        void destinationAuthorityIsNotEnough() {
            fixture.rank("팀장", "팀장", 50);
            PermissionPrincipal lead = fixture.person("emp-lead", "팀장");
            fixture.position("emp-lead", "sales", "팀장", OrgFixture.JANUARY);
            fixture.grant(GrantSource.RANK, "팀장", "hr.position:assign", PermissionScope.ORG_UNIT);
            // The person being moved sits in 재경팀, which this 팀장 does not reach.
            fixture.position("emp-1", "finance", "사원", OrgFixture.JANUARY);

            assertThatThrownBy(() -> fixture.positions.assign(lead, "emp-1", "sales", "사원", null,
                    OrgFixture.JUNE, false, OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("a 직무 from another company cannot be attached")
        void jobFunctionMustBelongToTheCompany() {
            assertThatThrownBy(() -> fixture.positions.assign(admin, "emp-1", "finance", "사원",
                    Immutables.listOf("없는직무"), OrgFixture.JANUARY, true, OrgFixture.TODAY))
                    .isInstanceOf(RecordNotFoundException.class);
        }
    }
}
