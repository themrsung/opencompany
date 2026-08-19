package com.coreintra.app.api.attendance;

import com.coreintra.app.api.approval.ApiNotFoundException;
import com.coreintra.app.api.approval.ApiWire;
import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.attendance.domain.StatusBehaviour;
import com.coreintra.attendance.entity.AttendanceStatusType;
import com.coreintra.attendance.service.AttendancePermissions;
import com.coreintra.attendance.service.AttendanceStatusService;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 근태 상태 유형 — the built-in six and whatever else the client needs.
 *
 * <h2>Behaviour is data, not code</h2>
 *
 * <p>§5 makes custom statuses first-class, which means there is no branch
 * anywhere on {@code code == "REMOTE"}. What a status does is decided entirely
 * by its flags, so a client that invents 교육 or 파견 gets a status that works
 * exactly as well as 재택 does, and one that renames 외근 changes only what
 * people read.
 *
 * <h2>Redefinition is not retroactive</h2>
 *
 * <p>Changing what a status means changes what it means from now on. The records
 * already written were made under the old meaning and keep it, which is why
 * {@code PATCH .../behaviour} exists at all instead of clients being told to
 * retire and redefine — retiring 교육 to fix a flag would strand a year of
 * records against a status nobody can look up.
 *
 * <h2>Two notes on what this controller had to do for itself</h2>
 *
 * <p>{@code AttendanceStatusService.active} takes no principal and checks
 * nothing — it is an internal lookup that the record and board services call
 * after they have already authorised the caller. Exposing it needed a gate, so
 * the read here goes through the same {@link PermissionEvaluator} as everything
 * else. That check belongs on the service and is noted as such.
 *
 * <p>There is likewise no authorised read of a single status by id, so the
 * {@code If-Match} preconditions resolve the current row out of the active list.
 * A retired status therefore cannot be relabelled through the API. That is a
 * small loss and a real one.
 */
@RestController
@RequestMapping("/api/v1/attendance/status-types")
@Tag(name = "근태 — status types",
        description = "근무, 재택, 외근, 자리비움, 휴가, 퇴근 and any status the client "
                + "defines, with the behaviour flags that decide what each one does.")
public class AttendanceStatusController {

    private final AttendanceStatusService statuses;
    private final PermissionEvaluator permissions;
    private final CurrentPrincipal currentPrincipal;

    public AttendanceStatusController(AttendanceStatusService statuses,
            PermissionEvaluator permissions, CurrentPrincipal currentPrincipal) {
        this.statuses = statuses;
        this.permissions = permissions;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "The statuses a company can choose from",
            description = "Active only, in the order the client arranged them. Retired ones "
                    + "are excluded: they exist so that old records still resolve, not so "
                    + "that anybody can pick them again.")
    public CursorPage<AttendanceStatusView> list(
            @RequestParam(name = "companyId") String companyId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        authoriseRead(caller, companyId, ApiWire.onDate(businessDate));
        return ApiWire.page(statuses.active(companyId), BY_SORT_ORDER,
                new Function<AttendanceStatusType, AttendanceStatusView>() {
                    @Override
                    public AttendanceStatusView apply(AttendanceStatusType row) {
                        return AttendanceStatusView.from(row);
                    }
                }, cursor, limit);
    }

    @PostMapping
    @Operation(summary = "Define a status",
            description = "Requires attendance.status:admin. The code is stable and cannot "
                    + "repeat within a company, because records refer to statuses by it. A "
                    + "status that deducts leave must also require approval — otherwise it "
                    + "spends somebody's balance with nobody having agreed to it — and the "
                    + "combination is refused at definition time rather than at use.")
    public ResponseEntity<AttendanceStatusView> define(
            @Valid @RequestBody DefineStatusRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        AttendanceStatusType defined = statuses.define(caller, body.getCompanyId(),
                body.getCode(), body.getLabelKo(), body.getLabelEn(), body.getColour(),
                body.getIcon(), body.behaviour(), body.getSortOrder(),
                ApiWire.onDate(businessDate));
        return tagged(defined, HttpStatus.CREATED);
    }

