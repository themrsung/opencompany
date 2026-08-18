package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.DocumentTemplateEntity;
import com.coreintra.documents.entity.DocumentTemplateVersionEntity;
import com.coreintra.documents.entity.DocumentVersionEntity;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.schema.FieldType;
import com.coreintra.documents.service.BlobService;
import com.coreintra.documents.service.DocumentService;
import com.coreintra.documents.service.FieldSchemaCodec;
import com.coreintra.documents.service.FieldValueExtractor;
import com.coreintra.documents.service.FieldValueFormatException;
import com.coreintra.documents.service.TemplateBody;
import com.coreintra.documents.service.TemplateService;
import com.coreintra.documents.support.Fakes;
import com.coreintra.documents.support.Fixture;
import com.coreintra.documents.support.InMemoryBlobStore;

/**
 * Persisting templates and documents, and the checks that happen on the way in.
 *
 * <p>Unit-level: the repositories are maps, because the documents module cannot reach the
 * app module's {@code DatabaseTestSupport}. What these prove is that the service decides
 * correctly. That the schema also refuses a bad row is the migration's job and is checked
 * where the app boots against real PostgreSQL.
 */
class DocumentPersistenceTest {

    private static final String COMPANY = "company-1";
    private static final String ACCOUNT = "account-1";
    private static final BusinessInstant NOW = BusinessInstant.of(LocalDate.of(2026, 8, 18), 9, 0, 0);

    private Fakes.Blobs blobRows;
    private Fakes.Templates templateRows;
    private Fakes.TemplateVersions versionRows;
    private Fakes.TemplateBodies bodyRows;
    private Fakes.Documents documentRows;
    private Fakes.DocumentVersions documentVersionRows;
    private Fakes.FieldValues fieldValueRows;
    private TemplateService templates;
    private DocumentService documents;

    @BeforeEach
    void setUp() {
        blobRows = new Fakes.Blobs();
        templateRows = new Fakes.Templates();
        versionRows = new Fakes.TemplateVersions();
        bodyRows = new Fakes.TemplateBodies();
        documentRows = new Fakes.Documents();
        documentVersionRows = new Fakes.DocumentVersions();
        fieldValueRows = new Fakes.FieldValues();
        BlobService blobs = new BlobService(new InMemoryBlobStore(), blobRows);
        templates = new TemplateService(templateRows, versionRows, bodyRows, blobs);
        documents = new DocumentService(documentRows, documentVersionRows, fieldValueRows,
                templates, blobs);
    }

    private DocumentTemplateEntity publishedTemplate(DocumentFieldSchema schema) {
        DocumentTemplateEntity template = templates.create(COMPANY, "LEAVE", "휴가신청서",
                "휴가신청서", "Leave request", ACCOUNT);
        templates.publishVersion(template.id(), schema, bodies(), ACCOUNT, NOW);
        return template;
    }

    private static List<TemplateBody> bodies() {
        List<TemplateBody> bodies = new ArrayList<TemplateBody>();
        bodies.add(new TemplateBody("ko", DocumentFormat.DOCX, Fixture.leaveRequestDocx(),
                "leave-ko.docx"));
        return bodies;
    }

    @Nested
    @DisplayName("the manifest and the document are cross-validated on save")
    class CrossValidation {

        /** ACCEPTANCE: a mismatch is a save-time error naming the exact field. */
        @Test
        @DisplayName("a manifest field with no control names that field and refuses the publish")
        void declaredButAbsentFieldIsNamed() {
            DocumentTemplateEntity template = templates.create(COMPANY, "LEAVE", "휴가신청서",
                    "휴가신청서", "Leave request", ACCOUNT);

            assertThatThrownBy(() -> templates.publishVersion(template.id(),
                    Fixture.schemaWithExtraField("annualLeaveBalance"), bodies(), ACCOUNT, NOW))
                    .isInstanceOf(DocumentFieldSchema.SchemaMismatchException.class)
                    .hasMessageContaining("annualLeaveBalance");

            assertThat(versionRows.count())
                    .describedAs("nothing is written when the two artefacts disagree")
                    .isZero();
        }

