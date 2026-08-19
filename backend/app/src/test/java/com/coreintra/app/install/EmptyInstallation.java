package com.coreintra.app.install;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Returns the database to the state a client's box is in before anybody has
 * opened it.
 *
 * <p>Every other integration test can set up what it needs; this one can only
 * be trusted if there is genuinely nothing there, because "nothing there" is the
 * condition the installer keys on.
 *
 * <h2>Why it disables triggers, and why nothing outside a test may</h2>
 *
 * <p>Two tables refuse to be emptied, on purpose. {@code audit_log} is
 * append-only by trigger (V9) so that a compromised administrator cannot erase
 * the trail, and {@code installation} refuses UPDATE and DELETE (V15) so that
 * nobody can reopen the installer on a system in use. Both refusals are exactly
 * what the tests below exist to prove, and both stand in the way of arranging
 * the fixture — audit rows carry a non-null foreign key to {@code company}, so
 * while one exists no company can be removed either.
 *
 * <p>So the triggers come off for the length of one {@code TRUNCATE} and go
 * straight back on. This is test-only surgery and it lives in the test tree:
 * there is no production code path that disables a trigger, and if one ever
 * appears it is a bug rather than a convenience.
 */
final class EmptyInstallation {

    private EmptyInstallation() {
    }

    static void reset(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE audit_log DISABLE TRIGGER audit_log_append_only");
        jdbc.execute("ALTER TABLE audit_log DISABLE TRIGGER audit_log_append_only_truncate");
        jdbc.execute("ALTER TABLE installation DISABLE TRIGGER installation_written_once");
        jdbc.execute("ALTER TABLE installation DISABLE TRIGGER installation_written_once_truncate");
        try {
            // CASCADE reaches everything hanging off a company or an account,
            // which on this schema is everything a client owns. Naming the two
            // roots rather than forty tables keeps this honest when another
            // module adds its own.
            jdbc.execute("TRUNCATE TABLE company, user_account CASCADE");
            // temporary_master_switch has a nullable foreign key to
            // user_account, so the cascade reaches it and takes with it the one
            // row V9 seeds for the installation. Nothing recreates that row —
            // §8's kill switch is read, not created, by the application — so
            // putting it back is part of leaving the database as we found it
            // for whichever test class runs next.
            jdbc.update("INSERT INTO temporary_master_switch (installation) "
                    + "VALUES ('INSTALLATION') ON CONFLICT DO NOTHING");
        } finally {
            jdbc.execute("ALTER TABLE audit_log ENABLE TRIGGER audit_log_append_only");
            jdbc.execute("ALTER TABLE audit_log ENABLE TRIGGER audit_log_append_only_truncate");
            jdbc.execute("ALTER TABLE installation ENABLE TRIGGER installation_written_once");
            jdbc.execute(
                    "ALTER TABLE installation ENABLE TRIGGER installation_written_once_truncate");
        }
    }
}
