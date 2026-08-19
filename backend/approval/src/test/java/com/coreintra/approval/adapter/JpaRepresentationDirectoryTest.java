package com.coreintra.approval.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.entity.CompanyRepresentationEntity;
import com.coreintra.approval.repository.CompanyRepresentationRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JpaRepresentationDirectoryTest {

    private final FakeRepresentations rows = new FakeRepresentations();
    private final JpaRepresentationDirectory directory = new JpaRepresentationDirectory(rows);

    @Test
    @DisplayName("a document is routed under the mode in force on its own business date")
    void readsTheModeOfTheDayNotOfToday() {
        rows.save(new CompanyRepresentationEntity("r1", "acme",
                RepresentationMode.several(2), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)));
        rows.save(new CompanyRepresentationEntity("r2", "acme",
                RepresentationMode.jointAll(2), LocalDate.of(2026, 4, 1), null));

        assertThat(directory.modeOn("acme", LocalDate.of(2026, 3, 31)).isJoint())
                .as("a March document was routed under March's 각자대표")
                .isFalse();
        assertThat(directory.modeOn("acme", LocalDate.of(2026, 5, 1)).isJoint())
                .isTrue();
    }

    @Test
    @DisplayName("the interval is half-open: the end date belongs to the successor")
    void intervalsAreHalfOpen() {
        rows.save(new CompanyRepresentationEntity("r1", "acme",
                RepresentationMode.several(1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)));
        rows.save(new CompanyRepresentationEntity("r2", "acme",
                RepresentationMode.joint(2, 3), LocalDate.of(2026, 4, 1), null));

        RepresentationMode onTheBoundary = directory.modeOn("acme", LocalDate.of(2026, 4, 1));

        assertThat(onTheBoundary.isJoint())
                .as("1 April is the first day of the new arrangement, not the last of the old")
                .isTrue();
        assertThat(onTheBoundary.requiredApprovals()).isEqualTo(2);
        assertThat(onTheBoundary.designatedRepresentatives()).isEqualTo(3);
    }

    @Test
    @DisplayName("a date before the first arrangement is refused, never assumed")
    void refusesRatherThanGuessing() {
        rows.save(new CompanyRepresentationEntity("r1", "acme",
                RepresentationMode.jointAll(2), LocalDate.of(2026, 4, 1), null));

        assertThatThrownBy(() -> directory.modeOn("acme", LocalDate.of(2026, 3, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2026-03-01")
                .hasMessageContaining("각자대표");
    }

    @Test
    @DisplayName("a company with no arrangement at all is refused with its id named")
    void refusesForAnUnconfiguredCompany() {
        assertThatThrownBy(() -> directory.modeOn("unconfigured", LocalDate.of(2026, 8, 18)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unconfigured");
    }

    @Test
    @DisplayName("when an old arrangement was left open, the newer one wins")
    void newestCoveringRowWins() {
        // Leaving effective_to null on the superseded row is the mistake a
        // client makes once. The answer must still be the arrangement that
        // started last, not whichever row came back first.
        rows.save(new CompanyRepresentationEntity("r1", "acme",
                RepresentationMode.several(1), LocalDate.of(2026, 1, 1), null));
        rows.save(new CompanyRepresentationEntity("r2", "acme",
                RepresentationMode.jointAll(3), LocalDate.of(2026, 4, 1), null));

        assertThat(directory.modeOn("acme", LocalDate.of(2026, 6, 1)).isJoint()).isTrue();
    }

    private static final class FakeRepresentations
            extends FakeRepository<CompanyRepresentationEntity, String>
            implements CompanyRepresentationRepository {

        @Override
        String idOf(CompanyRepresentationEntity entity) {
            return entity.id();
        }

        @Override
        public List<CompanyRepresentationEntity> findByCompanyIdOrderByEffectiveFromDesc(
                String companyId) {
            List<CompanyRepresentationEntity> found =
                    new ArrayList<CompanyRepresentationEntity>();
            for (CompanyRepresentationEntity row : all()) {
                if (row.companyId().equals(companyId)) {
                    found.add(row);
                }
            }
            Collections.sort(found, new Comparator<CompanyRepresentationEntity>() {
                @Override
                public int compare(CompanyRepresentationEntity left,
                        CompanyRepresentationEntity right) {
                    return right.effectiveFrom().compareTo(left.effectiveFrom());
                }
            });
            return found;
        }
    }
}
