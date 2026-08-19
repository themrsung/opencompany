package com.coreintra.runtime.module;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Installs, enables and disables client modules (§11).
 *
 * <p>The one rule worth stating plainly: a module gets what the installing
 * master approved, and nothing else. A manifest is a request, not a grant. So
 * {@link #register} takes both the manifest and the approved list, and refuses
 * when the first exceeds the second - refuses, rather than installing with the
 * intersection, because a module that quietly starts without a permission it
 * declared it needs will fail later in a way nobody connects to this decision.
 *
 * <p>What this class does not do is check that the caller is a master.
 * Installation being master-only is a permission decision, and permission
 * decisions belong to the one gate (ADR 0003). This service is what happens
 * after that gate said yes, and the audit row is written by the caller that
 * passed through it.
 */
@Service
public class ModuleRegistryService {

    private final ModuleRegistrationRepository modules;
    private final Clock clock;

    public ModuleRegistryService(ModuleRegistrationRepository modules, Clock clock) {
        this.modules = modules;
        this.clock = clock;
    }

    /**
     * Records an installation.
     *
     * @param approvedPermissions what the master ticked, which may be more than
     *        the manifest asks for but never less
     * @throws ModuleRefusedException if the manifest asks for something that was
     *         not approved, or the namespace belongs to another module
     */
    @Transactional
    public ModuleRegistrationRow register(String companyId, ModuleRegistration registration,
                                       Collection<String> approvedPermissions,
                                       String installedByAccountId, String approvalDocumentId) {
        if (Texts.isBlank(installedByAccountId)) {
            throw new IllegalArgumentException(
                    "an installation is attributable to the master who performed it (§11)");
        }
        Set<String> approved = approvedPermissions == null
                ? Immutables.<String>setOf()
                : Immutables.setCopyOf(approvedPermissions);

        Set<String> missing = new LinkedHashSet<String>(registration.requiredPermissions());
        missing.removeAll(approved);
        if (!missing.isEmpty()) {
            throw new ModuleRefusedException(
                    "module \"" + registration.moduleId() + "\" declares permissions that were "
                            + "not approved for this installation: " + missing + ". A manifest is "
                            + "a request, not a grant.");
        }

        ModuleRegistrationRow namespaceHolder =
                modules.findBySchemaNamespace(registration.schemaNamespace()).orElse(null);
        if (namespaceHolder != null && !namespaceHolder.moduleId().equals(registration.moduleId())) {
            throw new ModuleRefusedException(
                    "schema namespace \"" + registration.schemaNamespace() + "\" already belongs "
                            + "to module \"" + namespaceHolder.moduleId() + "\". Two modules "
                            + "sharing a namespace would each believe the other's tables are theirs.");
        }

        ModuleRegistrationRow existing =
                modules.findByCompanyIdAndModuleId(companyId, registration.moduleId()).orElse(null);
        if (existing != null) {
            // An upgrade. The approved list is not widened here: if the new
            // manifest asks for more, the check above has already refused.
            existing.upgradeTo(registration);
            return modules.save(existing);
        }
        return modules.save(new ModuleRegistrationRow(
                UUID.randomUUID().toString(), companyId, registration, approved,
                installedByAccountId, approvalDocumentId, OffsetDateTime.now(clock)));
    }

    /**
     * Checks a module that is about to start against what it was approved for.
     *
     * <p>Called at boot for every in-process module, because the jar on disk in
     * {@code modules/} today is not necessarily the one that was installed.
     */
    @Transactional(readOnly = true)
    public void verify(String companyId, ModuleRegistration registration) {
        ModuleRegistrationRow installed =
                modules.findByCompanyIdAndModuleId(companyId, registration.moduleId()).orElseThrow(
                        () -> new ModuleRefusedException(
                                "module \"" + registration.moduleId() + "\" is not installed for "
                                        + "this company. A jar in modules/ is not an installation."));
        if (!installed.isEnabled()) {
            throw new ModuleRefusedException(
                    "module \"" + registration.moduleId() + "\" is disabled: "
                            + installed.disabledReason());
        }
        if (!installed.coversRequirementsOf(registration)) {
            Set<String> missing = new LinkedHashSet<String>(registration.requiredPermissions());
            missing.removeAll(installed.approvedPermissions());
            throw new ModuleRefusedException(
                    "module \"" + registration.moduleId() + "\" now asks for permissions it was "
                            + "not installed with: " + missing + ". An upgrade that widens the "
                            + "manifest needs a new approval.");
        }
        if (!installed.schemaNamespace().equals(registration.schemaNamespace())) {
            throw new ModuleRefusedException(
                    "module \"" + registration.moduleId() + "\" changed its schema namespace from "
                            + installed.schemaNamespace() + " to " + registration.schemaNamespace()
                            + ". Its existing tables are in the first one.");
        }
    }

    /** Stops a module without losing its schema, approvals or history. */
    @Transactional
    public void disable(String companyId, String moduleId, String reason) {
        ModuleRegistrationRow row = require(companyId, moduleId);
        row.disable(reason, OffsetDateTime.now(clock));
        modules.save(row);
    }

    @Transactional
    public void enable(String companyId, String moduleId) {
        ModuleRegistrationRow row = require(companyId, moduleId);
        if (row.retiredAt() != null) {
            throw new ModuleRefusedException(
                    "module \"" + moduleId + "\" was uninstalled; enabling it again is an "
                            + "installation, which is a fresh approval");
        }
        row.enable();
        modules.save(row);
    }

    /** Uninstall. The row stays, so audit rows naming this module still resolve. */
    @Transactional
    public void retire(String companyId, String moduleId) {
        ModuleRegistrationRow row = require(companyId, moduleId);
        row.retire(OffsetDateTime.now(clock));
        modules.save(row);
    }

    @Transactional(readOnly = true)
    public List<ModuleRegistrationRow> installedFor(String companyId) {
        return modules.findByCompanyIdAndRetiredAtIsNullOrderByInstalledAtDesc(companyId);
    }

    private ModuleRegistrationRow require(String companyId, String moduleId) {
        return modules.findByCompanyIdAndModuleId(companyId, moduleId).orElseThrow(
                () -> new ModuleRefusedException("no such module for this company: " + moduleId));
    }
}
