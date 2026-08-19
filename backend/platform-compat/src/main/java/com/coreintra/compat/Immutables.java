package com.coreintra.compat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stand-ins for {@code List.of} / {@code Map.of} / {@code Set.of}, which are Java 9+.
 *
 * <p>This class exists so that the Java 8 baseline (see {@code docs/adr/0001})
 * shows up in exactly one package. When the baseline moves to 17 or 21, every
 * method here becomes a one-line delegation to the JDK equivalent and callers
 * are untouched.
 *
 * <p>Two deliberate differences from the JDK 9+ factories, both chosen because
 * this codebase relies on them:
 * <ul>
 *   <li>iteration order is <em>insertion order</em>, not unspecified - the
 *       approval line and the chart of accounts both render in declared order;</li>
 *   <li>nulls are rejected, same as the JDK, because a null in a permission set
 *       is always a bug rather than a value.</li>
 * </ul>
 */
public final class Immutables {

    private Immutables() {
    }

    @SafeVarargs
    public static <T> List<T> listOf(T... elements) {
        if (elements == null) {
            throw new NullPointerException("elements");
        }
        List<T> copy = new ArrayList<T>(elements.length);
        for (int i = 0; i < elements.length; i++) {
            copy.add(requireElement(elements[i], i));
        }
        return Collections.unmodifiableList(copy);
    }

    public static <T> List<T> copyOf(Collection<? extends T> source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        List<T> copy = new ArrayList<T>(source.size());
        int i = 0;
        for (T element : source) {
            copy.add(requireElement(element, i++));
        }
        return Collections.unmodifiableList(copy);
    }

    @SafeVarargs
    public static <T> Set<T> setOf(T... elements) {
        if (elements == null) {
            throw new NullPointerException("elements");
        }
        Set<T> copy = new LinkedHashSet<T>(Math.max(4, elements.length * 2));
        for (int i = 0; i < elements.length; i++) {
            copy.add(requireElement(elements[i], i));
        }
        return Collections.unmodifiableSet(copy);
    }

    public static <T> Set<T> setCopyOf(Collection<? extends T> source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        Set<T> copy = new LinkedHashSet<T>(Math.max(4, source.size() * 2));
        int i = 0;
        for (T element : source) {
            copy.add(requireElement(element, i++));
        }
        return Collections.unmodifiableSet(copy);
    }

    public static <K, V> Map<K, V> mapOf() {
        return Collections.emptyMap();
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1) {
        Map<K, V> map = new LinkedHashMap<K, V>();
        map.put(requireKey(k1), v1);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
        Map<K, V> map = new LinkedHashMap<K, V>();
        map.put(requireKey(k1), v1);
        map.put(requireKey(k2), v2);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
        Map<K, V> map = new LinkedHashMap<K, V>();
        map.put(requireKey(k1), v1);
        map.put(requireKey(k2), v2);
        map.put(requireKey(k3), v3);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        Map<K, V> map = new LinkedHashMap<K, V>();
        map.put(requireKey(k1), v1);
        map.put(requireKey(k2), v2);
        map.put(requireKey(k3), v3);
        map.put(requireKey(k4), v4);
        return Collections.unmodifiableMap(map);
    }

    public static <K, V> Map<K, V> mapCopyOf(Map<? extends K, ? extends V> source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(source));
    }

    /** {@code Arrays.asList} that copies, so the caller's array stays private. */
    public static <T> List<T> listOfArray(T[] elements) {
        return copyOf(Arrays.asList(elements));
    }

    /**
     * Set equivalent of {@link #listOfArray}, for a varargs array already in hand.
     *
     * <p>Present because {@link #setOf} cannot be called with an existing array
     * without an unchecked-varargs warning at every call site.
     */
    public static <T> Set<T> setOfArray(T[] elements) {
        return setCopyOf(Arrays.asList(elements));
    }

    private static <T> T requireElement(T element, int index) {
        if (element == null) {
            throw new NullPointerException("null element at index " + index);
        }
        return element;
    }

    private static <K> K requireKey(K key) {
        if (key == null) {
            throw new NullPointerException("null key");
        }
        return key;
    }
}
