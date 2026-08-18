package com.coreintra.app.api.documents;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.DocumentVersionEntity;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.service.DocumentService;
import com.coreintra.documents.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comparing two versions of a document.
 *
 * <p>The endpoint is deliberately honest about what a diff means per format
 * (§6.10): the typed field values are compared for every format, and the body is
 * compared line by line only for mdv, which is the only format here whose stored
 * form is canonical text. For the others the response says so and gives the
 * content hashes, which is the true statement available — a DOCX is a ZIP of XML
 * whose bytes move when nothing visible does.
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents — versions",
        description = "Comparing versions. Field values compare for every format; bodies "
                + "compare line by line for mdv only, and the response says which you got.")
public class DocumentDiffController {

    /** Separates the parts of a value's comparable form. See {@link #comparable}. */
    private static final char SEPARATOR = (char) 0x1f;

    private final DocumentService documents;
    private final TemplateService templates;
    private final DocumentAccess access;
    private final CurrentPrincipal current;

    public DocumentDiffController(DocumentService documents, TemplateService templates,
            DocumentAccess access, CurrentPrincipal current) {
        this.documents = documents;
        this.templates = templates;
        this.access = access;
        this.current = current;
    }

    @GetMapping("/{documentId}/versions/{versionNo}/diff")
    @Operation(summary = "Compare a version with an earlier one",
            description = "Requires documents.document:read. Compares against the version this "
                    + "one superseded unless another is named. body.available is false for every "
                    + "format but mdv — an empty body diff of a DOCX would read as \"nothing "
                    + "changed\", which is the one answer that must not be guessed.")
    @ApiResponse(responseCode = "200", content = @Content(
            schema = @Schema(implementation = VersionDiffView.class)))
    public ResponseEntity<Object> diff(@PathVariable String documentId,
            @PathVariable int versionNo,
            @RequestParam(name = "against", required = false) Integer against,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = current.require();
        LocalDate on = BusinessInstants.date(businessDate);
        Optional<DocumentEntity> found = access.document(caller, documentId,
                DocumentPermissions.DOCUMENT_READ, on, "comparing versions of " + documentId);
        if (!found.isPresent()) {
            return DocumentProblems.notFound("document", documentId);
        }
        Optional<DocumentVersionEntity> toRow = documents.version(documentId, versionNo);
        if (!toRow.isPresent()) {
            return DocumentProblems.notFound("version", documentId + " v" + versionNo);
        }
        DocumentVersionEntity to = toRow.get();

        int fromVersion = against != null
                ? against.intValue()
                : (to.supersedesVersionNo() == null ? versionNo - 1
                        : to.supersedesVersionNo().intValue());
        if (fromVersion < 1) {
            return DocumentProblems.badRequest("nothing_to_compare",
                    "There is nothing before version 1",
                    "Version " + versionNo + " is the first version of this document, so there "
                            + "is no earlier one to compare it with. Name another with ?against=.");
        }
        Optional<DocumentVersionEntity> fromRow = documents.version(documentId, fromVersion);
        if (!fromRow.isPresent()) {
            return DocumentProblems.notFound("version", documentId + " v" + fromVersion);
        }
        DocumentVersionEntity from = fromRow.get();

        return ResponseEntity.ok((Object) new VersionDiffView(documentId, fromVersion, versionNo,
                from.format().name(), to.format().name(), from.blobSha256(), to.blobSha256(),
                body(documentId, from, to, fromVersion, versionNo),
                fieldChanges(found.get(), fromVersion, versionNo)));
    }

    private VersionDiffView.Body body(String documentId, DocumentVersionEntity from,
            DocumentVersionEntity to, int fromVersion, int toVersion) {

        if (from.format() != DocumentFormat.MDV || to.format() != DocumentFormat.MDV) {
            return new VersionDiffView.Body(false,
                    "A line-by-line comparison is only meaningful for mdv, whose stored form is "
                            + "canonical text (mdv fmt runs on save, so two versions differ "
                            + "exactly where the author changed something). " + from.format()
                            + " and " + to.format() + " are packaged XML or binary whose bytes "
                            + "move when nothing visible does. Compare the field values below, "
                            + "and the content hashes: identicalBytes is the only claim about "
                            + "the body this endpoint will make for these formats.", null);
        }
        if (from.blobSha256() != null && from.blobSha256().equals(to.blobSha256())) {
            return new VersionDiffView.Body(true, "The two versions are the same bytes.",
                    TextDiff.of(new ArrayList<String>(), new ArrayList<String>()));
        }
        List<String> before = TextDiff.lines(documents.contentOf(documentId, fromVersion));
        List<String> after = TextDiff.lines(documents.contentOf(documentId, toVersion));
        return new VersionDiffView.Body(true, null, TextDiff.of(before, after));
    }

