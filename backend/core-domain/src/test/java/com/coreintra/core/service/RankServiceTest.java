package com.coreintra.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Rank;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The 직급 ladder.
 *
 * <p>The ladder decides who is senior to whom, and seniority decides who
 * approves what, so the interesting tests here are all about the ordering being
 * a single decision rather than a sequence of them.
 */
class RankServiceTest {

    private OrgFixture fixture;
    private PermissionPrincipal admin;

    @BeforeEach
    void setUp() {
        fixture = new OrgFixture();
        admin = fixture.administrator();
        // A seeded ladder the client is free to rewrite. Nothing in the service
        // knows what these words mean.
        fixture.rank("사원", "사원", 10);
        fixture.rank("대리", "대리", 20);
        fixture.rank("과장", "과장", 30);
        fixture.rank("부장", "부장", 40);
    }

    @Nested
    @DisplayName("a reorder is one decision about the whole ladder")
    class Reordering {

        @Test
        @DisplayName("renumbers every rung in the requested order")
        void renumbersInOrder() {
            fixture.ranks.reorder(admin, OrgFixture.COMPANY,
                    Immutables.listOf("대표", "과장", "부장", "대리", "사원"), OrgFixture.TODAY);

            assertThat(labels(fixture.ranks.list(admin, OrgFixture.COMPANY, OrgFixture.TODAY)))
                    .as("과장 was promoted above 부장, which is the client's business and not ours")
                    .containsExactly("대표", "과장", "부장", "대리", "사원");
        }

        @Test
        @DisplayName("leaves gaps between rungs so the next insertion is not another reorder")
        void leavesGaps() {
            fixture.ranks.reorder(admin, OrgFixture.COMPANY,
                    Immutables.listOf("대표", "부장", "과장", "대리", "사원"), OrgFixture.TODAY);

            List<Rank> ladder = fixture.ranks.list(admin, OrgFixture.COMPANY, OrgFixture.TODAY);
            for (int i = 1; i < ladder.size(); i++) {
                assertThat(ladder.get(i - 1).seniority() - ladder.get(i).seniority())
                        .isEqualTo(RankService.SENIORITY_STEP);
            }
        }

        @Test
        @DisplayName("a partial list is refused rather than half-applied")
        void partialListIsRefused() {
            Map<String, Integer> before = seniorities();

            assertThatThrownBy(() -> fixture.ranks.reorder(admin, OrgFixture.COMPANY,
                    Immutables.listOf("부장", "과장"), OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("every rank");

            assertThat(seniorities())
                    .as("a rejected reorder must leave no rung renumbered; a half-applied "
                            + "ordering is an ordering nobody chose")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("a rank listed twice is refused, and nothing is renumbered")
        void duplicateIsRefused() {
            Map<String, Integer> before = seniorities();

            assertThatThrownBy(() -> fixture.ranks.reorder(admin, OrgFixture.COMPANY,
                    Immutables.listOf("대표", "부장", "부장", "과장", "대리", "사원"), OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("twice");

            assertThat(seniorities()).isEqualTo(before);
        }

        @Test
        @DisplayName("a rank from another company is refused, and nothing is renumbered")
        void foreignRankIsRefused() {
            Map<String, Integer> before = seniorities();

            assertThatThrownBy(() -> fixture.ranks.reorder(admin, OrgFixture.COMPANY,
                    Immutables.listOf("대표", "부장", "과장", "대리", "사원", "없는직급"), OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(seniorities()).isEqualTo(before);
        }

        @Test
        @DisplayName("the whole renumbering happens inside one transaction")
        void reorderIsTransactional() throws NoSuchMethodException {
            // The unit test can prove that a rejected reorder writes nothing; that
            // no *other* reader sees a half-renumbered ladder is the transaction's
            // job, so the boundary itself is asserted rather than assumed.
            assertThat(RankService.class
                    .getMethod("reorder", com.coreintra.core.permission.PermissionPrincipal.class, String.class,
                            List.class, java.time.LocalDate.class)
                    .getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("reordering the ladder is its own permission, not an update")
        void reorderNeedsItsOwnPermission() {
            PermissionPrincipal nobody = fixture.person("emp-2", "이서준");

            assertThatThrownBy(() -> fixture.ranks.reorder(nobody, OrgFixture.COMPANY,
                    Immutables.listOf("대표", "부장", "과장", "대리", "사원"), OrgFixture.TODAY))
                    .isInstanceOf(PermissionDeniedException.class);
        }
    }

    @Nested
    @DisplayName("the ladder is the client's")
    class ClientDefined {

        @Test
        @DisplayName("a rung can be renamed to anything, in Korean or otherwise")
        void relabelIsFree() {
            fixture.ranks.relabel(admin, "부장", "General Manager", "General Manager", OrgFixture.TODAY);

            assertThat(fixture.book.ranks().findById("부장").get().labelKo()).isEqualTo("General Manager");
        }

        @Test
        @DisplayName("which rung counts as 대표 is a flag the client sets")
        void representativeIsAFlag() {
            fixture.ranks.setRepresentative(admin, "부장", true, OrgFixture.TODAY);

            assertThat(fixture.book.ranks().findById("부장").get().isRepresentative()).isTrue();
        }

        @Test
        @DisplayName("retiring a rung somebody stands on is refused")
        void retireRefusedWhileHeld() {
            fixture.employee("emp-1", "김민준");
            fixture.position("emp-1", "finance", "과장", OrgFixture.JANUARY);

            assertThatThrownBy(() -> fixture.ranks.retire(admin, "과장", OrgFixture.TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("held by");
        }

        @Test
        @DisplayName("an unheld rung retires without being deleted")
        void retireKeepsTheRow() {
            fixture.ranks.retire(admin, "과장", OrgFixture.TODAY);

            assertThat(fixture.book.ranks().findById("과장")).isPresent();
            assertThat(fixture.book.ranks().findById("과장").get().isActive()).isFalse();
        }
    }

    private Map<String, Integer> seniorities() {
        Map<String, Integer> byId = new HashMap<String, Integer>();
        for (Rank rank : fixture.book.allRanks()) {
            byId.put(rank.id(), Integer.valueOf(rank.seniority()));
        }
        return byId;
    }

    private static List<String> labels(List<Rank> ladder) {
        List<String> labels = new ArrayList<String>();
        for (Rank rank : ladder) {
            labels.add(rank.labelKo());
        }
        return labels;
    }
}
