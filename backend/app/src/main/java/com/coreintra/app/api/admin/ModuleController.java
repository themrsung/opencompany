package com.coreintra.app.api.admin;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.moduleapi.ModuleManifest;
import com.coreintra.runtime.module.ModuleRegistration;
import com.coreintra.runtime.module.ModuleRegistrationRow;
import com.coreintra.runtime.module.ModuleRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Installing, disabling and listing client modules (§11).
 *
 * <p>Installation is master-only and audited, and the risk warning is not a
 * "are you sure" — it names the consequence. An in-process module runs inside
 * this JVM with the permissions granted to it, is not sandboxed, and voids
 * support guarantees for the installation. The warning text comes from
 * {@link ModuleManifest}, in both languages, and the caller must echo back that
 * it was shown; a client that installs without displaying it has to say so by
 * omission, which is at least visible in the audit trail.
 *
 * <p>The recommended path is not this one. An external service holding a scoped
 * API key is fully isolated and can do everything a module can, which is why
 * the response says so on every in-process installation.
 */
@RestController
@RequestMapping("/api/v1/admin/modules")
@Tag(name = "Modules",
        description = "Client extensions. External services are isolated; in-process modules "
                + "are not, and the difference is the whole risk conversation.")
public class ModuleController {

    private static final PermissionKey READ = PermissionKey.parse("admin.module:read");
    private static final PermissionKey INSTALL = PermissionKey.parse("admin.module:install");

    private final ModuleRegistryService modules;
    private final PermissionEvaluator evaluator;
    private final CurrentPrincipal current;

    public ModuleController(ModuleRegistryService modules, PermissionEvaluator evaluator,
            CurrentPrincipal current) {
        this.modules = modules;
        this.evaluator = evaluator;
        this.current = current;
    }

    public static class InstallRequest {
        @NotBlank
        private String companyId;
        @NotBlank
        private String moduleId;
        @NotBlank
        private String name;
        @NotBlank
        private String version;
        private String vendor;
        /** {@code EXTERNAL_SERVICE} or {@code IN_PROCESS}. */
        @NotBlank
        private String kind;
        private String schemaNamespace;
        private String uiEntryPoint;
        private List<String> requiredPermissions;
        /**
         * What the installing master ticked. The manifest is verified against
         * this, so a module asking for something unapproved does not start.
         */
        private List<String> approvedPermissions;
        /** The approval that authorised the installation, for the audit trail. */
        private String approvalDocumentId;
        @NotNull(message = "Confirm that the risk warning was shown to the installing master.")
        private Boolean riskWarningShown;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public String getModuleId() {
            return moduleId;
        }

        public void setModuleId(String value) {
            this.moduleId = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String value) {
            this.name = value;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String value) {
            this.version = value;
        }

        public String getVendor() {
            return vendor;
        }

        public void setVendor(String value) {
            this.vendor = value;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String value) {
            this.kind = value;
        }

        public String getSchemaNamespace() {
            return schemaNamespace;
        }

        public void setSchemaNamespace(String value) {
            this.schemaNamespace = value;
        }

        public String getUiEntryPoint() {
            return uiEntryPoint;
        }

        public void setUiEntryPoint(String value) {
            this.uiEntryPoint = value;
        }

        public List<String> getRequiredPermissions() {
            return requiredPermissions;
        }

        public void setRequiredPermissions(List<String> value) {
            this.requiredPermissions = value;
        }

        public List<String> getApprovedPermissions() {
            return approvedPermissions;
        }

        public void setApprovedPermissions(List<String> value) {
            this.approvedPermissions = value;
        }

        public String getApprovalDocumentId() {
            return approvalDocumentId;
        }

        public void setApprovalDocumentId(String value) {
            this.approvalDocumentId = value;
        }

        public Boolean getRiskWarningShown() {
            return riskWarningShown;
        }

        public void setRiskWarningShown(Boolean value) {
            this.riskWarningShown = value;
        }
    }

    public static class InstalledModule {
        private final String moduleId;
        private final String name;
        private final String version;
        private final String vendor;
        private final String kind;
        private final String schemaNamespace;
        private final boolean enabled;
        private final boolean isolated;

