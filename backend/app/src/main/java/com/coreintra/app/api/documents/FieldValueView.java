package com.coreintra.app.api.documents;

import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.schema.FieldDefinition;
import java.math.BigDecimal;

/**
 * One typed field value.
 *
 * <h2>Typed, not a bag of strings</h2>
 *
 * <p>The docx is the truth; these rows are the projection that makes it
 * queryable (§6.1). Flattening them back into {@code {"tag": "1400000"}} on the
 * wire would throw away the only thing the projection is for: a client could no
 * longer tell 1,400,000 원 from the number 1400000 from the text "1400000", and
 * every consumer would re-guess the type from the tag name.
 *
 * <p>Exactly one of the value fields is set, chosen by {@link #getType()}. A
 * blank control writes no row at all, so an absent field id means "left empty"
 * rather than "empty string".
 *
 * <h2>Money and numbers are strings</h2>
 *
 * <p>JSON numbers are IEEE 754 doubles in every browser that will read this, and
 * 1400000.25 survives that but 0.1 + 0.2 does not. Amounts cross the wire as
 * exact decimal strings and are parsed into {@code BigDecimal}, never into a
 * number (ADR 0004). The currency code sits beside the amount rather than being
 * inferred from the company, because a document may carry a foreign invoice.
 */
public class FieldValueView {

    private final String fieldId;
    private final String type;
    private final String labelKo;
    private final String labelEn;
    private final Boolean required;
    private final String text;
    private final String number;
    private final String amount;
    private final String currencyCode;
    private final String date;
    private final String instant;
    private final String refId;
    private final String blobSha256;

    /**
     * @param definition the manifest entry for this field, or null when the
     *        document came from an upload and is pinned to no template — the
     *        value is still typed, but there are no labels to show
     */
    FieldValueView(DocumentFieldValueEntity value, FieldDefinition definition) {
        this.fieldId = value.fieldId();
        this.type = value.fieldType() == null ? null : value.fieldType().name();
        this.labelKo = definition == null ? null : definition.labelKo();
        this.labelEn = definition == null ? null : definition.labelEn();
        this.required = definition == null ? null : Boolean.valueOf(definition.isRequired());
        this.text = value.valueText();
        this.number = plain(value.valueNumber());
        this.amount = plain(value.valueAmount());
        this.currencyCode = value.valueCurrencyCode();
        this.date = value.valueDate() == null ? null : value.valueDate().toString();
        this.instant = value.valueInstant() == null ? null : value.valueInstant().toWireString();
        this.refId = value.valueRefId();
        this.blobSha256 = value.valueBlobSha256();
    }

    /** The content control's {@code w:sdt} tag. The stable handle for the field. */
    public String getFieldId() {
        return fieldId;
    }

    /** TEXT, MULTILINE_TEXT, NUMBER, MONEY, DATE, BUSINESS_INSTANT, EMPLOYEE_REF, ORG_REF, FILE, TABLE. */
    public String getType() {
        return type;
    }

    /** The label to show. Field labels are translatable; field data is not (§6.8). */
    public String getLabelKo() {
        return labelKo;
    }

    public String getLabelEn() {
        return labelEn;
    }

    /** Null when no manifest applies. Required-but-empty is a submission-time question. */
    public Boolean getRequired() {
        return required;
    }

    public String getText() {
        return text;
    }

    /** An exact decimal string, not a JSON number. */
    public String getNumber() {
        return number;
    }

    /** An exact decimal string, unrounded, as it was written (ADR 0004). */
    public String getAmount() {
        return amount;
    }

    /** ISO 4217, beside the amount rather than inferred from the company. */
    public String getCurrencyCode() {
        return currencyCode;
    }

    /** ISO-8601 calendar date, for a field that is a date and has no time. */
    public String getDate() {
        return date;
    }

    /** Business-time wire form, for a field that needs to be able to read 26:30. */
    public String getInstant() {
        return instant;
    }

    /** An employee id or an org unit id, depending on the type. */
    public String getRefId() {
        return refId;
    }

    /** The attachment's content address. Fetch it as a download, not as JSON. */
    public String getBlobSha256() {
        return blobSha256;
    }

    private static String plain(BigDecimal value) {
        // toPlainString rather than toString: the latter emits 1E+7 for a value
        // that arrived as 10000000, and a client comparing strings would then
        // see two different amounts.
        return value == null ? null : value.toPlainString();
    }
}
