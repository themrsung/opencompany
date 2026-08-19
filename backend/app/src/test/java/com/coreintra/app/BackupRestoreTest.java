package com.coreintra.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.support.DatabaseTestSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves the backup restores. In CI, not in a runbook.
 *
 * <p>§1 asks that {@code make restore} be proven to work in a test, and
 * {@code STATUS.md} recorded it as proven by a script only. The distinction
 * matters: a script somebody runs when they remember is not a guarantee, and
 * the first time anyone finds out a dump is unusable must not be during an
 * outage.
 *
 * <p>This runs the same {@code pg_dump} invocation {@code ops/backup.sh} runs,
 * in the same place — inside the database container — and then restores it
 * <b>twice</b> into a scratch database. Twice, because the second restore is the
 * case nobody tests by hand: applying a dump over a database that already has
 * something on it. A dump that only restores onto a pristine box is a dump
 * nobody can use in an emergency.
 *
 * <p>The scratch database matters too. A {@code --clean} restore drops and
 * recreates every table; aimed at the database the rest of the suite is using,
 * it would invalidate the connection pool and delete whatever the other test
 * classes had set up, and the resulting failures would look like anything except
 * a backup test being careless.
 *
 * <p>What this does not cover is the archive packaging — the blobs, fonts and
 * config that travel with the dump. Those need a composed stack and are checked
 * by {@code ops/verify-backup.sh}, because the four pieces are only meaningful
 * together.
 */
@SpringBootTest
@ActiveProfiles("test")
class BackupRestoreTest {

    /** Inside the container, so the dump never crosses the host boundary. */
    private static final String DUMP_PATH = "/tmp/coreintra-backup.sql";

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Test
    @DisplayName("a pg_dump of this schema restores, and restores again over a populated database")
    void dumpAndRestore() throws Exception {
        PostgreSQLContainer<?> postgres = DatabaseTestSupport.containerOrNull();
        Assumptions.assumeTrue(postgres != null,
                "This test runs pg_dump inside the database container, the way ops/backup.sh "
                        + "does, so it needs Testcontainers rather than an externally supplied "
                        + "SPRING_DATASOURCE_URL. CI runs it in its own job.");

        String marker = "backup-" + System.nanoTime();
        String scratch = "restore_check_" + Math.abs(System.nanoTime() % 100_000L);
        String user = postgres.getUsername();
        String database = postgres.getDatabaseName();

        try (Connection connection = dataSource.getConnection()) {
            seedCompany(connection, marker);
            int expectedTables = countTables(connection);
            assertThat(expectedTables)
                    .as("the full schema should be present before a dump is worth taking")
                    .isGreaterThan(20);

            // Dumped to a file inside the container, exactly as ops/backup.sh
            // does, and never carried across the host boundary. Testcontainers
            // cannot feed stdin to execInContainer, and copying the file back
            // and forth would be a different operation from the one production
            // performs.
            exec(postgres, "pg_dump", "--username=" + user, "--dbname=" + database,
                    "--clean", "--if-exists", "--no-owner", "--no-privileges",
                    "--file=" + DUMP_PATH);
            String dump = exec(postgres, "cat", DUMP_PATH);

            assertThat(dump)
                    .as("a dump that cannot drop what it is replacing cannot be restored over a "
                            + "live database")
                    .contains("DROP TABLE IF EXISTS");
            assertThat(dump)
                    .as("the dump must contain the row that existed when it was taken")
                    .contains(marker);

            psql(postgres, user, "postgres", "CREATE DATABASE \"" + scratch + "\"");
            try {
                restore(postgres, user, scratch);
                assertThat(tableCount(postgres, user, scratch))
                        .as("the first restore must bring the whole schema")
                        .isEqualTo(expectedTables);
                assertThat(markerCount(postgres, user, scratch, marker))
                        .as("and its data")
                        .isEqualTo(1);

                psql(postgres, user, scratch, "DELETE FROM company WHERE id = '" + marker + "'");
                assertThat(markerCount(postgres, user, scratch, marker)).isZero();

                restore(postgres, user, scratch);

                assertThat(markerCount(postgres, user, scratch, marker))
                        .as("restoring over a populated database must still bring the row back")
                        .isEqualTo(1);
                assertThat(tableCount(postgres, user, scratch))
                        .as("and must leave the schema neither doubled nor short")
                        .isEqualTo(expectedTables);
            } finally {
                psql(postgres, user, "postgres",
                        "DROP DATABASE IF EXISTS \"" + scratch + "\" WITH (FORCE)");
                deleteCompany(connection, marker);
            }
        }
    }

