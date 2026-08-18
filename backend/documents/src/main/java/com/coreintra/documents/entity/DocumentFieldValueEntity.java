package com.coreintra.documents.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.core.persistence.BusinessInstantEmbeddable;
import com.coreintra.documents.schema.FieldType;

/**
 * One extracted field value, in a column of its declared type.
 *
 * <p>The docx remains the source of truth (brief 6.1): these rows are a projection,
 * rebuilt from the content controls every time a version is written. They exist
 * because "every expense over 5,000,000 last quarter" must not mean unzipping forty
 * thousand packages, and because a report cannot join on a value that only exists
 * inside a ZIP.
 *
 * <p>Typed columns rather than one text column: a MONEY field compared as text sorts
 * 9 above 10, and a DATE field compared as text is only accidentally right. Which
 * column is populated is fixed by the database against {@link #fieldType}, so a bug
 * in the extractor cannot write a money value into the text column and be believed.
 */
@Entity
@Table(name = "document_field_value")
@IdClass(DocumentFieldValueId.class)
public class DocumentFieldValueEntity {

    @Id
    @Column(name = "document_id", length = 36)
    private String documentId;

    @Id
    @Column(name = "version_no")
    private Integer versionNo;

    /** The {@code w:tag} on the content control. The join between manifest and document. */
    @Id
    @Column(name = "field_id", length = 100)
    private String fieldId;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_type", nullable = false, length = 24)
    private FieldType fieldType;

    @Column(name = "value_text")
    private String valueText;

    @Column(name = "value_number")
    private BigDecimal valueNumber;

    /** Money is NUMERIC and unbounded, with its currency beside it (ADR 0004). */
    @Column(name = "value_amount")
    private BigDecimal valueAmount;

    @Column(name = "value_currency_code", length = 12)
    private String valueCurrencyCode;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "businessDate", column = @Column(name = "value_business_date")),
        @AttributeOverride(name = "offsetSeconds", column = @Column(name = "value_offset_seconds")),
        @AttributeOverride(name = "absoluteTs",
                column = @Column(name = "value_absolute_ts", insertable = false, updatable = false))
    })
    private BusinessInstantEmbeddable valueInstant;

    /** EMPLOYEE_REF / ORG_REF. Not a foreign key: see the migration's reasoning. */
    @Column(name = "value_ref_id", length = 36)
    private String valueRefId;

    @Column(name = "value_blob_sha256", length = 64)
    private String valueBlobSha256;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected DocumentFieldValueEntity() {
    }

    private DocumentFieldValueEntity(String documentId, int versionNo, String fieldId, FieldType type) {
        this.documentId = documentId;
        this.versionNo = Integer.valueOf(versionNo);
        this.fieldId = fieldId;
        this.fieldType = type;
        this.createdAt = OffsetDateTime.now();
    }

    /** TEXT, MULTILINE_TEXT, and the encoded rows of a TABLE. */
    public static DocumentFieldValueEntity text(String documentId, int versionNo, String fieldId,
            FieldType type, String value) {
        DocumentFieldValueEntity row = new DocumentFieldValueEntity(documentId, versionNo, fieldId, type);
        row.valueText = value;
        return row;
    }

    /** A number that is not money: headcount, a page count. */
    public static DocumentFieldValueEntity number(String documentId, int versionNo, String fieldId,
            BigDecimal value) {
        DocumentFieldValueEntity row =
                new DocumentFieldValueEntity(documentId, versionNo, fieldId, FieldType.NUMBER);
        row.valueNumber = value;
        return row;
    }

    /** Money arrives as an exact decimal and a currency code, never as a double (ADR 0004). */
    public static DocumentFieldValueEntity money(String documentId, int versionNo, String fieldId,
            BigDecimal amount, String currencyCode) {
        DocumentFieldValueEntity row =
                new DocumentFieldValueEntity(documentId, versionNo, fieldId, FieldType.MONEY);
        row.valueAmount = amount;
        row.valueCurrencyCode = currencyCode;
        return row;
    }

    /** A calendar date with no time: a due date, a date of birth. */
    public static DocumentFieldValueEntity date(String documentId, int versionNo, String fieldId,
            LocalDate value) {
        DocumentFieldValueEntity row =
                new DocumentFieldValueEntity(documentId, versionNo, fieldId, FieldType.DATE);
        row.valueDate = value;
        return row;
    }

    /** 27:00, which a date picker cannot express and a timestamp cannot hold (ADR 0002). */
    public static DocumentFieldValueEntity businessInstant(String documentId, int versionNo,
            String fieldId, BusinessInstant value) {
        DocumentFieldValueEntity row =
                new DocumentFieldValueEntity(documentId, versionNo, fieldId, FieldType.BUSINESS_INSTANT);
        row.valueInstant = BusinessInstantEmbeddable.from(value);
        return row;
    }

    /** EMPLOYEE_REF or ORG_REF: the id of the picked employee or org unit. */
    public static DocumentFieldValueEntity reference(String documentId, int versionNo, String fieldId,
            FieldType type, String refId) {
        DocumentFieldValueEntity row = new DocumentFieldValueEntity(documentId, versionNo, fieldId, type);
        row.valueRefId = refId;
        return row;
    }

    /** FILE: an attachment, addressed like everything else. */
    public static DocumentFieldValueEntity file(String documentId, int versionNo, String fieldId,
            String blobSha256) {
        DocumentFieldValueEntity row =
                new DocumentFieldValueEntity(documentId, versionNo, fieldId, FieldType.FILE);
        row.valueBlobSha256 = blobSha256;
        return row;
    }

    public String documentId() {
        return documentId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public String fieldId() {
        return fieldId;
    }

    public FieldType fieldType() {
        return fieldType;
    }

    public String valueText() {
        return valueText;
    }

    public BigDecimal valueNumber() {
        return valueNumber;
    }

    public BigDecimal valueAmount() {
        return valueAmount;
    }

    public String valueCurrencyCode() {
        return valueCurrencyCode;
    }

    public LocalDate valueDate() {
        return valueDate;
    }

    public BusinessInstant valueInstant() {
        return valueInstant == null ? null : valueInstant.toBusinessInstant();
    }

    public String valueRefId() {
        return valueRefId;
    }

    public String valueBlobSha256() {
        return valueBlobSha256;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
