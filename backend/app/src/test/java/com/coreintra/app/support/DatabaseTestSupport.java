package com.coreintra.app.support;

import org.junit.jupiter.api.Assumptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Supplies a real PostgreSQL for integration tests. Never H2.
 *
 * <p>H2 disagrees with PostgreSQL about generated columns, domain constraints,
 * {@code text_pattern_ops} indexes and half-open date ranges — every one of
 * which this schema relies on. A test that passes against H2 tells you nothing
 * about whether the migration works.
 *
 * <h2>Where the database comes from</h2>
 *
 * <ol>
 *   <li>{@code SPRING_DATASOURCE_URL} if set. This is how CI runs (a service
 *       container) and how a developer points at a local server.</li>
 *   <li>Otherwise Testcontainers, if a Docker daemon is reachable.</li>
 *   <li>Otherwise the test is <b>skipped with a reason</b>, not silently
 *       passed. A green build that ran no integration tests is a lie.</li>
 * </ol>
 */
public final class DatabaseTestSupport {

    /** Pinned: the deployment runs 16, and testing against a different major is testing something else. */
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

    private static PostgreSQLContainer<?> container;
    private static Boolean dockerAvailable;

    private DatabaseTestSupport() {
    }

    /** True when an external database was supplied by the environment. */
    public static boolean hasExternalDatabase() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        return url != null && !url.trim().isEmpty();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        if (hasExternalDatabase()) {
            // Boot already reads SPRING_DATASOURCE_* from the environment.
            return;
        }
        PostgreSQLContainer<?> running = startContainer();
        registry.add("spring.datasource.url", running::getJdbcUrl);
        registry.add("spring.datasource.username", running::getUsername);
        registry.add("spring.datasource.password", running::getPassword);
    }

    private static synchronized PostgreSQLContainer<?> startContainer() {
        if (container == null) {
            container = new PostgreSQLContainer<>(IMAGE);
            // Reused across the suite: starting a container per test class turns
            // a 30-second run into a 10-minute one.
            container.withReuse(true);
            container.start();
        }
        return container;
    }

    /**
     * Skips the calling test, with a reason, when no database is reachable.
     *
     * <p>Called from a {@code @BeforeAll} so the skip is visible in the report
     * rather than the test appearing to have passed.
     */
    public static void requireDatabase() {
        if (hasExternalDatabase()) {
            return;
        }
        Assumptions.assumeTrue(isDockerAvailable(),
                "No database available: set SPRING_DATASOURCE_URL, or run a Docker daemon so "
                        + "Testcontainers can start postgres:16-alpine. Integration tests are "
                        + "skipped rather than passed.");
    }

    private static synchronized boolean isDockerAvailable() {
        if (dockerAvailable == null) {
            try {
                dockerAvailable = Boolean.valueOf(
                        org.testcontainers.DockerClientFactory.instance().isDockerAvailable());
            } catch (RuntimeException e) {
                dockerAvailable = Boolean.FALSE;
            }
        }
        return dockerAvailable.booleanValue();
    }
}
