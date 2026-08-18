package com.coreintra.app.api.attendance;

import com.coreintra.app.api.approval.ApiWire;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.attendance.entity.AttendanceRecord;
import com.coreintra.attendance.service.AttendanceRecordService;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starting and ending a 근태 상태, and reading a person's day.
 *
 * <h2>Every instant is the caller's, and it is business time</h2>
 *
 * <p>These endpoints do not stamp {@code now()}. Somebody clocking out at 03:00
 * from a shift that began the previous evening sends
 * {@code 2026-08-30T27:00:00.000}, and the record stays on the 30th where the
 * rest of that shift is. If the server invented the timestamp it would put the
 * end of the shift on the 31st, split one spell across two business days, and
 * make the day's hours come out negative.
 *
 * <p>A trailing {@code Z} is refused rather than trimmed. An instant that has
 * been through a timezone is an instant about a different moment, and the whole
 * 72-hour business day exists because the two are not interchangeable.
 */
@RestController
@RequestMapping("/api/v1/attendance/records")
@Tag(name = "근태 — records",
        description = "Start and end a status, and read a person's business day. Times are "
                + "business instants: a shift ending at 03:00 reads 27:00 on the day it began.")
public class AttendanceRecordController {

    private final AttendanceRecordService records;
    private final CurrentPrincipal currentPrincipal;

    public AttendanceRecordController(AttendanceRecordService records,
            CurrentPrincipal currentPrincipal) {
        this.records = records;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "One person's business day",
            description = "Every spell that belongs to the named business day, in order. A "
                    + "shift that crossed midnight appears once, on the day it began, with an "
                    + "end offset past 24:00.")
    public CursorPage<AttendanceRecordView> day(
            @RequestParam(name = "companyId") String companyId,
            @RequestParam(name = "employeeId") String employeeId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = ApiWire.onDate(businessDate);
        return ApiWire.page(records.dayOf(caller, companyId, employeeId, on), BY_START,
                new Function<AttendanceRecord, AttendanceRecordView>() {
                    @Override
                    public AttendanceRecordView apply(AttendanceRecord row) {
                        return AttendanceRecordView.from(row);
                    }
                }, cursor, limit);
    }

    @PostMapping
    @Operation(summary = "Start a status",
            description = "Opens a spell at the business instant given. Overlap rules are "
                    + "policy rather than assumption, so whether this closes a previous open "
                    + "record or is refused alongside it depends on the company's "
                    + "configuration. A status that requires approval is refused without the "
                    + "id of the 결재 document that authorised it.")
    public ResponseEntity<AttendanceRecordView> start(
            @Valid @RequestBody StartStatusRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        AttendanceRecord started = records.startStatus(caller, body.getCompanyId(),
                body.getEmployeeId(), body.getStatusCode(),
                ApiWire.instant(body.getAt(), "at"), body.getNote(),
                body.getSourceDocumentId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AttendanceRecordView.from(started));
    }

    @PostMapping("/end")
    @Operation(summary = "End the open status",
            description = "Closes the spell that is currently open for this person. The "
                    + "instant is the caller's and must be on the same business day the spell "
                    + "began, which is what keeps a shift across midnight in one piece: 03:00 "
                    + "is sent as 27:00 on the previous day, not as 03:00 on this one. "
                    + "Refused with 409 when there is nothing open.")
    public AttendanceRecordView end(@Valid @RequestBody EndStatusRequest body) {
        PermissionPrincipal caller = currentPrincipal.require();
        return AttendanceRecordView.from(records.endOpenStatusAt(caller, body.getCompanyId(),
                body.getEmployeeId(), ApiWire.instant(body.getAt(), "at")));
    }

    @PostMapping("/sweep-expired")
    @Operation(summary = "Close records whose status has timed out",
            description = "For statuses with autoExpiresAfter set: 자리비움 left open "
                    + "overnight is not a fourteen-hour absence, it is somebody who forgot. "
                    + "Returns what was closed, so a scheduled caller can log it rather than "
                    + "silently rewriting people's days.")
    public SweepResult sweep(@Valid @RequestBody SweepRequest body) {
        PermissionPrincipal caller = currentPrincipal.require();
        List<AttendanceRecord> closed = records.sweepExpired(caller, body.getCompanyId(),
                ApiWire.instant(body.getNow(), "now"));
        List<AttendanceRecordView> views = new ArrayList<AttendanceRecordView>();
        for (AttendanceRecord record : closed) {
            views.add(AttendanceRecordView.from(record));
        }
        return new SweepResult(views);
    }

