package com.coreintra.runtime.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit log has no way to remove anything (§12).
 *
 * <p>These assertions are on the API surface, not on behaviour, because that is
 * where this invariant can actually be broken. The database refuses UPDATE,
 * DELETE and TRUNCATE on {@code audit_log} with a trigger, so the SQL side is
 * covered whatever the Java does - but a repository that inherited
 * {@code deleteAll()} would still compile and still be callable from any service
 * in the tree, and would fail at runtime as an error rather than at review as an
 * argument.
 */
class AuditLogHasNoDeletePathTest {

    private static final String[] REMOVAL_WORDS = {
            "delete", "remove", "purge", "truncate", "erase", "drop", "clear", "wipe"
    };

    @Test
    @DisplayName("the audit repository does not inherit the CRUD delete methods")
    void doesNotExtendCrudRepository() {
        assertThat(CrudRepository.class.isAssignableFrom(AuditLogRepository.class))
                .as("extending JpaRepository would hand every caller deleteAll() for free")
                .isFalse();
    }

    @Test
    @DisplayName("no method on the audit repository can remove a row")
    void repositoryHasNoRemovalMethods() {
        assertThat(methodNamesOf(AuditLogRepository.class))
                .as("including inherited methods, which is the point of the check")
                .noneMatch(AuditLogHasNoDeletePathTest::soundsLikeRemoval);
    }

    @Test
    @DisplayName("no method on the audit service can remove a row")
    void serviceHasNoRemovalMethods() {
        assertThat(methodNamesOf(AuditLogService.class))
                .noneMatch(AuditLogHasNoDeletePathTest::soundsLikeRemoval);
    }

    @Test
    @DisplayName("the retention policy has no method that shortens it")
    void retentionOnlyGrows() {
        assertThat(methodNamesOf(AuditRetentionPolicyRepository.class))
                .noneMatch(AuditLogHasNoDeletePathTest::soundsLikeRemoval);
        assertThat(methodNamesOf(AuditLogService.class))
                .filteredOn(name -> name.toLowerCase(Locale.ROOT).contains("retention"))
                .containsExactlyInAnyOrder("retentionDays", "raiseRetention");
    }

    @Test
    @DisplayName("an audit row cannot be edited after it is written")
    void rowHasNoSetters() {
        assertThat(declaredMethodNamesOf(AuditLogRow.class))
                .as("an append-only table with a mutable mapping is append-only until "
                        + "someone calls a setter")
                .noneMatch(name -> name.startsWith("set"));
    }

    private static boolean soundsLikeRemoval(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        for (String word : REMOVAL_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> methodNamesOf(Class<?> type) {
        List<String> names = new ArrayList<String>();
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() != Object.class) {
                names.add(method.getName());
            }
        }
        return names;
    }

    private static List<String> declaredMethodNamesOf(Class<?> type) {
        List<String> names = new ArrayList<String>();
        for (Method method : type.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }
}
