package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.documents.internal.FidelityMatrix;
import com.coreintra.documents.internal.FormatCapabilities;
import com.coreintra.documents.internal.FormatRegistry;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.internal.adapter.DocxAdapter;
import com.coreintra.documents.internal.adapter.HwpLegacyAdapter;
import com.coreintra.documents.internal.adapter.HwpxAdapter;
import com.coreintra.documents.internal.adapter.MdvAdapter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The fidelity matrix, and the export warnings derived from it.
 *
 * <p>Running this test <b>regenerates</b> {@code docs/documents/fidelity.md}
 * from the adapters' declared capabilities. That is deliberate: a
 * hand-maintained fidelity table drifts from the code silently, and the person
 * it misleads is the user deciding whether an export is safe to send to a
 * client.
 */
class FidelityMatrixTest {

    private FormatRegistry registry() {
        return new FormatRegistry()
                .register(new DocxAdapter())
                .register(new HwpxAdapter())
                .register(new HwpLegacyAdapter())
                .register(new MdvAdapter());
    }

    @Nested
    @DisplayName("honest capability declarations")
    class Honesty {

        @Test
        @DisplayName("an undeclared feature counts as dropped, never as supported")
        void undeclaredMeansDropped() {
            // Defaulting to FULL would let an adapter claim fidelity by omission,
            // which is exactly the drift this design prevents.
            FormatCapabilities sparse = FormatCapabilities.builder("x", "X")
                    .full(FormatCapabilities.Feature.HEADINGS)
                    .build();

            assertThat(sparse.support(FormatCapabilities.Feature.HEADINGS))
                    .isEqualTo(FormatCapabilities.Support.FULL);
            assertThat(sparse.support(FormatCapabilities.Feature.TABLES))
                    .isEqualTo(FormatCapabilities.Support.DROPPED);
        }

        @Test
        @DisplayName("complex-script support is not claimed where it does not exist")
        void complexScriptNotOverclaimed() {
            // mdv's conformance report substantiates Level 2, and complex-script
            // shaping is a Level 3 feature (ADR 0008). HWPX likewise does not get
            // an Arabic/Indic claim it cannot honour.
            assertThat(new HwpxAdapter().capabilities()
                    .support(FormatCapabilities.Feature.COMPLEX_SCRIPT))
                    .isEqualTo(FormatCapabilities.Support.DROPPED);
        }

        @Test
        @DisplayName("legacy .hwp is declared read-only and refuses to write")
        void legacyHwpIsReadOnly() {
            HwpLegacyAdapter adapter = new HwpLegacyAdapter();
            assertThat(adapter.capabilities().canRead()).isTrue();
            assertThat(adapter.capabilities().canWrite()).isFalse();

            InternalDoc empty = new InternalDoc(
                    new ArrayList<InternalDoc.Block>(), null, "hwp", null);
            assertThatThrownBy(() -> adapter.write(empty))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("HWPX");
        }
    }

    @Nested
    @DisplayName("export-time warnings")
    class Warnings {

        private InternalDoc documentWith(InternalDoc.Block... blocks) {
            List<InternalDoc.Block> list = new ArrayList<InternalDoc.Block>();
            for (InternalDoc.Block block : blocks) {
                list.add(block);
            }
            return new InternalDoc(list, null, "docx", null);
        }

        @Test
        @DisplayName("warnings name the feature and what happens to it, not a generic disclaimer")
        void warningsAreConcrete() {
            InternalDoc document = documentWith(
                    InternalDoc.Paragraph.heading("휴가신청서", 1),
                    new InternalDoc.FieldBlock("applicantName", "김민준"));

            List<String> warnings = new HwpxAdapter().capabilities().warningsFor(document);

            assertThat(warnings)
                    .as("a generic 'some formatting may be lost' teaches users to ignore it")
                    .anyMatch(w -> w.contains("typed fields") && w.contains("degrades"));
        }

        @Test
        @DisplayName("only features the document actually uses are warned about")
        void warningsAreRelevant() {
            // Warning about tables in a document with no tables is noise, and
            // noise is how a real warning gets ignored.
            InternalDoc plain = documentWith(InternalDoc.Paragraph.of("본문입니다."));
            assertThat(new HwpxAdapter().capabilities().warningsFor(plain))
                    .noneMatch(w -> w.contains("tables"));
        }

