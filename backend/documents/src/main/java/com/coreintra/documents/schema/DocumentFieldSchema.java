package com.coreintra.documents.schema;

import com.coreintra.compat.Immutables;
import com.coreintra.documents.ooxml.ContentControls;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A template's field manifest, cross-validated against the document itself.
 *
 * <p>The manifest is not a parallel document model. It <em>describes the content
 * controls present in the docx</em>: which tags exist, what type each is, and
 * how to label and validate it. The document remains the source of truth for
 * structure and prose.
 *
 * <p>Because there are two artefacts, they can disagree — and a disagreement is
 * always a real defect:
 *
 * <ul>
 *   <li>a manifest field with no matching control renders an input that saves
 *       into nothing, so the user fills in a value that silently vanishes;</li>
 *   <li>a control with no manifest entry is a field nobody can fill in, and it
 *       prints blank on an approved document.</li>
 * </ul>
 *
 * <p>Both are caught at template-save time by {@link #validateAgainst}, naming
 * the exact field, rather than at export time on a document somebody already
 * approved.
 */
public final class DocumentFieldSchema implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The tag reserved for the 결재란 block. Not a data field. */
    public static final String APPROVAL_BLOCK_TAG = "approvalBlock";

    private final List<FieldDefinition> fields;

    public DocumentFieldSchema(List<FieldDefinition> fields) {
        Map<String, FieldDefinition> byTag = new LinkedHashMap<String, FieldDefinition>();
        for (FieldDefinition field : fields) {
            FieldDefinition existing = byTag.put(field.tag(), field);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "duplicate field tag \"" + field.tag() + "\" in the manifest; a tag is the "
                                + "join to the document and must be unique");
            }
        }
        this.fields = Immutables.copyOf(new ArrayList<FieldDefinition>(byTag.values()));
    }

    public List<FieldDefinition> fields() {
        return fields;
    }

    public FieldDefinition field(String tag) {
        for (FieldDefinition field : fields) {
            if (field.tag().equals(tag)) {
                return field;
            }
        }
        return null;
    }

    public Set<String> tags() {
        Set<String> tags = new LinkedHashSet<String>();
        for (FieldDefinition field : fields) {
            tags.add(field.tag());
        }
        return tags;
    }

    /**
     * Checks the manifest against the controls actually in the document.
     *
     * @param documentPart the {@code word/document.xml} bytes
     */
    public ValidationResult validateAgainst(byte[] documentPart) {
        Set<String> inDocument = new LinkedHashSet<String>();
        for (ContentControls.Control control : ContentControls.read(documentPart)) {
            inDocument.add(control.tag());
        }
        return validateAgainstTags(inDocument);
    }

    /** The tag-level check, separated so it can be tested without a document. */
    public ValidationResult validateAgainstTags(Set<String> tagsInDocument) {
        List<String> missingFromDocument = new ArrayList<String>();
        for (FieldDefinition field : fields) {
            if (!tagsInDocument.contains(field.tag())) {
                missingFromDocument.add(field.tag());
            }
        }

        List<String> missingFromSchema = new ArrayList<String>();
        Set<String> declared = tags();
        for (String tag : tagsInDocument) {
            // The 결재란 is a rendered block, not a data field, so it is expected
            // in the document and correctly absent from the manifest.
            if (!declared.contains(tag) && !APPROVAL_BLOCK_TAG.equals(tag)) {
                missingFromSchema.add(tag);
            }
        }
        return new ValidationResult(missingFromDocument, missingFromSchema,
                tagsInDocument.contains(APPROVAL_BLOCK_TAG));
    }

    /** The outcome, naming exactly which fields disagree. */
    public static final class ValidationResult implements Serializable {

        private static final long serialVersionUID = 1L;

        private final List<String> declaredButNotInDocument;
        private final List<String> inDocumentButNotDeclared;
        private final boolean hasApprovalBlock;

        ValidationResult(List<String> declaredButNotInDocument,
                List<String> inDocumentButNotDeclared, boolean hasApprovalBlock) {
            this.declaredButNotInDocument = Immutables.copyOf(declaredButNotInDocument);
            this.inDocumentButNotDeclared = Immutables.copyOf(inDocumentButNotDeclared);
            this.hasApprovalBlock = hasApprovalBlock;
        }

        public boolean isValid() {
            return declaredButNotInDocument.isEmpty() && inDocumentButNotDeclared.isEmpty();
        }

        /** Manifest fields with no control: an input that saves into nothing. */
        public List<String> declaredButNotInDocument() {
            return declaredButNotInDocument;
        }

        /** Controls with no manifest entry: a field nobody can fill in. */
        public List<String> inDocumentButNotDeclared() {
            return inDocumentButNotDeclared;
        }

        /** Whether a 결재란 block was detected. */
        public boolean hasApprovalBlock() {
            return hasApprovalBlock;
        }

        /**
         * Every problem, in one message, naming every field.
         *
         * <p>All of them at once rather than one at a time: a template author
         * fixing five mismatches should not have to save five times to discover
         * them.
         */
        public String describe() {
            if (isValid()) {
                return "The field manifest matches the document.";
            }
            StringBuilder message = new StringBuilder("This template cannot be saved:\n");
            if (!declaredButNotInDocument.isEmpty()) {
                message.append("  · declared in the manifest but absent from the document: ")
                        .append(declaredButNotInDocument)
                        .append("\n    Those fields would render an input that saves into nothing.\n");
            }
            if (!inDocumentButNotDeclared.isEmpty()) {
                message.append("  · present in the document but absent from the manifest: ")
                        .append(inDocumentButNotDeclared)
                        .append("\n    Those fields cannot be filled in and will print blank.\n");
            }
            return message.toString();
        }

        /** @throws SchemaMismatchException if invalid */
        public void orThrow() {
            if (!isValid()) {
                throw new SchemaMismatchException(describe(), this);
            }
        }
    }

    /** Thrown when a template's manifest and document disagree. */
    public static class SchemaMismatchException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient ValidationResult result;

        public SchemaMismatchException(String message, ValidationResult result) {
            super(message);
            this.result = result;
        }

        public ValidationResult result() {
            return result;
        }
    }
}
