package com.coreintra.app.api.org;

import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.JobFunctionService;
import com.coreintra.core.service.RecordNotFoundException;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 직무 — what a person actually does, independent of how senior they are.
 *
 * <p>Retired functions are excluded by default and included on request. Both
 * behaviours are needed and they are not the same question: an admin filling in
 * a form wants the live catalogue, and an admin reading last year's grant chain
 * needs the 직무 that has since been retired, or the explanation has a hole in
 * it exactly where it matters.
 */
@RestController
@RequestMapping("/api/v1/org/companies/{companyId}/job-functions")
@Tag(name = "Org — job functions",
        description = "The 직무 catalogue: 회계, 인사, 개발, 영업 and whatever else the client "
                + "defines. Many-to-many with people, and independent of rank.")
public class JobFunctionController {

    private final JobFunctionService jobFunctions;
    private final CurrentPrincipal currentPrincipal;

    public JobFunctionController(JobFunctionService jobFunctions, CurrentPrincipal currentPrincipal) {
        this.jobFunctions = jobFunctions;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "The catalogue of one company",
            description = "Live entries only unless includeRetired is set.")
    public CursorPage<JobFunctionView> list(
            @PathVariable String companyId,
            @RequestParam(name = "includeRetired", required = false, defaultValue = "false")
            boolean includeRetired,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return Pages.page(jobFunctions.list(caller, companyId, includeRetired, on),
                JobFunctionView.KEYS, JobFunctionView.MAPPER, cursor, limit);
    }

    @PostMapping
    @Operation(summary = "Add a 직무")
    public ResponseEntity<JobFunctionView> create(
            @PathVariable String companyId,
            @Valid @RequestBody CreateJobFunctionRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return tagged(jobFunctions.create(caller, companyId, body.getCode(), body.getLabelKo(),
                body.getLabelEn(), on));
    }

    @PatchMapping("/{jobFunctionId}/labels")
    @Operation(summary = "Relabel a 직무", description = "Requires If-Match.")
    public ResponseEntity<JobFunctionView> relabel(
            @PathVariable String companyId,
            @PathVariable String jobFunctionId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody RankController.LabelsRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, companyId, jobFunctionId, ifMatch, on);
        return tagged(jobFunctions.relabel(caller, jobFunctionId, body.getLabelKo(),
                body.getLabelEn(), on));
    }

    @DeleteMapping("/{jobFunctionId}")
    @Operation(summary = "Retire a 직무",
            description = "Refused while anyone is assigned to it. Requires If-Match.")
    public ResponseEntity<JobFunctionView> retire(
            @PathVariable String companyId,
            @PathVariable String jobFunctionId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, companyId, jobFunctionId, ifMatch, on);
        return tagged(jobFunctions.retire(caller, jobFunctionId, on));
    }

    private void requireCurrent(PermissionPrincipal caller, String companyId, String jobFunctionId,
            String ifMatch, LocalDate on) {
        // Retired entries included: relabelling one is legitimate (a typo in a
        // 직무 that people held last year still shows up in the explainer), and
        // excluding them here would answer "no such 직무" for a row that exists.
        for (JobFunction jobFunction : jobFunctions.list(caller, companyId, true, on)) {
            if (jobFunction.id().equals(jobFunctionId)) {
                ETags.require(ifMatch, JobFunctionView.tagOf(jobFunction),
                        "job function " + jobFunction.labelKo());
                return;
            }
        }
        throw new RecordNotFoundException("no such job function in this company: " + jobFunctionId);
    }

    private static ResponseEntity<JobFunctionView> tagged(JobFunction jobFunction) {
        return ResponseEntity.ok().eTag(JobFunctionView.tagOf(jobFunction))
                .body(JobFunctionView.from(jobFunction));
    }

    /** A new 직무. */
    public static class CreateJobFunctionRequest {
        @NotBlank
        @Size(max = 40)
        private String code;
        @NotBlank
        @Size(max = 100)
        private String labelKo;
        @Size(max = 100)
        private String labelEn;

        public String getCode() {
            return code;
        }

        public void setCode(String value) {
            this.code = value;
        }

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
    }
}