    @PatchMapping("/{statusId}/labels")
    @Operation(summary = "Rename or restyle a status",
            description = "Labels and colours are display only; nothing branches on them. "
                    + "Requires If-Match.")
    public ResponseEntity<AttendanceStatusView> relabel(
            @PathVariable String statusId,
            @RequestParam(name = "companyId") String companyId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody RelabelStatusRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = ApiWire.onDate(businessDate);
        requireCurrent(caller, companyId, statusId, ifMatch, on);
        return tagged(statuses.relabel(caller, companyId, statusId, body.getLabelKo(),
                body.getLabelEn(), body.getColour(), body.getIcon(), on), HttpStatus.OK);
    }

    @PatchMapping("/{statusId}/behaviour")
    @Operation(summary = "Change what a status means",
            description = "Applies from now on. Records already written keep the meaning "
                    + "they were written under, which is a property of the ledger rather than "
                    + "something this endpoint enforces. Requires If-Match.")
    public ResponseEntity<AttendanceStatusView> redefine(
            @PathVariable String statusId,
            @RequestParam(name = "companyId") String companyId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody BehaviourRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = ApiWire.onDate(businessDate);
        requireCurrent(caller, companyId, statusId, ifMatch, on);
        return tagged(statuses.redefine(caller, companyId, statusId, body.toBehaviour(), on),
                HttpStatus.OK);
    }

    @DeleteMapping("/{statusId}")
    @Operation(summary = "Retire a status",
            description = "It can no longer be chosen. Existing records keep pointing at it: "
                    + "deleting the row would leave a year of timesheets referring to nothing, "
                    + "and the person who asks why their 교육 days vanished would be right to "
                    + "be annoyed. Requires If-Match.")
    public ResponseEntity<Void> retire(
            @PathVariable String statusId,
            @RequestParam(name = "companyId") String companyId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = ApiWire.onDate(businessDate);
        requireCurrent(caller, companyId, statusId, ifMatch, on);
        statuses.retire(caller, companyId, statusId, on);
        return ResponseEntity.noContent().build();
    }

    private void authoriseRead(PermissionPrincipal caller, String companyId, LocalDate on) {
        permissions.check(caller, AttendancePermissions.ATTENDANCE_READ,
                PermissionTarget.builder()
                        .companyId(companyId)
                        .asOfBusinessDate(on)
                        .description("attendance status types of " + companyId)
                        .build()).orThrow();
    }

    /**
     * Holds the caller to the version they read.
     *
     * <p>The read is authorised first for its own sake: without it the 404 below
     * would tell somebody who may not see this company's statuses whether a
     * given id is one of them.
     */
    private void requireCurrent(PermissionPrincipal caller, String companyId, String statusId,
            String ifMatch, LocalDate on) {
        authoriseRead(caller, companyId, on);
        List<AttendanceStatusType> active = statuses.active(companyId);
        for (AttendanceStatusType status : active) {
            if (status.id().equals(statusId)) {
                ETags.require(ifMatch, AttendanceStatusView.tagOf(status),
                        "attendance status " + status.labelKo());
                return;
            }
        }
        throw new ApiNotFoundException("no active attendance status " + statusId
                + " in company " + companyId);
    }

    private static ResponseEntity<AttendanceStatusView> tagged(AttendanceStatusType status,
            HttpStatus httpStatus) {
        return ResponseEntity.status(httpStatus)
                .eTag(AttendanceStatusView.tagOf(status))
                .body(AttendanceStatusView.from(status));
    }

    /** Sorted the way the client arranged the board, with the code as the tiebreaker. */
    private static final ApiWire.Keys<AttendanceStatusType> BY_SORT_ORDER =
            new ApiWire.Keys<AttendanceStatusType>() {
                @Override
                public String sortKey(AttendanceStatusType row) {
                    return ApiWire.number(row.sortOrder()) + ':' + row.code();
                }

                @Override
                public String id(AttendanceStatusType row) {
                    return row.id();
                }
            };

    /** The behaviour flags, on their own, for redefinition. */
    @Schema(name = "AttendanceStatusBehaviourRequest")
    public static class BehaviourRequest {

        private boolean countsAsWorking;
        private boolean requiresApproval;
        private boolean deductsLeaveBalance;
        private boolean visibleToPeers = true;
        private Long autoExpiresAfterSeconds;

