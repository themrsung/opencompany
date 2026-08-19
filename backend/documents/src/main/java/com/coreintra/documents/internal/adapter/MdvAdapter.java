package com.coreintra.documents.internal.adapter;

import com.coreintra.compat.Texts;
import com.coreintra.documents.internal.DocumentAdapter;
import com.coreintra.documents.internal.FormatCapabilities;
import com.coreintra.documents.internal.FormatCapabilities.Feature;
import com.coreintra.documents.internal.InternalDoc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * mdv (Markdown Visual) — CommonMark that carries charts as plain text.
 *
 * <h2>What this adapter is, and what it deliberately is not</h2>
 *
 * <p>It is mdv's door onto the pivot model, so that an mdv document is
 * viewable, submittable to 결재, exportable and present in the fidelity matrix
 * like any other format (§6.10).
 *
 * <p>It is <b>not</b> an mdv renderer, and it must not become one. There is no
 * JVM implementation of mdv; rendering runs in Node in the conversion worker
 * (ADR 0008). This class therefore reads the <em>text</em> — the prose
 * structure a CommonMark reader can see — and treats every mdv block as opaque,
 * carrying its source verbatim. The worker turns those blocks into SVG or PNG
 * when a document is exported to a format that cannot render them itself.
 *
 * <p>That division is the reason the degradation is honest: this side of the
 * system genuinely cannot draw a chart, and pretending otherwise here would
 * produce a DOCX with a chart-shaped hole in it.
 *
 * <h2>Why an mdv block survives a naive reader</h2>
 *
 * <p>An mdv block is a fenced code block, so it is valid CommonMark. A reader
 * that knows nothing about mdv shows the data as a table of numbers rather than
 * as a broken document, which is why {@code --to md} is a real export target
 * and not a lossy one.
 */
public class MdvAdapter implements DocumentAdapter {

    public static final String FORMAT_ID = "mdv";

    /** The fence that opens a chart, a dataset or a directive. */
    private static final String FENCE = "```";

    private static final FormatCapabilities CAPABILITIES = FormatCapabilities
            .builder(FORMAT_ID, "mdv (Markdown Visual)")
            .mediaType("text/vnd.mdv")
            .full(Feature.HEADINGS, Feature.RUN_EMPHASIS, Feature.LISTS, Feature.TABLES,
                    Feature.CHARTS, Feature.PAGE_BREAKS)
            .degraded(Feature.IMAGES, Feature.CONTENT_CONTROLS, Feature.APPROVAL_BLOCK)
            .dropped(Feature.MERGED_CELLS, Feature.HEADERS_FOOTERS, Feature.FOOTNOTES,
                    Feature.TRACKED_CHANGES, Feature.COMMENTS, Feature.COMPLEX_SCRIPT)
            .notes("Charts, financial plots and heatmaps are first-class here and are plain text "
                    + "in the source, so two versions of a board pack produce a readable diff — "
                    + "the one format in this system where that is true. THERE IS NO NATIVE DOCX "
                    + "OR HWP WRITER: those exports go mdv → InternalDoc → the DOCX/HWPX "
                    + "adapters. Rendering is not available in the JVM at all; it runs in Node in "
                    + "the conversion worker (ADR 0008), so on this side of the system every mdv "
                    + "block is carried verbatim as opaque content. Complex-script shaping is a "
                    + "Level 3 feature and this build substantiates Level 2, so Arabic and Indic "
                    + "are declared unsupported rather than misrendered.")
            .pairNote("docx", "Charts materialise as embedded SVG, and the conversion worker is "
                    + "what materialises them: it renders each chart before the DOCX is written, "
                    + "because nothing in the JVM can draw one. They arrive as pictures — the "
                    + "figure is exact, and it is no longer a chart anyone can edit or re-bind to "
                    + "new data. A chart that reaches the DOCX writer unrendered (an export run "
                    + "without the worker) is written as a labelled placeholder naming the chart "
                    + "type, never dropped and never left blank.")
            .pairNote("hwpx", "Charts materialise as embedded PNG rather than SVG, because 한글's "
                    + "picture handling for vector content is not reliable enough to promise. "
                    + "Rendered by the conversion worker, as for DOCX, and subject to the same "
                    + "placeholder rule when the worker is not in the path.")
            .pairNote("hwp", "Legacy .hwp cannot be written at all; export to HWPX instead.")
            .build();

