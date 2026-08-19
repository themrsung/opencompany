package com.coreintra.app.api.org;

import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.OrgUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The org tree.
 *
 * <p>The tree is read as of a business date, because it moves: a unit created
 * in April did not exist in March, and a check on a March document must not see
 * it. The date is a query parameter rather than an implicit "now" for the reason
 * §2 gives — during the 72-hour business day, "now" is ambiguous by up to two
 * calendar days.
 *
 * <p>Moving a unit is a separate operation from renaming one, and a separate
 * permission ({@code hr.orgUnit:move}), because a move silently redirects every
 * {@code ORG_UNIT_SUBTREE} grant above it. Somebody who may correct a typo in
 * 영업1팀 is not thereby somebody who may hand that team's documents to a
 * different 본부.
 */
@RestController
@RequestMapping("/api/v1/org/units")
@Tag(name = "Org — units",
        description = "The 조직도 tree, read as of a business date and paged in depth-first order.")
public class OrgUnitController {

    private final OrgUnitService units;
    private final CurrentPrincipal currentPrincipal;

    public OrgUnitController(OrgUnitService units, CurrentPrincipal currentPrincipal) {
        this.units = units;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "The tree of one company",
            description = "Flat and path-ordered, so a parent always precedes its children and "
                    + "the client can re-nest a page without waiting for the rest.")
    public CursorPage<OrgUnitView> tree(
            @RequestParam("companyId") String companyId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return Pages.page(units.tree(caller, companyId, on), OrgUnitView.KEYS, OrgUnitView.MAPPER,
                cursor, limit);
    }

    @GetMapping("/{unitId}")
    @Operation(summary = "Read one unit")
    public ResponseEntity<OrgUnitView> read(
            @PathVariable String unitId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        return tagged(units.read(caller, unitId, BusinessDates.resolve(businessDate)));
    }

    @PostMapping
    @Operation(summary = "Create a unit",
            description = "A null parentUnitId puts it at the top of the company's tree.")
    public ResponseEntity<OrgUnitView> create(
            @Valid @RequestBody CreateUnitRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return tagged(units.create(caller, body.getCompanyId(), body.getParentUnitId(),
                body.getCode(), body.getNameKo(), on));
    }

    @PatchMapping("/{unitId}/name")
    @Operation(summary = "Rename a unit", description = "Requires If-Match.")
    public ResponseEntity<OrgUnitView> rename(
            @PathVariable String unitId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody CompanyController.NameRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, unitId, ifMatch, on);
        return tagged(units.rename(caller, unitId, body.getNameKo(), body.getNameEn(), on));
    }

    @PatchMapping("/{unitId}/sort-order")
    @Operation(summary = "Reorder a unit among its siblings", description = "Requires If-Match.")
    public ResponseEntity<OrgUnitView> setSortOrder(
            @PathVariable String unitId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody SortOrderRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, unitId, ifMatch, on);
        return tagged(units.setSortOrder(caller, unitId, body.getSortOrder(), on));
    }

    @PutMapping("/{unitId}/parent")
    @Operation(summary = "Move a unit",
            description = "Requires hr.orgUnit:move, which is not hr.orgUnit:update: a move "
                    + "redirects every subtree grant above the unit. Requires If-Match.")
    public ResponseEntity<OrgUnitView> move(
            @PathVariable String unitId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody ParentUnitRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, unitId, ifMatch, on);
        return tagged(units.move(caller, unitId, body.getParentUnitId(), on));
    }

    @DeleteMapping("/{unitId}")
    @Operation(summary = "Retire a unit",
            description = "Refused while people still hold positions in it. Requires If-Match.")
    public ResponseEntity<OrgUnitView> retire(
            @PathVariable String unitId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, unitId, ifMatch, on);
        return tagged(units.retire(caller, unitId, on));
    }

    private void requireCurrent(PermissionPrincipal caller, String unitId, String ifMatch,
            LocalDate on) {
        OrgUnit current = units.read(caller, unitId, on);
        ETags.require(ifMatch, OrgUnitView.tagOf(current), "unit " + current.nameKo());
    }

    private static ResponseEntity<OrgUnitView> tagged(OrgUnit unit) {
        return ResponseEntity.ok().eTag(OrgUnitView.tagOf(unit)).body(OrgUnitView.from(unit));
    }

    /** A new node. */
    public static class CreateUnitRequest {
        @NotBlank
        private String companyId;
        private String parentUnitId;
        @NotBlank
        @Size(max = 40)
        private String code;
        @NotBlank
        @Size(max = 200)
        private String nameKo;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public String getParentUnitId() {
            return parentUnitId;
        }

        public void setParentUnitId(String value) {
            this.parentUnitId = value;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String value) {
            this.code = value;
        }

        public String getNameKo() {
            return nameKo;
        }

        public void setNameKo(String value) {
            this.nameKo = value;
        }
    }

    /** Sibling order. */
    public static class SortOrderRequest {
        private int sortOrder;

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int value) {
            this.sortOrder = value;
        }
    }

    /** Null detaches the unit to the top of its company. */
    public static class ParentUnitRequest {
        private String parentUnitId;

        public String getParentUnitId() {
            return parentUnitId;
        }

        public void setParentUnitId(String value) {
            this.parentUnitId = value;
        }
    }
}
