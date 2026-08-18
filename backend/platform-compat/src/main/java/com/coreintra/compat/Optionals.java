package com.coreintra.compat;

import java.util.Optional;
import java.util.function.Supplier;

/** Stand-ins for {@code Optional.or} (Java 9+) and {@code Optional.stream} (Java 9+). */
public final class Optionals {

    private Optionals() {
    }

    public static <T> Optional<T> or(Optional<T> primary, Supplier<Optional<T>> fallback) {
        if (primary == null) {
            throw new NullPointerException("primary");
        }
        if (primary.isPresent()) {
            return primary;
        }
        Optional<T> alternative = fallback.get();
        if (alternative == null) {
            throw new NullPointerException("fallback returned null");
        }
        return alternative;
    }

    public static <T> java.util.stream.Stream<T> stream(Optional<T> optional) {
        return optional.isPresent()
                ? java.util.stream.Stream.of(optional.get())
                : java.util.stream.Stream.<T>empty();
    }
}
