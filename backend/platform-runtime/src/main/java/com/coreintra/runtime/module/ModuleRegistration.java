package com.coreintra.runtime.module;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;

import java.io.Serializable;
import java.util.Collection;
import java.util.Set;

/**
 * A module's manifest, as the registry needs it (§11).
 *
 * <p>This deliberately is not {@code com.coreintra.moduleapi.ModuleManifest}.
 * The SPI is a compatibility commitment to client developers and is versioned
 * separately; a table that stored it directly would make every SPI change a
 * migration, and would make this module depend on the SPI to record that an
 * external-service integration exists - which has no jar and no manifest class
 * at all. The {@code app} layer adapts one to the other, which is exactly the
 * kind of translation an adapter is for.
 *
 * <p>The rules restated here are the two the registry itself must enforce: the
 * id shape, because it becomes a schema name, and the namespace prefix, because
 * "may not touch core tables" has to mean something before the module starts.
 */
public final class ModuleRegistration implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Talks to the API with a scoped service account. Fully isolated. */
    public static final String EXTERNAL_SERVICE = "EXTERNAL_SERVICE";

    /** A jar in {@code modules/}, running inside the application process. */
    public static final String IN_PROCESS = "IN_PROCESS";

    private final String moduleId;
    private final String name;
    private final String version;
    private final String vendor;
    private final String kind;
    private final String schemaNamespace;
    private final String uiEntryPoint;
    private final Set<String> requiredPermissions;

    public ModuleRegistration(String moduleId, String name, String version, String vendor,
                              String kind, String schemaNamespace, String uiEntryPoint,
                              Collection<String> requiredPermissions) {
        if (moduleId == null || !moduleId.matches("[a-z][a-z0-9_]{2,39}")) {
            throw new IllegalArgumentException(
                    "module id must be 3-40 characters of lowercase letters, digits and "
                            + "underscores, starting with a letter. Got: " + moduleId);
        }
        if (Texts.isBlank(version)) {
            throw new IllegalArgumentException("module " + moduleId + " must declare a version");
        }
        if (Texts.isBlank(name) || Texts.isBlank(vendor)) {
            throw new IllegalArgumentException(
                    "a module is installed by a person who is shown its name and who made it");
        }
        if (!EXTERNAL_SERVICE.equals(kind) && !IN_PROCESS.equals(kind)) {
            throw new IllegalArgumentException(
                    "module kind must be " + EXTERNAL_SERVICE + " or " + IN_PROCESS);
        }
        String namespace = Texts.isBlank(schemaNamespace) ? "mod_" + moduleId : Texts.strip(schemaNamespace);
        if (!namespace.matches("mod_[a-z][a-z0-9_]{2,39}")) {
            throw new IllegalArgumentException(
                    "a module's schema namespace must start with mod_ so it cannot name a core "
                            + "schema. Got: " + namespace);
        }
        this.moduleId = moduleId;
        this.name = name;
        this.version = version;
        this.vendor = vendor;
        this.kind = kind;
        this.schemaNamespace = namespace;
        this.uiEntryPoint = uiEntryPoint;
        this.requiredPermissions = requiredPermissions == null
                ? Immutables.<String>setOf()
                : Immutables.setCopyOf(requiredPermissions);
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

    /**
     * What the module says it needs, declared up front.
     *
     * <p>Up front so the decision is made once with the full list visible,
     * rather than as a series of prompts during use that get clicked through.
     */
    public Set<String> requiredPermissions() {
        return requiredPermissions;
    }

    public boolean isInProcess() {
        return IN_PROCESS.equals(kind);
    }

    /**
     * The manifest as it will be stored, verbatim.
     *
     * <p>Written by hand rather than by a JSON library because this module
     * deliberately carries no serialisation dependency, and because the shape
     * is fixed: a change here is a schema change to
     * {@code module_registration.manifest_json}, which should be visible in a
     * diff rather than emergent from an object mapper's configuration.
     */
    public String toManifestJson() {
        StringBuilder json = new StringBuilder("{");
        field(json, "id", moduleId).append(',');
        field(json, "name", name).append(',');
        field(json, "version", version).append(',');
        field(json, "vendor", vendor).append(',');
        field(json, "kind", kind).append(',');
        field(json, "schemaNamespace", schemaNamespace).append(',');
        field(json, "uiEntryPoint", uiEntryPoint).append(',');
        json.append("\"requiredPermissions\":[");
        boolean first = true;
        for (String permission : requiredPermissions) {
            if (!first) {
                json.append(',');
            }
            json.append('"').append(escape(permission)).append('"');
            first = false;
        }
        return json.append("]}").toString();
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":");
        return value == null ? json.append("null")
                : json.append('"').append(escape(value)).append('"');
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
