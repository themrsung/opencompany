package com.coreintra.app.api.org;

import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.org.Position;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.PositionService;
import com.coreintra.core.service.RecordNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
 * Positions — the assignment tuple that the whole permission model rests on.
 *
 * <p>A position is {@code (employee, unit, rank, 직무[], from, to)} and history
 * is preserved rather than overwritten. That is the difference between an org
 * chart and an audit trail: reassigning someone closes the old row and opens a
 * new one, so a check on a document dated before the move still resolves the
 * unit they were in when they approved it.
 *
 * <p>Everything is nested under the employee, including the mutations. That is
 * not REST decoration — {@code PositionService} exposes history by employee and
 * no read by position id, so the current state an {@code If-Match} is checked
 * against can only be found through the person. A flat {@code /positions/{id}}
 * would have needed a repository lookup in this class to find the employee, and
 * a controller holding a repository fails the build.
 */
@RestController
@RequestMapping("/api/v1/org")
@Tag(name = "Org — positions",
        description = "Who sits where, at what rank, over which interval. The history is the "
                + "record; nothing is overwritten.")
public class PositionController {

    private final PositionService positions;
    private final CurrentPrincipal currentPrincipal;

    public PositionController(PositionService positions, CurrentPrincipal currentPrincipal) {
        this.positions = positions;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/employees/{employeeId}/positions")
    @Operation(summary = "One person's assignment history",
            description = "Chronological, including closed intervals.")
    public CursorPage<PositionView> history(
            @PathVariable String employeeId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return Pages.page(positions.history(caller, employeeId, on), PositionView.KEYS,
                PositionView.MAPPER, cursor, limit);
    }

    @GetMapping("/units/{unitId}/positions")
    @Operation(summary = "Who is in a unit on a date",
            description = "Live assignments only, as of the business date — the roster behind "
                    + "the who's-in view.")
    public CursorPage<PositionView> roster(
            @PathVariable String unitId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return Pages.page(positions.liveInUnit(caller, unitId, on), PositionView.KEYS,
                PositionView.MAPPER, cursor, limit);
    }

    @PostMapping("/employees/{employeeId}/positions")
    @Operation(summary = "Assign someone to a unit at a rank",
            description = "effectiveFrom is inclusive. Marking it primary closes any other "
                    + "primary assignment from the same day.")
    public ResponseEntity<PositionView> assign(
            @PathVariable String employeeId,
            @Valid @RequestBody AssignRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return tagged(positions.assign(caller, employeeId, body.getOrgUnitId(), body.getRankId(),
                body.getJobFunctionIds(), body.getEffectiveFrom(), body.isPrimary(), on));
    }

    @PatchMapping("/employees/{employeeId}/positions/{positionId}")
    @Operation(summary = "Reassign: close this position and open its successor",
            description = "Returns the new position. The old one is closed the day the new one "
                    + "begins, never deleted. Requires If-Match on the position being closed.")
    public ResponseEntity<PositionView> reassign(
            @PathVariable String employeeId,
            @PathVariable String positionId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ReassignRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, employeeId, positionId, ifMatch, on);
        return tagged(positions.reassign(caller, positionId, body.getOrgUnitId(),
                body.getRankId(), body.getJobFunctionIds(), body.getEffectiveFrom(), on));
    }

    @PostMapping("/employees/{employeeId}/positions/{positionId}/closure")
    @Operation(summary = "End an assignment on a date",
            description = "A closure, not a deletion: the interval stays readable so past-dated "
                    + "checks keep resolving. Requires If-Match.")
    public ResponseEntity<PositionView> close(
            @PathVariable String employeeId,
            @PathVariable String positionId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ClosureRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, employeeId, positionId, ifMatch, on);
        return tagged(positions.close(caller, positionId, body.getEndsOn(), on));
    }

    private void requireCurrent(PermissionPrincipal caller, String employeeId, String positionId,
            String ifMatch, LocalDate on) {
        for (Position position : positions.history(caller, employeeId, on)) {
            if (position.id().equals(positionId)) {
                ETags.require(ifMatch, PositionView.tagOf(position), "position " + positionId);
                return;
            }
        }
        throw new RecordNotFoundException(
                "no such position for this employee: " + positionId);
    }

    private static ResponseEntity<PositionView> tagged(Position position) {
        return ResponseEntity.ok().eTag(PositionView.tagOf(position))
                .body(PositionView.from(position));
    }

    /** A new assignment. */
    public static class AssignRequest {
        @NotBlank
        private String orgUnitId;
        @NotBlank
        private String rankId;
        private List<String> jobFunctionIds;
        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate effectiveFrom;
        private boolean primary;

        public String getOrgUnitId() {
            return orgUnitId;
        }

        public void setOrgUnitId(String value) {
            this.orgUnitId = value;
        }

        public String getRankId() {
            return rankId;
        }

        public void setRankId(String value) {
            this.rankId = value;
        }

        /** May be empty: a person can hold a position before their 직무 is decided. */
        public List<String> getJobFunctionIds() {
            return jobFunctionIds == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(jobFunctionIds);
        }

        public void setJobFunctionIds(List<String> value) {
            this.jobFunctionIds = value;
        }

        public LocalDate getEffectiveFrom() {
            return effectiveFrom;
        }

        public void setEffectiveFrom(LocalDate value) {
            this.effectiveFrom = value;
        }

        /** The assignment that answers "which team is this person on". */
        public boolean isPrimary() {
            return primary;
        }

        public void setPrimary(boolean value) {
            this.primary = value;
        }
    }

    /** Where they are moving to, and from when. */
    public static class ReassignRequest {
        @NotBlank
        private String orgUnitId;
        @NotBlank
        private String rankId;
        private List<String> jobFunctionIds;
        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate effectiveFrom;

        public String getOrgUnitId() {
            return orgUnitId;
        }

        public void setOrgUnitId(String value) {
            this.orgUnitId = value;
        }

        public String getRankId() {
            return rankId;
        }

        public void setRankId(String value) {
            this.rankId = value;
        }

        public List<String> getJobFunctionIds() {
            return jobFunctionIds == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(jobFunctionIds);
        }

        public void setJobFunctionIds(List<String> value) {
            this.jobFunctionIds = value;
        }

        public LocalDate getEffectiveFrom() {
            return effectiveFrom;
        }

        public void setEffectiveFrom(LocalDate value) {
            this.effectiveFrom = value;
        }
    }

    /** The day the assignment ends. Exclusive, matching effectiveTo. */
    public static class ClosureRequest {
        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate endsOn;

        public LocalDate getEndsOn() {
            return endsOn;
        }

        public void setEndsOn(LocalDate value) {
            this.endsOn = value;
        }
    }
}
