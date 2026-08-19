package com.coreintra.moduleapi;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a module declares about itself, checked before it is allowed to start.
 *
 * <p>Everything here is a promise the platform verifies rather than trusts. A
 * module that asks for a permission the installing master did not approve does
 * not start; a module whose schema namespace collides with another's does not
 * start.
 */
public final class ModuleManifest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String name;
    private final String version;
    private final String vendor;
    private final List<String> requiredPermissions;
    private final String schemaNamespace;
    private final String uiEntryPoint;

    public ModuleManifest(String id, String name, String version, String vendor,
            List<String> requiredPermissions, String schemaNamespace, String uiEntryPoint) {
        if (id == null || !id.matches("[a-z][a-z0-9_]{2,39}")) {
            // The id becomes a schema name and a URL segment, so it is
            // constrained rather than sanitised at every use site.
            throw new IllegalArgumentException(
                    "module id must be 3-40 characters of lowercase letters, digits and "
                            + "underscores, starting with a letter. Got: " + id);
        }
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("module " + id + " must declare a version");
        }
        this.id = id;
        this.name = name;
        this.version = version;
        this.vendor = vendor;
        this.requiredPermissions = Collections.unmodifiableList(
                new ArrayList<String>(requiredPermissions == null
                        ? new ArrayList<String>() : requiredPermissions));
        this.schemaNamespace = schemaNamespace == null ? "mod_" + id : schemaNamespace;
        this.uiEntryPoint = uiEntryPoint;
    }

    public String id() {
        return id;
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

    /**
     * Permissions this module needs, shown to the installing master for approval.
     *
     * <p>Declared up front so the decision is made once, with the full list
     * visible, rather than as a series of prompts during use that get clicked
     * through.
     */
    public List<String> requiredPermissions() {
        return requiredPermissions;
    }

    /** The module's own schema. It may not touch core tables. */
    public String schemaNamespace() {
        return schemaNamespace;
    }

    /** Optional remote entry mounted into a reserved UI slot. */
    public String uiEntryPoint() {
        return uiEntryPoint;
    }

    /**
     * The warning shown before installation, in both languages.
     *
     * <p>Deliberately concrete about the consequence rather than a generic "are
     * you sure": an in-process module runs with these permissions inside the
     * application process.
     */
    public String installationWarning(String locale) {
        if ("en".equals(locale)) {
            return "\"" + name + "\" (" + vendor + ") runs INSIDE the application process with "
                    + "the permissions listed below. It is not sandboxed. A fault in it can "
                    + "affect the whole intranet, and installing it voids support guarantees "
                    + "for this installation.\n\nPermissions requested: " + requiredPermissions
                    + "\n\nThe recommended alternative is an external service using a scoped "
                    + "API key, which is fully isolated.";
        }
        return "\"" + name + "\" (" + vendor + ") 모듈은 애플리케이션 프로세스 내부에서 "
                + "아래 권한으로 실행됩니다. 별도의 격리가 적용되지 않으므로, 모듈의 오류가 "
                + "인트라넷 전체에 영향을 줄 수 있으며 설치 시 이 설치본에 대한 지원 보증이 "
                + "종료됩니다.\n\n요청 권한: " + requiredPermissions
                + "\n\n권장되는 대안은 범위가 지정된 API 키를 사용하는 외부 서비스 연동입니다.";
    }
}
