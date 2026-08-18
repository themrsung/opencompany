package com.coreintra.businesstime;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wire form: {@code YYYY-MM-DDT[-]HH:MM:SS.mmm}.
 *
 * <p>No timezone. No trailing {@code Z}. No {@code +} sign. The hour field runs
 * {@code 00}–{@code 48} and a leading {@code -} negates the whole time part, so
 * {@code 2026-08-31T-03:22:00.000} is 3h22m <em>before</em> the 31st opened.
 *
 * <p>Everything here rejects rather than repairs. A timestamp that arrives with
 * a {@code Z} was produced by something that believes this is a UTC instant,
 * and quietly accepting it would let that misunderstanding write rows. Same for
 * a non-zero millisecond field: the model is second-granularity, so accepting
 * {@code .500} would silently discard half a second.
 */
public final class BusinessInstantFormat {

    /**
     * Deliberately strict. Note the absence of any timezone alternative — those
     * are matched separately below so they can be rejected by name rather than
     * as a generic shape mismatch.
     */
    private static final Pattern WIRE = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(-)?(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})$");

    private static final Pattern LOOKS_ZONED = Pattern.compile(".*([Zz]|[+][0-9]{2}:?[0-9]{2}|GMT|UTC)\\s*$");

    private BusinessInstantFormat() {
    }

    /**
     * @throws BusinessInstantParseException naming what was wrong with the input
     */
    public static BusinessInstant parse(String wire) {
        if (wire == null) {
            throw new BusinessInstantParseException("null", "input is null");
        }
        String trimmed = wire.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessInstantParseException(wire, "input is empty");
        }

        // Reject timezone designators by name. A generic "does not match" here
        // would send the caller hunting through a regex; naming the Z tells them
        // their producer thinks this is a UTC instant, which is the real bug.
        if (LOOKS_ZONED.matcher(trimmed).matches()) {
            throw new BusinessInstantParseException(wire,
                    "business instants carry no timezone. A trailing 'Z' or UTC offset means the "
                            + "producer is sending an absolute instant; business time is not UTC and "
                            + "the two must not be conflated");
        }

        Matcher matcher = WIRE.matcher(trimmed);
        if (!matcher.matches()) {
            throw new BusinessInstantParseException(wire,
                    "expected YYYY-MM-DDT[-]HH:MM:SS.mmm (hour 00-48, optional leading '-', "
                            + "exactly three millisecond digits)");
        }

        LocalDate date = parseDate(wire, matcher);

        boolean negative = matcher.group(4) != null;
        int hours = Integer.parseInt(matcher.group(5));
        int minutes = Integer.parseInt(matcher.group(6));
        int seconds = Integer.parseInt(matcher.group(7));
        int millis = Integer.parseInt(matcher.group(8));

        if (minutes > 59) {
            throw new BusinessInstantParseException(wire, "minute field is " + minutes + ", must be 00-59");
        }
        if (seconds > 59) {
            throw new BusinessInstantParseException(wire, "second field is " + seconds + ", must be 00-59");
        }
        if (millis != 0) {
            throw new BusinessInstantParseException(wire,
                    "millisecond field is ." + matcher.group(8) + " but business instants are "
                            + "second-granularity; accepting it would silently discard precision");
        }

        long magnitude = (long) hours * 3600L + (long) minutes * 60L + (long) seconds;
        long offset = negative ? -magnitude : magnitude;

        if (offset < BusinessInstant.MIN_OFFSET_SECONDS || offset > BusinessInstant.MAX_OFFSET_SECONDS) {
            throw new BusinessInstantParseException(wire,
                    "offset " + offset + "s is outside the 72-hour window [-24:00:00, +48:00:00]");
        }
        return BusinessInstant.of(date, (int) offset);
    }

    private static LocalDate parseDate(String wire, Matcher matcher) {
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        try {
            // LocalDate.of is strict: 2026-02-30 throws rather than rolling over.
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            throw new BusinessInstantParseException(wire, "not a real calendar date: " + e.getMessage());
        }
    }

    /**
     * The canonical wire form.
     *
     * <p>Canonical means: no sign on a zero offset, hours zero-padded to at
     * least two digits, milliseconds always present as {@code .000}. Parsing a
     * non-canonical but valid input (e.g. {@code -00:00:00.000}) and formatting
     * it again yields the canonical spelling.
     */
    public static String format(BusinessInstant instant) {
        if (instant == null) {
            throw new NullPointerException("instant");
        }
        int offset = instant.offsetSeconds();
        boolean negative = offset < 0;
        int magnitude = Math.abs(offset);

        int hours = magnitude / 3600;
        int minutes = (magnitude % 3600) / 60;
        int seconds = magnitude % 60;

        StringBuilder out = new StringBuilder(28);
        out.append(instant.businessDate().toString()).append('T');
        if (negative) {
            out.append('-');
        }
        appendTwo(out, hours);
        out.append(':');
        appendTwo(out, minutes);
        out.append(':');
        appendTwo(out, seconds);
        out.append(".000");
        return out.toString();
    }

    private static void appendTwo(StringBuilder out, int value) {
        if (value < 10) {
            out.append('0');
        }
        out.append(value);
    }
}