    /** Ordered by when the spell began, in business time: date then offset. */
    private static final ApiWire.Keys<AttendanceRecord> BY_START =
            new ApiWire.Keys<AttendanceRecord>() {
                @Override
                public String sortKey(AttendanceRecord row) {
                    return com.coreintra.app.api.paging.Cursors.sortKey(
                            row.interval().startedAt());
                }

                @Override
                public String id(AttendanceRecord row) {
                    return row.id();
                }
            };

    /** What a sweep closed. */
    @Schema(name = "AttendanceSweepResult")
    public static class SweepResult {
        private final List<AttendanceRecordView> closed;

        SweepResult(List<AttendanceRecordView> closed) {
            this.closed = Immutables.copyOf(closed);
        }

        public List<AttendanceRecordView> getClosed() {
            return closed;
        }

        public int getCount() {
            return closed.size();
        }
    }

    /** 출근, 재택 시작, 외근 나감. */
    @Schema(name = "StartAttendanceStatusRequest")
    public static class StartStatusRequest {

        @NotBlank
        private String companyId;

        @NotBlank
        private String employeeId;

        @NotBlank(message = "상태 코드를 입력해 주십시오. (Which status is being started.)")
        private String statusCode;

        @NotBlank(message = "시각을 입력해 주십시오. (The business instant the spell began, as "
                + "YYYY-MM-DDT[-]HH:MM:SS.mmm with no timezone.)")
        private String at;

        @Size(max = 500)
        private String note;

        private String sourceDocumentId;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public String getEmployeeId() {
            return employeeId;
        }

        public void setEmployeeId(String value) {
            this.employeeId = value;
        }

        @Schema(example = "REMOTE")
        public String getStatusCode() {
            return statusCode;
        }

        public void setStatusCode(String value) {
            this.statusCode = value;
        }

        @Schema(description = "Business instant, " + ApiWire.INSTANT_PATTERN + ". The "
                + "business date inside it decides which day the spell belongs to.",
                example = "2026-08-30T18:00:00.000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        public String getAt() {
            return at;
        }

        public void setAt(String value) {
            this.at = value;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String value) {
            this.note = value;
        }

        @Schema(description = "The approved 결재 document, for a status that requires one.")
        public String getSourceDocumentId() {
            return sourceDocumentId;
        }

        public void setSourceDocumentId(String value) {
            this.sourceDocumentId = value;
        }
    }

    /** 퇴근, or the end of any open spell. */
    @Schema(name = "EndAttendanceStatusRequest")
    public static class EndStatusRequest {

        @NotBlank
        private String companyId;

        @NotBlank
        private String employeeId;

        @NotBlank(message = "시각을 입력해 주십시오.")
        private String at;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public String getEmployeeId() {
            return employeeId;
        }

        public void setEmployeeId(String value) {
            this.employeeId = value;
        }

        @Schema(description = "Business instant on the business day the spell began. A "
                + "shift that ends at 03:00 the next morning ends at 27:00:00.000 on the "
                + "starting day — sending 03:00:00.000 on the next date is a different, and "
                + "wrong, statement about when the person went home.",
                example = ApiWire.INSTANT_EXAMPLE,
                requiredMode = Schema.RequiredMode.REQUIRED)
        public String getAt() {
            return at;
        }

        public void setAt(String value) {
            this.at = value;
        }
    }

    /** Housekeeping. */
    @Schema(name = "AttendanceSweepRequest")
    public static class SweepRequest {

        @NotBlank
        private String companyId;

        @NotBlank(message = "기준 시각을 입력해 주십시오.")
        private String now;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        @Schema(description = "The business instant to sweep as of. A parameter rather than "
                + "the server's clock so that a replayed or back-dated sweep closes what it "
                + "would have closed at the time.",
                example = ApiWire.INSTANT_EXAMPLE,
                requiredMode = Schema.RequiredMode.REQUIRED)
        public String getNow() {
            return now;
        }

        public void setNow(String value) {
            this.now = value;
        }
    }
}
