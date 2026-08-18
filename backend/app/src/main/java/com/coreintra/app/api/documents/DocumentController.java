package com.coreintra.app.api.documents;

import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.DocumentVersionEntity;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.service.DocumentService;
import com.coreintra.documents.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Documents: drafting one from a template, versioning it, reading its typed
 * fields, and retiring it.
 *
 * <h2>Two shapes of request, for one reason</h2>
 *
 * <p>Metadata is JSON; bytes are {@code multipart/form-data}. A document is a
 * file — a 20 MB HWP is ordinary — and base64 inside a JSON string costs a third
 * again in size and is held in memory twice. Every endpoint that carries a
 * document body is therefore multipart with a size cap checked before the file
 * is read.
 *
 * <h2>Reading is not exporting</h2>
 *
 * <p>{@code documents.document:read} answers the metadata and the typed field
 * values. Obtaining the file itself — including the stored bytes of a version,
 * which for a DOCX document are byte-for-byte the DOCX export — requires
 * {@code documents.document:export}. Splitting them is what lets an installation
 * grant a reviewer sight of a contract without granting them a copy of it to
 * send on.
 *
 * <h2>Nothing here returns a 도장</h2>
 *
 * <p>Signature and seal images are composited server-side at render time and are
 * never served as a reusable asset (§6.3). There is no endpoint on this
 * controller, or anywhere in this package, that returns those bytes in any
 * encoding, and {@code SignatureNotServedTest} fails the build if one appears.
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents",
        description = "Drafting, versioning and reading documents. The docx is the truth; the "
                + "typed field values are the queryable projection of it.")
public class DocumentController {

    private final DocumentService documents;
    private final TemplateService templates;
    private final DocumentAccess access;
    private final CurrentPrincipal current;

    public DocumentController(DocumentService documents, TemplateService templates,
            DocumentAccess access, CurrentPrincipal current) {
        this.documents = documents;
        this.templates = templates;
        this.access = access;
        this.current = current;
    }

    /** Drafting a document from a published template version. */
    public static class CreateFromTemplateRequest {
        @NotBlank
        private String companyId;
        @NotBlank
        private String templateId;
        private int templateVersionNo;
        @NotBlank
        @Size(max = 300)
        private String title;
        private String locale;
        private String authoredAt;
        private String defaultCurrencyCode;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public String getTemplateId() {
            return templateId;
        }

        public void setTemplateId(String value) {
            this.templateId = value;
        }

        /** The published version to pin to. The document renders against it forever. */
        public int getTemplateVersionNo() {
            return templateVersionNo;
        }

        public void setTemplateVersionNo(int value) {
            this.templateVersionNo = value;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String value) {
            this.title = value;
        }

        /** Which language body to start from. Falls back to Korean, the default locale. */
        public String getLocale() {
            return locale;
        }

        public void setLocale(String value) {
            this.locale = value;
        }

        /** Business-time wire form. Omit and the API stamps today's wall clock. */
        public String getAuthoredAt() {
            return authoredAt;
        }

        public void setAuthoredAt(String value) {
            this.authoredAt = value;
        }

        /**
         * The currency a MONEY field with no code written into it is read as —
         * the company's base currency, never a hardcoded KRW.
         */
        public String getDefaultCurrencyCode() {
            return defaultCurrencyCode;
        }

        public void setDefaultCurrencyCode(String value) {
            this.defaultCurrencyCode = value;
        }
    }

    @PostMapping
    @Operation(summary = "Draft a document from a template version",
            description = "Requires documents.document:write. The first version is the template "
                    + "body byte for byte, so an untouched draft is provably identical to the "
                    + "template it came from. The document is pinned to the template version "
                    + "given and does not follow later edits to the template.")
    @ApiResponse(responseCode = "201", content = @Content(
            schema = @Schema(implementation = DocumentView.class)))
    public ResponseEntity<Object> createFromTemplate(
            @Valid @RequestBody CreateFromTemplateRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        access.company(caller, body.getCompanyId(), DocumentPermissions.DOCUMENT_WRITE, on,
                "drafting \"" + body.getTitle() + "\"");

        BusinessInstant authoredAt = BusinessInstants.resolve(body.getAuthoredAt());
        String locale = Texts.isBlank(body.getLocale()) ? "ko" : Texts.strip(body.getLocale());
        DocumentEntity document = documents.createFromTemplate(body.getCompanyId(),
                body.getTemplateId(), body.getTemplateVersionNo(), locale, body.getTitle(),
                caller.accountId(), authoredAt, body.getDefaultCurrencyCode());

        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(DocumentView.tagOf(document))
                .body((Object) DocumentView.from(document));
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Create a document from an uploaded file",
            description = "Requires documents.document:write. The upload is stored as version 1 "
                    + "exactly as it arrived and is never overwritten (§6.6). A document created "
                    + "this way is pinned to no template, so it has no field manifest and its "
                    + "field values are not extracted until it is promoted to one.")
    @ApiResponse(responseCode = "201", content = @Content(
            schema = @Schema(implementation = DocumentView.class)))
    public ResponseEntity<Object> createFromUpload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("companyId") String companyId,
            @RequestParam("documentType") String documentType,
            @RequestParam("title") String title,
            @RequestParam("format") String format,
            @RequestParam(name = "authoredAt", required = false) String authoredAt,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        access.company(caller, companyId, DocumentPermissions.DOCUMENT_WRITE, on,
                "uploading \"" + title + "\"");

