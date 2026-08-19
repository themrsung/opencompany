package com.coreintra.accounting.support;

import java.io.File;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Supplies a real PostgreSQL, and the real migrations, for this module's integration tests.
 *
 * <h2>Why this is not {@code com.coreintra.app.support.DatabaseTestSupport}</h2>
 *
 * <p>That class lives in the application module's <em>test</em> sources, and the application module
 * depends on this one - so reaching for it would invert the dependency and no test-jar is
 * published. This is the same contract in this module's own terms: a real PostgreSQL 16 or a
 * skip with a reason, never H2 and never a silent pass.
 *
 * <h2>Why the migrations are read from the application module's resources</h2>
 *
 * <p>Because they are the ones that will run in production. A copy on this module's test classpath
 * would drift from {@code V8__accounting.sql} the first time either was edited, and the test would
 * then be checking the mapping against a schema nobody deploys. Reading the real file is the point
 * of the test: {@code ddl-auto: validate} then proves that the entities in this module agree with
 * the DDL that shipped.
 *
 * <p>Flyway is stopped at version 8. Later migrations belong to other modules being written
 * alongside this one, and a half-finished V9 elsewhere must not turn into a red build here.
 */
public final class AccountingDatabaseTestSupport {

    /** Pinned: the deployment runs 16, and testing against another major is testing something else. */
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

    private static final String MIGRATION_PATH = "app/src/main/resources/db/migration";

    private static PostgreSQLContainer<?> container;
    private static Boolean dockerAvailable;
    private static boolean published;

    private AccountingDatabaseTestSupport() {
    }

    /**
     * Skips the calling test, with a reason, when no database or no migration directory is
     * reachable. Called from {@code @BeforeAll}, which JUnit runs before Spring builds the context,
     * so the coordinates are in the environment in time.
     */
    public static void requireDatabase() {
        File migrations = findMigrations();
        Assumptions.assumeTrue(migrations != null,
                "Could not find " + MIGRATION_PATH + " above " + System.getProperty("user.dir")
                        + ". The accounting schema is owned by the application module's Flyway "
                        + "directory and this test refuses to run against a copy.");
        System.setProperty("spring.flyway.locations", "filesystem:" + migrations.getAbsolutePath());

        if (hasExternalDatabase()) {
            return;
        }
        Assumptions.assumeTrue(isDockerAvailable(),
                "No database available: set SPRING_DATASOURCE_URL, or run a Docker daemon so "
                        + "Testcontainers can start postgres:16-alpine. Integration tests skipped "
                        + "rather than passed.");
        publishContainerProperties();
    }

    /** True when an external database was supplied by the environment, as CI does. */
    public static boolean hasExternalDatabase() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        return url != null && !url.trim().isEmpty();
    }

    private static File findMigrations() {
        File directory = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 6 && directory != null; depth++) {
            File candidate = new File(directory, MIGRATION_PATH);
            if (candidate.isDirectory()) {
                return candidate;
            }
            directory = directory.getParentFile();
        }
        return null;
    }

    private static synchronized void publishContainerProperties() {
        if (published) {
            return;
        }
        PostgreSQLContainer<?> running = startContainer();
        System.setProperty("spring.datasource.url", running.getJdbcUrl());
        System.setProperty("spring.datasource.username", running.getUsername());
        System.setProperty("spring.datasource.password", running.getPassword());
        published = true;
    }

    private static synchronized PostgreSQLContainer<?> startContainer() {
        if (container == null) {
            container = new PostgreSQLContainer<>(IMAGE).withReuse(true);
            container.start();
        }
        return container;
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