        @Test
        @DisplayName("a control with no manifest entry names that field and refuses the publish")
        void controlWithNoManifestEntryIsNamed() {
            DocumentTemplateEntity template = templates.create(COMPANY, "LEAVE", "휴가신청서",
                    "휴가신청서", "Leave request", ACCOUNT);

            assertThatThrownBy(() -> templates.publishVersion(template.id(),
                    Fixture.schemaMissingField(Fixture.REASON), bodies(), ACCOUNT, NOW))
                    .isInstanceOf(DocumentFieldSchema.SchemaMismatchException.class)
                    .hasMessageContaining(Fixture.REASON);
        }

        @Test
        @DisplayName("the 결재란 is not treated as an undeclared field")
        void approvalBlockIsNotAField() {
            DocumentTemplateEntity template = publishedTemplate(Fixture.matchingSchema());

            DocumentTemplateVersionEntity version =
                    versionRows.findFirstByTemplateIdOrderByVersionNoDesc(template.id()).get();
            assertThat(version.hasApprovalBlock())
                    .describedAs("detected at publish, so the approval module never opens the docx")
                    .isTrue();
        }

        @Test
        @DisplayName("a version published without a DOCX body is refused")
        void everyVersionNeedsACanonicalBody() {
            DocumentTemplateEntity template = templates.create(COMPANY, "LEAVE", "휴가신청서",
                    "휴가신청서", "Leave request", ACCOUNT);
            List<TemplateBody> mdvOnly = new ArrayList<TemplateBody>();
            mdvOnly.add(new TemplateBody("ko", DocumentFormat.MDV, "# 휴가신청서".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8), "leave.mdv"));

            assertThatThrownBy(() -> templates.publishVersion(template.id(),
                    Fixture.matchingSchema(), mdvOnly, ACCOUNT, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DOCX");
        }
    }

    @Nested
    @DisplayName("versions supersede rather than overwrite")
    class Versioning {

        @Test
        @DisplayName("a document created from a template is pinned to that template version")
        void creationPinsTheTemplateVersion() {
            DocumentTemplateEntity template = publishedTemplate(Fixture.matchingSchema());

            DocumentEntity document = documents.createFromTemplate(COMPANY, template.id(), 1, "ko",
                    "김민준 휴가신청서", ACCOUNT, NOW, "KRW");

            assertThat(document.templateId()).isEqualTo(template.id());
            assertThat(document.templateVersionNo()).isEqualTo(1);
            assertThat(document.currentVersionNo()).isEqualTo(1);
        }

