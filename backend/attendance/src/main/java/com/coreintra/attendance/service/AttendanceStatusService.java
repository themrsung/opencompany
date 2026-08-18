package com.coreintra.attendance.service;

import com.coreintra.attendance.domain.StatusBehaviour;
import com.coreintra.attendance.entity.AttendanceStatusType;
import com.coreintra.attendance.repository.AttendanceStatusTypeRepository;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company's attendance statuses — the six built-in ones and the client's own.
 *
 * <h2>Custom statuses are first-class, not an OTHER case</h2>
 *
 * <p>근무, 재택, 외근, 자리비움, 휴가 and 퇴근 are seeded rows, not an enum. A client
 * who adds 교육, 출장, 병가 or 육아휴직 gets a status carrying the same behaviour
 * flags, and it therefore behaves identically everywhere: in the who's-in board,
 * in overlap handling, in the working-hours total, in the leave deduction.
 * {@code built_in} means only "restorable to factory state".
 *
 * <p>That is why the flags live on the row and the code branches on
 * {@link StatusBehaviour}, never on a code. Nothing here compares a status to
 * the string {@code "LEAVE"}; if it did, a client's 병가 would silently stop
 * deducting the balance it was configured to deduct.
 *
 * <h2>No hard delete</h2>
 *
 * <p>A status is retired, never removed: attendance records reference it, and a
 * historical row that suddenly names nothing turns a year of timesheets into
 * blanks.
 */
@Service
public class AttendanceStatusService {

    /** Refusing a status definition that would misreport. */
    public static class StatusDefinitionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public StatusDefinitionException(String message) {
            super(message);
        }
    }

    private final AttendanceStatusTypeRepository statuses;
    private final PermissionEvaluator permissions;

    public AttendanceStatusService(AttendanceStatusTypeRepository statuses,
            PermissionEvaluator permissions) {
        this.statuses = statuses;
        this.permissions = permissions;
    }

    /** Every active status for a company, in the order the client arranged them. */
    @Transactional(readOnly = true)
    public List<AttendanceStatusType> active(String companyId) {
        return statuses.findByCompanyIdAndActiveTrueOrderBySortOrderAsc(companyId);
    }

    /**
     * A status by its code.
     *
     * @throws StatusDefinitionException if no such status exists, naming the
     *         company — a typo in a code is otherwise a silent no-op
     */
    @Transactional(readOnly = true)
    public AttendanceStatusType require(String companyId, String code) {
        Optional<AttendanceStatusType> found = statuses.findByCompanyIdAndCode(companyId, code);
        if (!found.isPresent()) {
            throw new StatusDefinitionException(
                    "\"" + code + "\" 근태 상태가 없습니다. (Company " + companyId + " has no "
                            + "attendance status with code \"" + code + "\".)");
        }
        return found.get();
    }

    /**
     * Defines a client status.
     *
     * @param labelEn optional; Korean is the authoritative label because Korean
     *        is the default locale, and a status with no Korean name is unusable
     *        on the board it appears on
     */
    @Transactional
    public AttendanceStatusType define(PermissionPrincipal actor, String companyId, String code,
            String labelKo, String labelEn, String colour, String icon,
            StatusBehaviour behaviour, int sortOrder, LocalDate asOf) {

        authoriseAdmin(actor, companyId, asOf, "defining attendance status " + code);
        if (Texts.isBlank(code)) {
            throw new StatusDefinitionException("a status needs a stable code");
        }
        if (Texts.isBlank(labelKo)) {
            throw new StatusDefinitionException(
                    "상태 이름(한국어)을 입력해 주십시오. (A status needs a Korean label.)");
        }
        if (statuses.findByCompanyIdAndCode(companyId, code).isPresent()) {
            throw new StatusDefinitionException(
                    "\"" + code + "\" 코드의 상태가 이미 있습니다. (Company " + companyId + " already has "
                            + "a status with code \"" + code + "\". Codes are how records refer to "
                            + "statuses, so they cannot repeat.)");
        }
        AttendanceStatusType status = new AttendanceStatusType(UUID.randomUUID().toString(),
                companyId, code, labelKo, behaviour);
        status.relabel(labelKo, labelEn);
        status.setColour(colour);
        status.setIcon(icon);
        status.setSortOrder(sortOrder);
        return statuses.save(status);
    }

    /**
     * Changes what a status means.
     *
     * <p>Behaviour changes are not retroactive and cannot be: the records
     * already written were made under the old meaning. That is a property of the
     * ledger rather than something enforced here, but it is why this method
     * exists at all instead of callers being told to retire and redefine.
     */
    @Transactional
    public AttendanceStatusType redefine(PermissionPrincipal actor, String companyId,
            String statusId, StatusBehaviour behaviour, LocalDate asOf) {

        authoriseAdmin(actor, companyId, asOf, "redefining attendance status " + statusId);
        AttendanceStatusType status = requireById(companyId, statusId);
        status.applyBehaviour(behaviour);
        return statuses.save(status);
    }

    /** Renames and restyles. Labels are display only; nothing branches on them. */
    @Transactional
    public AttendanceStatusType relabel(PermissionPrincipal actor, String companyId,
            String statusId, String labelKo, String labelEn, String colour, String icon,
            LocalDate asOf) {

        authoriseAdmin(actor, companyId, asOf, "relabelling attendance status " + statusId);
        AttendanceStatusType status = requireById(companyId, statusId);
        status.relabel(labelKo, labelEn);
        status.setColour(colour);
        status.setIcon(icon);
        return statuses.save(status);
    }

    /**
     * Retires a status so it can no longer be chosen.
     *
     * <p>Existing records keep pointing at it. Deleting the row would leave a
     * year of timesheets referring to nothing, and the person who asks why their
     * 교육 days vanished will be right to be annoyed.
     */
    @Transactional
    public void retire(PermissionPrincipal actor, String companyId, String statusId,
            LocalDate asOf) {
        authoriseAdmin(actor, companyId, asOf, "retiring attendance status " + statusId);
        AttendanceStatusType status = requireById(companyId, statusId);
        status.deactivate();
        statuses.save(status);
    }

    private AttendanceStatusType requireById(String companyId, String statusId) {
        Optional<AttendanceStatusType> found = statuses.findById(statusId);
        if (!found.isPresent() || !found.get().companyId().equals(companyId)) {
            throw new StatusDefinitionException(
                    "no attendance status " + statusId + " in company " + companyId);
        }
        return found.get();
    }

    private void authoriseAdmin(PermissionPrincipal actor, String companyId, LocalDate asOf,
            String description) {
        permissions.check(actor, AttendancePermissions.STATUS_ADMIN,
                PermissionTarget.builder()
                        .companyId(companyId)
                        .asOfBusinessDate(asOf)
                        .description(description)
                        .build()).orThrow();
    }
}
