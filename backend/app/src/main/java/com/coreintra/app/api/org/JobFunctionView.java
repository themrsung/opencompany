package com.coreintra.app.api.org;

import com.coreintra.core.org.JobFunction;
import java.util.function.Function;

/**
 * A 직무 on the wire — 회계, 인사, 개발, 영업.
 *
 * <p>Independent of rank by design (§3): what a person does and how senior they
 * are are different facts, and a permission granted to 회계 must follow the
 * accountant when they are promoted.
 */
public class JobFunctionView {

    public static final Function<JobFunction, JobFunctionView> MAPPER =
            new Function<JobFunction, JobFunctionView>() {
                @Override
                public JobFunctionView apply(JobFunction jobFunction) {
                    return from(jobFunction);
                }
            };

    public static final Pages.Keys<JobFunction> KEYS = new Pages.Keys<JobFunction>() {
        @Override
        public String sortKey(JobFunction jobFunction) {
            return jobFunction.code();
        }

        @Override
        public String id(JobFunction jobFunction) {
            return jobFunction.id();
        }
    };

    private final String id;
    private final String companyId;
    private final String code;
    private final String labelKo;
    private final String labelEn;
    private final boolean active;

    JobFunctionView(String id, String companyId, String code, String labelKo, String labelEn,
            boolean active) {
        this.id = id;
        this.companyId = companyId;
        this.code = code;
        this.labelKo = labelKo;
        this.labelEn = labelEn;
        this.active = active;
    }

    public static JobFunctionView from(JobFunction jobFunction) {
        return new JobFunctionView(jobFunction.id(), jobFunction.companyId(), jobFunction.code(),
                jobFunction.labelKo(), jobFunction.labelEn(), jobFunction.isActive());
    }

    public static String tagOf(JobFunction jobFunction) {
        return OrgVersions.tag(jobFunction.id(), jobFunction.code(), jobFunction.labelKo(),
                jobFunction.labelEn(), Boolean.valueOf(jobFunction.isActive()));
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getCode() {
        return code;
    }

    public String getLabelKo() {
        return labelKo;
    }

    public String getLabelEn() {
        return labelEn;
    }

    /** Retired functions stay listed on request: history has to keep resolving. */
    public boolean isActive() {
        return active;
    }
}
