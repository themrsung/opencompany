package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.internal.adapter.DocxAdapter;
import com.coreintra.documents.internal.adapter.HwpxAdapter;
import com.coreintra.documents.internal.adapter.hwpx.HwpxPackage;
import com.coreintra.documents.ooxml.ContentControls;
import com.coreintra.documents.ooxml.OoxmlPackage;
import com.coreintra.documents.support.Fixture;
import com.coreintra.documents.support.InMemoryBinaryStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The HWPX write path, and the round trip §13 names as its acceptance test.
 *
 * <p>The interesting question is not whether an HWPX file comes out — it is
 * whether the <em>binding</em> between a field id and its value survives a
 * format that has no content controls, including when the file has been through
 * a program that knows nothing about this system.
 */
class HwpxRoundTripTest {

    /** A real 1×1 PNG, so an image test exercises bytes rather than a placeholder. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGA"
                    + "hKmMIQAAAABJRU5ErkJggg==");

    @Nested
    @DisplayName("A docx → HWPX → docx round trip")
    class DocxRoundTrip {

        @Test
        @DisplayName("preserves every content control and its bound field value")
        void preservesEveryControl() {
            byte[] docx = Fixture.leaveRequestDocx();
            Map<String, String> before =
                    ContentControls.readValues(OoxmlPackage.read(docx).documentPart());
            assertThat(before).isNotEmpty();

            DocxAdapter docxAdapter = new DocxAdapter();
            HwpxAdapter hwpxAdapter = new HwpxAdapter();

            byte[] hwpx = hwpxAdapter.write(docxAdapter.read(docx));
            byte[] roundTripped = docxAdapter.write(hwpxAdapter.read(hwpx));

            Map<String, String> after =
                    ContentControls.readValues(OoxmlPackage.read(roundTripped).documentPart());
            assertThat(after).containsExactlyInAnyOrderEntriesOf(before);
        }

        @Test
        @DisplayName("carries the field ids into the HWPX itself, as 누름틀 fields")
        void fieldsAreNativeInTheHwpx() {
            byte[] hwpx = new HwpxAdapter().write(new DocxAdapter().read(Fixture.leaveRequestDocx()));
            String section = new String(
                    HwpxPackage.entriesOf(hwpx).get("Contents/section0.xml"),
                    java.nio.charset.StandardCharsets.UTF_8);

            // The binding is in the body of the document, not in a private
            // annotation beside it. That is the whole point of the mapping.
            assertThat(section).contains("fieldBegin");
            assertThat(section).contains("CLICK_HERE");
            assertThat(section).contains(Fixture.APPLICANT);
            assertThat(section).contains(Fixture.START_DATE);
        }

        @Test
        @DisplayName("still preserves the bindings after another program strips our sidecar")
        void survivesAForeignProgramSaving() {
            byte[] docx = Fixture.leaveRequestDocx();
            Map<String, String> before =
                    ContentControls.readValues(OoxmlPackage.read(docx).documentPart());

            byte[] hwpx = new HwpxAdapter().write(new DocxAdapter().read(docx));

            // 한글 keeps the parts it understands and discards a package part
            // nothing in the document references. This is that, exactly: the
            // fallback fidelity is gone and only the format's own field
            // mechanism is left to carry the binding.
            byte[] stripped = withoutOurSidecar(hwpx);
            assertThat(entryNames(stripped)).noneMatch(name -> name.contains("coreintra-opaque"));

            InternalDoc reread = new HwpxAdapter().read(stripped);
            assertThat(reread.fieldValues()).containsExactlyInAnyOrderEntriesOf(before);

            // And the regenerated DOCX still carries real content controls,
            // because the pivot model still knows which region was which field.
            byte[] regenerated = new DocxAdapter().write(reread);
            Map<String, String> after =
                    ContentControls.readValues(OoxmlPackage.read(regenerated).documentPart());
            assertThat(after).containsExactlyInAnyOrderEntriesOf(before);
        }

        @Test
        @DisplayName("returns the original package byte for byte when the sidecar is intact")
        void sidecarReturnsTheOriginalExactly() {
            byte[] docx = Fixture.leaveRequestDocx();
            byte[] roundTripped =
                    new DocxAdapter().write(new HwpxAdapter().read(
                            new HwpxAdapter().write(new DocxAdapter().read(docx))));
            assertThat(roundTripped).isEqualTo(docx);
        }
    }

    @Nested
    @DisplayName("The HWPX serialiser")
    class Serialiser {

        @Test
        @DisplayName("round-trips the editing subset: emphasis, headings, lists and tables")
        void roundTripsTheSubset() {
            List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
            blocks.add(InternalDoc.Paragraph.heading("휴가신청서", 1));
            List<InternalDoc.Run> runs = new ArrayList<InternalDoc.Run>();
            runs.add(new InternalDoc.Run("굵게", true, false, false, false, null));
            runs.add(new InternalDoc.Run("기울임", false, true, false, false, null));
            runs.add(new InternalDoc.Run("밑줄", false, false, true, false, null));
            runs.add(new InternalDoc.Run("취소선", false, false, false, true, null));
            blocks.add(new InternalDoc.Paragraph(runs, 0, null, 0));
            blocks.add(new InternalDoc.Paragraph(
                    listOf(InternalDoc.Run.plain("첫째 항목")), 0, "ordered", 0));
            blocks.add(new InternalDoc.Paragraph(
                    listOf(InternalDoc.Run.plain("둘째 항목")), 0, "bullet", 0));
            blocks.add(table());
            blocks.add(new InternalDoc.PageBreak());
            blocks.add(InternalDoc.Paragraph.of("다음 쪽"));

            InternalDoc source = new InternalDoc(blocks, new LinkedHashMap<String, String>(),
                    "hwpx", null);
            InternalDoc back = new HwpxAdapter().read(new HwpxAdapter().write(source));

            assertThat(textOf(back)).contains("휴가신청서", "굵게", "기울임", "밑줄", "취소선",
                    "첫째 항목", "둘째 항목", "부서", "개발팀", "다음 쪽");

            InternalDoc.Paragraph heading = firstParagraph(back);
            assertThat(heading.headingLevel()).isEqualTo(1);
            assertThat(emphasisOf(back, "굵게").bold()).isTrue();
            assertThat(emphasisOf(back, "기울임").italic()).isTrue();
            assertThat(emphasisOf(back, "밑줄").underline()).isTrue();
            assertThat(emphasisOf(back, "취소선").strike()).isTrue();
            assertThat(listStyles(back)).contains("ordered", "bullet");
            assertThat(blocksOfType(back, InternalDoc.Table.class)).hasSize(1);
            assertThat(blocksOfType(back, InternalDoc.PageBreak.class)).hasSize(1);
        }

        @Test
        @DisplayName("embeds an image's bytes in the package and reads the same bytes back")
        void roundTripsAnImage() {
            InMemoryBinaryStore binaries = new InMemoryBinaryStore();
            String sha256 = binaries.store(PNG, "image/png");

            List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
            blocks.add(new InternalDoc.Image(sha256, "image/png", "법인 인감", 914400, 914400));
            InternalDoc source = new InternalDoc(blocks, new LinkedHashMap<String, String>(),
                    "hwpx", null);

            byte[] hwpx = new HwpxAdapter(binaries).write(source);
            assertThat(HwpxPackage.entriesOf(hwpx))
                    .hasEntrySatisfying("BinData/image1.png",
                            bytes -> assertThat(bytes).isEqualTo(PNG));

            List<InternalDoc.Image> images =
                    blocksOfType(new HwpxAdapter(binaries).read(hwpx), InternalDoc.Image.class);
            assertThat(images).hasSize(1);
            assertThat(images.get(0).blobSha256()).isEqualTo(sha256);
            assertThat(images.get(0).altText()).isEqualTo("법인 인감");
            assertThat(binaries.load(images.get(0).blobSha256())).isEqualTo(PNG);
        }

        @Test
        @DisplayName("refuses a document whose images it cannot read rather than dropping them")
        void refusesImagesWithNoBlobStore() {
            List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
            blocks.add(new InternalDoc.Image("deadbeef", "image/png", "도장", 100, 100));
            InternalDoc source = new InternalDoc(blocks, new LinkedHashMap<String, String>(),
                    "hwpx", null);

            assertThatThrownBy(() -> new HwpxAdapter().write(source))
                    .hasMessageContaining("deadbeef")
                    .hasMessageContaining("lose it silently");
        }

        @Test
        @DisplayName("writes the same bytes twice, so an archived render can be reproduced")
        void writeIsDeterministic() {
            InternalDoc source = new InternalDoc(
                    listOfBlocks(InternalDoc.Paragraph.of("결재 문서")),
                    new LinkedHashMap<String, String>(), "hwpx", null);

            // hwpxlib stamps every zip entry with the current time, so this only
            // holds because the package layer fixes them. Two writes a moment
            // apart differing would silently break the reproducibility contract.
            byte[] first = new HwpxAdapter().write(source);
            byte[] second = new HwpxAdapter().write(source);
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("produces an OCF container: mimetype first, and stored rather than deflated")
        void isAnOcfContainer() throws IOException {
            byte[] hwpx = new HwpxAdapter().write(new InternalDoc(
                    listOfBlocks(InternalDoc.Paragraph.of("본문")),
                    new LinkedHashMap<String, String>(), "hwpx", null));

            ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(hwpx));
            ZipEntry first = zip.getNextEntry();
            assertThat(first.getName()).isEqualTo("mimetype");
            // Stored, so a reader can identify the format from the first bytes of
            // the file without inflating anything. hwpxlib deflates it.
            assertThat(first.getMethod()).isEqualTo(ZipEntry.STORED);
            zip.close();
        }

        @Test
        @DisplayName("keeps the section properties the blank scaffold supplies")
        void keepsSectionProperties() {
            byte[] hwpx = new HwpxAdapter().write(new InternalDoc(
                    listOfBlocks(InternalDoc.Paragraph.of("본문")),
                    new LinkedHashMap<String, String>(), "hwpx", null));
            String section = new String(
                    HwpxPackage.entriesOf(hwpx).get("Contents/section0.xml"),
                    java.nio.charset.StandardCharsets.UTF_8);

            // Page size, margins and the outline shape live here. A section
            // rebuilt without them opens in 한글 with default page setup.
            assertThat(section).contains("secPr");
            assertThat(section).contains("본문");
        }

        @Test
        @DisplayName("refuses a file that is not an HWPX package, naming what it might be")
        void refusesNonPackages() {
            assertThatThrownBy(() -> new HwpxAdapter().read("not a zip".getBytes()))
                    .hasMessageContaining("legacy .hwp");
        }
    }

    @Nested
    @DisplayName("A 결재란 bound as one control")
    class ApprovalBlock {

        @Test
        @DisplayName("keeps its table structure and its binding through HWPX")
        void keepsStructureAndBinding() {
            List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
            blocks.add(new InternalDoc.ControlBlock("approvalBlock", "결재란",
                    listOfBlocks(table())));
            InternalDoc source = new InternalDoc(blocks, new LinkedHashMap<String, String>(),
                    "docx", null);

            InternalDoc back = new HwpxAdapter().read(new HwpxAdapter().write(source));

            List<InternalDoc.ControlBlock> controls =
                    blocksOfType(back, InternalDoc.ControlBlock.class);
            assertThat(controls).hasSize(1);
            assertThat(controls.get(0).tag()).isEqualTo("approvalBlock");
            // A 결재란 that comes back as flat text is not a 결재란.
            assertThat(controls.get(0).blocks())
                    .anyMatch(block -> block instanceof InternalDoc.Table);
        }

        @Test
        @DisplayName("becomes a block-level w:sdt in the generated DOCX")
        void becomesASdtInDocx() {
            InternalDoc source = new InternalDoc(
                    listOfBlocks(new InternalDoc.ControlBlock("approvalBlock", "결재란",
                            listOfBlocks(table()))),
                    new LinkedHashMap<String, String>(), "hwpx", null);

            byte[] docx = new DocxAdapter().write(source);
            String documentPart = new String(OoxmlPackage.read(docx).documentPart(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(documentPart).contains("w:tag w:val=\"approvalBlock\"");
            assertThat(documentPart).contains("<w:tbl>");
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private static InternalDoc.Table table() {
        List<List<List<InternalDoc.Block>>> rows =
                new ArrayList<List<List<InternalDoc.Block>>>();
        rows.add(row("부서", "성명"));
        rows.add(row("개발팀", "김민준"));
        return new InternalDoc.Table(rows, true);
    }

    private static List<List<InternalDoc.Block>> row(String left, String right) {
        List<List<InternalDoc.Block>> cells = new ArrayList<List<InternalDoc.Block>>();
        cells.add(listOfBlocks(InternalDoc.Paragraph.of(left)));
        cells.add(listOfBlocks(InternalDoc.Paragraph.of(right)));
        return cells;
    }

    private static List<InternalDoc.Block> listOfBlocks(InternalDoc.Block... blocks) {
        List<InternalDoc.Block> list = new ArrayList<InternalDoc.Block>();
        for (int index = 0; index < blocks.length; index++) {
            list.add(blocks[index]);
        }
        return list;
    }

    private static List<InternalDoc.Run> listOf(InternalDoc.Run run) {
        List<InternalDoc.Run> runs = new ArrayList<InternalDoc.Run>();
        runs.add(run);
        return runs;
    }

    private static <T> List<T> blocksOfType(InternalDoc document, Class<T> type) {
        List<T> found = new ArrayList<T>();
        collect(document.blocks(), type, found);
        return found;
    }

    private static <T> void collect(List<InternalDoc.Block> blocks, Class<T> type, List<T> into) {
        for (InternalDoc.Block block : blocks) {
            if (type.isInstance(block)) {
                into.add(type.cast(block));
            }
            if (block instanceof InternalDoc.ControlBlock) {
                collect(((InternalDoc.ControlBlock) block).blocks(), type, into);
            }
        }
    }

    private static String textOf(InternalDoc document) {
        StringBuilder text = new StringBuilder();
        appendText(document.blocks(), text);
        return text.toString();
    }

    private static void appendText(List<InternalDoc.Block> blocks, StringBuilder text) {
        for (InternalDoc.Block block : blocks) {
            if (block instanceof InternalDoc.Paragraph) {
                text.append(((InternalDoc.Paragraph) block).text()).append('\n');
            } else if (block instanceof InternalDoc.FieldBlock) {
                text.append(((InternalDoc.FieldBlock) block).value()).append('\n');
            } else if (block instanceof InternalDoc.ControlBlock) {
                appendText(((InternalDoc.ControlBlock) block).blocks(), text);
            } else if (block instanceof InternalDoc.Table) {
                for (List<List<InternalDoc.Block>> row : ((InternalDoc.Table) block).rows()) {
                    for (List<InternalDoc.Block> cell : row) {
                        appendText(cell, text);
                    }
                }
            }
        }
    }

    private static InternalDoc.Paragraph firstParagraph(InternalDoc document) {
        for (InternalDoc.Block block : document.blocks()) {
            if (block instanceof InternalDoc.Paragraph) {
                return (InternalDoc.Paragraph) block;
            }
        }
        throw new AssertionError("no paragraph in " + document.blocks());
    }

    private static InternalDoc.Run emphasisOf(InternalDoc document, String text) {
        for (InternalDoc.Paragraph paragraph : blocksOfType(document, InternalDoc.Paragraph.class)) {
            for (InternalDoc.Run run : paragraph.runs()) {
                if (text.equals(run.text())) {
                    return run;
                }
            }
        }
        throw new AssertionError("no run reading \"" + text + "\"");
    }

    private static List<String> listStyles(InternalDoc document) {
        List<String> styles = new ArrayList<String>();
        for (InternalDoc.Paragraph paragraph : blocksOfType(document, InternalDoc.Paragraph.class)) {
            if (paragraph.listStyle() != null) {
                styles.add(paragraph.listStyle());
            }
        }
        return styles;
    }

    private static List<String> entryNames(byte[] archive) {
        return new ArrayList<String>(HwpxPackage.entriesOf(archive).keySet());
    }

    /** Rebuilds the package without the parts only this system understands. */
    private static byte[] withoutOurSidecar(byte[] archive) {
        Map<String, byte[]> entries = HwpxPackage.entriesOf(archive);
        ByteArrayOutputStream out = new ByteArrayOutputStream(archive.length);
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (entry.getKey().contains("coreintra-opaque")) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(entry.getKey());
                copy.setTime(0L);
                zip.putNextEntry(copy);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            zip.close();
        } catch (IOException e) {
            throw new IllegalStateException("cannot rebuild the package", e);
        }
        return out.toByteArray();
    }
}
