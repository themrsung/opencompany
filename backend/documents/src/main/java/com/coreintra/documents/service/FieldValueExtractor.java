package com.coreintra.documents.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.ooxml.ContentControls;
import com.coreintra.documents.ooxml.OoxmlPackage;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.schema.FieldType;

/**
 * Turns the text inside content controls into typed rows.
 *
 * <p>The docx is still the truth (brief 6.1); this is the projection that makes it
 * queryable. Every row is rebuilt from the document on every write, so the two cannot
 * drift: there is no path that updates a value here without a new version there.
 *
 * <h2>Blank is not zero</h2>
 *
 * <p>A blank control writes no row at all rather than a row full of NULLs. "The field
 * was left empty" and "the field says nothing" are the same fact, and one of them
 * pretends to be data. Required-but-empty is a submission-time question, not a
 * save-time one - drafts are saved half-filled all day - so it lives in
 * {@link #missingRequired(DocumentFieldSchema, Map)} rather than here.
 */
public final class FieldValueExtractor {

    private FieldValueExtractor() {
    }

    /** Reads the content controls out of a docx and types them against the manifest. */
    public static List<DocumentFieldValueEntity> extractFromDocx(String documentId, int versionNo,
            DocumentFieldSchema schema, byte[] docx, String defaultCurrencyCode) {
        Map<String, String> values = ContentControls.readValues(OoxmlPackage.read(docx).documentPart());
        return extract(documentId, versionNo, schema, values, defaultCurrencyCode);
    }

    /**
     * Types each declared field against the values found in the document.
     *
     * @param defaultCurrencyCode used for a MONEY field whose text carries no code; the
     *        company's base currency, never a hardcoded KRW
     * @throws FieldValueFormatException naming the field whose text does not fit its type
     */
    public static List<DocumentFieldValueEntity> extract(String documentId, int versionNo,
            DocumentFieldSchema schema, Map<String, String> values, String defaultCurrencyCode) {
        List<DocumentFieldValueEntity> rows = new ArrayList<DocumentFieldValueEntity>();
        for (FieldDefinition field : schema.fields()) {
            String raw = values.get(field.tag());
            if (Texts.isBlank(raw)) {
                continue;
            }
            rows.add(row(documentId, versionNo, field, Texts.strip(raw), defaultCurrencyCode));
        }
        return Immutables.copyOf(rows);
    }

    /**
     * The required fields nobody filled in. Asked at submission, when it becomes an answer
     * a person owes rather than a draft they are still writing.
     */
    public static List<String> missingRequired(DocumentFieldSchema schema, Map<String, String> values) {
        List<String> missing = new ArrayList<String>();
        for (FieldDefinition field : schema.fields()) {
            if (field.isRequired() && Texts.isBlank(values.get(field.tag()))) {
                missing.add(field.tag());
            }
        }
        return Immutables.copyOf(missing);
    }

    private static DocumentFieldValueEntity row(String documentId, int versionNo,
            FieldDefinition field, String text, String defaultCurrencyCode) {
        FieldType type = field.type();
        if (type == FieldType.TEXT || type == FieldType.MULTILINE_TEXT || type == FieldType.TABLE) {
            return DocumentFieldValueEntity.text(documentId, versionNo, field.tag(), type, text);
        }
        if (type == FieldType.NUMBER) {
            return DocumentFieldValueEntity.number(documentId, versionNo, field.tag(),
                    decimal(field, text, text));
        }
        if (type == FieldType.MONEY) {
            return money(documentId, versionNo, field, text, defaultCurrencyCode);
        }
        if (type == FieldType.DATE) {
            return DocumentFieldValueEntity.date(documentId, versionNo, field.tag(), date(field, text));
        }
        if (type == FieldType.BUSINESS_INSTANT) {
            return DocumentFieldValueEntity.businessInstant(documentId, versionNo, field.tag(),
                    instant(field, text));
        }
        if (type == FieldType.EMPLOYEE_REF || type == FieldType.ORG_REF) {
            return DocumentFieldValueEntity.reference(documentId, versionNo, field.tag(), type, text);
        }
        if (type == FieldType.FILE) {
            return DocumentFieldValueEntity.file(documentId, versionNo, field.tag(), hash(field, text));
        }
        throw new IllegalStateException(
                "field type " + type + " has no extraction rule; a type was added to FieldType "
                + "without teaching the extractor what its column is");
    }

    /**
     * Money arrives as {@code "KRW 1,400,000.25"} or as a bare amount in the company's
     * currency. Never as a double, and never rounded on the way in (ADR 0004): the
     * document said what it said.
     */
    private static DocumentFieldValueEntity money(String documentId, int versionNo,
            FieldDefinition field, String text, String defaultCurrencyCode) {
        String currency = defaultCurrencyCode;
        String amountText = text;
        int space = text.indexOf(' ');
        if (space > 0) {
            String head = text.substring(0, space);
            if (looksLikeCurrencyCode(head)) {
                currency = head.toUpperCase(Locale.ROOT);
                amountText = Texts.strip(text.substring(space + 1));
            }
        }
        if (Texts.isBlank(currency)) {
            throw new FieldValueFormatException(field.tag(), "MONEY", text,
                    new IllegalArgumentException(
                            "no currency code in the field and no company currency supplied; "
                            + "an amount without a currency is not money"));
        }
        return DocumentFieldValueEntity.money(documentId, versionNo, field.tag(),
                decimal(field, amountText, text), currency);
    }

    private static boolean looksLikeCurrencyCode(String head) {
        if (head.length() != 3) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (!Character.isLetter(head.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Group separators are typography, not value. Everything else must parse exactly. */
    private static BigDecimal decimal(FieldDefinition field, String text, String original) {
        String cleaned = text.replace(",", "").replace(" ", "");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new FieldValueFormatException(field.tag(), field.type().name(), original, e);
        }
    }

    private static LocalDate date(FieldDefinition field, String text) {
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            throw new FieldValueFormatException(field.tag(), "DATE", text, e);
        }
    }

    private static BusinessInstant instant(FieldDefinition field, String text) {
        try {
            return BusinessInstant.parse(text);
        } catch (RuntimeException e) {
            throw new FieldValueFormatException(field.tag(), "BUSINESS_INSTANT", text, e);
        }
    }

    private static String hash(FieldDefinition field, String text) {
        String candidate = text.toLowerCase(Locale.ROOT);
        if (candidate.length() != 64 || !candidate.matches("[0-9a-f]{64}")) {
            throw new FieldValueFormatException(field.tag(), "FILE", text,
                    new IllegalArgumentException(
                            "a FILE field holds the SHA-256 of the attachment, not its name"));
        }
        return candidate;
    }
}
