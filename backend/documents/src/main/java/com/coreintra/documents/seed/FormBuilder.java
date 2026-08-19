package com.coreintra.documents.seed;

import com.coreintra.compat.Texts;
import com.coreintra.documents.internal.InternalDoc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Builds a seeded form body in the pivot model.
 *
 * <h2>Why the templates are built rather than checked in</h2>
 *
 * <p>A DOCX in the repository is a binary nobody can review. A change to the
 * 지출결의서 would arrive in a pull request as "4.2 KB of bytes differ", which
 * is not a change anybody can approve. Built from this, the diff reads as
 * Korean and English sentences and a list of field ids.
 *
 * <p>It also means one description produces every format: the same
 * {@link InternalDoc} goes through the DOCX serialiser and the HWPX serialiser,
 * which is what §6.6 asks for and what stops the Korean and English bodies
 * drifting from each other.
 *
 * <h2>The shape of a Korean form</h2>
 *
 * <p>Korean office documents are laid out as a label column and a value column
 * in a bordered table, with the 결재란 at the top right. {@link #row} builds
 * that: the label cell is prose, the value cell is a content control bound to a
 * field id. A form that ignores the convention looks foreign, and a 결재 document
 * that looks foreign does not get signed.
 */
final class FormBuilder {

    private final List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
    private List<List<List<InternalDoc.Block>>> openFieldTable;

    static FormBuilder create() {
        return new FormBuilder();
    }

    /** The document title, as a level-one heading. */
    FormBuilder title(String text) {
        closeFieldTable();
        blocks.add(InternalDoc.Paragraph.heading(text, 1));
        return this;
    }

    FormBuilder heading(String text, int level) {
        closeFieldTable();
        blocks.add(InternalDoc.Paragraph.heading(text, level));
        return this;
    }

    FormBuilder paragraph(String text) {
        closeFieldTable();
        blocks.add(InternalDoc.Paragraph.of(text));
        return this;
    }

    /**
     * An instruction to whoever fills the form in.
     *
     * <p>Italic and set apart, because it is guidance rather than content and a
     * reader must be able to tell at a glance which is which.
     */
    FormBuilder guidance(String text) {
        closeFieldTable();
        List<InternalDoc.Run> runs = new ArrayList<InternalDoc.Run>();
        runs.add(new InternalDoc.Run(text, false, true, false, false, null));
        blocks.add(new InternalDoc.Paragraph(runs, 0, null, 0));
        return this;
    }

    FormBuilder bullet(String text) {
        closeFieldTable();
        List<InternalDoc.Run> runs = new ArrayList<InternalDoc.Run>();
        runs.add(InternalDoc.Run.plain(text));
        blocks.add(new InternalDoc.Paragraph(runs, 0, "bullet", 0));
        return this;
    }

    /** A label / value row, the value being a field the client fills in. */
    FormBuilder row(String label, String tag) {
        if (openFieldTable == null) {
            openFieldTable = new ArrayList<List<List<InternalDoc.Block>>>();
        }
        List<List<InternalDoc.Block>> row = new ArrayList<List<InternalDoc.Block>>();
        row.add(cellOf(bold(label)));
        row.add(cellOf(new InternalDoc.FieldBlock(tag, "")));
        openFieldTable.add(row);
        return this;
    }

    /** A free table: a header row of labels, then rows of prose. */
    FormBuilder table(String[] header, String[][] rows) {
        closeFieldTable();
        List<List<List<InternalDoc.Block>>> built =
                new ArrayList<List<List<InternalDoc.Block>>>();
        built.add(textRow(header));
        for (int index = 0; index < rows.length; index++) {
            built.add(textRow(rows[index]));
        }
        blocks.add(new InternalDoc.Table(built, true));
        return this;
    }

    /**
     * A table whose value cells are fields — a line-item grid.
     *
     * <p>The tags are per column and per row, so a three-line expense grid has
     * nine distinct field ids rather than one repeating one. The pivot model has
     * no repeating-section type and inventing one here would mean inventing it
     * in both serialisers too.
     */
    FormBuilder fieldTable(String[] header, String[][] tagRows) {
        closeFieldTable();
        List<List<List<InternalDoc.Block>>> built =
                new ArrayList<List<List<InternalDoc.Block>>>();
        built.add(textRow(header));
        for (int rowIndex = 0; rowIndex < tagRows.length; rowIndex++) {
            List<List<InternalDoc.Block>> row = new ArrayList<List<InternalDoc.Block>>();
            for (int cellIndex = 0; cellIndex < tagRows[rowIndex].length; cellIndex++) {
                String tag = tagRows[rowIndex][cellIndex];
                row.add(Texts.isBlank(tag)
                        ? cellOf(InternalDoc.Paragraph.of(""))
                        : cellOf(new InternalDoc.FieldBlock(tag, "")));
            }
            built.add(row);
        }
        blocks.add(new InternalDoc.Table(built, true));
        return this;
    }

    /**
     * The 결재란, bound as one control.
     *
     * <p>Bound as a whole rather than cell by cell because the question the
     * approval module asks is "does this template have an approvalBlock?", and
     * that is a question about the grid. {@code hasApprovalBlock} on the
     * published version is detected from exactly this control.
     */
    FormBuilder approvalBlock(String[] roles) {
        closeFieldTable();
        List<List<List<InternalDoc.Block>>> rows =
                new ArrayList<List<List<InternalDoc.Block>>>();
        rows.add(textRow(roles));
        // The empty row is where the 도장 or the signature image is composited.
        String[] signatureSpace = new String[roles.length];
        for (int index = 0; index < roles.length; index++) {
            signatureSpace[index] = "";
        }
        rows.add(textRow(signatureSpace));
        List<InternalDoc.Block> inner = new ArrayList<InternalDoc.Block>();
        inner.add(new InternalDoc.Table(rows, true));
        blocks.add(new InternalDoc.ControlBlock(
                com.coreintra.documents.schema.DocumentFieldSchema.APPROVAL_BLOCK_TAG,
                roles.length > 0 ? "결재란" : null, inner));
        return this;
    }

    /**
     * An mdv chart, carried verbatim.
     *
     * <p>Only ever reaches an mdv body. Nothing in the JVM renders mdv, so in a
     * DOCX or HWPX body this would be an opaque block the reader could not draw
     * — which is why the mdv companions are separate bodies rather than a chart
     * dropped into the Word file.
     */
    FormBuilder mdvChart(String source) {
        closeFieldTable();
        blocks.add(new InternalDoc.OpaqueBlock("mdv", "mdv chart",
                source.getBytes(Texts.UTF_8)));
        return this;
    }

    FormBuilder pageBreak() {
        closeFieldTable();
        blocks.add(new InternalDoc.PageBreak());
        return this;
    }

    InternalDoc build(String formatId) {
        closeFieldTable();
        return new InternalDoc(blocks, new LinkedHashMap<String, String>(), formatId, null);
    }

    private void closeFieldTable() {
        if (openFieldTable == null) {
            return;
        }
        blocks.add(new InternalDoc.Table(openFieldTable, false));
        openFieldTable = null;
    }

    private static List<List<InternalDoc.Block>> textRow(String[] cells) {
        List<List<InternalDoc.Block>> row = new ArrayList<List<InternalDoc.Block>>();
        for (int index = 0; index < cells.length; index++) {
            row.add(cellOf(bold(cells[index])));
        }
        return row;
    }

    private static List<InternalDoc.Block> cellOf(InternalDoc.Block block) {
        List<InternalDoc.Block> cell = new ArrayList<InternalDoc.Block>();
        cell.add(block);
        return cell;
    }

    private static InternalDoc.Paragraph bold(String text) {
        List<InternalDoc.Run> runs = new ArrayList<InternalDoc.Run>();
        runs.add(new InternalDoc.Run(text, true, false, false, false, null));
        return new InternalDoc.Paragraph(runs, 0, null, 0);
    }
}
