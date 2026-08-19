package com.coreintra.moduleapi;

/**
 * The in-process client module SPI.
 *
 * <p>Discovered by {@link java.util.ServiceLoader} from the {@code modules/}
 * directory. Implementing this is the <b>second-choice</b> extension path: the
 * recommended one is an external service talking to the REST/MCP API with a
 * scoped service account, which cannot crash the intranet or corrupt core data
 * however badly it is written (ADR 0005).
 *
 * <h2>What you are agreeing to</h2>
 *
 * <p>A module implementing this runs <em>inside the application process</em>,
 * with the permissions its manifest declares. It is unsupported, it voids
 * support guarantees, and installing one is master-only and audited. That is
 * stated here as well as in the UI because the person reading this file is the
 * one writing the module.
 *
 * <h2>Rules the platform enforces</h2>
 *
 * <ul>
 *   <li>Your DB objects live in your own schema namespace. Core tables are not
 *       readable or writable directly — only through the SPI. Your migrations
 *       are namespaced and run separately.</li>
 *   <li>Every operation goes through the same {@code PermissionEvaluator} as
 *       everything else. A module cannot grant itself anything, and its service
 *       account's grants are visible in the effective-permissions explainer
 *       like anyone else's.</li>
 *   <li>This SPI is compiled for <b>Java 8</b>, like the rest of the backend.
 *       No Java 9+ type may appear in a signature you implement.</li>
 * </ul>
 *
 * <p>The SPI is versioned separately from the core and does not depend on core
 * internals, so an internal refactor is not a breaking change for installed
 * modules.
 */
public interface IntranetModule {

    /** Identity, version, required permissions, schema namespace, UI entry point. */
    ModuleManifest manifest();

    /**
     * Called once at startup, after the manifest is validated and the module's
     * permissions are granted.
     *
     * <p>Throwing here disables the module and records why. It does not take the
     * application down: a client's module failing to start must never stop
     * everyone else working.
     */
    void start(ModuleContext context);

    /**
     * Called on shutdown, and on disable.
     *
     * <p>A module can be disabled without being uninstalled, so this may be
     * followed by another {@link #start} in the same process.
     */
    void stop();
}
