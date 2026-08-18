package com.coreintra.approval.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.entity.EmploymentRulesAcknowledgementEntity;
import com.coreintra.approval.entity.EmploymentRulesEntity;
import com.coreintra.approval.entity.EmploymentRulesRepresentativeEntity;
import com.coreintra.approval.entity.EmploymentRulesSectionEntity;
import com.coreintra.approval.repository.EmploymentRulesAcknowledgementRepository;
import com.coreintra.approval.repository.EmploymentRulesRepresentativeRepository;
import com.coreintra.approval.repository.EmploymentRulesRepository;
import com.coreintra.approval.repository.EmploymentRulesSectionRepository;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.compat.Immutables;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JpaEmploymentRulesStoreTest {

    private final FakeVersions versions = new FakeVersions();
    private final FakeSections sections = new FakeSections();
    private final FakeSigners signers = new FakeSigners();
    private final FakeAcknowledgements acknowledgements = new FakeAcknowledgements();
    private final JpaEmploymentRulesStore store =
            new JpaEmploymentRulesStore(versions, sections, signers, acknowledgements);

    private static final RepresentationMode SEVERAL = RepresentationMode.several(2);

    @Test
    @DisplayName("a company's first version is number 1")
    void firstVersionIsOne() {
        assertThat(store.nextVersionNumber("acme")).isEqualTo(1);
    }

    @Test
    @DisplayName("the next version follows the highest already published")
    void nextVersionFollowsTheHighest() {
        store.save(rules("er-1", 1, LocalDate.of(2024, 1, 1)));
        store.save(rules("er-2", 2, LocalDate.of(2026, 1, 1)));

        assertThat(store.nextVersionNumber("acme")).isEqualTo(3);
    }

    @Test
    @DisplayName("a saved version comes back with its sections in printed order")
    void roundTripsSectionsInOrder() {
        store.save(EmploymentRules.publish("er-1", "acme", 1, LocalDate.of(2026, 1, 1),
                Immutables.listOf(
                        new EmploymentRules.Section("제1조", "목적", "Purpose", "이 규칙은…", "These…"),
                        new EmploymentRules.Section("제2조", "적용범위", "Scope", "이 규칙은…", "These…")),
                "doc-1", ApprovalState.APPROVED, SEVERAL, Immutables.listOf("rep-1")));

        List<EmploymentRules> all = store.findAllVersions("acme");

        assertThat(all).hasSize(1);
        assertThat(all.get(0).sections()).hasSize(2);
        assertThat(all.get(0).sections().get(0).number()).isEqualTo("제1조");
        assertThat(all.get(0).sections().get(1).headingKo()).isEqualTo("적용범위");
        assertThat(all.get(0).approvalDocumentId()).isEqualTo("doc-1");
        assertThat(all.get(0).approvingRepresentativeIds()).containsExactly("rep-1");
    }

    @Test
    @DisplayName("a 공동대표 version remembers the quorum it was enacted under")
    void jointModeSurvivesTheRoundTrip() {
        RepresentationMode joint = RepresentationMode.joint(2, 3);
        store.save(EmploymentRules.publish("er-1", "acme", 1, LocalDate.of(2026, 1, 1),
                Immutables.listOf(section()), "doc-1", ApprovalState.APPROVED, joint,
                Immutables.listOf("rep-1", "rep-2")));

        EmploymentRules loaded = store.findAllVersions("acme").get(0);

        assertThat(loaded.approvedUnderMode().isJoint()).isTrue();
        assertThat(loaded.approvedUnderMode().requiredApprovals()).isEqualTo(2);
        assertThat(loaded.approvedUnderMode().designatedRepresentatives()).isEqualTo(3);
        assertThat(loaded.approvingRepresentativeIds()).containsExactly("rep-1", "rep-2");
    }

    @Test
    @DisplayName("the version in force is the latest one that has already started")
    void effectiveOnPicksTheLatestStarted() {
        store.save(rules("er-1", 1, LocalDate.of(2024, 1, 1)));
        store.save(rules("er-2", 2, LocalDate.of(2026, 3, 1)));
        store.save(rules("er-3", 3, LocalDate.of(2027, 1, 1)));

        Optional<EmploymentRules> onTheDay = store.findEffectiveOn("acme", LocalDate.of(2026, 8, 18));

        assertThat(onTheDay).isPresent();
        assertThat(onTheDay.get().version())
                .as("a version taking effect next January does not bind anyone in August")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("effectiveFrom is inclusive: the version binds on its first day")
    void effectiveFromIsInclusive() {
        store.save(rules("er-1", 1, LocalDate.of(2026, 3, 1)));

        assertThat(store.findEffectiveOn("acme", LocalDate.of(2026, 3, 1))).isPresent();
        assertThat(store.findEffectiveOn("acme", LocalDate.of(2026, 2, 28))).isEmpty();
    }

    @Test
    @DisplayName("acknowledging twice keeps the first date, not the second")
    void acknowledgementIsIdempotent() {
        store.save(rules("er-1", 1, LocalDate.of(2026, 1, 1)));

        store.acknowledge("er-1", "emp-1", LocalDate.of(2026, 1, 5));
        store.acknowledge("er-1", "emp-1", LocalDate.of(2026, 9, 30));

        assertThat(store.acknowledgedBy("er-1")).containsExactly("emp-1");
        assertThat(acknowledgements.all().get(0).acknowledgedOn())
                .as("the receipt is for the day they read it, not the day they clicked again")
                .isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    @DisplayName("a row doctored to have too few signatures for its quorum will not load")
    void tamperedRowRefusesToLoad() {
        // The schema makes writing this hard; reading it must be impossible.
        // Otherwise a doctored dump becomes the text employees are told binds
        // them, and nothing in the system ever says so.
        versions.save(new EmploymentRulesEntity("er-1", "acme", 1, LocalDate.of(2026, 1, 1),
                "doc-1", RepresentationMode.jointAll(2)));
        sections.save(new EmploymentRulesSectionEntity("er-1", "제1조", 0, "목적", null, "…", null));
        signers.save(new EmploymentRulesRepresentativeEntity("er-1", "rep-1", 0));

        assertThatThrownBy(() -> store.findAllVersions("acme"))
                .isInstanceOf(EmploymentRules.RepresentativeApprovalRequiredException.class)
                .hasMessageContaining("공동대표");
    }

    private EmploymentRules rules(String id, int version, LocalDate effectiveFrom) {
        return EmploymentRules.publish(id, "acme", version, effectiveFrom,
                Immutables.listOf(section()), "doc-" + version, ApprovalState.APPROVED, SEVERAL,
                Immutables.listOf("rep-1"));
    }

    private static EmploymentRules.Section section() {
        return new EmploymentRules.Section("제1조", "목적", "Purpose", "이 규칙은…", "These rules…");
    }

    private static final class FakeVersions extends FakeRepository<EmploymentRulesEntity, String>
            implements EmploymentRulesRepository {
        @Override
        String idOf(EmploymentRulesEntity entity) {
            return entity.id();
        }

        @Override
        public List<EmploymentRulesEntity> findByCompanyIdOrderByVersionAsc(String companyId) {
            List<EmploymentRulesEntity> found = new ArrayList<EmploymentRulesEntity>();
            for (EmploymentRulesEntity row : all()) {
                if (row.companyId().equals(companyId)) {
                    found.add(row);
                }
            }
            return found;
        }
    }

    private static final class FakeSections
            extends FakeRepository<EmploymentRulesSectionEntity, EmploymentRulesSectionEntity.Key>
            implements EmploymentRulesSectionRepository {
        @Override
        EmploymentRulesSectionEntity.Key idOf(EmploymentRulesSectionEntity entity) {
            return new EmploymentRulesSectionEntity.Key(entity.rulesId(), entity.sectionNumber());
        }

        @Override
        public List<EmploymentRulesSectionEntity> findByRulesIdOrderBySortOrderAsc(String rulesId) {
            List<EmploymentRulesSectionEntity> found = new ArrayList<EmploymentRulesSectionEntity>();
            for (EmploymentRulesSectionEntity row : all()) {
                if (row.rulesId().equals(rulesId)) {
                    found.add(row);
                }
            }
            return found;
        }

        @Override
        public List<EmploymentRulesSectionEntity> findByRulesIdInOrderBySortOrderAsc(
                Collection<String> rulesIds) {
            List<EmploymentRulesSectionEntity> found = new ArrayList<EmploymentRulesSectionEntity>();
            for (EmploymentRulesSectionEntity row : all()) {
                if (rulesIds.contains(row.rulesId())) {
                    found.add(row);
                }
            }
            return found;
        }
    }

    private static final class FakeSigners
            extends FakeRepository<EmploymentRulesRepresentativeEntity,
                    EmploymentRulesRepresentativeEntity.Key>
            implements EmploymentRulesRepresentativeRepository {
        @Override
        EmploymentRulesRepresentativeEntity.Key idOf(EmploymentRulesRepresentativeEntity entity) {
            return new EmploymentRulesRepresentativeEntity.Key(entity.rulesId(), entity.accountId());
        }

        @Override
        public List<EmploymentRulesRepresentativeEntity> findByRulesIdOrderBySortOrderAsc(
                String rulesId) {
            List<EmploymentRulesRepresentativeEntity> found =
                    new ArrayList<EmploymentRulesRepresentativeEntity>();
            for (EmploymentRulesRepresentativeEntity row : all()) {
                if (row.rulesId().equals(rulesId)) {
                    found.add(row);
                }
            }
            return found;
        }

        @Override
        public List<EmploymentRulesRepresentativeEntity> findByRulesIdInOrderBySortOrderAsc(
                Collection<String> rulesIds) {
            List<EmploymentRulesRepresentativeEntity> found =
                    new ArrayList<EmploymentRulesRepresentativeEntity>();
            for (EmploymentRulesRepresentativeEntity row : all()) {
                if (rulesIds.contains(row.rulesId())) {
                    found.add(row);
                }
            }
            return found;
        }
    }

    private static final class FakeAcknowledgements
            extends FakeRepository<EmploymentRulesAcknowledgementEntity,
                    EmploymentRulesAcknowledgementEntity.Key>
            implements EmploymentRulesAcknowledgementRepository {
        @Override
        EmploymentRulesAcknowledgementEntity.Key idOf(
                EmploymentRulesAcknowledgementEntity entity) {
            return new EmploymentRulesAcknowledgementEntity.Key(
                    entity.rulesId(), entity.employeeId());
        }

        @Override
        public List<EmploymentRulesAcknowledgementEntity> findByRulesIdOrderByEmployeeIdAsc(
                String rulesId) {
            List<EmploymentRulesAcknowledgementEntity> found =
                    new ArrayList<EmploymentRulesAcknowledgementEntity>();
            for (EmploymentRulesAcknowledgementEntity row : all()) {
                if (row.rulesId().equals(rulesId)) {
                    found.add(row);
                }
            }
            return found;
        }

        @Override
        public List<EmploymentRulesAcknowledgementEntity> findByEmployeeId(String employeeId) {
            List<EmploymentRulesAcknowledgementEntity> found =
                    new ArrayList<EmploymentRulesAcknowledgementEntity>();
            for (EmploymentRulesAcknowledgementEntity row : all()) {
                if (row.employeeId().equals(employeeId)) {
                    found.add(row);
                }
            }
            return found;
        }
    }
}
