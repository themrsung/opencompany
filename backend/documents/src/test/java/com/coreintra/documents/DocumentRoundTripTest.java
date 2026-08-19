package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.documents.blob.BlobRef;
import com.coreintra.documents.blob.BlobStore;
import com.coreintra.documents.blob.LocalFileBlobStore;
import com.coreintra.documents.ooxml.ContentControls;
import com.coreintra.documents.ooxml.OoxmlException;
import com.coreintra.documents.ooxml.OoxmlPackage;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.schema.FieldType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The document core, against a real-world DOCX.
 *
 * <p>The fixture was produced by LibreOffice, not hand-written: it carries a
 * theme, a numbering definition, a font table, settings, and document
 * properties — exactly the parts a naive round trip mangles. Content controls
 * were then injected so the fixture also exercises field binding.
 */
class DocumentRoundTripTest {

    private static final String FIXTURE = "/fixtures/leave-request-template.docx";

    private static byte[] fixture() {
        InputStream stream = DocumentRoundTripTest.class.getResourceAsStream(FIXTURE);
        if (stream == null) {
            throw new IllegalStateException("fixture not on the test classpath: " + FIXTURE);
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
            try {
                stream.close();
            } catch (IOException ignored) {
                // Nothing useful to do; the read already succeeded or threw.
            }
        }
    }

    @Nested
    @DisplayName("lossless round trip")
    class RoundTrip {

        /**
         * ACCEPTANCE: opening and saving an untouched docx changes nothing.
         *
         * <p>Asserted part by part rather than on the archive bytes, because zip
         * timestamps and the deflate level are not part of the document. Every
         * part must come out byte-identical — not merely equivalent after
         * normalisation, which is a weaker claim than this implementation makes.
         */
        @Test
        @DisplayName("every part is byte-identical after an untouched open and save")
        void untouchedDocumentIsUnchanged() {
            byte[] original = fixture();
            OoxmlPackage opened = OoxmlPackage.read(original);
            byte[] saved = opened.write();
            OoxmlPackage reopened = OoxmlPackage.read(saved);

            assertThat(opened.isUnmodified())
                    .as("nothing was edited, so nothing should be marked modified")
                    .isTrue();
            assertThat(reopened.partNames())
                    .as("part order must survive, including [Content_Types].xml first")
                    .containsExactlyElementsOf(opened.partNames());

            for (String part : opened.partNames()) {
                assertThat(reopened.part(part))
                        .as("part %s must be byte-identical", part)
                        .isEqualTo(opened.part(part));
            }
        }

        @Test
        @DisplayName("the parts a naive round trip mangles are all present and untouched")
        void ancillaryPartsSurvive() {
            byte[] original = fixture();
            OoxmlPackage opened = OoxmlPackage.read(original);
            OoxmlPackage reopened = OoxmlPackage.read(opened.write());

            // These are exactly the parts an object-model round trip drops or
            // rewrites: they are not the document body, so nothing that only
            // understands paragraphs knows to keep them.
            String[] fragile = {
                    "word/theme/theme1.xml",
                    "word/numbering.xml",
                    "word/fontTable.xml",
                    "word/settings.xml",
                    "word/styles.xml",
                    "docProps/core.xml",
                    "docProps/app.xml",
                    "docProps/custom.xml",
                    "[Content_Types].xml",
            };
            for (String part : fragile) {
                assertThat(reopened.hasPart(part)).as("%s must survive", part).isTrue();
                assertThat(reopened.part(part))
                        .as("%s must be untouched", part)
                        .isEqualTo(opened.part(part));
            }
        }

        @Test
        @DisplayName("writing twice produces identical bytes, so hashes are stable")
        void writeIsDeterministic() {
            // The approval trail hashes document bytes. If saving the same
            // document twice produced different archives, a re-render could
            // never be shown to match the archived one.
            OoxmlPackage opened = OoxmlPackage.read(fixture());
            assertThat(opened.write()).isEqualTo(opened.write());
        }

        @Test
        @DisplayName("editing one field rewrites one part and leaves the rest alone")
        void editingIsSurgical() {
            OoxmlPackage document = OoxmlPackage.read(fixture());
            byte[] originalTheme = document.part("word/theme/theme1.xml");
            byte[] originalStyles = document.part("word/styles.xml");

            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("applicantName", "이서연");
            document.replacePart(OoxmlPackage.DOCUMENT_PART,
                    ContentControls.write(document.documentPart(), values));

            assertThat(document.modifiedParts())
                    .containsExactly(OoxmlPackage.DOCUMENT_PART);
            assertThat(document.part("word/theme/theme1.xml")).isEqualTo(originalTheme);
            assertThat(document.part("word/styles.xml")).isEqualTo(originalStyles);
        }
    }

