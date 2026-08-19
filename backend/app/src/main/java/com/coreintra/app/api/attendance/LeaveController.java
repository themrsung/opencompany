package com.coreintra.app.api.attendance;

import com.coreintra.app.api.approval.ApiWire;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.attendance.service.LeaveService;
import com.coreintra.core.permission.PermissionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 연차 — balances, the ledger behind them, and what a request would cost.
 *
 * <h2>Reads only, and that is not an omission</h2>
 *
 * <p>Leave is spent by 결재: a 휴가 request is a document, it flows through its
 * line, and the approval is what writes the balance transaction (§5). There is
 * deliberately no "deduct three days" endpoint, because one would be a way to
 * spend somebody's leave without anybody having approved it. Grants, carry-over
 * and corrections are policy-driven or explicitly reasoned writes that belong to
 * an HR screen with its own permission; they are not on this controller.
 *
 * <h2>Days are decimal strings</h2>
 *
 * <p>Half-days and quarter-days are ordinary and 0.25 is not representable in
 * binary floating point. Nothing here parses a day count out of a JSON number.
 */
@RestController
@RequestMapping("/api/v1/leave")
@Tag(name = "근태 — leave",
        description = "연차 balances, the ledger they are computed from, expiry warnings, "
                + "and a quote for a request before it is submitted.")
public class LeaveController {

    private final LeaveService leave;
    private final CurrentPrincipal currentPrincipal;

    public LeaveController(LeaveService leave, CurrentPrincipal currentPrincipal) {
        this.leave = leave;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/balance")
    @Operation(summary = "The balance on a date",
            description = "Computed from the ledger rows every time rather than stored, so "
                    + "it cannot drift away from them. A past date gives the balance as it "
                    + "stood then: grants that had not vested are excluded and days that had "
                    + "already lapsed are gone.")
    public LeaveViews.Balance balance(
            @RequestParam(name = "companyId") String companyId,
            @RequestParam(name = "employeeId") String employeeId,
            @RequestParam(name = "policyId") String policyId,
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = ApiWire.onDate(asOf);
        return new LeaveViews.Balance(employeeId, policyId, on,
                leave.balanceOn(caller, companyId, employeeId, policyId, on));
    }

    @GetMapping("/ledger")
    @Operation(summary = "Where the days went",
            description = "Every grant, use, carry-over, expiry and correction, in "
                    + "business-time order, with the reason on each. This is the screen that "
                    + "answers 'I thought I had twelve days' without anybody having to open a "
                    + "database.")
    public LeaveViews.Ledger ledger(
            @RequestParam(name = "companyId") String companyId,
            @RequestParam(name = "employeeId") String employeeId,
            @RequestParam(name = "policyId") String policyId,
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = ApiWire.onDate(asOf);
        return new LeaveViews.Ledger(policyId, on,
                leave.ledger(caller, companyId, employeeId, policyId, on));
    }

    @GetMapping("/quote")
    @Operation(summary = "What a request would cost",
            description = "Rounds the request up to the policy's minimum bookable unit and "
                    + "says whether the balance covers it. Advice, not a reservation: the same "
                    + "arithmetic runs again and is authoritative when the 휴가 is approved, "
                    + "and a colleague booking the same days in between changes the answer. "
                    + "requestedDays is an exact decimal string — 0.25 is a real request.")
    public LeaveViews.Quote quote(
            @RequestParam(name = "companyId") String companyId,
            @RequestParam(name = "employeeId") String employeeId,
            @RequestParam(name = "policyId") String policyId,
            @RequestParam(name = "requestedDays") String requestedDays,
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = ApiWire.onDate(asOf);
        BigDecimal days = ApiWire.amount(requestedDays, "requestedDays");
        if (days == null) {
            throw new IllegalArgumentException(
                    "'requestedDays' is required, as an exact decimal string such as \"0.5\"");
        }
        return new LeaveViews.Quote(
                leave.quote(caller, companyId, employeeId, policyId, days, on));
    }

    @GetMapping("/expiring")
    @Operation(summary = "Grants that will lapse",
            description = "Drives the '연차가 곧 소멸됩니다' reminder. Give a horizon; the "
                    + "answer is the grants whose unused remainder expires on or before it.")
    public LeaveViews.Expiring expiring(
            @RequestParam(name = "companyId") String companyId,
            @RequestParam(name = "employeeId") String employeeId,
            @RequestParam(name = "policyId") String policyId,
            @RequestParam(name = "by")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate by,
            @RequestParam(name = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = ApiWire.onDate(asOf);
        return new LeaveViews.Expiring(employeeId, policyId, by, on,
                leave.expiringBy(caller, companyId, employeeId, policyId, by, on));
    }
}
