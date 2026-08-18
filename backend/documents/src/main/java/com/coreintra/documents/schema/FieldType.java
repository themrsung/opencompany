package com.coreintra.documents.schema;

/**
 * The types a document field can have.
 *
 * <p>The field schema is a <b>manifest describing the content controls present
 * in the docx</b>, not a parallel document model. Each entry says what type of
 * editor to render and how to validate what is typed; the value itself lives
 * inside the {@code w:sdt} in the document.
 */
public enum FieldType {

    /** Free text, single line. */
    TEXT,

    /** Multi-line prose — a 사유, a set of resolutions. */
    MULTILINE_TEXT,

    /** A number that is not money. Headcount, a page count. */
    NUMBER,

    /**
     * Money. Always {@code BigDecimal}, always a decimal string on the wire,
     * never rounded on write (ADR 0004).
     */
    MONEY,

    /** A calendar date with no time. */
    DATE,

    /**
     * A moment in the 72-hour business day (ADR 0002).
     *
     * <p>Distinct from {@link #DATE} because attendance and approval fields need
     * an offset that can read 27:00 or -02:00, which a date picker cannot express.
     */
    BUSINESS_INSTANT,

    /** A reference to an employee. Renders as a picker, stores the employee id. */
    EMPLOYEE_REF,

    /** A reference to an org unit. */
    ORG_REF,

    /** An attachment — a receipt, a scan. Stores a blob hash. */
    FILE,

    /** A repeating table of typed columns. */
    TABLE;

    /** True when this type's value is stored as an id rather than as literal text. */
    public boolean isReference() {
        return this == EMPLOYEE_REF || this == ORG_REF || this == FILE;
    }

    /** True when the money rules apply — exact decimal strings, no rounding on write. */
    public boolean isMoney() {
        return this == MONEY;
    }
}
