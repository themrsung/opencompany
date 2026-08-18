package com.coreintra.compat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stand-ins for the Java 16+ {@code Stream.toList} family.
 *
 * <p>Note that {@code Stream.toList()} returns an unmodifiable list, while
 * {@code Collectors.toList()} does not. These methods match the Java 16
 * behaviour (unmodifiable), so a later baseline bump is a straight swap and
 * does not quietly make returned lists mutable.
 */
public final class Streams {

    private Streams() {
    }

    public static <T> List<T> toList(Stream<T> stream) {
        return Immutables.copyOf(stream.collect(Collectors.toList()));
    }

    public static <T> Set<T> toSet(Stream<T> stream) {
        return Immutables.setCopyOf(stream.collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
    }
}
