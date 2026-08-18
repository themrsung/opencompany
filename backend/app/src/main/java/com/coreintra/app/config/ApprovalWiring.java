package com.coreintra.app.config;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.approval.notify.ApprovalNotification;
import com.coreintra.approval.notify.Notifier;
import com.coreintra.approval.repository.ApprovalDocumentRepository;
import com.coreintra.approval.service.AbsenceDirectory;
import com.coreintra.approval.service.ApprovalOutcome;
import com.coreintra.approval.service.ApprovalOutcomeListener;
import com.coreintra.attendance.domain.LeaveAccrualPolicy;
import com.coreintra.attendance.entity.AttendanceRecord;
import com.coreintra.attendance.entity.AttendanceStatusType;
import com.coreintra.attendance.repository.AttendanceRecordRepository;
import com.coreintra.attendance.repository.AttendanceStatusTypeRepository;
import com.coreintra.attendance.service.ApprovedLeave;
import com.coreintra.attendance.service.AttendanceSettings;
import com.coreintra.attendance.service.LeavePolicyStore;
import com.coreintra.attendance.service.LeaveService;
import com.coreintra.attendance.service.OverlapPolicy;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.repository.DocumentFieldValueRepository;
import com.coreintra.documents.repository.DocumentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Joins 결재 to the things an approval is supposed to make happen.
 *
 * <p>The approval module declares what it needs and refuses to know who
 * provides it. Approving a 휴가신청서 has to write a leave balance transaction;
 * checking a 대결 has to know whether the absent approver really was away. Both
 * answers live in {@code attendance}, and neither module may depend on the
 * other — approval has no business reading timesheets, and attendance has no
 * business knowing what a 결재선 is. This is the module that already depends on
 * both, so this is where the joins belong.
 *
 * <p>Everything here is thin by design. No rule is decided in this file: the
 * accrual arithmetic is {@code LeaveService}'s, the absence definition is the
 * client's own status rows, and the quorum is the representation mode's.
 */
