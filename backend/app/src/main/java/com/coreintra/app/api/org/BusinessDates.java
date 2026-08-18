package com.coreintra.app.api.org;

import java.time.LocalDate;

/**
 * Resolves the business date a request is asking about.
 *
 * <p>Every org service takes the business date explicitly, because a permission
 * check on a past-dated document has to see the org chart as it stood on that
 * document's date and not as it stands now (ADR 0003). The API therefore lets
 * the caller state it, and only falls back to today when the caller said
 * nothing.
 *
 * <p>The fallback is deliberately here and not inside a service: a service that
 * called {@code LocalDate.now()} for itself would silently answer a different
 * question at 02:00 than at 14:00 during the 72-hour business day (ADR 0002),
 * and nobody reading the call site would know.
 */
public final class BusinessDates {

    private BusinessDates() {
    }

    /** The requested date, or today when the request did not name one. */
    public static LocalDate resolve(LocalDate requested) {
        return requested == null ? LocalDate.now() : requested;
    }
}
