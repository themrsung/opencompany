package com.coreintra.core.service;

import com.coreintra.compat.Immutables;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직무 - what the person actually does, as against what rung they stand on.
 *
 * <p>Independent of {@link com.coreintra.core.org.Rank} and many-to-many with
 * employees, because a 과장 in 회계 and a 과장 in 영업 sit at the same rung and do
 * entirely different work, and permissions follow the work at least as often as
 * they follow the rung.
 *
 * <p>Like the ladder, the catalogue is the client's: labels, codes and how many
 * there are. Nothing in the system compares a 직무 label against a fixed string.
 */
@Service
public class JobFunctionService {

    private final JobFunctionCatalogRepository jobFunctions;
    private final PositionAssignmentRepository positions;
    private final PermissionEvaluator evaluator;

    public JobFunctionService(JobFunctionCatalogRepository jobFunctions, PositionAssignmentRepository positions,
            PermissionEvaluator evaluator) {
        if (jobFunctions == null) {
            throw new NullPointerException("jobFunctions");
        }
        if (positions == null) {
            throw new NullPointerException("positions");
        }
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        this.jobFunctions = jobFunctions;
        this.positions = positions;
        this.evaluator = evaluator;
    }

    /** The catalogue of a company. Retired entries are included only when asked for. */
    @Transactional(readOnly = true)
    public List<JobFunction> list(PermissionPrincipal caller, String companyId, boolean includeRetired,
            LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        String company = Arguments.required(companyId, "companyId");
        evaluator.check(caller, OrgPermissions.JOB_FUNCTION_READ, companyTarget(company, businessDate)).orThrow();

        List<JobFunction> visible = new ArrayList<JobFunction>();
        for (JobFunction jobFunction : jobFunctions.findByCompanyIdOrderByCodeAsc(company)) {
            if (includeRetired || jobFunction.isActive()) {
                visible.add(jobFunction);
            }
        }
        return Immutables.copyOf(visible);
    }

    @Transactional
    public JobFunction create(PermissionPrincipal caller, String companyId, String code, String labelKo,
            String labelEn, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        String company = Arguments.required(companyId, "companyId");
        evaluator.check(caller, OrgPermissions.JOB_FUNCTION_CREATE, companyTarget(company, businessDate)).orThrow();

        String cleanCode = Arguments.required(code, "code");
        String cleanLabel = Arguments.required(labelKo, "labelKo");
        for (JobFunction existing : jobFunctions.findByCompanyIdOrderByCodeAsc(company)) {
            if (existing.code().equals(cleanCode)) {
                // Retired entries count towards the constraint, so they count here.
                throw new IllegalArgumentException(
                        "job function code is already in use in this company: " + cleanCode);
            }
        }

        JobFunction jobFunction = new JobFunction(UUID.randomUUID().toString(), company, cleanCode, cleanLabel);
        jobFunction.relabel(cleanLabel, Arguments.optional(labelEn));
        return jobFunctions.save(jobFunction);
    }

    @Transactional
    public JobFunction relabel(PermissionPrincipal caller, String jobFunctionId, String labelKo, String labelEn,
            LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        JobFunction jobFunction = require(jobFunctionId);
        evaluator.check(caller, OrgPermissions.JOB_FUNCTION_UPDATE,
                companyTarget(jobFunction.companyId(), businessDate)).orThrow();
        jobFunction.relabel(Arguments.required(labelKo, "labelKo"), Arguments.optional(labelEn));
        return jobFunctions.save(jobFunction);
    }

    /**
     * Takes a 직무 out of use.
     *
     * <p>Refused while live positions still perform it, for the same reason as a
     * rank: grants attached to a 직무 reach people through their position, and
     * retiring it takes authority away from everyone performing it without saying
     * so.
     */
    @Transactional
    public JobFunction retire(PermissionPrincipal caller, String jobFunctionId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        JobFunction jobFunction = require(jobFunctionId);
        evaluator.check(caller, OrgPermissions.JOB_FUNCTION_RETIRE,
                companyTarget(jobFunction.companyId(), businessDate)).orThrow();

        long performing = positions.countLiveWithJobFunction(jobFunction.id(), businessDate);
        if (performing > 0) {
            throw new IllegalArgumentException("job function " + jobFunction.labelKo() + " is performed by "
                    + performing + " live position(s) on " + businessDate + "; reassign them first");
        }
        jobFunction.deactivate();
        return jobFunctions.save(jobFunction);
    }

    private JobFunction require(String jobFunctionId) {
        Optional<JobFunction> found = jobFunctions.findById(Arguments.required(jobFunctionId, "jobFunctionId"));
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("job function", jobFunctionId);
        }
        return found.get();
    }

    private static PermissionTarget companyTarget(String companyId, LocalDate businessDate) {
        return OrgTargets.company(companyId, businessDate, "job functions of company " + companyId);
    }
}
