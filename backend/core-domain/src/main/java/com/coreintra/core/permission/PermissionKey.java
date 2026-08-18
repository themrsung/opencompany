package com.coreintra.core.permission;

import com.coreintra.compat.Texts;
import java.io.Serializable;

/**
 * A permission, written {@code resource:action} — {@code accounting.entry:post},
 * {@code hr.employee:read}.
 *
 * <p>Resources are dotted and hierarchical; actions are single words. Two
 * wildcard forms are supported, and only these two:
 *
 * <ul>
 *   <li>{@code accounting.entry:*} — every action on that resource;</li>
 *   <li>{@code accounting.*:post} — that action on every resource beneath
 *       {@code accounting}. A trailing {@code .*} matches descendants, not the
 *       prefix itself: {@code accounting.*} does not match a bare
 *       {@code accounting} resource.</li>
 * </ul>
 *
 * <p>There is no {@code *:*}. A grant that means "everything" would make the
 * explainer useless exactly when it matters most, and master status is an
 * account flag with its own explicit handling rather than a wildcard grant.
 */
public final class PermissionKey implements Serializable, Comparable<PermissionKey> {

    private static final long serialVersionUID = 1L;
    private static final String WILDCARD = "*";

    private final String resource;
    private final String action;

    private PermissionKey(String resource, String action) {
        this.resource = resource;
        this.action = action;
    }

    public static PermissionKey of(String resource, String action) {
        if (Texts.isBlank(resource)) {
            throw new IllegalArgumentException("resource is blank");
        }
        if (Texts.isBlank(action)) {
            throw new IllegalArgumentException("action is blank");
        }
        String trimmedResource = Texts.strip(resource);
        String trimmedAction = Texts.strip(action);
        if (trimmedResource.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "resource must not contain ':' — write it as resource:action: " + resource);
        }
        if (WILDCARD.equals(trimmedResource)) {
            throw new IllegalArgumentException(
                    "a bare '*' resource is not permitted; grant the specific resource trees "
                            + "instead, so the effective-permissions explainer stays readable");
        }
        return new PermissionKey(trimmedResource, trimmedAction);
    }

    /** Parses {@code resource:action}. */
    public static PermissionKey parse(String text) {
        if (Texts.isBlank(text)) {
            throw new IllegalArgumentException("permission is blank");
        }
        String trimmed = Texts.strip(text);
        int colon = trimmed.indexOf(':');
        if (colon < 0 || colon != trimmed.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "expected exactly one ':' in resource:action, got: " + text);
        }
        return of(trimmed.substring(0, colon), trimmed.substring(colon + 1));
    }

    public String resource() {
        return resource;
    }

    public String action() {
        return action;
    }

    public boolean isWildcard() {
        return WILDCARD.equals(action) || resource.endsWith(".*");
    }

    /**
     * True when this key — possibly a wildcard — authorises {@code required},
     * which must be concrete.
     *
     * <p>Direction matters: a granted {@code accounting.*:post} matches a
     * required {@code accounting.entry:post}, never the reverse.
     */
    public boolean matches(PermissionKey required) {
        if (required.isWildcard()) {
            throw new IllegalArgumentException(
                    "the required permission must be concrete, not a wildcard: " + required);
        }
        return actionMatches(required.action) && resourceMatches(required.resource);
    }

    private boolean actionMatches(String requiredAction) {
        return WILDCARD.equals(action) || action.equals(requiredAction);
    }

    private boolean resourceMatches(String requiredResource) {
        if (resource.equals(requiredResource)) {
            return true;
        }
        if (!resource.endsWith(".*")) {
            return false;
        }
        // "accounting.*" covers "accounting.entry" but not "accounting" itself,
        // and not "accountingx.entry".
        String prefix = resource.substring(0, resource.length() - 1);
        return requiredResource.startsWith(prefix);
    }

    /** How specific this key is. Used only to order the explainer, never to decide. */
    public int specificity() {
        int score = WILDCARD.equals(action) ? 0 : 1_000;
        score += resource.endsWith(".*") ? resource.length() : resource.length() * 2;
        return score;
    }

    @Override
    public int compareTo(PermissionKey other) {
        int byResource = resource.compareTo(other.resource);
        return byResource != 0 ? byResource : action.compareTo(other.action);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PermissionKey)) {
            return false;
        }
        PermissionKey other = (PermissionKey) obj;
        return resource.equals(other.resource) && action.equals(other.action);
    }

    @Override
    public int hashCode() {
        return resource.hashCode() * 31 + action.hashCode();
    }

    @Override
    public String toString() {
        return resource + ":" + action;
    }
}
