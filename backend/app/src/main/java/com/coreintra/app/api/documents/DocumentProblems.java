package com.coreintra.app.api.documents;

import com.coreintra.app.api.error.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The failures this surface answers with that {@code ApiExceptionHandler} does
 * not already own.
 *
 * <h2>Why they are returned rather than thrown</h2>
 *
 * <p>The handler maps the failures every module shares — a denial, a validation
 * error, a stale {@code If-Match}. It has no mapping for "no such document",
 * "the file is too big" or "the conversion has not finished", and it belongs to
 * nobody in particular, so adding cases to it from here would be editing a file
 * four other agents are also reading. Returning the same
 * {@code application/problem+json} shape directly keeps every failure on this
 * surface machine-readable and RFC 7807 shaped, which is what §10 asks for; the
 * {@code code} is the stable half and clients branch on it, never on the title.
 */
public final class DocumentProblems {

    private DocumentProblems() {
    }

    /** 404 with a code, because "not found" and "not allowed" must not look alike. */
    public static ResponseEntity<Object> notFound(String what, String id) {
        return problem(HttpStatus.NOT_FOUND, "not_found", "No such " + what,
                "No " + what + " has the id " + id + ".");
    }

    /** 400 for a request that is well-formed but asks for something impossible. */
    public static ResponseEntity<Object> badRequest(String code, String title, String detail) {
        return problem(HttpStatus.BAD_REQUEST, code, title, detail);
    }

    /**
     * 413. The cap is enforced here as well as by the container, because the
     * container's limit produces a message about multipart parsing that tells a
     * user nothing about which file was refused or what the limit is.
     */
    public static ResponseEntity<Object> tooLarge(String detail) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "File too large", detail);
    }

    /** 409 where the request is legal but the state is not ready for it. */
    public static ResponseEntity<Object> conflict(String code, String title, String detail) {
        return problem(HttpStatus.CONFLICT, code, title, detail);
    }

    /**
     * 422 for a request the API understood and refused on a rule of the format
     * rather than of the request — a body diff of two DOCX files, say.
     */
    public static ResponseEntity<Object> unprocessable(String code, String title, String detail) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, code, title, detail);
    }

    /**
     * 422 naming every field that failed, one violation each.
     *
     * <p>§10 requires every validation failure at once, and the template save in
     * §6.1 requires the failure to name the exact field. A generic 400 saying
     * "the schema does not match the document" leaves the user opening a docx in
     * Word and comparing content controls by hand.
     */
    public static ResponseEntity<Object> fieldViolations(String code, String title, String detail,
            java.util.List<ProblemDetail.Violation> violations) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body((Object) new ProblemDetail("about:blank", title,
                        HttpStatus.UNPROCESSABLE_ENTITY.value(), detail, code, violations, null));
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String code, String title,
            String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body((Object) ProblemDetail.of(status.value(), code, title, detail));
    }
}
