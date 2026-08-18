package com.coreintra.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * No two API types may share a simple name.
 *
 * <h2>What went wrong without this</h2>
 *
 * <p>springdoc names every schema by the class's <em>simple</em> name. Two
 * controllers each declared a nested {@code IssueRequest} — one for issuing an
 * API key, one for issuing a temporary master session — and the second silently
 * overwrote the first in {@code components.schemas}. The document then told
 * anyone generating a client that {@code POST /account/api-keys} takes a company
 * name, a list of capabilities and a representative quorum.
 *
 * <p>Nothing failed. The spec was valid, the client generated, and the only
 * symptom was a request body that would have been rejected at runtime for
 * reasons no error message would have explained. It was found by a person
 * reading the generated types, which is not a control.
 *
 * <h2>Why the whole API package rather than just request bodies</h2>
 *
 * <p>Distinguishing "types that become schemas" from types that do not means
 * predicting springdoc's resolution, which is the thing that surprised us in the
 * first place. Every class under {@code com.coreintra.app.api} having a distinct
 * simple name is a stronger rule, trivially satisfiable, and costs a prefix on
 * the rare occasion two areas want the same word.
 */
class SchemaNamesAreUniqueTest {

    @Test
    @DisplayName("two API types with one simple name would silently overwrite each other's schema")
    void simpleNamesAreUnique() {
        Map<String, List<String>> byName = new LinkedHashMap<String, List<String>>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // A name pattern, not AssignableTypeFilter(Object.class): the hierarchy
        // filter stops at java.lang.Object and matches nothing, which would make
        // this test pass by scanning zero classes.
        scanner.addIncludeFilter(new RegexPatternTypeFilter(java.util.regex.Pattern.compile(".*")));

        for (BeanDefinition definition : scanner.findCandidateComponents("com.coreintra.app.api")) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                Class<?> type = ClassUtils.forName(className, getClass().getClassLoader());
                consider(byName, type);
                for (Class<?> nested : type.getDeclaredClasses()) {
                    consider(byName, nested);
                }
            } catch (ClassNotFoundException | LinkageError e) {
                // Not loadable is not a name clash.
                continue;
            }
        }

        assertThat(byName)
                .as("scanning must actually find the API classes, or this test proves nothing")
                .isNotEmpty();

        List<String> clashes = new ArrayList<String>();
        for (Map.Entry<String, List<String>> entry : byName.entrySet()) {
            if (entry.getValue().size() > 1) {
                clashes.add(entry.getKey() + " → " + entry.getValue());
            }
        }

        assertThat(clashes)
                .as("springdoc keys components.schemas by simple name, so the second of these "
                        + "silently replaces the first and the committed contract then describes "
                        + "the wrong request body. Prefix one of them with its area.")
                .isEmpty();
    }

    /**
     * Only what springdoc could turn into a schema.
     *
     * <p>Interfaces and non-public types never become one — the three
     * {@code Keys<T>} lambda interfaces in the paging helpers are the example —
     * and a test class is not part of the contract at all. Including either
     * would make this fail for reasons that are not the bug it exists to catch,
     * and a rule that cries wolf gets an exclusion added rather than a name
     * changed.
     */
    private static void consider(Map<String, List<String>> byName, Class<?> type) {
        if (type.isInterface() || type.isSynthetic() || type.isAnonymousClass()
                || !java.lang.reflect.Modifier.isPublic(type.getModifiers())) {
            return;
        }
        Class<?> outermost = type;
        while (outermost.getEnclosingClass() != null) {
            outermost = outermost.getEnclosingClass();
        }
        String outerName = outermost.getSimpleName();
        if (outerName.endsWith("Test") || outerName.endsWith("IT")
                || outerName.endsWith("Fixture") || outerName.endsWith("Support")
                || outerName.endsWith("World")) {
            return;
        }
        record(byName, type.getName());
    }

    private static void record(Map<String, List<String>> byName, String className) {
        String simple = className.substring(Math.max(className.lastIndexOf('.'),
                className.lastIndexOf('$')) + 1);
        List<String> holders = byName.get(simple);
        if (holders == null) {
            holders = new ArrayList<String>(1);
            byName.put(simple, holders);
        }
        if (!holders.contains(className)) {
            holders.add(className);
        }
    }
}