    @Nested
    @DisplayName("content controls")
    class Controls {

        @Test
        @DisplayName("every tagged control is found, with its value")
        void readsControls() {
            Map<String, String> values =
                    ContentControls.readValues(OoxmlPackage.read(fixture()).documentPart());

            assertThat(values).containsEntry("applicantName", "김민준");
            assertThat(values).containsEntry("department", "회계팀");
            assertThat(values).containsEntry("rank", "과장");
            assertThat(values).containsEntry("leaveDays", "1.5");
            assertThat(values).containsKey("approvalBlock");
        }

        @Test
        @DisplayName("a written value reads back, including Korean text")
        void writesAndReadsBack() {
            OoxmlPackage document = OoxmlPackage.read(fixture());
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("applicantName", "박지훈");
            values.put("reason", "가족 행사 참석을 위한 연차 사용입니다.");

            byte[] edited = ContentControls.write(document.documentPart(), values);
            Map<String, String> readBack = ContentControls.readValues(edited);

            assertThat(readBack).containsEntry("applicantName", "박지훈");
            assertThat(readBack).containsEntry("reason", "가족 행사 참석을 위한 연차 사용입니다.");
            assertThat(readBack)
                    .as("untouched controls keep their values")
                    .containsEntry("department", "회계팀");
        }

        @Test
        @DisplayName("leading and trailing spaces survive")
        void preservesSignificantWhitespace() {
            // Without xml:space="preserve" Word silently trims these, which
            // corrupts any field a user padded deliberately.
            OoxmlPackage document = OoxmlPackage.read(fixture());
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("reason", "  앞뒤 공백  ");

            byte[] edited = ContentControls.write(document.documentPart(), values);
            assertThat(ContentControls.readValues(edited).get("reason")).isEqualTo("  앞뒤 공백  ");
        }

        @Test
        @DisplayName("writing to a tag that has no control fails loudly, naming it")
        void unknownTagIsRefused() {
            // A silent no-op here means a document that looks filled in and is not.
            OoxmlPackage document = OoxmlPackage.read(fixture());
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("noSuchField", "x");

            assertThatThrownBy(() -> ContentControls.write(document.documentPart(), values))
                    .isInstanceOf(OoxmlException.class)
                    .hasMessageContaining("noSuchField")
                    .hasMessageContaining("diverged");
        }