        public boolean isCountsAsWorking() {
            return countsAsWorking;
        }

        public void setCountsAsWorking(boolean value) {
            this.countsAsWorking = value;
        }

        public boolean isRequiresApproval() {
            return requiresApproval;
        }

        public void setRequiresApproval(boolean value) {
            this.requiresApproval = value;
        }

        @Schema(description = "Must be accompanied by requiresApproval; the combination "
                + "without it is refused.")
        public boolean isDeductsLeaveBalance() {
            return deductsLeaveBalance;
        }

        public void setDeductsLeaveBalance(boolean value) {
            this.deductsLeaveBalance = value;
        }

        @Schema(description = "Defaults to true. False keeps the status off colleagues' "
                + "who's-in board, where the person shows as 비공개.")
        public boolean isVisibleToPeers() {
            return visibleToPeers;
        }

        public void setVisibleToPeers(boolean value) {
            this.visibleToPeers = value;
        }

        @Schema(description = "Sweep an open record in this status closed after this many "
                + "seconds. Omit for a status that stays open until somebody ends it.")
        public Long getAutoExpiresAfterSeconds() {
            return autoExpiresAfterSeconds;
        }

        public void setAutoExpiresAfterSeconds(Long value) {
            this.autoExpiresAfterSeconds = value;
        }

        StatusBehaviour toBehaviour() {
            return StatusBehaviour.builder()
                    .countsAsWorking(countsAsWorking)
                    .requiresApproval(requiresApproval)
                    .deductsLeaveBalance(deductsLeaveBalance)
                    .visibleToPeers(visibleToPeers)
                    .autoExpiresAfter(autoExpiresAfterSeconds == null
                            ? null : Duration.ofSeconds(autoExpiresAfterSeconds.longValue()))
                    .build();
        }
    }

    /** A new status. */
    @Schema(name = "DefineAttendanceStatusRequest")
    public static class DefineStatusRequest extends BehaviourRequest {

        @NotBlank
        private String companyId;

        @NotBlank(message = "a status needs a stable code; records refer to it by this")
        @Size(max = 40)
        private String code;

        @NotBlank(message = "상태 이름(한국어)을 입력해 주십시오. (A status needs a Korean label: "
                + "Korean is the default locale and a status with no Korean name is unusable "
                + "on the board it appears on.)")
        @Size(max = 60)
        private String labelKo;

        @Size(max = 60)
        private String labelEn;

        private String colour;
        private String icon;
        private int sortOrder;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        @Schema(example = "TRAINING")
        public String getCode() {
            return code;
        }

        public void setCode(String value) {
            this.code = value;
        }

        @Schema(example = "교육")
        public String getLabelKo() {
            return labelKo;
        }

        public void setLabelKo(String value) {
            this.labelKo = value;
        }

        public String getLabelEn() {
            return labelEn;
        }

        public void setLabelEn(String value) {
            this.labelEn = value;
        }

        public String getColour() {
            return colour;
        }

        public void setColour(String value) {
            this.colour = value;
        }

        public String getIcon() {
            return icon;
        }

        public void setIcon(String value) {
            this.icon = value;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int value) {
            this.sortOrder = value;
        }

        StatusBehaviour behaviour() {
            return toBehaviour();
        }
    }

    /** A rename or a restyle. */
    @Schema(name = "RelabelAttendanceStatusRequest")
    public static class RelabelStatusRequest {

        @NotBlank(message = "상태 이름(한국어)을 입력해 주십시오.")
        @Size(max = 60)
        private String labelKo;

        @Size(max = 60)
        private String labelEn;

        private String colour;
        private String icon;

        public String getLabelKo() {
            return labelKo;
        }

        public void setLabelKo(String value) {
            this.labelKo = value;
        }

        public String getLabelEn() {
            return labelEn;
        }

        public void setLabelEn(String value) {
            this.labelEn = value;
        }

        public String getColour() {
            return colour;
        }

        public void setColour(String value) {
            this.colour = value;
        }

        public String getIcon() {
            return icon;
        }

        public void setIcon(String value) {
            this.icon = value;
        }
    }
}