        InstalledModule(ModuleRegistrationRow row) {
            this.moduleId = row.moduleId();
            this.name = row.name();
            this.version = row.version();
            this.vendor = row.vendor();
            this.kind = row.kind();
            this.schemaNamespace = row.schemaNamespace();
            this.enabled = row.isEnabled();
            this.isolated = ModuleRegistration.EXTERNAL_SERVICE.equals(row.kind());
        }

        public String getModuleId() {
            return moduleId;
        }

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        public String getVendor() {
            return vendor;
        }

        public String getKind() {
            return kind;
        }

        public String getSchemaNamespace() {
            return schemaNamespace;
        }

        public boolean isEnabled() {
            return enabled;
        }

        /**
         * False for an in-process module, and the UI must say so where anyone
         * can see it — not only on the install screen they saw once.
         */
        public boolean isIsolated() {
            return isolated;
        }
    }

    @GetMapping("/warning")
    @Operation(summary = "The risk warning for a module, in both languages",
            description = "Fetched and displayed before installation. It names the consequence "
                    + "rather than asking whether you are sure.")
    public String warning(@RequestParam String moduleId, @RequestParam String name,
            @RequestParam(required = false) String vendor,
            @RequestParam(required = false) List<String> requiredPermissions,
            @RequestParam(defaultValue = "ko") String locale) {
        current.require();
        ModuleManifest manifest = new ModuleManifest(moduleId, name, "0", vendor,
                requiredPermissions, null, null);
        return manifest.installationWarning(locale);
    }

    @GetMapping
    @Operation(summary = "Modules installed for a company")
    public List<InstalledModule> installed(@RequestParam String companyId) {
        require(READ, companyId, "installed modules");
        List<InstalledModule> found = new ArrayList<InstalledModule>();
        for (ModuleRegistrationRow row : modules.installedFor(companyId)) {
            found.add(new InstalledModule(row));
        }
        return Immutables.copyOf(found);
    }

    @PostMapping
    @Operation(summary = "Install a module",
            description = "Master only, audited, and refused if the manifest asks for a "
                    + "permission the installing master did not approve.")
    public ResponseEntity<InstalledModule> install(@Valid @RequestBody InstallRequest body) {
        PermissionPrincipal principal = require(INSTALL, body.getCompanyId(), "module installation");
        if (!principal.isMaster()) {
            throw new IllegalArgumentException("Only a master account may install a module.");
        }
        if (!Boolean.TRUE.equals(body.getRiskWarningShown())) {
            throw new IllegalArgumentException(
                    "The risk warning must be shown to the installing master before a module is "
                            + "installed. Fetch it from /admin/modules/warning and display it.");
        }

        ModuleRegistration registration = new ModuleRegistration(body.getModuleId(), body.getName(),
                body.getVersion(), body.getVendor(), body.getKind(), body.getSchemaNamespace(),
                body.getUiEntryPoint(), body.getRequiredPermissions());

        ModuleRegistrationRow row = modules.register(body.getCompanyId(), registration,
                body.getApprovedPermissions(), principal.accountId(), body.getApprovalDocumentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new InstalledModule(row));
    }

    @PostMapping("/{moduleId}/disable")
    @Operation(summary = "Disable without uninstalling",
            description = "§11: a module can be turned off and left in place, so that turning it "
                    + "off is not a decision about its data.")
    public ResponseEntity<Void> disable(@PathVariable String moduleId,
            @RequestParam String companyId, @RequestParam String reason) {
        require(INSTALL, companyId, "module");
        modules.disable(companyId, moduleId, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{moduleId}/enable")
    @Operation(summary = "Re-enable a disabled module")
    public ResponseEntity<Void> enable(@PathVariable String moduleId,
            @RequestParam String companyId) {
        require(INSTALL, companyId, "module");
        modules.enable(companyId, moduleId);
        return ResponseEntity.noContent().build();
    }

    private PermissionPrincipal require(PermissionKey key, String companyId, String what) {
        PermissionPrincipal principal = current.require();
        evaluator.check(principal, key, PermissionTarget.builder()
                .companyId(companyId)
                .asOfBusinessDate(LocalDate.now())
                .description(what)
                .build()).orThrow();
        return principal;
    }
}
