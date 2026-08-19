package com.coreintra.moduleapi;

import java.util.List;

/**
 * What a module is given at start-up. The only door onto the platform.
 *
 * <p>Every method here is permission-checked against the module's own service
 * account, through the same evaluator every other caller uses. There is no
 * privileged path: a module asking for something its manifest did not declare
 * gets the same refusal a user would.
 *
 * <p>Note what is absent — no {@code DataSource}, no repository, no entity
 * manager onto core tables. A module gets its own namespaced schema through
 * {@link #moduleDataSource()} and reaches core data only through these typed
 * calls, so an internal refactor cannot break it and it cannot corrupt core
 * state.
 */
public interface ModuleContext {

    /** This module's manifest, as validated at install time. */
    ModuleManifest manifest();

    /** The service account this module acts as. Its grants are inspectable. */
    String serviceAccountId();

    /**
     * A connection to the module's OWN schema.
     *
     * <p>Scoped to {@link ModuleManifest#schemaNamespace()}; core tables are not
     * reachable through it.
     */
    javax.sql.DataSource moduleDataSource();

    /**
     * Reads through the platform, permission-checked.
     *
     * @throws SecurityException if the module's account may not read this
     */
    <T> T read(ModuleQuery<T> query);

    /**
     * Writes through the platform, permission-checked and audited as the
     * module's service account.
     */
    <T> T write(ModuleCommand<T> command);

    /** Subscribes to platform events — document approved, status changed. */
    void subscribe(String eventType, ModuleEventListener listener);

    /** Structured logging that lands in the host's log with the module id attached. */
    ModuleLogger logger();

    /** Read-only settings the installing master configured for this module. */
    List<String> settingKeys();

    String setting(String key);

    /** A typed read. Implementations are supplied by the platform. */
    interface ModuleQuery<T> {
        String requiredPermission();
    }

    /** A typed write. */
    interface ModuleCommand<T> {
        String requiredPermission();
    }

    interface ModuleEventListener {
        /**
         * Handles an event.
         *
         * <p>Must not throw for ordinary failure: an exception here is logged
         * and the module is disabled if it recurs, but it never rolls back the
         * platform operation that raised the event.
         */
        void onEvent(String eventType, String payloadJson);
    }

    interface ModuleLogger {
        void info(String message);

        void warn(String message);

        void error(String message, Throwable cause);
    }
}