        String oversize = Uploads.tooLarge(file, Uploads.DOCUMENT_MAX_BYTES, "document");
        if (oversize != null) {
            return DocumentProblems.tooLarge(oversize);
        }

        DocumentEntity document = documents.createFromUpload(companyId, documentType, title,
                Uploads.bytes(file, "document"), documentFormat(format), caller.accountId(),
                BusinessInstants.resolve(authoredAt));

        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(DocumentView.tagOf(document))
                .body((Object) DocumentView.from(document));
    }

    @GetMapping
    @Operation(summary = "List documents in a company",
            description = "Requires documents.document:read, decided per row. A page can come "
                    + "back shorter than the limit because rows the evaluator refused are simply "
                    + "absent — read nextCursor, not the count. Retired documents are excluded.")
    public CursorPage<DocumentView> list(
            @RequestParam("companyId") String companyId,
            @RequestParam(name = "documentType", required = false) String documentType,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        return DocumentPages.page(access.list(caller, companyId, documentType, on),
                DocumentView.KEYS, DocumentView.MAPPER, cursor, limit);
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "Read one document's header",
            description = "Requires documents.document:read. The ETag returned is the one an "
                    + "If-Match on any mutation of this document must carry.")
    @ApiResponse(responseCode = "200", content = @Content(
            schema = @Schema(implementation = DocumentView.class)))
    public ResponseEntity<Object> read(@PathVariable String documentId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> document = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_READ, on, "reading document " + documentId);
        if (!document.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        return ResponseEntity.ok().eTag(DocumentView.tagOf(document.get()))
                .body((Object) DocumentView.from(document.get()));
    }

    @GetMapping("/{documentId}/versions")
    @Operation(summary = "The version history",
            description = "Requires documents.document:read. Append-only: a version is never "
                    + "rewritten, so an approval that pointed at version 3 still resolves to what "
                    + "was approved.")
    public ResponseEntity<Object> versions(@PathVariable String documentId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> document = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_READ, on, "version history of " + documentId);
        if (!document.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        return ResponseEntity.ok((Object) DocumentPages.page(documents.versionsOf(documentId),
                DocumentVersionView.KEYS, DocumentVersionView.MAPPER, cursor, limit));
    }

    @GetMapping("/{documentId}/versions/{versionNo}")
    @Operation(summary = "Read one version",
            description = "Requires documents.document:read. Metadata only — the bytes are a "
                    + "separate download so that a version listing is not a file transfer.")
    @ApiResponse(responseCode = "200", content = @Content(
            schema = @Schema(implementation = DocumentVersionView.class)))
    public ResponseEntity<Object> version(@PathVariable String documentId,
            @PathVariable int versionNo,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> document = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_READ, on, "version of " + documentId);
        if (!document.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        Optional<DocumentVersionEntity> version = documents.version(documentId, versionNo);
        if (!version.isPresent()) {
            return DocumentProblems.notFound("version",
                    documentId + " v" + versionNo);
        }
        return ResponseEntity.ok((Object) DocumentVersionView.from(version.get()));
    }

    @GetMapping("/{documentId}/versions/{versionNo}/content")
    @Operation(summary = "Download the stored bytes of a version",
            description = "Requires documents.document:export, not merely :read. For a DOCX "
                    + "document these bytes are the DOCX export, so serving them under the read "
                    + "permission would make the export permission decorative.")
    public ResponseEntity<Object> content(@PathVariable String documentId,
            @PathVariable int versionNo,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> document = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_EXPORT, on, "downloading " + documentId);
        if (!document.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        Optional<DocumentVersionEntity> version = documents.version(documentId, versionNo);
        if (!version.isPresent()) {
            return DocumentProblems.notFound("version", documentId + " v" + versionNo);
        }
        byte[] bytes = documents.contentOf(documentId, versionNo);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        ExportFormats.mediaTypeOf(version.get().format())))
                .header("Content-Disposition", ExportFormats.attachment(
                        document.get().title(), version.get().format().name()))
                .body((Object) bytes);
    }

    @PostMapping(path = "/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Save a new version",
            description = "Requires documents.document:write and If-Match carrying the ETag of "
                    + "the document as read. Cross-validation against the pinned field manifest "
                    + "runs before a byte is stored and names every field that disagrees, so a "
                    + "document whose fields save into nothing never reaches the store.")
    @ApiResponse(responseCode = "201", content = @Content(
            schema = @Schema(implementation = DocumentVersionView.class)))
    public ResponseEntity<Object> saveVersion(@PathVariable String documentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestPart("file") MultipartFile file,
            @RequestParam("format") String format,
            @RequestParam(name = "authoredAt", required = false) String authoredAt,
            @RequestParam(name = "defaultCurrencyCode", required = false) String defaultCurrencyCode,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> found = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_WRITE, on, "saving a version of " + documentId);
        if (!found.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        DocumentEntity document = found.get();
        ETags.require(ifMatch, DocumentView.tagOf(document), "document \"" + document.title() + "\"");

        String oversize = Uploads.tooLarge(file, Uploads.DOCUMENT_MAX_BYTES, "document");
        if (oversize != null) {
            return DocumentProblems.tooLarge(oversize);
        }

        DocumentVersionEntity version = documents.saveVersion(documentId,
                Uploads.bytes(file, "document"), documentFormat(format), caller.accountId(),
                BusinessInstants.resolve(authoredAt), defaultCurrencyCode);

        // The tag moves with the version: the caller's next write must carry the
        // one this save produced, not the one they arrived with.
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ETags.of(documentId, version.versionNo().longValue()))
                .body((Object) DocumentVersionView.from(version));
    }

    @GetMapping("/{documentId}/versions/{versionNo}/fields")
    @Operation(summary = "The typed field values of a version",
            description = "Requires documents.document:read. Each value carries its type and its "
                    + "label; money is an exact decimal string with its currency code beside it, "
                    + "never a JSON number. A field left blank has no row at all.")
    public ResponseEntity<Object> fields(@PathVariable String documentId,
            @PathVariable int versionNo,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> document = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_READ, on, "field values of " + documentId);
        if (!document.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        return ResponseEntity.ok((Object) fieldViews(document.get(),
                documents.fieldValuesOf(documentId, versionNo)));
    }

    @GetMapping("/{documentId}/versions/{versionNo}/missing-required")
    @Operation(summary = "Required fields still empty",
            description = "Requires documents.document:read. The submission gate: drafts are "
                    + "saved half-filled all day, so this is asked before 결재 rather than on "
                    + "save. An empty list means the document is ready to submit.")
    public ResponseEntity<Object> missingRequired(@PathVariable String documentId,
            @PathVariable int versionNo,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> document = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_READ, on, "submission check on " + documentId);
        if (!document.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        return ResponseEntity.ok((Object) documents.missingRequiredFields(documentId, versionNo));
    }

    @PostMapping("/{documentId}/retirement")
    @Operation(summary = "Retire a document",
            description = "Requires documents.document:retire and If-Match. There is no delete: "
                    + "the row stays and the versions stay, because an approved document is "
                    + "evidence. A retired document takes no further versions.")
    @ApiResponse(responseCode = "200", content = @Content(
            schema = @Schema(implementation = DocumentView.class)))
    public ResponseEntity<Object> retire(@PathVariable String documentId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> found = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_RETIRE, on, "retiring document " + documentId);
        if (!found.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        ETags.require(ifMatch, DocumentView.tagOf(found.get()),
                "document \"" + found.get().title() + "\"");

        documents.retire(documentId, OffsetDateTime.now());
        Optional<DocumentEntity> retired = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_READ, on, "retired document " + documentId);
        return ResponseEntity.ok((Object) DocumentView.from(retired.get()));
    }

    @GetMapping("/field-values")
    @Operation(summary = "Find documents by a typed field value",
            description = "Requires documents.document:read, decided per document, so a search "
                    + "cannot be used to learn the contents of a document the caller may not "
                    + "open. Exactly one of text, amountAtLeast or refId must be given: a MONEY "
                    + "question is asked of the amount and a text question of the text, because "
                    + "comparing money as text is how \"over 5,000,000\" starts matching 900,000.")
    public ResponseEntity<Object> fieldValues(
            @RequestParam("fieldId") String fieldId,
            @RequestParam(name = "text", required = false) String text,
            @RequestParam(name = "amountAtLeast", required = false) String amountAtLeast,
            @RequestParam(name = "refId", required = false) String refId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);

        int given = (Texts.isBlank(text) ? 0 : 1) + (Texts.isBlank(amountAtLeast) ? 0 : 1)
                + (Texts.isBlank(refId) ? 0 : 1);
        if (given != 1) {
            return DocumentProblems.badRequest("one_criterion_required",
                    "Give exactly one criterion",
                    "Send one of text, amountAtLeast or refId. " + given + " were given, and "
                            + "combining them silently would answer a different question from "
                            + "the one asked.");
        }

        List<DocumentFieldValueEntity> matches;
        if (!Texts.isBlank(text)) {
            matches = access.findByText(caller, fieldId, text, on);
        } else if (!Texts.isBlank(refId)) {
            matches = access.findByReference(caller, fieldId, refId, on);
        } else {
            // From a string, never from a double: new BigDecimal(0.1) is
            // 0.1000000000000000055511151231257827 and would silently move the
            // threshold (ADR 0004).
            matches = access.findByAmountAtLeast(caller, fieldId,
                    new BigDecimal(Texts.strip(amountAtLeast)), on);
        }

        return ResponseEntity.ok((Object) DocumentPages.page(matches, FieldValueHit.KEYS,
                FieldValueHit.MAPPER, cursor, limit));
    }

    /** A search hit: which document and version carried the value. */
    public static class FieldValueHit {

        static final java.util.function.Function<DocumentFieldValueEntity, FieldValueHit> MAPPER =
                new java.util.function.Function<DocumentFieldValueEntity, FieldValueHit>() {
                    @Override
                    public FieldValueHit apply(DocumentFieldValueEntity value) {
                        return new FieldValueHit(value);
                    }
                };

        static final DocumentPages.Keys<DocumentFieldValueEntity> KEYS =
                new DocumentPages.Keys<DocumentFieldValueEntity>() {
                    @Override
                    public String sortKey(DocumentFieldValueEntity value) {
                        return value.documentId();
                    }

                    @Override
                    public String id(DocumentFieldValueEntity value) {
                        return value.documentId() + "#" + value.versionNo() + "#" + value.fieldId();
                    }
                };

        private final String documentId;
        private final int versionNo;
        private final FieldValueView value;

        FieldValueHit(DocumentFieldValueEntity value) {
            this.documentId = value.documentId();
            this.versionNo = value.versionNo().intValue();
            // No manifest is loaded for a search hit: the labels belong to the
            // document's own template version, and fetching one per row would
            // turn a search into N template reads.
            this.value = new FieldValueView(value, null);
        }

        public String getDocumentId() {
            return documentId;
        }

        public int getVersionNo() {
            return versionNo;
        }

        public FieldValueView getValue() {
            return value;
        }
    }

    private List<FieldValueView> fieldViews(DocumentEntity document,
            List<DocumentFieldValueEntity> values) {
        DocumentFieldSchema schema = null;
        if (document.templateId() != null && document.templateVersionNo() != null) {
            schema = templates.schemaOf(document.templateId(),
                    document.templateVersionNo().intValue());
        }
        List<FieldValueView> views = new ArrayList<FieldValueView>(values.size());
        for (DocumentFieldValueEntity value : values) {
            views.add(new FieldValueView(value,
                    schema == null ? null : schema.field(value.fieldId())));
        }
        return Immutables.copyOf(views);
    }

    /**
     * The four formats a document body can be <em>stored</em> in.
     *
     * <p>Deliberately not {@code valueOf}: its message on a typo names the
     * constant that was not found and nothing else, so a client sending "pdf"
     * would learn that PDF is missing rather than that PDF is an export target
     * and not a storage format.
     */
    private static DocumentFormat documentFormat(String requested) {
        if (!Texts.isBlank(requested)) {
            String wanted = Texts.strip(requested).toUpperCase(java.util.Locale.ROOT);
            for (DocumentFormat candidate : DocumentFormat.values()) {
                if (candidate.name().equals(wanted)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("\"" + requested + "\" is not a storage format. A "
                + "document body is DOCX, HWPX, HWP or MDV; PDF and DOC are export targets, "
                + "which is a different question asked of /export.");
    }
}
