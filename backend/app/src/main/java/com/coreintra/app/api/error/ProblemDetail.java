package com.coreintra.app.api.error;

import com.coreintra.compat.Immutables;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RFC 7807 problem+json.
 *
 * <p>Carries a machine-readable {@code code} alongside the human title, and
 * returns <b>every</b> validation failure at once. Returning them one at a time
 * turns a form with four mistakes into four round trips, which users experience
 * as the software arguing with them.
 */
public class ProblemDetail {

    /** One field-level failure. */
    public static class Violation {
        private final String field;
        private final String code;
        private final String message;

        public Violation(String field, String code, String message) {
            this.field = field;
            this.code = code;
            this.message = message;
        }

        public String getField() {
            return field;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }

    private final String type;
    private final String title;
    private final int status;
    private final String detail;
    private final String code;
    private final List<Violation> violations;
    private final Map<String, Object> extensions;

    public ProblemDetail(String type, String title, int status, String detail, String code,
            List<Violation> violations, Map<String, Object> extensions) {
        this.type = type;
        this.title = title;
        this.status = status;
        this.detail = detail;
        this.code = code;
        this.violations = violations == null
                ? Immutables.<Violation>listOf()
                : Immutables.copyOf(violations);
        this.extensions = extensions == null
                ? Immutables.<String, Object>mapOf()
                : Immutables.mapCopyOf(extensions);
    }

    public static ProblemDetail of(int status, String code, String title, String detail) {
        return new ProblemDetail("about:blank", title, status, detail, code,
                new ArrayList<Violation>(), null);
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public int getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    /** Stable, machine-readable. Clients branch on this, never on the title. */
    public String getCode() {
        return code;
    }

    public List<Violation> getViolations() {
        return violations;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }
}
