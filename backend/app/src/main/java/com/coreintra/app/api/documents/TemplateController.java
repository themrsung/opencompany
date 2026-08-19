package com.coreintra.app.api.documents;

import com.coreintra.app.api.error.ProblemDetail;
import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.DocumentTemplateBodyEntity;
import com.coreintra.documents.entity.DocumentTemplateEntity;
import com.coreintra.documents.entity.DocumentTemplateVersionEntity;
import com.coreintra.documents.ooxml.OoxmlException;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.schema.FieldType;
import com.coreintra.documents.service.BlobService;
import com.coreintra.documents.service.FieldValueFormatException;
import com.coreintra.documents.service.TemplateBody;
import com.coreintra.documents.service.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
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
 * Templates: the versioned, client-editable documents everything else is drafted
 * from.
 *
 * <h2>Publishing is the interesting operation</h2>
 *
 * <p>A published version is frozen and every document drafted from it renders
 * against it forever (§6.8), so the cross-validation in §6.1 happens here and
 * not later: a manifest field with no matching content control, or a control
 * with no manifest entry, fails the save. The failure comes back as a 422 with
 * <b>one violation per field</b>, naming the tag — discovering at export time
 * that an input has been saving into nothing, on a document somebody has already
 * approved, is the outcome this prevents.
 *
 * <h2>Forking and restoring</h2>
 *
 * <p>A seeded template is editable by forking it, not by overwriting it, so that
 * {@code restore-to-factory} always has something to restore to. That is why
 * fork is a separate operation rather than a flag on save.
 */
@RestController
@RequestMapping("/api/v1/templates")
@Tag(name = "Templates",
        description = "Versioned document templates. The field manifest and the docx content "
                + "controls are cross-validated on publish, and the failure names the field.")
public class TemplateController {

    private final TemplateService templates;
    private final BlobService blobs;
    private final DocumentAccess access;
    private final CurrentPrincipal current;
    private final ObjectMapper json;

    public TemplateController(TemplateService templates, BlobService blobs, DocumentAccess access,
            CurrentPrincipal current, ObjectMapper json) {
        this.templates = templates;
        this.blobs = blobs;
        this.access = access;
        this.current = current;
        this.json = json;
    }

    /** Creating the template row. Bodies and fields arrive with the first published version. */
    public static class CreateTemplateRequest {
        @NotBlank
        private String companyId;
        @NotBlank
        private String code;
        @NotBlank
        private String documentType;
        @NotBlank
        private String nameKo;
        private String nameEn;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        /** Unique per company, and the handle a seeded template is found by. */
        public String getCode() {
            return code;
        }

        public void setCode(String value) {
            this.code = value;
        }

        public String getDocumentType() {
            return documentType;
        }

        public void setDocumentType(String value) {
            this.documentType = value;
        }

        /** Required: Korean is the default locale. */
        public String getNameKo() {
            return nameKo;
        }

        public void setNameKo(String value) {
            this.nameKo = value;
        }

        public String getNameEn() {
            return nameEn;
        }

        public void setNameEn(String value) {
            this.nameEn = value;
        }
    }

    /** Copying a template into one the client owns. */
    public static class ForkRequest {
        @NotBlank
        private String newCode;
        @NotBlank
        private String newNameKo;
        private String at;

        public String getNewCode() {
            return newCode;
        }

        public void setNewCode(String value) {
            this.newCode = value;
        }

        public String getNewNameKo() {
            return newNameKo;
        }

        public void setNewNameKo(String value) {
            this.newNameKo = value;
        }

        /** Business-time wire form for the fork's first published version. */
        public String getAt() {
            return at;
        }

        public void setAt(String value) {
            this.at = value;
        }
    }

