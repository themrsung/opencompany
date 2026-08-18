package com.coreintra.app.api.documents;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Resolves the business instant a write is stamped with.
 *
 * <p>Saving a version is a business event, so it carries a
 * {@link BusinessInstant} and not a UTC timestamp (ADR 0002). The caller may
 * state it — a document typed up at 26:30 on the 30th belongs to the 30th — and
 * when they say nothing this falls back to the wall clock on today's business
 * date.
 *
 * <p>The fallback is deliberately here and not inside a service, for the reason
 * {@code BusinessDates} gives for dates: a service that stamped
 * {@code LocalTime.now()} for itself would silently answer a different question
 * at 02:00 than at 14:00 during the 72-hour day, and nobody reading the call
 * site would know. Here it is one line in the API layer, in front of the reader.
 */
public final class BusinessInstants {

    private BusinessInstants() {
    }

    /**
     * The business date a request is asking about, or today when it said nothing.
     *
     * <p>The same fallback {@code api/org/BusinessDates} makes for the org
     * surface, and made again here rather than imported so that this package
     * compiles against nothing another surface owns. The reasoning is theirs and
     * still applies: a permission check on a past-dated document has to see the
     * org chart as it stood on that date, so the caller may state it, and only
     * the API layer may default it.
     */
    public static LocalDate date(LocalDate requested) {
        return requested == null ? LocalDate.now() : requested;
    }

    /**
     * @param wire the wire form {@code YYYY-MM-DDT[-]HH:MM:SS.mmm}, or null
     * @throws com.coreintra.businesstime.BusinessInstantParseException named and
     *         mapped to a 400 by the exception handler, so a malformed instant
     *         is never quietly replaced with now
     */
    public static BusinessInstant resolve(String wire) {
        if (Texts.isBlank(wire)) {
            LocalTime now = LocalTime.now();
            return BusinessInstant.of(LocalDate.now(), now.getHour(), now.getMinute(),
                    now.getSecond());
        }
        return BusinessInstant.parse(Texts.strip(wire));
    }
}