    /**
     * Restores through {@code psql}, not JDBC.
     *
     * <p>The dump carries many statements and psql's own directives; pushing it
     * through the driver would restore something other than what production
     * restores, and a green test for the wrong thing is worse than no test.
     *
     * <p>{@code ON_ERROR_STOP} stays off, deliberately: a {@code --clean} dump's
     * leading DROPs legitimately fail for objects that are not there yet,
     * exactly as they do in a real restore.
     */
    private static void restore(PostgreSQLContainer<?> postgres, String user, String database)
            throws Exception {
        exec(postgres, "psql", "--username=" + user, "--dbname=" + database, "--quiet",
                "--set", "ON_ERROR_STOP=0", "--file=" + DUMP_PATH);
    }

    private static int tableCount(PostgreSQLContainer<?> postgres, String user, String database)
            throws Exception {
        return Integer.parseInt(query(postgres, user, database,
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"));
    }

    private static int markerCount(PostgreSQLContainer<?> postgres, String user, String database,
            String marker) throws Exception {
        return Integer.parseInt(query(postgres, user, database,
                "SELECT count(*) FROM company WHERE id = '" + marker + "'"));
    }

    private static String query(PostgreSQLContainer<?> postgres, String user, String database,
            String sql) throws Exception {
        return exec(postgres, "psql", "--username=" + user, "--dbname=" + database,
                "--tuples-only", "--no-align", "--command", sql).trim();
    }

    private static void psql(PostgreSQLContainer<?> postgres, String user, String database,
            String sql) throws Exception {
        exec(postgres, "psql", "--username=" + user, "--dbname=" + database, "--quiet",
                "--command", sql);
    }

    /**
     * Runs a command inside the database container.
     *
     * <p>Testcontainers' {@code execInContainer} cannot supply stdin, so the
     * restore is piped through a shell instead. The dump is written to a file in
     * the container rather than interpolated into the command line: it is
     * megabytes of SQL containing every quoting character there is, and a
     * command line is the wrong place for it.
     */
    /**
     * Runs a command inside the database container.
     *
     * <p>Everything stays inside: the dump is written to a file there and read
     * back with {@code cat}. Testcontainers' {@code execInContainer} cannot
     * supply stdin, and the obvious workaround — copy the file out and back —
     * needs {@code MountableFile}, which pulls in a commons-lang3 newer than the
     * one Boot 2.7 manages. Keeping the file in the container avoids both, and
     * is closer to what {@code ops/backup.sh} actually does.
     */
    private static String exec(PostgreSQLContainer<?> postgres, String... command)
            throws Exception {
        Container.ExecResult result = postgres.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(
                    command[0] + " exited " + result.getExitCode() + ": " + result.getStderr());
        }
        return result.getStdout();
    }

    private static void seedCompany(Connection connection, String marker) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO company (id, code, name_ko, name_en, kind, active) VALUES ("
                            + "'" + marker + "', '" + marker + "', '백업검증', 'Backup check', "
                            + "'HEAD_OFFICE', TRUE)");
        }
    }

    private static void deleteCompany(Connection connection, String marker) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM company WHERE id = '" + marker + "'");
        }
    }

    private static int countTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
