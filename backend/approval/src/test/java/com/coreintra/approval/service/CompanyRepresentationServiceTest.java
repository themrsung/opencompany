package com.coreintra.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.entity.CompanyRepresentationEntity;
import com.coreintra.approval.repository.CompanyRepresentationRepository;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.service.OrgPermissions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The writer that {@code company_representation} never had.
 *
 * <p>The evaluator here is the real one over an in-memory org chart, so a test
 * that forgot to grant anything fails for the right reason.
 */
class CompanyRepresentationServiceTest {

    private static final String COMPANY = "co-1";
    private static final String ADMIN = "acc-admin";
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);
    private static final LocalDate MAY = LocalDate.of(2026, 5, 1);

    private final ApprovalTestWorld world = new ApprovalTestWorld();
    private final Representations rows = new Representations();
    private CompanyRepresentationService service;

    @BeforeEach
    void setUp() {
        service = new CompanyRepresentationService(rows, world.permissions);
        world.grants.grant(ADMIN, OrgPermissions.COMPANY_UPDATE, PermissionScope.ALL);
    }

    private static PermissionPrincipal admin() {
        return PermissionPrincipal.user(ADMIN, "김서연", null);
    }

    @Test
    @DisplayName("adopting 각자대표 records the arrangement the directory then reads")
    void adoptsSeveral() {
        CompanyRepresentationEntity written =
                service.adopt(admin(), COMPANY, "SEVERAL", 1, 2, MARCH);

        assertThat(written.mode()).isEqualTo("SEVERAL");
        assertThat(written.requiredApprovals()).isEqualTo(1);
        assertThat(written.designatedRepresentatives()).isEqualTo(2);
        assertThat(written.effectiveFrom()).isEqualTo(MARCH);
        assertThat(written.effectiveTo())
                .as("the arrangement in force has no end until another one starts")
                .isNull();
        assertThat(written.toMode().isJoint()).isFalse();
    }

    @Test
    @DisplayName("공동대표 with a quorum of one is refused with the reason, before anything is written")
    void refusesJointQuorumOfOne() {
        assertThatThrownBy(() -> service.adopt(admin(), COMPANY, "JOINT", 1, 3, MARCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("각자대표");

        assertThat(rows.rows)
                .as("the refusal has to happen before the insert, or the client gets a "
                        + "constraint violation from representation_quorum_sane instead of a "
                        + "sentence")
                .isEmpty();
    }

    @Test
    @DisplayName("각자대표 asked for with a quorum of two is refused rather than quietly reduced")
    void refusesSeveralWithAQuorum() {
        assertThatThrownBy(() -> service.adopt(admin(), COMPANY, "SEVERAL", 2, 3, MARCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JOINT");
    }

    @Test
    @DisplayName("a new arrangement closes the one in force instead of editing it")
    void supersedesRatherThanEdits() {
        service.adopt(admin(), COMPANY, "SEVERAL", 1, 3, MARCH);
        CompanyRepresentationEntity joint = service.adopt(admin(), COMPANY,
                RepresentationMode.joint(2, 3), MAY);

        List<CompanyRepresentationEntity> history =
                rows.findByCompanyIdOrderByEffectiveFromDesc(COMPANY);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).id()).isEqualTo(joint.id());
        assertThat(history.get(1).effectiveTo())
                .as("half-open: the old arrangement stops covering the day the new one starts, "
                        + "so a document approved in April is still explained by it")
                .isEqualTo(MAY);
        assertThat(history.get(1).coversBusinessDate(MAY.minusDays(1))).isTrue();
        assertThat(history.get(1).coversBusinessDate(MAY)).isFalse();
    }

    @Test
    @DisplayName("back-dating over the arrangement in force is refused, naming the earliest date it could take effect")
    void refusesBackDating() {
        service.adopt(admin(), COMPANY, "SEVERAL", 1, 3, MAY);

        assertThatThrownBy(() -> service.adopt(admin(), COMPANY, "JOINT", 2, 3, MARCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MAY.plusDays(1).toString());
        assertThat(rows.rows).hasSize(1);
    }

    @Test
    @DisplayName("adopting the arrangement already in force from the same date changes nothing")
    void reAdoptingIsANoOp() {
        CompanyRepresentationEntity first = service.adopt(admin(), COMPANY, "JOINT", 2, 3, MARCH);
        CompanyRepresentationEntity again = service.adopt(admin(), COMPANY, "JOINT", 2, 3, MARCH);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(rows.rows).hasSize(1);
    }

    @Test
    @DisplayName("a caller without company.settings:update cannot decide how 대표 authority works")
    void refusesWithoutThePermission() {
        PermissionPrincipal stranger = PermissionPrincipal.user("acc-stranger", "이준호", null);

        assertThatThrownBy(() -> service.adopt(stranger, COMPANY, "SEVERAL", 1, 1, MARCH))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(rows.rows).isEmpty();
    }

    /** The one table this service writes, in memory. */
    static final class Representations extends InMemoryRepository<CompanyRepresentationEntity, String>
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
            for (CompanyRepresentationEntity row : rows.values()) {
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
