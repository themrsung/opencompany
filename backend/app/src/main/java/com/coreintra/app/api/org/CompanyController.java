package com.coreintra.app.api.org;

import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.org.Company;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.CompanyService;
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
 * Legal entities — 본사, 지사, 자회사.
 *
 * <p>Multi-entity from day one (§3), so this is a collection and not a
 * singleton settings page: an installation that starts with one company must
 * not need a migration to acquire a second.
 *
 * <h2>Why each change is its own sub-resource</h2>
 *
 * <p>Renaming a company, correcting its registration number and re-parenting it
 * under another entity are three different amounts of authority and three
 * different service methods, each with its own check. Collapsing them into one
 * {@code PATCH} body would mean deciding, in this class, which permission a
 * given combination of fields needs — a second authorisation rule living
 * outside the evaluator, which is exactly what ADR 0003 forbids.
 */
@RestController
@RequestMapping("/api/v1/org/companies")
@Tag(name = "Org — companies",
        description = "Legal entities: 본사, 지사 and 자회사. Every read and write is decided by "
                + "the permission evaluator on the company row itself.")
public class CompanyController {

    private final CompanyService companies;
    private final CurrentPrincipal currentPrincipal;

    public CompanyController(CompanyService companies, CurrentPrincipal currentPrincipal) {
        this.companies = companies;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "List companies",
            description = "Only the entities the caller may read, cursor-paginated. A short page "
                    + "is not the end of the collection; a null nextCursor is.")
    public CursorPage<CompanyView> list(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return Pages.page(companies.list(caller, on), CompanyView.KEYS, CompanyView.MAPPER,
                cursor, limit);
    }

    @GetMapping("/{companyId}")
    @Operation(summary = "Read one company",
            description = "Carries the ETag that a later mutation must present as If-Match.")
    public ResponseEntity<CompanyView> read(
            @PathVariable String companyId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return tagged(companies.read(caller, companyId, on));
    }

    @PostMapping
    @Operation(summary = "Incorporate a company",
            description = "Requires admin.company:create — authority over 본사 is not authority "
                    + "to add a subsidiary beside it.")
    public ResponseEntity<CompanyView> create(
            @Valid @RequestBody CreateCompanyRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        Company created = companies.create(caller, body.getCode(), body.getNameKo(),
                kind(body.getKind()), body.getParentCompanyId(), on);
        return tagged(created);
    }

    @PatchMapping("/{companyId}/name")
    @Operation(summary = "Rename a company", description = "Requires If-Match.")
    public ResponseEntity<CompanyView> rename(
            @PathVariable String companyId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody NameRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, companyId, ifMatch, on);
        return tagged(companies.rename(caller, companyId, body.getNameKo(), body.getNameEn(), on));
    }

    @PatchMapping("/{companyId}/registration")
    @Operation(summary = "Correct the registration details",
            description = "사업자등록번호, base currency and the incorporation date. Requires If-Match.")
    public ResponseEntity<CompanyView> updateRegistration(
            @PathVariable String companyId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody RegistrationRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, companyId, ifMatch, on);
        return tagged(companies.updateRegistration(caller, companyId,
                body.getBusinessRegistrationNumber(), body.getBaseCurrencyCode(),
                body.getEstablishedOn(), on));
    }

    @PutMapping("/{companyId}/parent")
    @Operation(summary = "Re-parent a company",
            description = "A null parentCompanyId detaches it to the top level. Requires If-Match.")
    public ResponseEntity<CompanyView> reparent(
            @PathVariable String companyId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody ParentRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, companyId, ifMatch, on);
        return tagged(companies.reparent(caller, companyId, body.getParentCompanyId(), on));
    }

    @DeleteMapping("/{companyId}")
    @Operation(summary = "Deactivate a company",
            description = "Nothing is deleted: the row is marked inactive and its history stays "
                    + "readable. Requires If-Match.")
    public ResponseEntity<CompanyView> deactivate(
            @PathVariable String companyId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, companyId, ifMatch, on);
        return tagged(companies.deactivate(caller, companyId, on));
    }

    /**
     * Reads the row the caller claims to have read, and enforces the precondition.
     *
     * <p>The read is permission-checked in its own right, which is the intended
     * consequence: you cannot overwrite a company you were never allowed to look
     * at, and the 403 names {@code company.settings:read} rather than the write
     * permission, which is the truthful answer.
     */
    private void requireCurrent(PermissionPrincipal caller, String companyId, String ifMatch,
            LocalDate on) {
        Company current = companies.read(caller, companyId, on);
        ETags.require(ifMatch, CompanyView.tagOf(current), "company " + current.code());
    }

    private static Company.CompanyKind kind(String value) {
        try {
            return Company.CompanyKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            // Rethrown with the alternatives spelled out: the enum's own message
            // is the constant name and the class name, which tells a client
            // nothing it can act on.
            throw new IllegalArgumentException("kind must be one of HEAD_OFFICE (본사), "
                    + "BRANCH (지사) or SUBSIDIARY (자회사); got '" + value + "'");
        }
    }

    private static ResponseEntity<CompanyView> tagged(Company company) {
        return ResponseEntity.ok().eTag(CompanyView.tagOf(company)).body(CompanyView.from(company));
    }

    /** A new legal entity. */
    public static class CreateCompanyRequest {
        @NotBlank
        @Size(max = 40)
        private String code;
        @NotBlank
        @Size(max = 200)
        private String nameKo;
        @NotBlank
        private String kind;
        private String parentCompanyId;

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

        /** HEAD_OFFICE, BRANCH or SUBSIDIARY. */
        public String getKind() {
            return kind;
        }

        public void setKind(String value) {
            this.kind = value;
        }

        public String getParentCompanyId() {
            return parentCompanyId;
        }

        public void setParentCompanyId(String value) {
            this.parentCompanyId = value;
        }
    }

    /** Both names at once: a rename that set only one would silently strand the other. */
    public static class NameRequest {
        @NotBlank
        @Size(max = 200)
        private String nameKo;
        @Size(max = 200)
        private String nameEn;

        public String getNameKo() {
            return nameKo;
        }

        public void setNameKo(String value) {
            this.nameKo = value;
        }

        public String getNameEn() {
            return nameEn;
        }

        public void setNameEn(String value) {
            this.nameEn = value;
        }
    }

    /** 사업자등록번호 and friends. */
    public static class RegistrationRequest {
        private String businessRegistrationNumber;
        private String baseCurrencyCode;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate establishedOn;

        public String getBusinessRegistrationNumber() {
            return businessRegistrationNumber;
        }

        public void setBusinessRegistrationNumber(String value) {
            this.businessRegistrationNumber = value;
        }

        public String getBaseCurrencyCode() {
            return baseCurrencyCode;
        }

        public void setBaseCurrencyCode(String value) {
            this.baseCurrencyCode = value;
        }

        public LocalDate getEstablishedOn() {
            return establishedOn;
        }

        public void setEstablishedOn(LocalDate value) {
            this.establishedOn = value;
        }
    }

    /** Null means "no parent", which is a legitimate state and not a missing field. */
    public static class ParentRequest {
        private String parentCompanyId;

        public String getParentCompanyId() {
            return parentCompanyId;
        }

        public void setParentCompanyId(String value) {
            this.parentCompanyId = value;
        }
    }
}