        @Test
        @DisplayName("a later template version does not move a document already drafted")
        void thePinSurvivesATemplateEdit() {
            DocumentTemplateEntity template = publishedTemplate(Fixture.matchingSchema());
            DocumentEntity document = documents.createFromTemplate(COMPANY, template.id(), 1, "ko",
                    "김민준 휴가신청서", ACCOUNT, NOW, "KRW");

            templates.publishVersion(template.id(), Fixture.matchingSchema(), bodies(), ACCOUNT, NOW);

            assertThat(templateRows.findById(template.id()).get().currentVersionNo()).isEqualTo(2);
            assertThat(documentRows.findById(document.id()).get().templateVersionNo())
                    .describedAs("a document approved against v1 renders against v1 forever")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("saving names the version it supersedes and leaves the earlier bytes alone")
        void savingSupersedes() {
            DocumentTemplateEntity template = publishedTemplate(Fixture.matchingSchema());
            DocumentEntity document = documents.createFromTemplate(COMPANY, template.id(), 1, "ko",
                    "김민준 휴가신청서", ACCOUNT, NOW, "KRW");
            byte[] firstBytes = documents.contentOf(document.id(), 1);

            documents.saveVersion(document.id(), Fixture.leaveRequestDocx(), DocumentFormat.DOCX,
                    ACCOUNT, NOW, "KRW");

            DocumentVersionEntity second = documents.version(document.id(), 2).get();
            assertThat(second.supersedesVersionNo()).isEqualTo(1);
            assertThat(documents.contentOf(document.id(), 1))
                    .describedAs("version 1 is what was approved; it does not change")
                    .isEqualTo(firstBytes);
            assertThat(documentRows.findById(document.id()).get().currentVersionNo()).isEqualTo(2);
        }

        @Test
        @DisplayName("a retired document takes no further versions")
        void retiredDocumentsAreClosed() {
            DocumentTemplateEntity template = publishedTemplate(Fixture.matchingSchema());
            DocumentEntity document = documents.createFromTemplate(COMPANY, template.id(), 1, "ko",
                    "김민준 휴가신청서", ACCOUNT, NOW, "KRW");
            documents.retire(document.id(), java.time.OffsetDateTime.now());

            assertThatThrownBy(() -> documents.saveVersion(document.id(),
                    Fixture.leaveRequestDocx(), DocumentFormat.DOCX, ACCOUNT, NOW, "KRW"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("retired");
        }
    }

    @Nested
    @DisplayName("field values are extracted into columns of their declared type")
    class Extraction {

        @Test
        @DisplayName("a date is a date and a number is a number, not two strings")
        void valuesLandInTypedColumns() {
            DocumentTemplateEntity template = publishedTemplate(Fixture.matchingSchema());
            DocumentEntity document = documents.createFromTemplate(COMPANY, template.id(), 1, "ko",
                    "김민준 휴가신청서", ACCOUNT, NOW, "KRW");

            Map<String, DocumentFieldValueEntity> byField =
                    new LinkedHashMap<String, DocumentFieldValueEntity>();
            for (DocumentFieldValueEntity row : documents.fieldValuesOf(document.id(), 1)) {
                byField.put(row.fieldId(), row);
            }

            assertThat(byField.get(Fixture.APPLICANT).valueText()).isEqualTo("김민준");
            assertThat(byField.get(Fixture.START_DATE).valueDate())
                    .isEqualTo(LocalDate.of(2026, 8, 30));
            assertThat(byField.get(Fixture.DAYS).valueNumber())
                    .isEqualByComparingTo(new BigDecimal("1.5"));
            assertThat(byField.get(Fixture.DAYS).valueText())
                    .describedAs("a number does not also land in the text column")
                    .isNull();
        }

        @Test
        @DisplayName("money keeps its exact decimal and carries a currency")
        void moneyIsExactAndCarriesItsCurrency() {
            DocumentFieldSchema schema = new DocumentFieldSchema(java.util.Collections.singletonList(
                    FieldDefinition.required("amount", FieldType.MONEY, "금액", "Amount")));
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("amount", "KRW 1,400,000.25");

            List<DocumentFieldValueEntity> rows =
                    FieldValueExtractor.extract("doc", 1, schema, values, "USD");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).valueAmount()).isEqualByComparingTo(new BigDecimal("1400000.25"));
            assertThat(rows.get(0).valueAmount().toPlainString())
                    .describedAs("not rounded on write, and never through a double")
                    .isEqualTo("1400000.25");
            assertThat(rows.get(0).valueCurrencyCode()).isEqualTo("KRW");
        }

        @Test
        @DisplayName("a bare amount takes the company's currency, not a hardcoded one")
        void bareAmountsUseTheCompanyCurrency() {
            DocumentFieldSchema schema = new DocumentFieldSchema(java.util.Collections.singletonList(
                    FieldDefinition.required("amount", FieldType.MONEY, "금액", "Amount")));
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("amount", "980.00");

            List<DocumentFieldValueEntity> rows =
                    FieldValueExtractor.extract("doc", 1, schema, values, "JPY");

            assertThat(rows.get(0).valueCurrencyCode()).isEqualTo("JPY");
        }

        @Test
        @DisplayName("a business instant keeps its 26:01 offset rather than becoming a wall clock")
        void businessInstantsSurviveExtraction() {
            DocumentFieldSchema schema = new DocumentFieldSchema(java.util.Collections.singletonList(
                    FieldDefinition.required("shiftEnd", FieldType.BUSINESS_INSTANT, "종료", "End")));
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("shiftEnd", "2026-08-30T26:01:00.000");

            List<DocumentFieldValueEntity> rows =
                    FieldValueExtractor.extract("doc", 1, schema, values, "KRW");

            BusinessInstant extracted = rows.get(0).valueInstant();
            assertThat(extracted.businessDate()).isEqualTo(LocalDate.of(2026, 8, 30));
            assertThat(extracted.offsetSeconds()).isEqualTo(26 * 3600 + 60);
        }

        @Test
        @DisplayName("text that does not fit the declared type names the field and shows the text")
        void unreadableValuesNameTheField() {
            DocumentFieldSchema schema = new DocumentFieldSchema(java.util.Collections.singletonList(
                    FieldDefinition.required("leaveDays", FieldType.NUMBER, "일수", "Days")));
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("leaveDays", "하루 반");

            assertThatThrownBy(() -> FieldValueExtractor.extract("doc", 1, schema, values, "KRW"))
                    .isInstanceOf(FieldValueFormatException.class)
                    .hasMessageContaining("leaveDays")
                    .hasMessageContaining("하루 반");
        }

        @Test
        @DisplayName("an empty optional field writes no row rather than a row of NULLs")
        void blankFieldsAreAbsentNotEmpty() {
            DocumentFieldSchema schema = Fixture.matchingSchema();
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put(Fixture.APPLICANT, "김민준");
            values.put(Fixture.REASON, "   ");

            List<DocumentFieldValueEntity> rows =
                    FieldValueExtractor.extract("doc", 1, schema, values, "KRW");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).fieldId()).isEqualTo(Fixture.APPLICANT);
        }

