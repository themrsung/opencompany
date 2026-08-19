package com.coreintra.compat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextsTest {

    @Test
    @DisplayName("the ideographic space U+3000 counts as blank")
    void ideographicSpaceIsBlank() {
        // Easy to type from a Korean IME. A 반려 reason of "　" is not a reason.
        assertThat(Texts.isBlank("　")).isTrue();
        assertThat(Texts.isBlank("　 \t")).isTrue();
        assertThat(Texts.isBlank("　거부")).isFalse();
    }

    @Test
    void stripAndRepeat() {
        assertThat(Texts.strip("  　abc 　 ")).isEqualTo("abc");
        assertThat(Texts.repeat("ab", 3)).isEqualTo("ababab");
        assertThat(Texts.repeat("ab", 0)).isEmpty();
    }
}
