package com.coreintra.app.api.attendance;

import com.coreintra.app.api.approval.ApiWire;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.attendance.service.WhoIsInEntry;
import com.coreintra.attendance.service.WhosInService;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The who's-in board — the other screen §12 says this product is judged on.
 *
 * <h2>Membership is resolved as of the date, not as of now</h2>
 *
 * <p>Ask for last month's board and you get last month's team. A board that
 * resolved membership against today's org chart would show the person who joined
 * on Monday as having been absent all of the previous month, and would omit the
 * person who left.
 *
 * <p>Filtering by org unit is the ordinary case and filtering by company is the
 * exception, so {@code orgUnitId} is the parameter and omitting it widens the
 * question deliberately. The company board costs one query per unit to resolve
 * membership; units are tens rather than thousands, so that is a small fixed
 * cost and the honest one — there is no "employees of a company as of a date"
 * query to use instead.
 */
@RestController
@RequestMapping("/api/v1/whos-in")
@Tag(name = "근태 — who's in",
        description = "The team board for a business date, filtered by org unit, honouring "
                + "each status's visibleToPeers flag.")
public class WhosInController {

    private final WhosInService board;
    private final CurrentPrincipal currentPrincipal;

    public WhosInController(WhosInService board, CurrentPrincipal currentPrincipal) {
        this.board = board;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "Who is in, on a business date",
            description = "Give orgUnitId for one team, or omit it for the whole company. "
                    + "Membership is resolved as of the date asked about. A colleague whose "
                    + "status is not visibleToPeers still appears, labelled 비공개 with "
                    + "visible=false — leaving them out would itself say something about "
                    + "them. Somebody who has recorded nothing appears with a null status, "
                    + "because a blank line is information too.")
    public CursorPage<WhoIsInView> board(
            @RequestParam(name = "companyId") String companyId,
            @RequestParam(name = "orgUnitId", required = false) String orgUnitId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = ApiWire.onDate(businessDate);
        List<WhoIsInEntry> entries = Texts.isBlank(orgUnitId)
                ? board.forCompany(caller, companyId, on)
                : board.forUnit(caller, companyId, Texts.strip(orgUnitId), on);

        return ApiWire.page(entries, BY_EMPLOYEE,
                new Function<WhoIsInEntry, WhoIsInView>() {
                    @Override
                    public WhoIsInView apply(WhoIsInEntry entry) {
                        return WhoIsInView.from(entry);
                    }
                }, cursor, limit);
    }

    /**
     * By employee id.
     *
     * <p>Not by name: the board carries no name to sort on, and not by status
     * either, because a board that reordered itself as people came and went
     * would move the row under the cursor of someone reading it.
     */
    private static final ApiWire.Keys<WhoIsInEntry> BY_EMPLOYEE =
            new ApiWire.Keys<WhoIsInEntry>() {
                @Override
                public String sortKey(WhoIsInEntry entry) {
                    return entry.employeeId();
                }

                @Override
                public String id(WhoIsInEntry entry) {
                    return entry.employeeId();
                }
            };
}