        @Test
        @DisplayName("XXE is not reachable through an uploaded document")
        void externalEntitiesAreRefused() {
            String hostile = "<?xml version=\"1.0\"?>"
                    + "<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                    + "<w:document xmlns:w=\"" + ContentControls.W_NS + "\"><w:body>"
                    + "<w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body></w:document>";

            assertThatThrownBy(() -> ContentControls.read(
                    hostile.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(OoxmlException.class);
        }
    }

    @Nested
    @DisplayName("schema cross-validation")
    class SchemaValidation {

        private DocumentFieldSchema schemaWith(String... tags) {
            List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
            for (String tag : tags) {
                fields.add(FieldDefinition.of(tag, FieldType.TEXT, tag, tag));
            }
            return new DocumentFieldSchema(fields);
        }

        @Test
        @DisplayName("a manifest matching the document validates, and finds the 결재란")
        void matchingSchemaIsValid() {
            DocumentFieldSchema schema = schemaWith("applicantName", "department", "rank",
                    "leaveStartDate", "leaveDays", "reason");

            DocumentFieldSchema.ValidationResult result =
                    schema.validateAgainst(OoxmlPackage.read(fixture()).documentPart());

            assertThat(result.isValid()).as(result.describe()).isTrue();
            assertThat(result.hasApprovalBlock())
                    .as("the 결재란 is a rendered block, expected in the document and absent "
                            + "from the manifest")
                    .isTrue();
        }

        @Test
        @DisplayName("a declared field with no control is named, with the consequence spelled out")
        void declaredButAbsentIsCaught() {
            DocumentFieldSchema schema = schemaWith("applicantName", "department", "rank",
                    "leaveStartDate", "leaveDays", "reason", "phantomField");

            DocumentFieldSchema.ValidationResult result =
                    schema.validateAgainst(OoxmlPackage.read(fixture()).documentPart());

            assertThat(result.isValid()).isFalse();
            assertThat(result.declaredButNotInDocument()).containsExactly("phantomField");
            assertThat(result.describe()).contains("saves into nothing");
            assertThatThrownBy(result::orThrow)
                    .isInstanceOf(DocumentFieldSchema.SchemaMismatchException.class);
        }

        @Test
        @DisplayName("a control with no manifest entry is named too")
        void presentButUndeclaredIsCaught() {
            DocumentFieldSchema schema = schemaWith("applicantName");

            DocumentFieldSchema.ValidationResult result =
                    schema.validateAgainst(OoxmlPackage.read(fixture()).documentPart());

            assertThat(result.isValid()).isFalse();
            assertThat(result.inDocumentButNotDeclared())
                    .contains("department", "rank", "reason")
                    .doesNotContain("approvalBlock");
            assertThat(result.describe()).contains("will print blank");
        }

        @Test
        @DisplayName("both kinds of mismatch are reported at once, not one at a time")
        void reportsEverythingTogether() {
            // A template author fixing five mismatches should not have to save
            // five times to discover them.
            Set<String> inDocument = new LinkedHashSet<String>();
            inDocument.add("a");
            inDocument.add("stray1");
            inDocument.add("stray2");

            DocumentFieldSchema.ValidationResult result =
                    schemaWith("a", "missing1", "missing2").validateAgainstTags(inDocument);

            assertThat(result.declaredButNotInDocument()).containsExactly("missing1", "missing2");
            assertThat(result.inDocumentButNotDeclared()).containsExactly("stray1", "stray2");
        }

        @Test
        @DisplayName("a duplicate tag in the manifest is refused at construction")
        void duplicateTagRefused() {
            List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
            fields.add(FieldDefinition.of("dup", FieldType.TEXT, "가", "A"));
            fields.add(FieldDefinition.of("dup", FieldType.MONEY, "나", "B"));

            assertThatThrownBy(() -> new DocumentFieldSchema(fields))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be unique");
        }

        @Test
        @DisplayName("a field without a Korean label is refused")
        void koreanLabelRequired() {
            assertThatThrownBy(() ->
                    new FieldDefinition("x", FieldType.TEXT, null, "English only", false, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Korean is the default locale");
        }
    }

    @Nested
    @DisplayName("blob store")
    class Blobs {

        @Test
        @DisplayName("content addressing deduplicates and round-trips")
        void storesAndRetrieves(@TempDir java.nio.file.Path tempDir) {
            BlobStore store = new LocalFileBlobStore(tempDir.toString());
            byte[] document = fixture();

            BlobRef first = store.put(document, "application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document", "휴가신청서.docx");
            BlobRef second = store.put(document, "application/octet-stream", "copy.docx");

            assertThat(second.sha256())
                    .as("identical bytes must yield one key and one copy")
                    .isEqualTo(first.sha256());
            assertThat(store.get(first.sha256())).isEqualTo(document);
            assertThat(store.exists(first.sha256())).isTrue();
        }

        @Test
        @DisplayName("a hash containing a path is refused, not resolved")
        void pathTraversalRefused(@TempDir java.nio.file.Path tempDir) {
            BlobStore store = new LocalFileBlobStore(tempDir.toString());
            assertThatThrownBy(() -> store.get("../../../../etc/passwd"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.get("NOTAHASH"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the stored hash is the approval trail's hash")
        void hashIsTheTrailHash(@TempDir java.nio.file.Path tempDir) {
            BlobStore store = new LocalFileBlobStore(tempDir.toString());
            byte[] document = fixture();
            assertThat(store.put(document, null, null).sha256())
                    .isEqualTo(LocalFileBlobStore.sha256Hex(document));
        }
    }

    @Nested
    @DisplayName("rejecting what is not a docx")
    class Rejections {

        @Test
        @DisplayName("a non-package file is refused with a useful message")
        void notAPackage() {
            assertThatThrownBy(() -> OoxmlPackage.read("not a zip".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(OoxmlException.class);
            assertThatThrownBy(() -> OoxmlPackage.read(new byte[0]))
                    .isInstanceOf(OoxmlException.class)
                    .hasMessageContaining("empty");
        }
    }
}
