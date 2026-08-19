package com.coreintra.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImmutablesTest {

    @Test
    @DisplayName("listOf copies and refuses mutation")
    void listOfIsImmutable() {
        assertThat(Immutables.listOf("a", "b")).containsExactly("a", "b");
        assertThatThrownBy(() -> Immutables.listOf("a").add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("setOf and mapOf iterate in insertion order, unlike the JDK 9+ factories")
    void iterationOrderIsInsertionOrder() {
        Set<String> set = Immutables.setOf("z", "a", "m");
        assertThat(set).containsExactly("z", "a", "m");

        Map<String, Integer> map = Immutables.mapOf("z", 1, "a", 2, "m", 3);
        Iterator<String> keys = map.keySet().iterator();
        assertThat(keys.next()).isEqualTo("z");
        assertThat(keys.next()).isEqualTo("a");
        assertThat(keys.next()).isEqualTo("m");
    }

    @Test
    @DisplayName("nulls are rejected, with the offending index named")
    void nullsRejected() {
        assertThatThrownBy(() -> Immutables.listOf("a", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("index 1");
    }

    @Test
    @DisplayName("copyOf snapshots the source, so later source mutation does not leak")
    void copyOfSnapshots() {
        java.util.List<String> source = new java.util.ArrayList<String>(Arrays.asList("a"));
        java.util.List<String> copy = Immutables.copyOf(source);
        source.add("b");
        assertThat(copy).containsExactly("a");
    }
}
