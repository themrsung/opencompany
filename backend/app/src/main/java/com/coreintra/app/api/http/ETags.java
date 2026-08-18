package com.coreintra.app.api.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Entity tags for optimistic concurrency on mutations.
 *
 * <p>The problem this solves is ordinary and expensive: two people open the same
 * 지출결의서, both edit, and the second save silently discards the first. Nobody
 * finds out until the numbers are wrong. With {@code If-Match} the second save
 * fails with a 412 naming the conflict, and the person can reload and redo their
 * change knowingly.
 *
 * <h2>Version counter, not content hash</h2>
 *
 * <p>The tag is derived from the row's version and id rather than by hashing the
 * serialised representation. Hashing the response would make the tag depend on
 * things that are not the resource — the caller's locale, whether the
 * full-decimal toggle was on, a field added to the DTO — so two clients looking
 * at the identical row would hold different tags and block each other for no
 * reason.
 *
 * <p>Tags are <b>strong</b>. A weak tag means "semantically equivalent", and
 * there is no such thing here: either the row is at the version you read or your
 * write is based on something that no longer exists.
 */
public final class ETags {

    private ETags() {
    }

    /**
     * @param resourceId the row id
     * @param version    the row's monotonically increasing version
     */
    public static String of(String resourceId, long version) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId");
        }
        return "\"" + version + "-" + shortDigest(resourceId) + "\"";
    }

    /**
     * True when the client's {@code If-Match} authorises writing over {@code current}.
     *
     * <p>{@code *} matches anything that exists, per RFC 9110, and is how a
     * client says "I do not care which version, only that it is there".
     */
    public static boolean matches(String ifMatchHeader, String current) {
        if (ifMatchHeader == null) {
            return false;
        }
        String header = ifMatchHeader.trim();
        if ("*".equals(header)) {
            return true;
        }
        for (String candidate : header.split(",")) {
            String tag = candidate.trim();
            // A weak tag is not acceptable for a write, so W/ prefixes are not
            // stripped here: they simply will not match.
            if (tag.equals(current)) {
                return true;
            }
        }
        return false;
    }

    private static String shortDigest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            byte[] head = new byte[6];
            System.arraycopy(hash, 0, head, 0, head.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(head);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE this runs on.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Thrown when {@code If-Match} is absent on a mutation that requires it. Maps to 428. */
    public static class PreconditionRequiredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PreconditionRequiredException(String resource) {
            super("This change needs an If-Match header carrying the version of the " + resource
                    + " you read. Without it a concurrent edit would be overwritten silently.");
        }
    }

    /** Thrown when {@code If-Match} names a version that is no longer current. Maps to 412. */
    public static class PreconditionFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String currentTag;

        public PreconditionFailedException(String currentTag) {
            super("Someone else changed this first. Reload and reapply your change.");
            this.currentTag = currentTag;
        }

        public String currentTag() {
            return currentTag;
        }
    }

    /**
     * Enforces the precondition, or throws.
     *
     * <p>A missing header is a distinct failure from a stale one, and they need
     * different responses: the first is a client that has not implemented
     * concurrency control, the second is a user who needs to reload.
     */
    public static void require(String ifMatchHeader, String currentTag, String resource) {
        if (ifMatchHeader == null || ifMatchHeader.trim().isEmpty()) {
            throw new PreconditionRequiredException(resource);
        }
        if (!matches(ifMatchHeader, currentTag)) {
            throw new PreconditionFailedException(currentTag);
        }
    }
}
