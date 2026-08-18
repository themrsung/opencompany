package com.coreintra.businesstime;

/**
 * Thrown when a string is not a valid {@link BusinessInstant} wire form.
 *
 * <p>Carries the offending input so the message is actionable. Validation
 * errors reach the API as RFC 7807 problem+json and a user needs to see what
 * they actually sent, not just that it was wrong.
 */
public class BusinessInstantParseException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String input;

    public BusinessInstantParseException(String input, String reason) {
        super("Cannot parse business instant \"" + input + "\": " + reason);
        this.input = input;
    }

    /** The rejected input, verbatim. */
    public String input() {
        return input;
    }
}