        @Test
        @DisplayName("required-but-empty is a submission question, not a save-time one")
        void requiredFieldsAreCheckedSeparately() {
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put(Fixture.APPLICANT, "김민준");

            assertThat(FieldValueExtractor.missingRequired(Fixture.matchingSchema(), values))
                    .containsExactly(Fixture.DEPARTMENT, Fixture.START_DATE, Fixture.DAYS);
        }
    }

    @Nested
    @DisplayName("the stored manifest round-trips")
    class SchemaStorage {

        @Test
        @DisplayName("labels containing tabs and newlines survive encoding")
        void awkwardLabelsSurvive() {
            DocumentFieldSchema original = new DocumentFieldSchema(
                    java.util.Collections.singletonList(new FieldDefinition("reason",
                            FieldType.MULTILINE_TEXT, "사\t유", "Rea\nson", true, "도움말", null)));

            DocumentFieldSchema decoded = FieldSchemaCodec.decode(FieldSchemaCodec.encode(original));

            assertThat(decoded.fields()).hasSize(1);
            assertThat(decoded.field("reason").labelKo()).isEqualTo("사\t유");
            assertThat(decoded.field("reason").labelEn()).isEqualTo("Rea\nson");
            assertThat(decoded.field("reason").isRequired()).isTrue();
            assertThat(decoded.field("reason").helpEn()).isNull();
        }

        @Test
        @DisplayName("the manifest a document is validated against is the pinned version's")
        void schemaComesFromThePinnedVersion() {
            DocumentTemplateEntity template = publishedTemplate(Fixture.matchingSchema());

            DocumentFieldSchema stored = templates.schemaOf(template.id(), 1);

            assertThat(stored.tags()).containsExactlyInAnyOrder(Fixture.APPLICANT,
                    Fixture.DEPARTMENT, Fixture.RANK, Fixture.START_DATE, Fixture.DAYS,
                    Fixture.REASON);
        }
    }
}
