package com.coreintra.core.service;

import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import java.time.LocalDate;

/**
 * The argument checks every service in this package repeats.
 *
 * <p>Small, but worth naming: {@link #caller} is the one that matters. A null
 * principal or a null business date reaching the evaluator produces a
 * {@code NullPointerException} from inside the permission engine, which reads in
 * the log like a bug in authorisation rather than a caller that forgot to say
 * who is asking and when.
 */
final class Arguments {

    private Arguments() {
    }

    /** Who is asking, and which business date they are asking about. Both mandatory. */
    static void caller(PermissionPrincipal caller, LocalDate businessDate) {
        if (caller == null) {
            throw new NullPointerException("caller");
        }
        if (businessDate == null) {
            throw new NullPointerException("businessDate");
        }
    }

    /** A mandatory string field, trimmed. Blank is a bad request, not an empty value. */
    static String required(String value, String field) {
        if (Texts.isBlank(value)) {
            throw new IllegalArgumentException(field + " is blank");
        }
        return Texts.strip(value);
    }

    /** An optional string field: trimmed, or null when there was nothing but whitespace. */
    static String optional(String value) {
        return Texts.isBlank(value) ? null : Texts.strip(value);
    }
}
