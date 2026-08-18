package com.coreintra.compat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Stand-ins for {@code String.isBlank}, {@code String.strip}, {@code String.repeat}
 * and {@code String.lines} (all Java 11+).
 *
 * <p>{@link #isBlank} follows the Java 11 definition - it uses
 * {@code Character.isWhitespace}, so it treats the ideographic space U+3000 as
 * blank. That matters here: it is easy to type into a Korean IME and a "blank"
 * check that missed it would let an empty 반려 reason through.
 */
public final class Texts {

    public static final Charset UTF_8 = StandardCharsets.UTF_8;

    private Texts() {
    }

    public static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasText(String value) {
        return !isBlank(value);
    }

    public static String strip(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    public static String repeat(String value, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count is negative: " + count);
        }
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