@Configuration
public class ApprovalWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalWiring.class);

    /**
     * What a 휴가신청서 asks for, read from the document body.
     *
     * <p>An interface rather than a class because the body is the documents
     * module's business and the way it is stored will change — the in-app body
     * editor and the seeded 휴가신청서 template are both still to come. What the
     * leave adapter needs from it will not.
     */
    public interface LeaveRequestDetails {

        /**
         * @param approvalDocumentId the 결재 document's id, not the body's
         * @throws IllegalStateException if this is not a readable leave request;
         *         the message reaches the approver, so it names the missing field
         */
        LeaveRequest of(String approvalDocumentId);
    }

    /** The three answers a leave deduction cannot be written without, plus the reason. */
    public static final class LeaveRequest {
        private final String employeeId;
        private final String policyId;
        private final BigDecimal days;
        private final String reason;

        public LeaveRequest(String employeeId, String policyId, BigDecimal days, String reason) {
            this.employeeId = employeeId;
            this.policyId = policyId;
            this.days = days;
            this.reason = reason;
        }

        public String employeeId() {
            return employeeId;
        }

        public String policyId() {
            return policyId;
        }

        public BigDecimal days() {
            return days;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * The document type a leave request is filed as, and the fields it must carry.
     *
     * <p>These ids are a contract between the seeded 휴가신청서 template and this
     * adapter. The template does not exist yet (§6.8 is unbuilt), so the contract
     * is stated here rather than being discovered later by whoever writes it.
     */
    public static final class LeaveRequestFields {
        public static final String DOCUMENT_TYPE = "LEAVE_REQUEST";

        /** {@code EMPLOYEE_REF} — whose balance the days come out of. */
        public static final String EMPLOYEE = "leave.employee";
        /** {@code TEXT} — the policy's stable code, e.g. {@code ANNUAL}. */
        public static final String POLICY_CODE = "leave.policy";
        /** {@code NUMBER} — days, in the policy's bookable unit. */
        public static final String DAYS = "leave.days";
        /** {@code TEXT}, optional — what the employee wrote on the form. */
        public static final String REASON = "leave.reason";

        private LeaveRequestFields() {
        }
    }

    // ------------------------------------------------------------------
    // Attendance settings

    /**
     * The overlap rule, installation-wide until there is a settings table.
     *
     * <p>{@code CLOSE_PREVIOUS} is the forgiving answer and the right default for
     * an office: someone going from 근무 to 외근 at 14:00 stopped being at their
     * desk at 14:00, and making them clock out first produces gaps whenever they
     * forget. A site that bills clients by the hour wants {@code REFUSE} and will
     * have to say so — which is why this is a bean rather than a constant.
     */
    @Bean
    public AttendanceSettings attendanceSettings() {
        return AttendanceSettings.fixed(OverlapPolicy.CLOSE_PREVIOUS);
    }

    // ------------------------------------------------------------------
    // Notifications

    /**
     * The placeholder notification channel.
     *
     * <p>There is no notification table and no in-app inbox yet, so the honest
     * thing a channel can do today is put the message somewhere an operator can
     * find it. It is registered rather than omitted because the approval service
     * needs at least one channel to exist, and because a notification that was
     * never delivered should at least be traceable to the approval that produced
     * it.
     *
     * <p>It follows the SPI's one hard rule: it never throws. A channel being
     * down must not roll back the approval it was reporting.
     */
    @Bean
    public Notifier loggingNotifier() {
        return new Notifier() {
            private final Logger log = LoggerFactory.getLogger("com.coreintra.approval.notify");

            @Override
            public String channel() {
                return "log";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void notify(ApprovalNotification notification) {
                try {
                    log.info("결재 알림 {}", notification);
                } catch (RuntimeException e) {
                    log.debug("notification could not be logged", e);
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // 대결: was the approver actually away?

    /**
     * Answers 대결's question from the client's own attendance rows.
     *
     * <p>"Absent" is whatever the installation configured as not
     * {@code countsAsWorking} — 휴가, 병가, 출장 — never a list of codes hard-coded
     * anywhere. A client who invents 육아휴직 gets it for free; a client who
     * decides 재택 counts as working gets that too.
     *
     * <p>Acting for an approver who is at their desk is not 대결; it is someone
     * else signing in their name, and the trail must not show the two the same
     * way.
     */
    @Bean
    public AbsenceDirectory attendanceAbsenceDirectory(final UserAccountRepository accounts,
            final AttendanceRecordRepository records,
            final AttendanceStatusTypeRepository statusTypes) {
        return new AbsenceDirectory() {
            @Override
            public boolean isAbsent(String accountId, LocalDate businessDate) {
                if (accountId == null || businessDate == null) {
                    return false;
                }
                Optional<UserAccount> account = accounts.findById(accountId);
                if (!account.isPresent() || !Texts.hasText(account.get().employeeId())) {
                    // An account with no employee behind it — a service account,
                    // a master — has no attendance to read. Not absent, rather
                    // than unknown: 대결 then falls back to its written reason.
                    return false;
                }
                List<AttendanceRecord> day = records
                        .findByEmployeeIdAndBusinessDateOrderByStartedOffsetSecondsAsc(
                                account.get().employeeId(), businessDate);
                for (AttendanceRecord record : day) {
                    Optional<AttendanceStatusType> status =
                            statusTypes.findById(record.statusTypeId());
                    if (status.isPresent() && !status.get().behaviour().countsAsWorking()) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    // ------------------------------------------------------------------
    // 휴가: approving one writes the balance transaction

    /**
     * Reads a 휴가신청서's fields out of the document body projection.
     *
     * <p>{@code document_field_value} is the documents module's own extract of a
     * body's content controls, kept precisely so that a caller can ask "how many
     * days does this request ask for" without unzipping a DOCX. The route from
     * the approval to the body is {@code approval_document.document_id}, added in
     * V14 — before it, there was no route at all until somebody signed.
     *
     * <p>Every failure here refuses rather than guesses. Defaulting the employee
     * to the drafter would be right most of the time and would, the rest of the
     * time, take the days out of the wrong person's balance.
     */
    @Bean
    public LeaveRequestDetails leaveRequestDetails(final ApprovalDocumentRepository approvals,
            final DocumentRepository documents, final DocumentFieldValueRepository fieldValues,
            final LeavePolicyStore policies) {

        return new LeaveRequestDetails() {
            @Override
            public LeaveRequest of(String approvalDocumentId) {
                ApprovalDocumentEntity approval = approvals.findById(approvalDocumentId)
                        .orElseThrow(new StateSupplier(
                                "결재 문서 " + approvalDocumentId + " 을(를) 찾을 수 없습니다."));
                if (!Texts.hasText(approval.documentId())) {
                    throw new IllegalStateException(
                            "휴가신청서에 본문이 연결되어 있지 않아 잔여 연차를 차감할 수 없습니다. (This leave "
                                    + "request has no document body attached, so there is nothing "
                                    + "to read the requested days from. Attach the 휴가신청서 body "
                                    + "and submit it again.)");
                }
                DocumentEntity body = documents.findById(approval.documentId())
                        .orElseThrow(new StateSupplier(
                                "휴가신청서 본문 " + approval.documentId() + " 을(를) 찾을 수 없습니다."));
                if (body.currentVersionNo() == null) {
                    throw new IllegalStateException(
                            "휴가신청서 본문이 아직 저장되지 않았습니다. (The leave request body has no saved "
                                    + "version, so it has no field values to read.)");
                }

                List<DocumentFieldValueEntity> fields = fieldValues
                        .findByDocumentIdAndVersionNo(body.id(), body.currentVersionNo());

                String employeeId = null;
                String policyCode = null;
                BigDecimal days = null;
                String reason = null;
                for (DocumentFieldValueEntity field : fields) {
                    if (LeaveRequestFields.EMPLOYEE.equals(field.fieldId())) {
                        employeeId = field.valueRefId();
                    } else if (LeaveRequestFields.POLICY_CODE.equals(field.fieldId())) {
                        policyCode = field.valueText();
                    } else if (LeaveRequestFields.DAYS.equals(field.fieldId())) {
                        days = field.valueNumber();
                    } else if (LeaveRequestFields.REASON.equals(field.fieldId())) {
                        reason = field.valueText();
                    }
                }

                requirePresent(employeeId, LeaveRequestFields.EMPLOYEE, "휴가 대상자");
                requirePresent(policyCode, LeaveRequestFields.POLICY_CODE, "휴가 종류");
                if (days == null) {
                    throw missing(LeaveRequestFields.DAYS, "휴가 일수");
                }
                Optional<LeaveAccrualPolicy> policy =
                        policies.findByCode(approval.companyId(), policyCode);
                if (!policy.isPresent()) {
                    throw new IllegalStateException(
                            "\"" + policyCode + "\" 휴가 정책이 이 회사에 없습니다. (No leave policy with "
                                    + "code \"" + policyCode + "\" exists in company "
                                    + approval.companyId() + ". Which policy the days come out of "
                                    + "decides the bookable unit and the expiry, so it cannot be "
                                    + "inferred.)");
                }
                return new LeaveRequest(employeeId, policy.get().id(), days, reason);
            }

            private void requirePresent(String value, String fieldId, String labelKo) {
                if (!Texts.hasText(value)) {
                    throw missing(fieldId, labelKo);
                }
            }

            private IllegalStateException missing(String fieldId, String labelKo) {
                return new IllegalStateException(
                        "휴가신청서에 " + labelKo + " 항목이 비어 있습니다. (The leave request does not carry "
                                + "a \"" + fieldId + "\" field. Approving it would have to guess "
                                + "whose balance to take the days from, so it is refused. Fill the "
                                + "field in and submit again.)");
            }
        };
    }

    /**
     * Writes the leave balance transaction as part of the approval.
     *
     * <p>Not afterwards, and not on an event bus: if the write is a separate step
     * it can fail on its own, and then the employee has an approved 휴가 and an
     * unchanged balance — a discrepancy nobody finds until they dispute it months
     * later. This runs inside the approval's transaction and before it is
     * persisted, so a refusal takes the approval down with it and nothing is
     * written anywhere.
     *
     * <p>Both calls it makes are idempotent on the 결재 document id, so an
     * approval retried after a rollback deducts once rather than twice.
     */
    @Bean
    public ApprovalOutcomeListener leaveRequestEffect(final LeaveService leave,
            final LeaveRequestDetails details) {

        return new ApprovalOutcomeListener() {
            @Override
            public boolean appliesTo(String documentType) {
                return LeaveRequestFields.DOCUMENT_TYPE.equals(documentType);
            }

            @Override
            public void documentSettled(ApprovalOutcome outcome) {
                if (outcome.state() == ApprovalState.APPROVED) {
                    LeaveRequest request = details.of(outcome.documentId());
                    leave.recordApprovedLeave(new ApprovedLeave(request.employeeId(),
                            request.policyId(), request.days(), outcome.decidedAt(),
                            outcome.documentId(), outcome.actorAccountId(), request.reason()));
                    return;
                }

                // 반려 or 회수. Whatever the approval took, it gives back.
                LeaveRequest request;
                try {
                    request = details.of(outcome.documentId());
                } catch (RuntimeException e) {
                    // An unreadable body cannot have been approved — the branch
                    // above would have refused — so there is nothing to give
                    // back. Refusing the 반려 here would trap the document with
                    // no way out, which is strictly worse than logging it.
                    LOG.warn("{} was {} and its body could not be read; nothing to reverse: {}",
                            outcome.documentId(), outcome.state(), e.getMessage());
                    return;
                }
                leave.cancelApprovedLeave(request.employeeId(), request.policyId(),
                        outcome.documentId(), outcome.decidedAt(), null,
                        outcome.actorAccountId());
            }
        };
    }

    /** {@code orElseThrow} wants a supplier, and Java 8 wants it spelled out. */
    private static final class StateSupplier
            implements java.util.function.Supplier<IllegalStateException> {
        private final String message;

        StateSupplier(String message) {
            this.message = message;
        }

        @Override
        public IllegalStateException get() {
            return new IllegalStateException(message);
        }
    }
}
