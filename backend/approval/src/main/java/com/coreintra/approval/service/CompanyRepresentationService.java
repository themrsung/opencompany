package com.coreintra.approval.service;

import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.entity.CompanyRepresentationEntity;
import com.coreintra.approval.repository.CompanyRepresentationRepository;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.core.service.OrgPermissions;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records how a company's 대표이사 authority is exercised, and from when.
 *
 * <p>{@code company_representation} was read by
 * {@link com.coreintra.approval.adapter.JpaRepresentationDirectory} and written
 * by nothing, so on a fresh installation every submission that reached a
 * representative step threw and 공동대표 was unreachable. This is the writer.
 *
 * <h2>A succession of arrangements, never an edit</h2>
 *
 * <p>Adopting a new arrangement closes the one in force on the day the new one
 * begins and inserts a row beside it. Editing the old row would retroactively
 * change how documents approved months ago were routed: a change signed by one
 * 대표 in March under 각자대표 would silently become an incomplete 공동대표
 * approval, and nothing in the trail would say why.
 *
 * <p>Back-dating is therefore refused rather than merged. An arrangement that
 * started before the one already recorded cannot be inserted without deciding
 * which documents to re-route, and that is not a decision a setter should take.
 *
 * <h2>The quorum rule is not restated here</h2>
 *
 * <p>{@link RepresentationMode#joint} owns it, and the numbers reach the
 * database through that factory precisely so that a quorum of one is refused
 * with the sentence that explains why rather than as a check-constraint
 * violation from {@code representation_quorum_sane}. This service's job is to
 * make sure the factory is on the path, including when the numbers arrive from
 * the wire as two integers and a word.
 *
 * <h2>Why {@code company.settings:update}</h2>
 *
 * <p>The mode is a company-level setting — {@code CompanyService} says as much
 * where it explains why it does not own this table — so it is gated on the key
 * that already governs company settings. A new {@code approval.representation:*}
 * key would be tidier in the abstract and unusable in practice: no existing
 * installation grants it, and {@code PermissionGrantService} will not hand out a
 * key the grantor does not hold, so nobody could ever be given it.
 */
@Service
public class CompanyRepresentationService {

    private final CompanyRepresentationRepository representations;
    private final PermissionEvaluator permissions;

    public CompanyRepresentationService(CompanyRepresentationRepository representations,
            PermissionEvaluator permissions) {
        if (representations == null) {
            throw new NullPointerException("representations");
        }
        if (permissions == null) {
            throw new NullPointerException("permissions");
        }
        this.representations = representations;
        this.permissions = permissions;
    }

    /**
     * Adopts an arrangement from the wire form: a mode name and two counts.
     *
     * <p>The counts go straight into {@link RepresentationMode}'s factories, so
     * "공동대표, 1 of 3" is refused here — in the service, before any row is
     * written — with the factory's own explanation.
     *
     * @param mode {@code SEVERAL} (각자대표) or {@code JOINT} (공동대표)
     * @throws IllegalArgumentException if the mode name is unknown, or the
     *         counts do not describe an arrangement that mode can express
     */
    @Transactional
    public CompanyRepresentationEntity adopt(PermissionPrincipal caller, String companyId,
            String mode, int requiredApprovals, int designatedRepresentatives,
            LocalDate effectiveFrom) {
        return adopt(caller, companyId,
                modeOf(mode, requiredApprovals, designatedRepresentatives), effectiveFrom);
    }

    /**
     * Adopts an already-valid arrangement.
     *
     * <p>Re-adopting the arrangement already in force from the same date is a
     * no-op that returns the existing row. Two administrators pressing the same
     * button is not a failure, and a second identical row would make the
     * effective-dated history harder to read for no gain.
     */
    @Transactional
    public CompanyRepresentationEntity adopt(PermissionPrincipal caller, String companyId,
            RepresentationMode mode, LocalDate effectiveFrom) {
        if (caller == null) {
            throw new NullPointerException("caller");
        }
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        if (effectiveFrom == null) {
            throw new NullPointerException("effectiveFrom");
        }
        String company = required(companyId, "companyId");

        permissions.check(caller, OrgPermissions.COMPANY_UPDATE,
                PermissionTarget.builder()
                        .companyId(company)
                        .asOfBusinessDate(effectiveFrom)
                        .description("adopting " + mode + " from " + effectiveFrom)
                        .build()).orThrow();

        List<CompanyRepresentationEntity> history =
                representations.findByCompanyIdOrderByEffectiveFromDesc(company);
        if (!history.isEmpty()) {
            CompanyRepresentationEntity newest = history.get(0);
            if (sameArrangement(newest, mode, effectiveFrom)) {
                return newest;
            }
            refuseIfNotAfter(newest, effectiveFrom);
            if (newest.effectiveTo() == null) {
                // Half-open: effectiveTo is the first date the old arrangement
                // no longer covers, which is the day the new one starts.
                newest.closeOn(effectiveFrom);
                representations.save(newest);
            }
        }
        return representations.save(new CompanyRepresentationEntity(
                UUID.randomUUID().toString(), company, mode, effectiveFrom, null));
    }

    /** Every arrangement this company has had, newest first. */
    @Transactional(readOnly = true)
    public List<CompanyRepresentationEntity> history(PermissionPrincipal caller, String companyId,
            LocalDate businessDate) {
        if (caller == null) {
            throw new NullPointerException("caller");
        }
        String company = required(companyId, "companyId");
        permissions.check(caller, OrgPermissions.COMPANY_READ,
                PermissionTarget.builder()
                        .companyId(company)
                        .asOfBusinessDate(businessDate)
                        .description("reading the representation history")
                        .build()).orThrow();
        return representations.findByCompanyIdOrderByEffectiveFromDesc(company);
    }

    private static RepresentationMode modeOf(String mode, int requiredApprovals,
            int designatedRepresentatives) {
        String name = required(mode, "mode");
        if (RepresentationMode.Kind.JOINT.name().equalsIgnoreCase(name)) {
            return RepresentationMode.joint(requiredApprovals, designatedRepresentatives);
        }
        if (!RepresentationMode.Kind.SEVERAL.name().equalsIgnoreCase(name)) {
            throw new IllegalArgumentException(
                    "대표 형태는 SEVERAL(각자대표) 또는 JOINT(공동대표)여야 합니다: " + mode
                            + " (Representation mode must be SEVERAL or JOINT.)");
        }
        if (requiredApprovals > 1) {
            // Silently coercing to 1 would let a client believe they had asked
            // for two signatures and been given them.
            throw new IllegalArgumentException(
                    "각자대표는 정의상 1인의 결재로 완결됩니다. " + requiredApprovals
                            + "인의 결재가 필요하다면 공동대표(JOINT)로 지정해 주십시오. (각자대표 is "
                            + "settled by one representative by definition; ask for JOINT if you "
                            + "mean to require " + requiredApprovals + ".)");
        }
        return RepresentationMode.several(designatedRepresentatives);
    }

    private static boolean sameArrangement(CompanyRepresentationEntity existing,
            RepresentationMode mode, LocalDate effectiveFrom) {
        return effectiveFrom.equals(existing.effectiveFrom())
                && mode.kind().name().equals(existing.mode())
                && mode.requiredApprovals() == existing.requiredApprovals()
                && mode.designatedRepresentatives() == existing.designatedRepresentatives();
    }

    private static void refuseIfNotAfter(CompanyRepresentationEntity newest,
            LocalDate effectiveFrom) {
        if (!effectiveFrom.isAfter(newest.effectiveFrom())) {
            throw new IllegalArgumentException(
                    "이미 " + newest.effectiveFrom() + "부터 적용 중인 대표 형태가 있어 "
                            + effectiveFrom + " 부터의 변경은 소급 적용이 됩니다. 변경 시행일을 "
                            + newest.effectiveFrom().plusDays(1) + " 이후로 지정해 주십시오. "
                            + "(An arrangement已 in force from " + newest.effectiveFrom()
                            + " cannot be superseded from " + effectiveFrom + ": documents routed "
                            + "under it would be re-routed after the fact. Choose a date after "
                            + newest.effectiveFrom() + ".)");
        }
    }

    private static String required(String value, String field) {
        if (Texts.isBlank(value)) {
            throw new IllegalArgumentException(field + " is blank");
        }
        return Texts.strip(value);
    }
}
