package com.coreintra.businesstime;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;

/**
 * A moment inside a 72-hour business day.
 *
 * <p>A business day is a {@link LocalDate}. A moment within it is this pair:
 * the business date, plus an offset in seconds from that date's midnight. The
 * offset runs from {@code -86400} to {@code +172800} inclusive, so the clock
 * face reads {@code -24:00:00} to {@code +48:00:00} — a closed 72-hour window
 * centred on the business date.
 *
 * <p>That range is the whole point. A shift ending at 03:00 is stamped
 * {@code 27:00} on the business day it belongs to, not 03:00 on the next one.
 * A 22:00 briefing for tomorrow's business is {@code -02:00} on that next day.
 * The organisation decides which day work belongs to; the wall clock does not.
 *
 * <h2>Ordering is date first, then offset</h2>
 *
 * <p>{@code 2026-08-30T26:01:00.000} precedes {@code 2026-08-31T-03:22:00.000}
 * even though the second names an earlier wall-clock moment. Day-level
 * comparison always wins. This is deliberate and is what every consumer of this
 * type expects: an approval on the 30th precedes an approval on the 31st,
 * regardless of the hands on the clock.
 *
 * <p>Consequently {@link #absoluteDateTime()} — which <em>does</em> reorder
 * those two — exists only for range scans and must never be used as a sort key.
 *
 * <h2>Never compare wire strings</h2>
 *
 * <p>The wire form is designed for humans and for transport, not for sorting.
 * {@code '-'} sorts below every digit, so lexical comparison of wire strings
 * silently produces a different order. Use {@link #COMPARATOR} or
 * {@link Comparable}, both of which delegate to the same logic.
 *
 * <p>Business time is what the organisation agrees happened. It is never a
 * substitute for a UTC audit timestamp, which records what the machine
 * observed; entities carry both and never derive one from the other.
 *
 * <p>Immutable and thread-safe.
 */
public final class BusinessInstant implements Comparable<BusinessInstant>, Serializable {

    private static final long serialVersionUID = 1L;

    /** Earliest representable offset: {@code -24:00:00}. */
    public static final int MIN_OFFSET_SECONDS = -86_400;

    /** Latest representable offset: {@code +48:00:00}. */
    public static final int MAX_OFFSET_SECONDS = 172_800;

    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 3_600;

    /**
     * The canonical ordering: business date first, then offset.
     *
     * <p>Prefer this over {@link Comparable} at call sites where the ordering
     * rule matters to a reader — a sort that names its comparator is a sort
     * whose ordering was chosen rather than inherited.
     */
    public static final Comparator<BusinessInstant> COMPARATOR = new BusinessInstantComparator();

    private final LocalDate businessDate;
    private final int offsetSeconds;

    private BusinessInstant(LocalDate businessDate, int offsetSeconds) {
        this.businessDate = businessDate;
        this.offsetSeconds = offsetSeconds;
    }

    /**
     * @throws IllegalArgumentException if {@code offsetSeconds} falls outside
     *         the 72-hour window, naming the bound it broke
     * @throws NullPointerException if {@code businessDate} is null
     */
    public static BusinessInstant of(LocalDate businessDate, int offsetSeconds) {
        if (businessDate == null) {
            throw new NullPointerException("businessDate");
        }
        if (offsetSeconds < MIN_OFFSET_SECONDS) {
            throw new IllegalArgumentException(
                    "offsetSeconds " + offsetSeconds + " is before the -24:00:00 bound ("
                            + MIN_OFFSET_SECONDS + ")");
        }
        if (offsetSeconds > MAX_OFFSET_SECONDS) {
            throw new IllegalArgumentException(
                    "offsetSeconds " + offsetSeconds + " is after the +48:00:00 bound ("
                            + MAX_OFFSET_SECONDS + ")");
        }
        return new BusinessInstant(businessDate, offsetSeconds);
    }

    /** Convenience for the common case of an ordinary in-day wall time. */
    public static BusinessInstant of(LocalDate businessDate, int hours, int minutes, int seconds) {
        return of(businessDate, hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds);
    }

    /** Midnight opening the business day: offset zero. */
    public static BusinessInstant startOfDay(LocalDate businessDate) {
        return of(businessDate, 0);
    }

    /**
     * Parses the wire form {@code YYYY-MM-DDT[-]HH:MM:SS.mmm}.
     *
     * @throws BusinessInstantParseException on anything else, including a
     *         trailing {@code Z} or any timezone designator
     * @see BusinessInstantFormat#parse(String)
     */
    public static BusinessInstant parse(String wire) {
        return BusinessInstantFormat.parse(wire);
    }

    public LocalDate businessDate() {
        return businessDate;
    }

    public int offsetSeconds() {
        return offsetSeconds;
    }

    /**
     * The derived wall-clock moment, for range scans only.
     *
     * <p>This is the {@code absolute_ts} column's value. It is <em>not</em> an
     * ordering key: it reorders the two instants in this class's documentation,
     * which is exactly the bug this type exists to prevent.
     */
    public LocalDateTime absoluteDateTime() {
        return businessDate.atStartOfDay().plusSeconds(offsetSeconds);
    }

    /** True when the offset lands outside the ordinary 00:00–23:59:59 face. */
    public boolean isOutsideCalendarDay() {
        return offsetSeconds < 0 || offsetSeconds >= 86_400;
    }

    /** The wire form. Always emits three millisecond digits. */
    public String toWireString() {
        return BusinessInstantFormat.format(this);
    }

    /**
     * A new instant {@code seconds} later on the <em>same business day</em>.
     *
     * <p>The business date never rolls over: that is a domain decision, not
     * arithmetic. Exceeding the window is an error, because silently moving
     * work to another business day is precisely the corruption this model
     * prevents.
     *
     * @throws IllegalArgumentException if the result leaves the 72-hour window
     */
    public BusinessInstant plusSeconds(int seconds) {
        return of(businessDate, offsetSeconds + seconds);
    }

    public BusinessInstant plusMinutes(int minutes) {
        return plusSeconds(minutes * SECONDS_PER_MINUTE);
    }

    public BusinessInstant plusHours(int hours) {
        return plusSeconds(hours * SECONDS_PER_HOUR);
    }

    /** Same offset, different business day. */
    public BusinessInstant onBusinessDate(LocalDate other) {
        return of(other, offsetSeconds);
    }

    public boolean isBefore(BusinessInstant other) {
        return compareTo(other) < 0;
    }

    public boolean isAfter(BusinessInstant other) {
        return compareTo(other) > 0;
    }

    /** Date first, then offset. See the class documentation. */
    @Override
    public int compareTo(BusinessInstant other) {
        return COMPARATOR.compare(this, other);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BusinessInstant)) {
            return false;
        }
        BusinessInstant other = (BusinessInstant) obj;
        return offsetSeconds == other.offsetSeconds && businessDate.equals(other.businessDate);
    }

    @Override
    public int hashCode() {
        return businessDate.hashCode() * 31 + offsetSeconds;
    }

    /** The wire form, so logs and assertion failures read in the domain's terms. */
    @Override
    public String toString() {
        return toWireString();
    }
}
