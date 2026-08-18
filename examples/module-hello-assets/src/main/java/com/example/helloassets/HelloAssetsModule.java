package com.example.helloassets;

import com.coreintra.moduleapi.IntranetModule;
import com.coreintra.moduleapi.ModuleContext;
import com.coreintra.moduleapi.ModuleManifest;
import java.util.Arrays;

/**
 * A worked example: a simple asset register, as a client would write one.
 *
 * <p>Deliberately small and deliberately complete — it declares a manifest,
 * uses its own schema, reads platform data through the permission-checked
 * context, subscribes to an event, and shuts down cleanly. Everything a real
 * module needs and nothing else.
 */
public final class HelloAssetsModule implements IntranetModule {

    private ModuleContext context;

    @Override
    public ModuleManifest manifest() {
        return new ModuleManifest(
                "hello_assets",
                "자산 관리 (예제)",
                "1.0.0",
                "Example Vendor Co.",
                // Declared up front, shown to the installing master as one list.
                // Ask for the narrowest set that works: this list is what they
                // are agreeing to, and a long one invites a refusal.
                Arrays.asList("hr.employee:read", "approval.document:read"),
                // Own schema. Core tables are not reachable.
                "mod_hello_assets",
                "/modules/hello-assets/entry.js");
    }

    @Override
    public void start(ModuleContext moduleContext) {
        this.context = moduleContext;
        context.logger().info("hello-assets starting, version " + manifest().version());

        // Migrations run against the module's OWN schema. The platform creates
        // it and grants access; nothing here can reach core tables.
        ensureSchema();

        // React to platform events. The listener must not throw for ordinary
        // failure: an exception here never rolls back the approval that raised it.
        context.subscribe("approval.document.approved", new ModuleContext.ModuleEventListener() {
            @Override
            public void onEvent(String eventType, String payloadJson) {
                try {
                    context.logger().info("an approval completed: " + payloadJson);
                } catch (RuntimeException e) {
                    context.logger().error("hello-assets failed to handle " + eventType, e);
                }
            }
        });

        context.logger().info("hello-assets ready");
    }

    private void ensureSchema() {
        try {
            java.sql.Connection connection = context.moduleDataSource().getConnection();
            try {
                java.sql.Statement statement = connection.createStatement();
                try {
                    statement.execute(
                            "CREATE TABLE IF NOT EXISTS asset ("
                                    + "  id           VARCHAR(36) PRIMARY KEY,"
                                    + "  tag          VARCHAR(40) NOT NULL,"
                                    + "  description  TEXT,"
                                    // A foreign key to a core table is not
                                    // possible, by design. Hold the id and
                                    // resolve it through the context.
                                    + "  holder_employee_id VARCHAR(36),"
                                    + "  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()"
                                    + ")");
                } finally {
                    statement.close();
                }
            } finally {
                connection.close();
            }
        } catch (java.sql.SQLException e) {
            // Throwing from start() disables THIS module and records why. It
            // does not take the application down: a client module failing must
            // never stop everyone else working.
            throw new IllegalStateException("hello-assets could not create its schema", e);
        }
    }

    @Override
    public void stop() {
        if (context != null) {
            context.logger().info("hello-assets stopping");
        }
        // May be followed by another start() in the same process, because a
        // module can be disabled and re-enabled without being uninstalled.
        this.context = null;
    }
}
