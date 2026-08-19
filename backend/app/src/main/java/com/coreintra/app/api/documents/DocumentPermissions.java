package com.coreintra.app.api.documents;

import com.coreintra.core.permission.PermissionKey;

/**
 * The permission vocabulary the document, template and font surfaces check
 * against.
 *
 * <h2>Why this lives in the API module and should not stay here</h2>
 *
 * <p>Every other module writes its keys down beside the services that check
 * them — {@code OrgPermissions}, {@code AccountingPermissions},
 * {@code ApprovalPermissions}. The documents services take a bare
 * {@code companyId} and no caller, so there is nothing in that module to check
 * a key, and inventing the keys inline at each controller would produce a key
 * nobody can grant: an administrator only learns a permission exists because it
 * is written down in one place and printed by the explainer.
 *
 * <p>This class is therefore the same list in the wrong module, and it should
 * move to {@code com.coreintra.documents.service.DocumentPermissions} when the
 * documents services grow a {@code PermissionPrincipal} parameter. The keys
 * themselves are the contract and must not change when it moves.
 *
 * <h2>The resource tree</h2>
 *
 * <p>Everything is under {@code documents.*}, so a client whose 총무팀 job
 * function should reach the whole module can be granted
 * {@code documents.*:read} and have it mean what it looks like. Splitting
 * fonts off into their own tree would make that grant reach the documents and
 * silently miss the font manager.
 *
 * <h2>Why the actions are split the way they are</h2>
 *
 * <p>{@link #DOCUMENT_EXPORT} is not {@link #DOCUMENT_READ}. Reading is a
 * person looking at a document in the browser; exporting produces a PDF or a
 * HWP that leaves the building, is archived forever as what was approved
 * (§6.4), and occupies a LibreOffice worker while it happens. Those are three
 * different reasons to want the authority to be separately grantable, and an
 * install that could not express "may read the contract, may not email it out"
 * would end up granting everybody both.
 *
 * <p>{@link #TEMPLATE_PUBLISH} is not {@link #TEMPLATE_WRITE} for the reason
 * §6.8 gives: a published version is what every document drafted from it
 * renders against forever. Editing a draft template is ordinary work; freezing
 * a version that documents will be pinned to for years is not.
 *
 * <p>{@link #FONT_REMOVE} is not {@link #FONT_INSTALL} because removing a font
 * changes what already-approved documents look like when they are re-rendered
 * (§6.9). The service makes the caller acknowledge the affected render count;
 * this makes the installation able to decide who is allowed to acknowledge it.
 */
public final class DocumentPermissions {

    /** Seeing a document, its versions, its typed field values and its trail. */
    public static final PermissionKey DOCUMENT_READ =
            PermissionKey.of("documents.document", "read");

    /** Creating a document and saving a new version of one. */
    public static final PermissionKey DOCUMENT_WRITE =
            PermissionKey.of("documents.document", "write");

    /**
     * Producing an export, and downloading one that was produced.
     *
     * <p>Held separately from {@link #DOCUMENT_READ}: see the class note.
     */
    public static final PermissionKey DOCUMENT_EXPORT =
            PermissionKey.of("documents.document", "export");

    /** Retiring a document. There is no delete — an approved document is evidence. */
    public static final PermissionKey DOCUMENT_RETIRE =
            PermissionKey.of("documents.document", "retire");

    /** Listing templates, reading their field schema and their bodies. */
    public static final PermissionKey TEMPLATE_READ =
            PermissionKey.of("documents.template", "read");

    /** Creating a template, forking one, restoring a seeded one to factory state. */
    public static final PermissionKey TEMPLATE_WRITE =
            PermissionKey.of("documents.template", "write");

    /** Publishing a template version, which documents are then pinned to forever. */
    public static final PermissionKey TEMPLATE_PUBLISH =
            PermissionKey.of("documents.template", "publish");

    /** Retiring a template. Documents already drafted from it keep rendering. */
    public static final PermissionKey TEMPLATE_RETIRE =
            PermissionKey.of("documents.template", "retire");

    /** Seeing which fonts are installed, and what a family resolves to. */
    public static final PermissionKey FONT_READ =
            PermissionKey.of("documents.font", "read");

    /** Uploading a font, and enabling or disabling one. */
    public static final PermissionKey FONT_INSTALL =
            PermissionKey.of("documents.font", "install");

    /** Removing a font, which changes what already-rendered documents look like. */
    public static final PermissionKey FONT_REMOVE =
            PermissionKey.of("documents.font", "remove");

    private DocumentPermissions() {
    }
}