        @Test
        @DisplayName("a read-only format says so instead of listing feature losses")
        void readOnlyFormatSaysSo() {
            List<String> warnings = new HwpLegacyAdapter().capabilities()
                    .warningsFor(documentWith(InternalDoc.Paragraph.of("x")));
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).contains("read-only");
        }

        @Test
        @DisplayName("opaque content is reported by description, not silently dropped")
        void opaqueContentIsReported() {
            InternalDoc withChart = documentWith(
                    InternalDoc.Paragraph.of("매출 추이"),
                    new InternalDoc.OpaqueBlock("docx", "chart", new byte[] {1, 2, 3}));

            List<String> warnings = new HwpxAdapter().capabilities().warningsFor(withChart);
            assertThat(warnings).anyMatch(w -> w.contains("chart"));
            assertThat(withChart.hasOpaqueContent()).isTrue();
        }
    }

    @Nested
    @DisplayName("format detection")
    class Detection {

        @Test
        @DisplayName("a DOCX is recognised by its parts, not only its name")
        void docxDetectedByContent() throws IOException {
            byte[] docx = Files.readAllBytes(new File(
                    "src/test/resources/fixtures/leave-request-template.docx").toPath());
            assertThat(registry().detect(docx, "no-extension").capabilities().formatId())
                    .isEqualTo("docx");
        }

        @Test
        @DisplayName("HWPX and DOCX are both ZIPs, so the extension breaks the tie")
        void zipAmbiguityResolved() throws IOException {
            byte[] docx = Files.readAllBytes(new File(
                    "src/test/resources/fixtures/leave-request-template.docx").toPath());
            assertThat(new HwpxAdapter().looksLikeThisFormat(docx, "x.hwpx")).isTrue();
            assertThat(new DocxAdapter().looksLikeThisFormat(docx, "x.docx")).isTrue();
        }

        @Test
        @DisplayName("an unrecognisable upload is refused with the supported list")
        void unknownIsRefused() {
            assertThatThrownBy(() -> registry().detect(
                    "hello".getBytes(StandardCharsets.UTF_8), "mystery.bin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Supported:");
        }
    }

    @Nested
    @DisplayName("the matrix as data, for the export dialog")
    class AsData {

        @Test
        @DisplayName("answers about a pair, because that is the question a user has")
        void answersAboutAPair() {
            FidelityMatrix.Pair pair = registry().fidelityMatrix().pair("hwpx", "docx");

            assertThat(pair.isSupported()).isTrue();
            assertThat(pair.rows()).hasSize(FormatCapabilities.Feature.values().length);
            // Both ends are consulted: HWPX degrades merged cells, DOCX does not,
            // and the pair has to report the worse of the two.
            assertThat(pair.support(FormatCapabilities.Feature.MERGED_CELLS))
                    .isEqualTo(FormatCapabilities.Support.DEGRADED);
        }

        @Test
        @DisplayName("carries both languages, because Korean is the default locale")
        void carriesBothLanguages() {
            FidelityMatrix.Pair pair = registry().fidelityMatrix().pair("docx", "hwpx");
            assertThat(pair.concerns()).isNotEmpty();
            for (FidelityMatrix.Row row : pair.concerns()) {
                assertThat(row.describeKo()).isNotBlank();
                assertThat(row.describeEn()).isNotBlank();
                assertThat(row.describeKo()).isNotEqualTo(row.describeEn());
            }
        }

        @Test
        @DisplayName("says mdv has no native DOCX or HWP writer, and what charts become")
        void mdvRowIsHonestAboutCharts() {
            FidelityMatrix matrix = registry().fidelityMatrix();

            assertThat(new MdvAdapter().capabilities().notes())
                    .contains("THERE IS NO NATIVE DOCX OR HWP WRITER")
                    .contains("mdv → InternalDoc");
            assertThat(matrix.pair("mdv", "docx").note()).contains("SVG");
            assertThat(matrix.pair("mdv", "hwpx").note()).contains("PNG");
        }

        @Test
        @DisplayName("does what the mdv row says: an unrendered chart is a labelled placeholder")
        void anUnrenderedChartIsLabelledNotDropped() {
            // The matrix promises the worker renders charts and that one reaching
            // the writer unrendered is labelled rather than lost. A note the code
            // does not honour is the exact drift this whole design exists to
            // prevent, so it is asserted rather than trusted.
            byte[] source = ("# 이사회안건\n\n```mdv pie\ntitle: 의결 집계\n---\n구분,표수\n"
                    + "찬성,4\n```\n").getBytes(StandardCharsets.UTF_8);
            InternalDoc document = new MdvAdapter().read(source);

            String documentPart = new String(
                    com.coreintra.documents.ooxml.OoxmlPackage
                            .read(new DocxAdapter().write(document)).documentPart(),
                    StandardCharsets.UTF_8);

            assertThat(documentPart).contains("이사회안건");
            assertThat(documentPart).contains("변환할 수 없는 내용");
            assertThat(documentPart).contains("pie chart");
        }

        @Test
        @DisplayName("marks a read-only target unsupported rather than listing losses")
        void readOnlyTargetIsUnsupported() {
            FidelityMatrix.Pair pair = registry().fidelityMatrix().pair("docx", "hwp");
            assertThat(pair.isSupported()).isFalse();
            assertThat(pair.unsupportedReason()).contains("read-only");
        }

        @Test
        @DisplayName("refuses the PDF profiles the pinned mdv build cannot actually honour")
        void unhonourableProfilesAreRefused() {
            // Found by reading the pinned build: `pdf-a-3b` is accepted and does
            // nothing, and `pdf-ua-1` stamps a conformance claim into a file with
            // no embedded fonts. A false archival or accessibility claim is worse
            // than an absent one, so both are refused rather than warned about.
            java.util.Map<String, com.coreintra.documents.internal.RenderTarget> targets =
                    new java.util.LinkedHashMap<String,
                            com.coreintra.documents.internal.RenderTarget>();
            for (com.coreintra.documents.internal.RenderTarget target
                    : com.coreintra.documents.internal.RenderTarget.all()) {
                targets.put(target.id(), target);
            }

            assertThat(targets.get("pdf-a-3b").isOffered()).isFalse();
            assertThat(targets.get("pdf-ua-1").isOffered()).isFalse();
            assertThat(targets.get("pdf-from-mdv").isOffered()).isFalse();
            assertThat(targets.get("pdf-from-mdv").cautionKo()).contains("한글");
            assertThat(targets.get("pdf").isOffered()).isTrue();
            assertThat(targets.get("doc").isOffered()).isTrue();

            for (com.coreintra.documents.internal.RenderTarget target : targets.values()) {
                assertThat(target.cautionEn()).isNotBlank();
                assertThat(target.cautionKo()).isNotBlank();
            }
        }

        @Test
        @DisplayName("serialises to JSON the export dialog can render")
        void serialisesToJson() {
            String json = registry().fidelityMatrix().toJson();
            assertThat(json).startsWith("{\"pairs\":[").endsWith("]}");
            assertThat(json).contains("\"from\":\"mdv\"").contains("\"to\":\"hwpx\"");
            assertThat(json).contains("\"labelKo\":\"결재란\"");
            assertThat(json).contains("\"verdictKo\":");
        }
    }

    @Test
    @DisplayName("regenerates docs/documents/fidelity.md from the adapters")
    void regeneratesFidelityMatrix() throws IOException {
        String markdown = registry().toFidelityMarkdown();

        assertThat(markdown)
                .contains("| Feature |")
                .contains("DOCX (Word)")
                .contains("HWPX (한글)")
                .contains("HWP 5.0 (legacy binary)")
                .contains("mdv (Markdown Visual)")
                .contains("complex-script shaping")
                .contains("Conversions, pair by pair")
                .contains("THERE IS NO NATIVE DOCX OR HWP WRITER");

        File target = resolveRepoFile("docs/documents/fidelity.md");
        Files.write(target.toPath(), markdown.getBytes(StandardCharsets.UTF_8));
        assertThat(target).exists();
    }

    private static File resolveRepoFile(String relative) {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.getParentFile().isDirectory()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("cannot locate " + relative + " above "
                + new File("").getAbsolutePath());
    }
}