    @GetMapping
    @Operation(summary = "List templates in a company",
            description = "Requires documents.template:read. Ordered by code, cursor-paged.")
    public ResponseEntity<Object> list(@RequestParam("companyId") String companyId,
            @RequestParam(name = "documentType", required = false) String documentType,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        access.company(caller, companyId, DocumentPermissions.TEMPLATE_READ, on,
                "listing templates");

        List<DocumentTemplateEntity> rows = Texts.isBlank(documentType)
                ? templates.list(companyId)
                : templates.listFor(companyId, documentType);
        CursorPage<TemplateView> page = DocumentPages.page(rows, TemplateView.KEYS,
                TemplateView.MAPPER, cursor, limit);
        return ResponseEntity.ok((Object) page);
    }

    @GetMapping("/{templateId}")
    @Operation(summary = "Read one template",
            description = "Requires documents.template:read. The ETag is what If-Match on a "
                    + "publish or a retirement must carry.")
    @ApiResponse(responseCode = "200", content = @Content(
            schema = @Schema(implementation = TemplateView.class)))
    public ResponseEntity<Object> read(@PathVariable String templateId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<DocumentTemplateEntity> template = access.template(caller, templateId,
                DocumentPermissions.TEMPLATE_READ, BusinessInstants.date(businessDate),
                "reading template " + templateId);
        if (!template.isPresent()) {
            return DocumentProblems.notFound("template", templateId);
        }
        return ResponseEntity.ok().eTag(TemplateView.tagOf(template.get()))
                .body((Object) TemplateView.from(template.get()));
    }

    @PostMapping
    @Operation(summary = "Create a template",
            description = "Requires documents.template:write. Creates the row only: a template "
                    + "with no published version drafts nothing, which is deliberate — the "
                    + "manifest and the docx are validated together at publish.")
    @ApiResponse(responseCode = "201", content = @Content(
            schema = @Schema(implementation = TemplateView.class)))
    public ResponseEntity<Object> create(@Valid @RequestBody CreateTemplateRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        access.company(caller, body.getCompanyId(), DocumentPermissions.TEMPLATE_WRITE,
                BusinessInstants.date(businessDate), "creating template " + body.getCode());

        DocumentTemplateEntity template = templates.create(body.getCompanyId(), body.getCode(),
                body.getDocumentType(), body.getNameKo(), body.getNameEn(), caller.accountId());
        return ResponseEntity.status(HttpStatus.CREATED).eTag(TemplateView.tagOf(template))
                .body((Object) TemplateView.from(template));
    }

    @GetMapping("/{templateId}/versions")
    @Operation(summary = "The published versions",
            description = "Requires documents.template:read. Documents are pinned to one of "
                    + "these and never follow a later one.")
    public ResponseEntity<Object> versions(@PathVariable String templateId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<DocumentTemplateEntity> template = access.template(caller, templateId,
                DocumentPermissions.TEMPLATE_READ, BusinessInstants.date(businessDate),
                "versions of template " + templateId);
        if (!template.isPresent()) {
            return DocumentProblems.notFound("template", templateId);
        }
        return ResponseEntity.ok((Object) TemplateView.versions(templates.versionsOf(templateId)));
    }

    @GetMapping("/{templateId}/versions/{versionNo}/fields")
    @Operation(summary = "The field manifest of a version",
            description = "Requires documents.template:read. The manifest describes the content "
                    + "controls present in the docx — it is not a parallel document model — so "
                    + "every entry here has a matching w:sdt in the body.")
    public ResponseEntity<Object> fields(@PathVariable String templateId,
            @PathVariable int versionNo,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<DocumentTemplateEntity> template = access.template(caller, templateId,
                DocumentPermissions.TEMPLATE_READ, BusinessInstants.date(businessDate),
                "field manifest of template " + templateId);
        if (!template.isPresent()) {
            return DocumentProblems.notFound("template", templateId);
        }
        return ResponseEntity.ok((Object) TemplateView.fields(
                templates.schemaOf(templateId, versionNo)));
    }

