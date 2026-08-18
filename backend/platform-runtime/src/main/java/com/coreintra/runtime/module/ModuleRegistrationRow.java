package com.coreintra.runtime.module;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An installed module, and the permissions a master approved for it (§11).
 *
 * <p>The approved list is stored rather than re-read from the jar, and that is
 * the entire point of the row. A module that ships an upgrade with a wider
 * manifest has not been approved for the difference; comparing the two is only
 * possible because what was agreed to is written down separately from what is
 * now being claimed.
 *
 * <h2>Disabling is not uninstalling</h2>
 *
 * <p>§11 asks for both, and they are different rows in different states rather
 * than one row and a deletion: a disabled module keeps its schema, its
 * approvals and its history, so re-enabling is a decision rather than a
 * reinstallation.
 */
@Entity
@Table(name = "module_registration")
public class ModuleRegistrationRow {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "module_id", nullable = false, length = 40)
    private String moduleId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "version", nullable = false, length = 40)
    private String version;

    @Column(name = "vendor", nullable = false, length = 200)
    private String vendor;

    @Column(name = "kind", nullable = false, length = 24)
    private String kind;

    @Column(name = "schema_namespace", nullable = false, length = 63)
    private String schemaNamespace;

    @Column(name = "ui_entry_point", length = 400)
    private String uiEntryPoint;

    /** The manifest as declared, kept verbatim beside the columns parsed from it. */
    @Column(name = "manifest_json", nullable = false)
    private String manifestJson;

    /**
     * A flag, not a state machine.
     *
     * <p>§11 asks that modules can be disabled without uninstalling, and there
     * is nothing in between the two. A state column would invite a third value
     * that nothing knows how to handle.
     */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "installed_by_account_id", nullable = false, length = 36)
    private String installedByAccountId;

    @Column(name = "approval_document_id", length = 36)
    private String approvalDocumentId;

    @Column(name = "installed_at", nullable = false)
    private OffsetDateTime installedAt;

    @Column(name = "disabled_at")
    private OffsetDateTime disabledAt;

    @Column(name = "disabled_reason", length = 400)
    private String disabledReason;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    /** Exactly what the installing master ticked. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "module_registration_permission",
            joinColumns = @JoinColumn(name = "module_registration_id"))
    @Column(name = "permission_key", nullable = false, length = 120)
    private Set<String> approvedPermissions = new LinkedHashSet<String>();

    /** JPA requires a no-arg constructor. Not for application use. */
    protected ModuleRegistrationRow() {
    }

    ModuleRegistrationRow(String id, String companyId, ModuleRegistration registration,
                          Set<String> approvedPermissions, String installedByAccountId,
                          String approvalDocumentId, OffsetDateTime installedAt) {
        this.id = id;
        this.companyId = companyId;
        this.moduleId = registration.moduleId();
        this.name = registration.name();
        this.version = registration.version();
        this.vendor = registration.vendor();
        this.kind = registration.kind();
        this.schemaNamespace = registration.schemaNamespace();
        this.uiEntryPoint = registration.uiEntryPoint();
        this.manifestJson = registration.toManifestJson();
        this.enabled = true;
        this.installedByAccountId = installedByAccountId;
        this.approvalDocumentId = approvalDocumentId;
        this.installedAt = installedAt;
        this.approvedPermissions = new LinkedHashSet<String>(approvedPermissions);
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String moduleId() {
        return moduleId;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String vendor() {
        return vendor;
    }

    public String kind() {
        return kind;
    }

    public String schemaNamespace() {
        return schemaNamespace;
    }

    public String uiEntryPoint() {
        return uiEntryPoint;
    }

    public String manifestJson() {
        return manifestJson;
    }

    public String installedByAccountId() {
        return installedByAccountId;
    }

    public String approvalDocumentId() {
        return approvalDocumentId;
    }

    public OffsetDateTime installedAt() {
        return installedAt;
    }

    public OffsetDateTime disabledAt() {
        return disabledAt;
    }

    public String disabledReason() {
        return disabledReason;
    }

    public OffsetDateTime retiredAt() {
        return retiredAt;
    }

    public Set<String> approvedPermissions() {
        return Immutables.setCopyOf(approvedPermissions);
    }

    public boolean isEnabled() {
        return enabled && retiredAt == null;
    }

    /** True when everything the manifest asks for was approved for this install. */
    public boolean coversRequirementsOf(ModuleRegistration registration) {
        return approvedPermissions.containsAll(registration.requiredPermissions());
    }

    void disable(String reason, OffsetDateTime at) {
        if (Texts.isBlank(reason)) {
            throw new IllegalArgumentException(
                    "disabling a module is recorded with a reason; someone will ask why the "
                            + "client's integration stopped");
        }
        this.enabled = false;
        this.disabledReason = reason;
        this.disabledAt = at;
    }

    void enable() {
        this.enabled = true;
        this.disabledReason = null;
        // disabledAt is kept: it is when it last stopped, which is history, not state.
    }

    void retire(OffsetDateTime at) {
        if (retiredAt == null) {
            this.retiredAt = at;
            this.enabled = false;
            this.disabledAt = at;
            this.disabledReason = "uninstalled";
        }
    }

    void upgradeTo(ModuleRegistration registration) {
        this.name = registration.name();
        this.version = registration.version();
        this.vendor = registration.vendor();
        this.uiEntryPoint = registration.uiEntryPoint();
        this.manifestJson = registration.toManifestJson();
    }
}
