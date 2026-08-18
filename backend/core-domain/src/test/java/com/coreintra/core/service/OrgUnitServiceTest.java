package com.coreintra.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The org tree: what a move is allowed to do to it, and what it must never do. */
class OrgUnitServiceTest {

    private OrgFixture fixture;
    private PermissionPrincipal admin;

    @BeforeEach
    void setUp() {
        fixture = new OrgFixture();
        admin = fixture.administrator();
    }

    @Nested
    @DisplayName("a move may not turn the tree into a graph")
    class Cycles {

        @Test
        @DisplayName("moving a unit under its own descendant is refused")
        void underOwnDescendantIsRefused() {
            // hq is the parent of finance. Asking for the reverse is asking for a
            // ring, and a ring has no root to walk up to.
            assertThatThrownBy(() -> fixture.units.move(admin, "hq", "finance", OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cycle");
        }

        @Test
        @DisplayName("moving a unit under a deeper descendant is refused too")
        void underDeepDescendantIsRefused() {
            fixture.unit("team", "1팀", "finance");

            assertThatThrownBy(() -> fixture.units.move(admin, "hq", "team", OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cycle");
        }

        @Test
        @DisplayName("moving a unit under itself is refused")
        void underItselfIsRefused() {
            assertThatThrownBy(() -> fixture.units.move(admin, "finance", "finance", OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cycle");
        }

        @Test
        @DisplayName("a refused move leaves every path exactly as it was")
        void refusedMoveChangesNothing() {
            String before = fixture.book.units().findById("finance").get().path();

            assertThatThrownBy(() -> fixture.units.move(admin, "hq", "finance", OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(fixture.book.units().findById("hq").get().path()).isEqualTo("/hq/");
            assertThat(fixture.book.units().findById("finance").get().path()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("a move takes the whole subtree with it")
    class SubtreeFollows {

        @Test
        @DisplayName("descendants are re-pathed, not orphaned")
        void descendantsFollow() {
            fixture.unit("team", "1팀", "finance");
            fixture.unit("desk", "1파트", "team");
            assertThat(fixture.book.units().findById("desk").get().path()).isEqualTo("/hq/finance/team/desk/");

            fixture.units.move(admin, "team", "sales", OrgFixture.TODAY);

            assertThat(fixture.book.units().findById("team").get().path()).isEqualTo("/hq/sales/team/");
            assertThat(fixture.book.units().findById("desk").get().path())
                    .as("a descendant left pointing at the old path is an orphaned subtree, "
                            + "and every subtree-scoped grant above it stops reaching")
                    .isEqualTo("/hq/sales/team/desk/");
            assertThat(fixture.book.units().findById("desk").get().depth()).isEqualTo(3);
        }

        @Test
        @DisplayName("a subtree-scoped grant follows the unit to its new home")
        void subtreeGrantFollowsTheMove() {
            // 재경팀장 may read employees anywhere beneath 재경팀.
            fixture.rank("팀장", "팀장", 50);
            PermissionPrincipal lead = fixture.person("emp-lead", "팀장");
            fixture.position("emp-lead", "finance", "팀장", OrgFixture.JANUARY);
            fixture.grant(GrantSource.RANK, "팀장", "hr.orgUnit:read", PermissionScope.ORG_UNIT_SUBTREE);
            fixture.unit("team", "1팀", "finance");

            assertThat(names(fixture.units.tree(lead, OrgFixture.COMPANY, OrgFixture.TODAY)))
                    .containsExactly("finance", "team");

            fixture.units.move(admin, "team", "sales", OrgFixture.TODAY);

            assertThat(names(fixture.units.tree(lead, OrgFixture.COMPANY, OrgFixture.TODAY)))
                    .as("the team is no longer under 재경팀, so its 팀장 no longer reaches it")
                    .containsExactly("finance");
        }

        @Test
        @DisplayName("a unit can be moved out to the root")
        void moveToRoot() {
            fixture.units.move(admin, "finance", null, OrgFixture.TODAY);

            OrgUnit finance = fixture.book.units().findById("finance").get();
            assertThat(finance.parentId()).isNull();
            assertThat(finance.path()).isEqualTo("/finance/");
            assertThat(finance.depth()).isZero();
        }
    }

    @Nested
    @DisplayName("retiring a unit")
    class Retiring {

        @Test
        @DisplayName("is refused while active units still hang off it")
        void refusedWithActiveChildren() {
            assertThatThrownBy(() -> fixture.units.retire(admin, "hq", OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("active units beneath it");
        }

        @Test
        @DisplayName("is refused while somebody still holds a position in it")
        void refusedWithLivePositions() {
            fixture.rank("사원", "사원", 10);
            fixture.employee("emp-1", "김민준");
            fixture.position("emp-1", "finance", "사원", OrgFixture.JANUARY);

            assertThatThrownBy(() -> fixture.units.retire(admin, "finance", OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("live position");
        }

        @Test
        @DisplayName("leaves the row in place, because documents still point at it")
        void retirementIsNotDeletion() {
            fixture.units.retire(admin, "finance", OrgFixture.TODAY);

            assertThat(fixture.book.units().findById("finance")).isPresent();
            assertThat(fixture.book.units().findById("finance").get().isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("every write is checked, and reads are scoped")
    class Authorisation {

        @Test
        @DisplayName("a caller with no grants cannot move anything")
        void moveNeedsPermission() {
            PermissionPrincipal nobody = fixture.person("emp-2", "이서준");

            assertThatThrownBy(() -> fixture.units.move(nobody, "finance", "sales", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("authority over the unit being moved is not authority over where it lands")
        void bothEndsAreChecked() {
            fixture.rank("팀장", "팀장", 50);
            PermissionPrincipal lead = fixture.person("emp-lead", "팀장");
            fixture.position("emp-lead", "finance", "팀장", OrgFixture.JANUARY);
            fixture.unit("team", "1팀", "finance");
            // Everything beneath 재경팀 is theirs to rearrange - but 영업팀 is not.
            fixture.grant(GrantSource.RANK, "팀장", "hr.orgUnit:move", PermissionScope.ORG_UNIT_SUBTREE);

            assertThatThrownBy(() -> fixture.units.move(lead, "team", "sales", OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }

        @Test
        @DisplayName("the tree a caller sees is the part of it they may read")
        void treeIsFiltered() {
            fixture.rank("팀장", "팀장", 50);
            PermissionPrincipal lead = fixture.person("emp-lead", "팀장");
            fixture.position("emp-lead", "sales", "팀장", OrgFixture.JANUARY);
            fixture.grant(GrantSource.RANK, "팀장", "hr.orgUnit:read", PermissionScope.ORG_UNIT);

            assertThat(names(fixture.units.tree(lead, OrgFixture.COMPANY, OrgFixture.TODAY)))
                    .containsExactly("sales");
        }
    }

    @Nested
    @DisplayName("creating a unit")
    class Creating {

        @Test
        @DisplayName("refuses a code another unit already uses, retired ones included")
        void codeIsUniquePerCompany() {
            fixture.units.retire(admin, "sales", OrgFixture.TODAY);

            assertThatThrownBy(() -> fixture.units.create(admin, OrgFixture.COMPANY, "hq", "sales", "영업2팀",
                    OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already in use");
        }

        @Test
        @DisplayName("refuses a code containing the path delimiter")
        void codeCannotContainSlash() {
            assertThatThrownBy(() -> fixture.units.create(admin, OrgFixture.COMPANY, "hq", "a/b", "쪼갠팀",
                    OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'/'");
        }

        @Test
        @DisplayName("puts the new unit on the parent's path")
        void newUnitIsPathed() {
            OrgUnit created = fixture.units.create(admin, OrgFixture.COMPANY, "finance", "tax", "세무파트",
                    OrgFixture.TODAY);

            assertThat(created.path()).isEqualTo("/hq/finance/tax/");
            assertThat(created.depth()).isEqualTo(2);
            assertThat(created.id()).isNotEqualTo("tax");
        }
    }

    private static List<String> names(List<OrgUnit> units) {
        List<String> codes = new java.util.ArrayList<String>();
        for (OrgUnit unit : units) {
            codes.add(unit.code());
        }
        return codes;
    }
}