    @GetMapping("/{templateId}/versions/{versionNo}/body")
    @Operation(summary = "Download a template body",
            description = "Requires documents.template:read. Falls back to the Korean body when "
                    + "the locale asked for was never published, because Korean is the default "
                    + "locale and a template published in Korean alone is complete.")
    public ResponseEntity<Object> body(@PathVariable String templateId,
            @PathVariable int versionNo,
            @RequestParam(name = "locale", required = false) String locale,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<DocumentTemplateEntity> template = access.template(caller, templateId,
                DocumentPermissions.TEMPLATE_READ, BusinessInstants.date(businessDate),
                "body of template " + templateId);
        if (!template.isPresent()) {
            return DocumentProblems.notFound("template", templateId);
        }
        Optional<DocumentTemplateBodyEntity> found = templates.bodyOf(templateId, versionNo,
                Texts.isBlank(locale) ? "ko" : Texts.strip(locale));
        if (!found.isPresent()) {
            return DocumentProblems.notFound("template body",
                    templateId + " v" + versionNo);
        }
        DocumentTemplateBodyEntity body = found.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ExportFormats.mediaTypeOf(body.format())))
                .header("Content-Disposition", ExportFormats.attachment(
                        template.get().code() + "-v" + versionNo, body.format().name()))
                .body((Object) blobs.contentOf(body.blobSha256()));
    }

    @PostMapping(path = "/{templateId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Publish a template version",
            description = "Requires documents.template:publish and If-Match. Send one file per "
                    + "locale with matching locales[] and formats[] entries, and the field "
                    + "manifest as JSON in schema. At least one DOCX body is required: it is the "
                    + "canonical form and the only one the manifest can be checked against. A "
                    + "mismatch is a 422 with one violation per field, naming the tag.")
    public ResponseEntity<Object> publish(@PathVariable String templateId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestPart("files") MultipartFile[] files,
            @RequestParam("locales") List<String> locales,
            @RequestParam("formats") List<String> formats,
            @RequestParam("schema") String schemaJson,
            @RequestParam(name = "publishedAt", required = false) String publishedAt,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<DocumentTemplateEntity> found = access.template(caller, templateId,
                DocumentPermissions.TEMPLATE_PUBLISH, BusinessInstants.date(businessDate),
                "publishing a version of template " + templateId);
        if (!found.isPresent()) {
            return DocumentProblems.notFound("template", templateId);
        }
        ETags.require(ifMatch, TemplateView.tagOf(found.get()),
                "template \"" + found.get().nameKo() + "\"");

        if (files == null || files.length == 0) {
            return DocumentProblems.badRequest("no_bodies", "A version needs at least one body",
                    "Send the docx as files, with a locale and a format for it.");
        }
        if (files.length != locales.size() || files.length != formats.size()) {
            return DocumentProblems.badRequest("body_metadata_mismatch",
                    "Each body needs a locale and a format",
                    files.length + " files were sent with " + locales.size() + " locales and "
                            + formats.size() + " formats. They are matched by position, so a "
                            + "mismatch would publish a Korean body under an English locale.");
        }

        List<TemplateBody> bodies = new ArrayList<TemplateBody>();
        for (int i = 0; i < files.length; i++) {
            String oversize = Uploads.tooLarge(files[i], Uploads.DOCUMENT_MAX_BYTES,
                    "template body");
            if (oversize != null) {
                return DocumentProblems.tooLarge(oversize);
            }
            bodies.add(new TemplateBody(locales.get(i), storageFormat(formats.get(i)),
                    Uploads.bytes(files[i], "template body"), Uploads.name(files[i])));
        }

        DocumentFieldSchema schema;
        try {
            schema = parseSchema(schemaJson);
        } catch (IOException malformed) {
            return DocumentProblems.badRequest("schema_unreadable",
                    "The field manifest is not readable JSON", malformed.getMessage());
        }

        try {
            DocumentTemplateVersionEntity version = templates.publishVersion(templateId, schema,
                    bodies, caller.accountId(), BusinessInstants.resolve(publishedAt));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .eTag(ETags.of(templateId, version.versionNo().longValue()))
                    .body((Object) new TemplateView.Version(version));
        } catch (DocumentFieldSchema.SchemaMismatchException mismatch) {
            return mismatchProblem(mismatch);
        } catch (FieldValueFormatException unreadable) {
            return DocumentProblems.fieldFormat(unreadable);
        } catch (OoxmlException unreadable) {
            // The bytes are not the format they were announced as. That is the
            // uploader's mistake and it has an actionable message already.
            return DocumentProblems.unprocessable("body_unreadable",
                    "That file could not be read as a document", unreadable.getMessage());
        }
    }

    @PostMapping("/{templateId}/fork")
    @Operation(summary = "Fork a template",
            description = "Requires documents.template:write. Copies the latest version into a "
                    + "new template the client owns. Seeded templates are forked rather than "
                    + "edited so that restore-to-factory always has something to restore to.")
    @ApiResponse(responseCode = "201", content = @Content(
            schema = @Schema(implementation = TemplateView.class)))
    public ResponseEntity<Object> fork(@PathVariable String templateId,
            @Valid @RequestBody ForkRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<DocumentTemplateEntity> source = access.template(caller, templateId,
                DocumentPermissions.TEMPLATE_WRITE, BusinessInstants.date(businessDate),
                "forking template " + templateId);
        if (!source.isPresent()) {
            return DocumentProblems.notFound("template", templateId);
        }
        DocumentTemplateEntity forked = templates.fork(templateId, body.getNewCode(),
                body.getNewNameKo(), caller.accountId(), BusinessInstants.resolve(body.getAt()));
        return ResponseEntity.status(HttpStatus.CREATED).eTag(TemplateView.tagOf(forked))
                .body((Object) TemplateView.from(forked));
    }

    @PostMapping("/{templateId}/restore-to-factory")
    @Operation(summary = "Restore a seeded template to factory state",
            description = "Requires documents.template:write and If-Match. Publishes the shipped "
                    + "version as a new version rather than rewriting history, so documents "
                    + "drafted from the client's edits still render as they were approved. Only "
                    + "a built-in template can be restored; a fork has no factory state.")
    public ResponseEntity<Object> restore(@PathVariable String templateId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam(name = "at", required = false) String at,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<DocumentTemplateEntity> found = access.template(caller, templateId,
                DocumentPermissions.TEMPLATE_WRITE, BusinessInstants.date(businessDate),
                "restoring template " + templateId);
        if (!found.isPresent()) {
            return DocumentProblems.notFound("template", templateId);
        }
        ETags.require(ifMatch, TemplateView.tagOf(found.get()),
                "template \"" + found.get().nameKo() + "\"");

        DocumentTemplateVersionEntity restored = templates.restoreToFactory(templateId,
                caller.accountId(), BusinessInstants.resolve(at));
        return ResponseEntity.ok((Object) new TemplateView.Version(restored));
    }

    @PostMapping("/{templateId}/retirement")
    @Operation(summary = "Retire a template",
            description = "Requires documents.template:retire and If-Match. Documents already "
                    + "drafted from it keep rendering: they are pinned to a version, and the "
                    + "version rows stay.")
    public ResponseEntity<Object> retire(@PathVariable String templateId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        Optional<DocumentTemplateEntity> found = access.template(caller, templateId,
                DocumentPermissions.TEMPLATE_RETIRE, BusinessInstants.date(businessDate),
                "retiring template " + templateId);
        if (!found.isPresent()) {
            return DocumentProblems.notFound("template", templateId);
        }
        ETags.require(ifMatch, TemplateView.tagOf(found.get()),
                "template \"" + found.get().nameKo() + "\"");

        templates.retire(templateId, OffsetDateTime.now());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{templateId}/versions/{versionNo}/documents")
    @Operation(summary = "Documents drafted from this version",
            description = "Requires documents.template:read, and each document is filtered by "
                    + "documents.document:read. The impact list to look at before changing a "
                    + "template: these are the documents pinned to this version.")
    public ResponseEntity<Object> documents(@PathVariable String templateId,
            @PathVariable int versionNo,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentTemplateEntity> template = access.template(caller, templateId,
                DocumentPermissions.TEMPLATE_READ, on, "impact list for template " + templateId);
        if (!template.isPresent()) {
            return DocumentProblems.notFound("template", templateId);
        }
        return ResponseEntity.ok((Object) DocumentPages.page(
                access.draftedFrom(caller, templateId, versionNo, on),
                DocumentView.KEYS, DocumentView.MAPPER, cursor, limit));
    }

    /**
     * Turns the mismatch into one violation per field.
     *
     * <p>The service already names every field at once; this keeps them named on
     * the wire instead of flattening five specific problems into one paragraph
     * the client can only display verbatim.
     */
    private static ResponseEntity<Object> mismatchProblem(
            DocumentFieldSchema.SchemaMismatchException mismatch) {

        List<ProblemDetail.Violation> violations = new ArrayList<ProblemDetail.Violation>();
        DocumentFieldSchema.ValidationResult result = mismatch.result();
        if (result != null) {
            for (String tag : result.declaredButNotInDocument()) {
                violations.add(new ProblemDetail.Violation(tag, "field_not_in_document",
                        "The manifest declares \"" + tag + "\" but the document has no content "
                                + "control with that tag, so the field would render an input that "
                                + "saves into nothing."));
            }
            for (String tag : result.inDocumentButNotDeclared()) {
                violations.add(new ProblemDetail.Violation(tag, "control_not_declared",
                        "The document has a content control tagged \"" + tag + "\" that the "
                                + "manifest does not declare, so nobody can fill it in and it "
                                + "will print blank."));
            }
        }
        return DocumentProblems.fieldViolations("schema_mismatch",
                "The field manifest and the document disagree", mismatch.getMessage(), violations);
    }

    private DocumentFieldSchema parseSchema(String schemaJson) throws IOException {
        JsonNode root = json.readTree(Texts.isBlank(schemaJson) ? "{}" : schemaJson);
        JsonNode fields = root.isArray() ? root : root.path("fields");
        List<FieldDefinition> definitions = new ArrayList<FieldDefinition>();
        for (int i = 0; i < fields.size(); i++) {
            JsonNode field = fields.get(i);
            definitions.add(new FieldDefinition(
                    text(field, "tag"),
                    fieldType(text(field, "type")),
                    text(field, "labelKo"),
                    text(field, "labelEn"),
                    field.path("required").asBoolean(false),
                    text(field, "helpKo"),
                    text(field, "helpEn")));
        }
        return new DocumentFieldSchema(definitions);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static FieldType fieldType(String requested) {
        if (!Texts.isBlank(requested)) {
            String wanted = Texts.strip(requested).toUpperCase(Locale.ROOT);
            for (FieldType candidate : FieldType.values()) {
                if (candidate.name().equals(wanted)) {
                    return candidate;
                }
            }
        }
        StringBuilder known = new StringBuilder();
        for (FieldType candidate : FieldType.values()) {
            if (known.length() > 0) {
                known.append(", ");
            }
            known.append(candidate.name());
        }
        throw new IllegalArgumentException("\"" + requested + "\" is not a field type. The types "
                + "are: " + known + ".");
    }

    private static DocumentFormat storageFormat(String requested) {
        if (!Texts.isBlank(requested)) {
            String wanted = Texts.strip(requested).toUpperCase(Locale.ROOT);
            for (DocumentFormat candidate : DocumentFormat.values()) {
                if (candidate.name().equals(wanted)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("\"" + requested + "\" is not a body format. A template "
                + "body is DOCX, HWPX, HWP or MDV, and at least one must be DOCX.");
    }
}
