package com.coreintra.documents.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.schema.FieldType;

/**
 * The 휴가신청서 fixture and the manifest that matches it.
 *
 * <p>A real LibreOffice-produced document with content controls injected, not a
 * hand-written one: it carries a theme, a numbering definition and a font table, so a test
 * that opens it exercises the parts a naive round trip mangles.
 */
public final class Fixture {

    public static final String APPLICANT = "applicantName";
    public static final String DEPARTMENT = "department";
    public static final String RANK = "rank";
    public static final String START_DATE = "leaveStartDate";
    public static final String DAYS = "leaveDays";
    public static final String REASON = "reason";

    private static final String PATH = "/fixtures/leave-request-template.docx";

    private Fixture() {
    }

    public static byte[] leaveRequestDocx() {
        InputStream stream = Fixture.class.getResourceAsStream(PATH);
        if (stream == null) {
            throw new IllegalStateException("fixture not on the test classpath: " + PATH);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the fixture", e);
        } finally {
            close(stream);
        }
    }

    /** The manifest that matches the fixture exactly. */
    public static DocumentFieldSchema matchingSchema() {
        return new DocumentFieldSchema(baseFields());
    }

    /** The matching manifest plus one field the document does not have. */
    public static DocumentFieldSchema schemaWithExtraField(String tag) {
        List<FieldDefinition> fields = baseFields();
        fields.add(FieldDefinition.of(tag, FieldType.TEXT, "없는 항목", "Absent field"));
        return new DocumentFieldSchema(fields);
    }

    /** The matching manifest with one field removed, leaving a control nobody declared. */
    public static DocumentFieldSchema schemaMissingField(String tag) {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        for (FieldDefinition field : baseFields()) {
            if (!field.tag().equals(tag)) {
                fields.add(field);
            }
        }
        return new DocumentFieldSchema(fields);
    }

    private static List<FieldDefinition> baseFields() {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        fields.add(FieldDefinition.required(APPLICANT, FieldType.TEXT, "신청자", "Applicant"));
        fields.add(FieldDefinition.required(DEPARTMENT, FieldType.TEXT, "부서", "Department"));
        fields.add(FieldDefinition.of(RANK, FieldType.TEXT, "직급", "Rank"));
        fields.add(FieldDefinition.required(START_DATE, FieldType.DATE, "시작일", "Start date"));
        fields.add(FieldDefinition.required(DAYS, FieldType.NUMBER, "일수", "Days"));
        fields.add(FieldDefinition.of(REASON, FieldType.MULTILINE_TEXT, "사유", "Reason"));
        return fields;
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The read already succeeded or threw; nothing useful to do here.
        }
    }
}