    @Override
    public FormatCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean looksLikeThisFormat(byte[] content, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".mdv")) {
            return true;
        }
        if (content == null || content.length == 0 || HwpxAdapter.isZip(content)) {
            return false;
        }
        // A .md that contains an mdv block is an mdv document; one that does not
        // is plain Markdown, which this system does not claim to own.
        String text = new String(content, Texts.UTF_8);
        return name.endsWith(".md") && text.contains(FENCE + "mdv");
    }

    @Override
    public InternalDoc read(byte[] content) {
        String text = content == null ? "" : new String(content, Texts.UTF_8);
        List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
        List<String> pending = new ArrayList<String>();

        String[] lines = text.split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            if (line.startsWith(FENCE)) {
                flushParagraph(pending, blocks);
                index = readFencedBlock(lines, index, blocks);
                continue;
            }
            if (line.startsWith("#")) {
                flushParagraph(pending, blocks);
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                blocks.add(InternalDoc.Paragraph.heading(
                        Texts.strip(line.substring(level)), Math.min(6, level)));
            } else if (isListItem(line)) {
                flushParagraph(pending, blocks);
                blocks.add(listItem(line));
            } else if (line.startsWith("|")) {
                flushParagraph(pending, blocks);
                index = readTable(lines, index, blocks);
                continue;
            } else if (Texts.isBlank(line)) {
                flushParagraph(pending, blocks);
            } else {
                pending.add(line);
            }
            index++;
        }
        flushParagraph(pending, blocks);
        return new InternalDoc(blocks, new LinkedHashMap<String, String>(), FORMAT_ID, null);
    }

    /**
     * A fenced block.
     *
     * <p>An {@code mdv} fence is a chart and stays opaque — nothing in the JVM
     * can draw it. Any other fence is a code block, which the pivot model has no
     * type for, so it is also opaque. Both keep their source verbatim, which is
     * what lets an mdv → mdv round trip be exact.
     */
    private int readFencedBlock(String[] lines, int start, List<InternalDoc.Block> blocks) {
        StringBuilder source = new StringBuilder(lines[start]).append('\n');
        String info = Texts.strip(lines[start].substring(FENCE.length()));
        int index = start + 1;
        while (index < lines.length && !lines[index].startsWith(FENCE)) {
            source.append(lines[index]).append('\n');
            index++;
        }
        if (index < lines.length) {
            source.append(lines[index]).append('\n');
            index++;
        }
        String description = Texts.isBlank(info) ? "code block"
                : (info.startsWith("mdv") ? "mdv " + describeChart(info) : info + " code block");
        blocks.add(new InternalDoc.OpaqueBlock(FORMAT_ID, description,
                source.toString().getBytes(Texts.UTF_8)));
        return index;
    }

    /** {@code mdv bar}, {@code mdv ohlcv} — the chart type, for the warning text. */
    private static String describeChart(String info) {
        String rest = Texts.strip(info.substring("mdv".length()));
        if (Texts.isBlank(rest)) {
            return "block";
        }
        int space = rest.indexOf(' ');
        return (space < 0 ? rest : rest.substring(0, space)) + " chart";
    }

    private int readTable(String[] lines, int start, List<InternalDoc.Block> blocks) {
        List<List<List<InternalDoc.Block>>> rows =
                new ArrayList<List<List<InternalDoc.Block>>>();
        boolean headerRow = false;
        int index = start;
        int rowNumber = 0;
        while (index < lines.length && lines[index].startsWith("|")) {
            String line = lines[index];
            if (rowNumber == 1 && isDelimiterRow(line)) {
                // The |---|---| row is not data: it marks the row above as a header.
                headerRow = true;
                index++;
                rowNumber++;
                continue;
            }
            List<List<InternalDoc.Block>> row = new ArrayList<List<InternalDoc.Block>>();
            for (String cell : splitRow(line)) {
                List<InternalDoc.Block> content = new ArrayList<InternalDoc.Block>();
                content.add(InternalDoc.Paragraph.of(Texts.strip(cell)));
                row.add(content);
            }
            rows.add(row);
            index++;
            rowNumber++;
        }
        blocks.add(new InternalDoc.Table(rows, headerRow));
        return index;
    }

    private static boolean isDelimiterRow(String line) {
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character != '|' && character != '-' && character != ':' && character != ' ') {
                return false;
            }
        }
        return true;
    }

    private static List<String> splitRow(String line) {
        List<String> cells = new ArrayList<String>();
        String trimmed = Texts.strip(line);
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.split("\\|", -1);
        for (int index = 0; index < parts.length; index++) {
            cells.add(parts[index]);
        }
        return cells;
    }

    private static boolean isListItem(String line) {
        String trimmed = Texts.strip(line);
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            return true;
        }
        int dot = trimmed.indexOf(". ");
        if (dot <= 0) {
            return false;
        }
        for (int index = 0; index < dot; index++) {
            if (!Character.isDigit(trimmed.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static InternalDoc.Paragraph listItem(String line) {
        String trimmed = Texts.strip(line);
        int indent = (line.length() - trimmed.length()) / 2;
        boolean ordered = !(trimmed.startsWith("- ") || trimmed.startsWith("* "));
        String text = ordered
                ? Texts.strip(trimmed.substring(trimmed.indexOf(". ") + 2))
                : Texts.strip(trimmed.substring(2));
        List<InternalDoc.Run> runs = new ArrayList<InternalDoc.Run>();
        runs.add(InternalDoc.Run.plain(text));
        return new InternalDoc.Paragraph(runs, 0, ordered ? "ordered" : "bullet", indent);
    }

    private static void flushParagraph(List<String> pending, List<InternalDoc.Block> blocks) {
        if (pending.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < pending.size(); index++) {
            if (index > 0) {
                text.append(' ');
            }
            text.append(pending.get(index));
        }
        pending.clear();
        blocks.add(InternalDoc.Paragraph.of(text.toString()));
    }

    @Override
    public byte[] write(InternalDoc document) {
        StringBuilder out = new StringBuilder();
        for (InternalDoc.Block block : document.blocks()) {
            appendBlock(out, block);
        }
        return out.toString().getBytes(Texts.UTF_8);
    }

    private void appendBlock(StringBuilder out, InternalDoc.Block block) {
        if (block instanceof InternalDoc.Paragraph) {
            appendParagraph(out, (InternalDoc.Paragraph) block);
        } else if (block instanceof InternalDoc.FieldBlock) {
            InternalDoc.FieldBlock field = (InternalDoc.FieldBlock) block;
            // A field has no mdv equivalent, so it is written as its value with
            // the binding recorded in an attribute-style comment: legible to a
            // reader, and enough for the import flow to offer a re-binding.
            out.append("<!-- field:").append(field.tag()).append(" -->\n")
               .append(field.value() == null ? "" : field.value()).append("\n\n");
        } else if (block instanceof InternalDoc.ControlBlock) {
            InternalDoc.ControlBlock control = (InternalDoc.ControlBlock) block;
            out.append("<!-- field:").append(control.tag()).append(" -->\n");
            for (InternalDoc.Block child : control.blocks()) {
                appendBlock(out, child);
            }
        } else if (block instanceof InternalDoc.Table) {
            appendTable(out, (InternalDoc.Table) block);
        } else if (block instanceof InternalDoc.Image) {
            InternalDoc.Image image = (InternalDoc.Image) block;
            out.append("![").append(image.altText() == null ? "" : image.altText())
               .append("](blob:").append(image.blobSha256()).append(")\n\n");
        } else if (block instanceof InternalDoc.PageBreak) {
            // The spec's own page directive, so pagination survives rather than
            // being approximated with blank lines.
            out.append(":::mdv-page\n:::\n\n");
        } else if (block instanceof InternalDoc.OpaqueBlock) {
            InternalDoc.OpaqueBlock opaque = (InternalDoc.OpaqueBlock) block;
            if (FORMAT_ID.equals(opaque.sourceFormat()) && opaque.originalBytes().length > 0) {
                out.append(new String(opaque.originalBytes(), Texts.UTF_8)).append('\n');
            } else {
                out.append("<!-- ").append(opaque.description())
                   .append(": carried from ").append(opaque.sourceFormat())
                   .append(" and not representable here -->\n\n");
            }
        }
    }

    private void appendParagraph(StringBuilder out, InternalDoc.Paragraph paragraph) {
        if (paragraph.headingLevel() > 0) {
            out.append(Texts.repeat("#", Math.min(6, paragraph.headingLevel())))
               .append(' ').append(paragraph.text()).append("\n\n");
            return;
        }
        if (paragraph.listStyle() != null) {
            out.append(Texts.repeat("  ", Math.max(0, paragraph.listLevel())))
               .append("ordered".equals(paragraph.listStyle()) ? "1. " : "- ");
            appendRuns(out, paragraph);
            out.append('\n');
            return;
        }
        appendRuns(out, paragraph);
        out.append("\n\n");
    }

    private void appendRuns(StringBuilder out, InternalDoc.Paragraph paragraph) {
        for (InternalDoc.Run run : paragraph.runs()) {
            String text = run.text();
            if (run.strike()) {
                text = "~~" + text + "~~";
            }
            if (run.bold()) {
                text = "**" + text + "**";
            }
            if (run.italic()) {
                text = "*" + text + "*";
            }
            // CommonMark has no underline. Emphasis is the closest honest
            // mapping; inventing a raw <u> would need HTML, which is disabled.
            if (run.underline() && !run.italic()) {
                text = "*" + text + "*";
            }
            out.append(text);
        }
    }

    private void appendTable(StringBuilder out, InternalDoc.Table table) {
        List<List<List<InternalDoc.Block>>> rows = table.rows();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<List<InternalDoc.Block>> row = rows.get(rowIndex);
            out.append('|');
            for (List<InternalDoc.Block> cell : row) {
                out.append(' ').append(cellText(cell).replace("|", "\\|")).append(" |");
            }
            out.append('\n');
            if (rowIndex == 0 && table.hasHeaderRow()) {
                out.append('|');
                for (int index = 0; index < row.size(); index++) {
                    out.append(" --- |");
                }
                out.append('\n');
            }
        }
        out.append('\n');
    }

    private static String cellText(List<InternalDoc.Block> cell) {
        StringBuilder text = new StringBuilder();
        for (InternalDoc.Block block : cell) {
            if (block instanceof InternalDoc.Paragraph) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(((InternalDoc.Paragraph) block).text());
            } else if (block instanceof InternalDoc.FieldBlock) {
                text.append(((InternalDoc.FieldBlock) block).value());
            }
        }
        return text.toString();
    }
}
