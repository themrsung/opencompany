package com.coreintra.app.support;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Supplies a real PostgreSQL for integration tests. Never H2.
 *
 * <p>H2 disagrees with PostgreSQL about generated columns, domain constraints,
 * {@code text_pattern_ops} indexes and half-open date ranges - every one of
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
 *
 * <h2>Why system properties rather than {@code @DynamicPropertySource}</h2>
 *
 * <p>{@code @DynamicPropertySource} is only discovered on the test class and
 * its superclasses. On a standalone helper like this one it is never called,
 * which is a silent failure: the container never starts and Boot quietly falls
 * back to the {@code localhost:5432} default in {@code application.yml}, so the
 * suite fails with "connection refused" on a machine that had Docker running
 * the whole time. {@link #requireDatabase()} is called from {@code @BeforeAll},
 * which JUnit runs before the Spring context is created, so publishing the
 * coordinates as system properties gets them into the environment in time and
 * works no matter how a test class is structured.
 */
public final class DatabaseTestSupport {

    /** Pinned: the deployment runs 16, and testing against a different major is testing something else. */
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

    private static PostgreSQLContainer<?> container;
    private static Boolean dockerAvailable;
    private static boolean published;

    private DatabaseTestSupport() {
    }

    /** True when an external database was supplied by the environment. */
    public static boolean hasExternalDatabase() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        return url != null && !url.trim().isEmpty();
    }

    /**
     * Skips the calling test, with a reason, when no database is reachable.
     *
     * <p>Called from a {@code @BeforeAll} so the skip is visible in the report
     * rather than the test appearing to have passed. When Docker is available
     * this also starts the container and publishes its coordinates, so the
     * Spring context that is built moments later connects to it.
     */
    public static void requireDatabase() {
        if (hasExternalDatabase()) {
            return;
        }
        Assumptions.assumeTrue(isDockerAvailable(),
                "No database available: set SPRING_DATASOURCE_URL, or run a Docker daemon so "
                        + "Testcontainers can start postgres:16-alpine. Integration tests "
                        + "skipped rather than passed.");
        publishContainerProperties();
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

    /**
     * The running container, or null when the database came from the environment.
     *
     * <p>Exposed for the one test that needs to run {@code pg_dump} and
     * {@code psql} the way {@code ops/backup.sh} does — inside the database
     * container, with the server's own client tools, rather than against
     * whatever version happens to be installed on the machine running the
     * build. A test of the backup that used a different dumper from production
     * would prove something adjacent to the thing that matters.
     */
    public static synchronized PostgreSQLContainer<?> containerOrNull() {
        return hasExternalDatabase() ? null : container;
    }

    private static synchronized PostgreSQLContainer<?> startContainer() {
        if (container == null) {
            container = new PostgreSQLContainer<>(IMAGE)
                    // Reused across the suite: starting a container per test class turns
                    // a 30-second run into a 10-minute one.
                    .withReuse(true);
            container.start();
        }
        return container;
    }

    /**
     * Tables the migrations themselves populate. Emptying these would leave the
     * schema present and the installation meaningless — no currencies, no rank
     * ladder, no attendance statuses — and every test that touched them would
     * fail somewhere far from the cause.
     */
    private static final String KEEP = "'flyway_schema_history', 'accounting_currency', "
            + "'attendance_status_type', 'job_function', 'leave_policy', "
            + "'leave_tenure_increment', 'rank', 'temporary_master_switch'";

    /**
     * Empties every table a test may have written to.
     *
     * <h2>Why this exists</h2>
     *
     * <p>Test classes were each rolling their own cleanup — {@code delete from
     * user_account} and a handful of others, in an order that happened to work.
     * Every new table with a foreign key to an account broke a different set of
     * them, and the failure appears in whichever class runs next rather than in
     * the one that added the table. Twelve classes were red for this reason at
     * once, in four different areas, none of them at fault.
     *
     * <p>One statement, one order, decided by the database rather than by
     * whoever wrote the test.
     *
     * <h2>session_replication_role</h2>
     *
     * <p>Set to {@code replica} for the duration, which suspends foreign-key
     * enforcement <em>and</em> user triggers. Both matter: the first means the
     * deletion order does not have to be maintained by hand, and the second is
     * the only way past {@code audit_log}'s append-only trigger, which refuses
     * a delete by design.
     *
     * <p>{@code DELETE}, not {@code TRUNCATE}. TRUNCATE refuses outright on any
     * table referenced by a foreign key, and that check is structural rather
     * than trigger-based, so the replica role does not lift it; TRUNCATE CASCADE
     * would lift it by also emptying the tables the migrations seed. On tables
     * this size the difference in speed is not measurable.
     *
     * <p>That trigger is not being weakened. It is restored before the method
     * returns, and the production guarantee — that no account can erase the
     * audit log — is unaffected: this requires a superuser session on the
     * database itself, which is not a route any account of the application has.
     */
    public static void resetSchema(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        java.util.List<String> tables = jdbc.queryForList(
                "SELECT quote_ident(tablename) FROM pg_tables "
                        + "WHERE schemaname = 'public' AND tablename NOT IN (" + KEEP + ")",
                String.class);
        if (tables.isEmpty()) {
            return;
        }
        jdbc.execute("SET session_replication_role = 'replica'");
        try {
            for (String table : tables) {
                jdbc.execute("DELETE FROM " + table);
            }
        } finally {
            jdbc.execute("SET session_replication_role = 'origin'");
        }
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
