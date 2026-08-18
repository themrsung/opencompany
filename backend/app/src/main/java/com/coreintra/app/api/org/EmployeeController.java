package com.coreintra.app.api.org;

import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.org.Employee;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
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
 * People.
 *
 * <p>Every row here is decided on the row: {@code hr.employee:read} at
 * {@code ORG_UNIT_SUBTREE} returns the people beneath the caller's unit and
 * silently omits the rest, which is why a page can come back shorter than the
 * limit without being the last page. The list is not filtered by a query the
 * caller wrote — it is filtered by the evaluator, one employee at a time,
 * against the org chart as it stood on the business date.
 *
 * <p>Leavers are excluded by default. They are never deleted, because a
 * document approved by someone who has since left must keep resolving their
 * name and the unit they were in at the time.
 */
@RestController
@RequestMapping("/api/v1/org/employees")
@Tag(name = "Org — employees",
        description = "The people in the organisation, filtered row by row by the permission "
                + "evaluator against the org chart as of the request's business date.")
public class EmployeeController {

    private final EmployeeService employees;
    private final CurrentPrincipal currentPrincipal;

    public EmployeeController(EmployeeService employees, CurrentPrincipal currentPrincipal) {
        this.employees = employees;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "List people in a company",
            description = "Only those the caller may read. A short page is normal — rows the "
                    + "evaluator refused are simply absent — so read nextCursor, not the count.")
    public CursorPage<EmployeeView> list(
            @RequestParam("companyId") String companyId,
            @RequestParam(name = "includeLeavers", required = false, defaultValue = "false")
            boolean includeLeavers,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return Pages.page(employees.list(caller, companyId, includeLeavers, on),
                EmployeeView.KEYS, EmployeeView.MAPPER, cursor, limit);
    }

    @GetMapping("/{employeeId}")
    @Operation(summary = "Read one person")
    public ResponseEntity<EmployeeView> read(
            @PathVariable String employeeId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        return tagged(employees.read(caller, employeeId, BusinessDates.resolve(businessDate)));
    }

    @PostMapping
    @Operation(summary = "Add a person",
            description = "Creates the person only. Where they sit and at what rank is a "
                    + "position, which is a separate operation with its own permission.")
    public ResponseEntity<EmployeeView> create(
            @Valid @RequestBody CreateEmployeeRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return tagged(employees.create(caller, body.getCompanyId(), body.getEmployeeNumber(),
                body.getNameKo(), body.getNameEn(), body.getEmail(), body.getHiredOn(), on));
    }

    @PatchMapping("/{employeeId}")
    @Operation(summary = "Correct a person's details",
            description = "A whole-record update: send every field, because an omitted one is "
                    + "cleared rather than kept. Requires If-Match.")
    public ResponseEntity<EmployeeView> update(
            @PathVariable String employeeId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateEmployeeRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, employeeId, ifMatch, on);
        return tagged(employees.update(caller, employeeId, body.getEmployeeNumber(),
                body.getNameKo(), body.getNameEn(), body.getEmail(), body.getHiredOn(), on));
    }

    @PostMapping("/{employeeId}/termination")
    @Operation(summary = "Record a leaving date",
            description = "Requires hr.employee:terminate, and If-Match. The row stays; only the "
                    + "last day is recorded, so history keeps resolving.")
    public ResponseEntity<EmployeeView> terminate(
            @PathVariable String employeeId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody TerminationRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, employeeId, ifMatch, on);
        return tagged(employees.terminate(caller, employeeId, body.getLastDay(), on));
    }

    private void requireCurrent(PermissionPrincipal caller, String employeeId, String ifMatch,
            LocalDate on) {
        Employee current = employees.read(caller, employeeId, on);
        ETags.require(ifMatch, EmployeeView.tagOf(current), "employee " + current.nameKo());
    }

    private static ResponseEntity<EmployeeView> tagged(Employee employee) {
        return ResponseEntity.ok().eTag(EmployeeView.tagOf(employee))
                .body(EmployeeView.from(employee));
    }

    /** A new person. */
    public static class CreateEmployeeRequest {
        @NotBlank
        private String companyId;
        @Size(max = 40)
        private String employeeNumber;
        @NotBlank
        @Size(max = 100)
        private String nameKo;
        @Size(max = 100)
        private String nameEn;
        @Size(max = 320)
        private String email;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate hiredOn;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public String getEmployeeNumber() {
            return employeeNumber;
        }

        public void setEmployeeNumber(String value) {
            this.employeeNumber = value;
        }

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

        public String getEmail() {
            return email;
        }

        public void setEmail(String value) {
            this.email = value;
        }

        public LocalDate getHiredOn() {
            return hiredOn;
        }

        public void setHiredOn(LocalDate value) {
            this.hiredOn = value;
        }
    }

    /** The same fields, minus the company: nobody changes entity by editing a row. */
    public static class UpdateEmployeeRequest {
        @Size(max = 40)
        private String employeeNumber;
        @NotBlank
        @Size(max = 100)
        private String nameKo;
        @Size(max = 100)
        private String nameEn;
        @Size(max = 320)
        private String email;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate hiredOn;

        public String getEmployeeNumber() {
            return employeeNumber;
        }

        public void setEmployeeNumber(String value) {
            this.employeeNumber = value;
        }

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

        public String getEmail() {
            return email;
        }

        public void setEmail(String value) {
            this.email = value;
        }

        public LocalDate getHiredOn() {
            return hiredOn;
        }

        public void setHiredOn(LocalDate value) {
            this.hiredOn = value;
        }
    }

    /** The last day worked, which is inclusive. */
    public static class TerminationRequest {
        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate lastDay;

        public LocalDate getLastDay() {
            return lastDay;
        }

        public void setLastDay(LocalDate value) {
            this.lastDay = value;
        }
    }
}
