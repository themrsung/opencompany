package com.coreintra.runtime.module;

import com.coreintra.compat.Immutables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleRegistryServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-18T09:00:00Z"), ZoneOffset.UTC);

    private final FakeModules modules = new FakeModules();
    private final ModuleRegistryService registry = new ModuleRegistryService(modules, FIXED);

    @Test
    @DisplayName("a module is installed with exactly the permissions the master ticked")
    void installsWithApprovedPermissions() {
        ModuleRegistrationRow installed = registry.register("c1",
                manifest("hello_assets", "asset.item:read"),
                Immutables.setOf("asset.item:read", "asset.item:write"),
                "master-1", "doc-1");

        assertThat(installed.isEnabled()).isTrue();
        assertThat(installed.approvedPermissions())
                .as("more than the manifest asked for is the master's business; less is not")
                .containsExactlyInAnyOrder("asset.item:read", "asset.item:write");
    }

    @Test
    @DisplayName("a manifest asking for something that was not approved is refused")
    void refusesUnapprovedPermissions() {
        assertThatThrownBy(() -> registry.register("c1",
                manifest("hello_assets", "asset.item:read", "hr.compensation:read"),
                Immutables.setOf("asset.item:read"),
                "master-1", "doc-1"))
                .isInstanceOf(ModuleRefusedException.class)
                .hasMessageContaining("hr.compensation:read")
                .hasMessageContaining("a request, not a grant");
    }

    @Test
    @DisplayName("an upgrade that widens the manifest does not start on the old approval")
    void upgradeNeedsANewApproval() {
        registry.register("c1", manifest("hello_assets", "asset.item:read"),
                Immutables.setOf("asset.item:read"), "master-1", "doc-1");

        assertThatThrownBy(() ->
                registry.verify("c1", manifest("hello_assets", "asset.item:read", "hr.employee:read")))
                .isInstanceOf(ModuleRefusedException.class)
                .hasMessageContaining("hr.employee:read");
    }

    @Test
    @DisplayName("two modules cannot share a schema namespace")
    void namespacesAreUnique() {
        registry.register("c1", manifest("hello_assets", "asset.item:read"),
                Immutables.setOf("asset.item:read"), "master-1", "doc-1");

        ModuleRegistration collidingNamespace = new ModuleRegistration(
                "other_module", "Other", "1.0.0", "Vendor", ModuleRegistration.IN_PROCESS,
                "mod_hello_assets", null, Immutables.<String>setOf());

        assertThatThrownBy(() -> registry.register("c1", collidingNamespace,
                Immutables.<String>setOf(), "master-1", "doc-1"))
                .isInstanceOf(ModuleRefusedException.class)
                .hasMessageContaining("already belongs to module");
    }

    @Test
    @DisplayName("a module can be disabled without being uninstalled, and enabled again")
    void disableIsNotUninstall() {
        registry.register("c1", manifest("hello_assets", "asset.item:read"),
                Immutables.setOf("asset.item:read"), "master-1", "doc-1");

        registry.disable("c1", "hello_assets", "느려서 일시적으로 중지합니다.");
        assertThat(modules.byModuleId("hello_assets").isEnabled()).isFalse();
        assertThat(modules.byModuleId("hello_assets").retiredAt())
                .as("the schema, the approvals and the history are all still here")
                .isNull();
        assertThatThrownBy(() -> registry.verify("c1", manifest("hello_assets", "asset.item:read")))
                .isInstanceOf(ModuleRefusedException.class)
                .hasMessageContaining("disabled");

        registry.enable("c1", "hello_assets");
        assertThat(modules.byModuleId("hello_assets").isEnabled()).isTrue();
    }

    @Test
    @DisplayName("disabling is recorded with a reason someone can be shown")
    void disableNeedsAReason() {
        registry.register("c1", manifest("hello_assets"), Immutables.<String>setOf(),
                "master-1", "doc-1");

        assertThatThrownBy(() -> registry.disable("c1", "hello_assets", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an uninstalled module is not re-enabled; installing it again is a new decision")
    void retiredCannotBeEnabled() {
        registry.register("c1", manifest("hello_assets"), Immutables.<String>setOf(),
                "master-1", "doc-1");
        registry.retire("c1", "hello_assets");

        assertThatThrownBy(() -> registry.enable("c1", "hello_assets"))
                .isInstanceOf(ModuleRefusedException.class)
                .hasMessageContaining("fresh approval");
        assertThat(registry.installedFor("c1")).isEmpty();
    }

    @Test
    @DisplayName("a jar in modules/ that was never installed does not start")
    void unknownModuleDoesNotStart() {
        assertThatThrownBy(() -> registry.verify("c1", manifest("stowaway")))
                .isInstanceOf(ModuleRefusedException.class)
                .hasMessageContaining("not an installation");
    }

    @Test
    @DisplayName("a module cannot name a core schema as its namespace")
    void namespaceMustBePrefixed() {
        assertThatThrownBy(() -> new ModuleRegistration("hello_assets", "Hello", "1.0.0", "Vendor",
                ModuleRegistration.IN_PROCESS, "public", null, Immutables.<String>setOf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mod_");
    }

    @Test
    @DisplayName("a module id that would not survive being a schema name is refused")
    void moduleIdShape() {
        assertThatThrownBy(() -> new ModuleRegistration("Hello-Assets", "Hello", "1.0.0", "Vendor",
                ModuleRegistration.IN_PROCESS, null, null, Immutables.<String>setOf()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ModuleRegistration manifest(String moduleId, String... permissions) {
        return new ModuleRegistration(moduleId, "Hello Assets", "1.0.0", "Vendor Co",
                ModuleRegistration.IN_PROCESS, null, "/modules/" + moduleId + "/entry.js",
                Immutables.listOfArray(permissions));
    }

    private static final class FakeModules implements ModuleRegistrationRepository {

        private final Map<String, ModuleRegistrationRow> byId =
                new LinkedHashMap<String, ModuleRegistrationRow>();

        ModuleRegistrationRow byModuleId(String moduleId) {
            for (ModuleRegistrationRow row : byId.values()) {
                if (row.moduleId().equals(moduleId)) {
                    return row;
                }
            }
            throw new IllegalStateException("no such module: " + moduleId);
        }

        @Override
        public ModuleRegistrationRow save(ModuleRegistrationRow row) {
            byId.put(row.id(), row);
            return row;
        }

        @Override
        public Optional<ModuleRegistrationRow> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<ModuleRegistrationRow> findByCompanyIdAndModuleId(String companyId, String moduleId) {
            for (ModuleRegistrationRow row : byId.values()) {
                if (row.companyId().equals(companyId) && row.moduleId().equals(moduleId)) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<ModuleRegistrationRow> findBySchemaNamespace(String schemaNamespace) {
            for (ModuleRegistrationRow row : byId.values()) {
                if (row.schemaNamespace().equals(schemaNamespace)) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<ModuleRegistrationRow> findByCompanyIdAndRetiredAtIsNullOrderByInstalledAtDesc(
                String companyId) {
            List<ModuleRegistrationRow> live = new ArrayList<ModuleRegistrationRow>();
            for (ModuleRegistrationRow row : byId.values()) {
                if (row.companyId().equals(companyId) && row.retiredAt() == null) {
                    live.add(row);
                }
            }
            return live;
        }
    }
}