    /**
     * Which typed fields differ.
     *
     * <p>Values are compared on their typed contents rather than on rendered
     * text: 1400000.00 and 1400000 are the same amount and must not show as a
     * change, while 1,400,000 KRW and 1,400,000 JPY are not the same amount and
     * must.
     */
    private List<VersionDiffView.FieldChange> fieldChanges(DocumentEntity document,
            int fromVersion, int toVersion) {

        Map<String, DocumentFieldValueEntity> before =
                byField(documents.fieldValuesOf(document.id(), fromVersion));
        Map<String, DocumentFieldValueEntity> after =
                byField(documents.fieldValuesOf(document.id(), toVersion));

        DocumentFieldSchema schema = null;
        if (document.templateId() != null && document.templateVersionNo() != null) {
            schema = templates.schemaOf(document.templateId(),
                    document.templateVersionNo().intValue());
        }

        Set<String> fields = new LinkedHashSet<String>();
        fields.addAll(before.keySet());
        fields.addAll(after.keySet());

        List<VersionDiffView.FieldChange> changes =
                new ArrayList<VersionDiffView.FieldChange>();
        for (String fieldId : fields) {
            DocumentFieldValueEntity left = before.get(fieldId);
            DocumentFieldValueEntity right = after.get(fieldId);
            String change = verdict(left, right);
            if (change == null) {
                continue;
            }
            FieldDefinition definition = schema == null ? null : schema.field(fieldId);
            changes.add(new VersionDiffView.FieldChange(fieldId, change,
                    left == null ? null : new FieldValueView(left, definition),
                    right == null ? null : new FieldValueView(right, definition)));
        }
        return changes;
    }

    private static String verdict(DocumentFieldValueEntity left, DocumentFieldValueEntity right) {
        if (left == null) {
            return "added";
        }
        if (right == null) {
            return "removed";
        }
        return comparable(left).equals(comparable(right)) ? null : "changed";
    }

    /**
     * The comparable identity of a value.
     *
     * <p>Money compares by {@link java.math.BigDecimal#compareTo} semantics —
     * which is what stripping the trailing zeros achieves — because 1400000.00
     * and 1400000 are the same amount written twice, and reporting that as an
     * edit would train reviewers to ignore the field diff.
     */
    private static String comparable(DocumentFieldValueEntity value) {
        StringBuilder out = new StringBuilder();
        // ASCII unit separator: it cannot occur in a field value that came out
        // of a content control, so no two different values can collide into one
        // comparable string by containing the delimiter themselves.
        out.append(value.fieldType()).append(SEPARATOR);
        out.append(value.valueText() == null ? "" : value.valueText()).append(SEPARATOR);
        out.append(value.valueNumber() == null ? ""
                : value.valueNumber().stripTrailingZeros().toPlainString()).append(SEPARATOR);
        out.append(value.valueAmount() == null ? ""
                : value.valueAmount().stripTrailingZeros().toPlainString()).append(SEPARATOR);
        out.append(value.valueCurrencyCode() == null ? "" : value.valueCurrencyCode())
                .append(SEPARATOR);
        out.append(value.valueDate() == null ? "" : value.valueDate().toString()).append(SEPARATOR);
        out.append(value.valueInstant() == null ? "" : value.valueInstant().toWireString())
                .append(SEPARATOR);
        out.append(value.valueRefId() == null ? "" : value.valueRefId()).append(SEPARATOR);
        out.append(value.valueBlobSha256() == null ? "" : value.valueBlobSha256());
        return out.toString();
    }

    private static Map<String, DocumentFieldValueEntity> byField(
            List<DocumentFieldValueEntity> values) {
        Map<String, DocumentFieldValueEntity> byField =
                new LinkedHashMap<String, DocumentFieldValueEntity>();
        for (DocumentFieldValueEntity value : values) {
            byField.put(value.fieldId(), value);
        }
        return byField;
    }
}
