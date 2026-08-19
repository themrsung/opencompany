package com.coreintra.documents.internal;

import com.coreintra.compat.Immutables;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The adapters this installation has, and the fidelity matrix derived from them.
 *
 * <p>The matrix is generated rather than written. A hand-maintained table drifts
 * from the code silently, and the person it misleads is the user deciding
 * whether an export is safe to send to a client.
 */
public final class FormatRegistry {

    private final Map<String, DocumentAdapter> adapters =
            new LinkedHashMap<String, DocumentAdapter>();

    public FormatRegistry register(DocumentAdapter adapter) {
        adapters.put(adapter.capabilities().formatId(), adapter);
        return this;
    }

    public DocumentAdapter adapter(String formatId) {
        DocumentAdapter adapter = adapters.get(formatId);
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "no adapter for format \"" + formatId + "\". Available: " + adapters.keySet());
        }
        return adapter;
    }

    public List<DocumentAdapter> adapters() {
        return Immutables.copyOf(new ArrayList<DocumentAdapter>(adapters.values()));
    }

    /** Routes an upload by sniffing content, falling back to the filename. */
    public DocumentAdapter detect(byte[] content, String filename) {
        for (DocumentAdapter adapter : adapters.values()) {
            if (adapter.looksLikeThisFormat(content, filename)) {
                return adapter;
            }
        }
        throw new IllegalArgumentException(
                "cannot tell what kind of document \"" + filename + "\" is. Supported: "
                        + adapters.keySet());
    }

    /**
     * The fidelity matrix as data, one entry per format pair.
     *
     * <p>This is what the export dialog reads. The Markdown page below is
     * generated from the same declarations, so the document a developer reads
     * and the warning a user sees cannot drift apart.
     */
    public FidelityMatrix fidelityMatrix() {
        return FidelityMatrix.of(adapters());
    }

    /**
     * The fidelity matrix, as Markdown.
     *
     * <p>Written to {@code docs/documents/fidelity.md} by a build step and
     * surfaced row by row in the UI at export time.
     */
    public String toFidelityMarkdown() {
        StringBuilder out = new StringBuilder();
        out.append("<!-- GENERATED from each adapter's declared FormatCapabilities.\n")
           .append("     Do not edit by hand: a hand-maintained fidelity table drifts from the\n")
           .append("     code silently, and the person it misleads is the user deciding whether\n")
           .append("     an export is safe to send to a client.\n")
           .append("     Regenerate with: ./mvnw -pl documents test -Dtest=FidelityMatrixTest -->\n\n");
        out.append("# Format fidelity\n\n");
        out.append("What survives, what degrades, and what is dropped, per format.\n\n");

        out.append("| Feature |");
        for (DocumentAdapter adapter : adapters.values()) {
            out.append(' ').append(adapter.capabilities().displayName()).append(" |");
        }
        out.append("\n|---|");
        for (int i = 0; i < adapters.size(); i++) {
            out.append("---|");
        }
        out.append('\n');

        for (FormatCapabilities.Feature feature : FormatCapabilities.Feature.values()) {
            out.append("| ").append(feature.label()).append(" |");
            for (DocumentAdapter adapter : adapters.values()) {
                out.append(' ').append(symbol(adapter.capabilities().support(feature))).append(" |");
            }
            out.append('\n');
        }

        out.append("\nLegend: **yes** survives · **~** degrades · **opaque** preserved but not "
                + "editable · **no** dropped\n\n");

        out.append("## Read and write\n\n| Format | Read | Write |\n|---|---|---|\n");
        for (DocumentAdapter adapter : adapters.values()) {
            FormatCapabilities capabilities = adapter.capabilities();
            out.append("| ").append(capabilities.displayName()).append(" | ")
               .append(capabilities.canRead() ? "yes" : "no").append(" | ")
               .append(capabilities.canWrite() ? "yes" : "no").append(" |\n");
        }

        appendPairs(out);
        appendRenderTargets(out);

        out.append("\n## Notes\n\n");
        for (DocumentAdapter adapter : adapters.values()) {
            FormatCapabilities capabilities = adapter.capabilities();
            if (capabilities.notes() != null) {
                out.append("### ").append(capabilities.displayName()).append("\n\n")
                   .append(capabilities.notes()).append("\n\n");
            }
        }
        return out.toString();
    }

    /**
     * The pair table: what a user actually asks at export time.
     *
     * <p>The per-format table above answers "what can HWPX carry". Nobody has
     * that question. They have "I am sending this as a DOCX — what changes?",
     * which is about both ends at once.
     */
    private void appendPairs(StringBuilder out) {
        FidelityMatrix matrix = fidelityMatrix();
        out.append("\n## Conversions, pair by pair\n\n")
           .append("What changes converting one format to another. A conversion into the same "
                   + "format is not listed: an edit is surgical and leaves every untouched part "
                   + "byte-identical (see the DOCX note).\n\n")
           .append("| From | To | Degrades | Dropped | Preserved but not editable |\n")
           .append("|---|---|---|---|---|\n");
        for (FidelityMatrix.Pair pair : matrix.pairs()) {
            if (pair.fromFormatId().equals(pair.toFormatId())) {
                continue;
            }
            out.append("| ").append(pair.fromDisplayName()).append(" | ")
               .append(pair.toDisplayName()).append(" | ");
            if (!pair.isSupported()) {
                out.append("— | — | — |\n");
                continue;
            }
            out.append(featuresWith(pair, FormatCapabilities.Support.DEGRADED)).append(" | ")
               .append(featuresWith(pair, FormatCapabilities.Support.DROPPED)).append(" | ")
               .append(featuresWith(pair, FormatCapabilities.Support.PRESERVED_OPAQUE))
               .append(" |\n");
        }

        boolean anyNote = false;
        for (FidelityMatrix.Pair pair : matrix.pairs()) {
            if (pair.note() == null || pair.fromFormatId().equals(pair.toFormatId())) {
                continue;
            }
            if (!anyNote) {
                out.append("\n### Pair notes\n\n");
                anyNote = true;
            }
            out.append("- **").append(pair.fromDisplayName()).append(" → ")
               .append(pair.toDisplayName()).append("** — ").append(pair.note()).append('\n');
        }

        boolean anyRefusal = false;
        for (FidelityMatrix.Pair pair : matrix.pairs()) {
            if (pair.isSupported() || pair.fromFormatId().equals(pair.toFormatId())) {
                continue;
            }
            if (!anyRefusal) {
                out.append("\n### Targets that are refused rather than approximated\n\n");
                anyRefusal = true;
            }
            out.append("- **").append(pair.fromDisplayName()).append(" → ")
               .append(pair.toDisplayName()).append("** — ").append(pair.unsupportedReason())
               .append('\n');
            break;
        }
    }

    /**
     * The targets produced outside the JVM.
     *
     * <p>PDF and legacy {@code .doc} have no adapter and therefore no row in
     * the tables above, but a user still picks them from the same menu. Leaving
     * them out of the published matrix would make the matrix quietly incomplete
     * exactly where the biggest losses are.
     */
    private static void appendRenderTargets(StringBuilder out) {
        out.append("\n## Export targets produced by the conversion worker\n\n")
           .append("These have no adapter: nothing here reads them and nothing here writes them. "
                   + "They are rendered outside the JVM, so what is true about them is declared "
                   + "in `RenderTarget` and reproduced here.\n\n")
           .append("| Target | Produced by | Offered | What to know |\n|---|---|---|---|\n");
        for (RenderTarget target : RenderTarget.all()) {
            out.append("| ").append(target.displayName()).append(" | ")
               .append(target.producedBy()).append(" | ")
               .append(target.isOffered() ? "yes" : "**refused**").append(" | ")
               .append(target.cautionEn()).append(" |\n");
        }
    }

    private static String featuresWith(FidelityMatrix.Pair pair,
            FormatCapabilities.Support support) {
        StringBuilder listed = new StringBuilder();
        for (FidelityMatrix.Row row : pair.rows()) {
            if (row.support() != support) {
                continue;
            }
            if (listed.length() > 0) {
                listed.append(", ");
            }
            listed.append(row.feature().label());
        }
        return listed.length() == 0 ? "—" : listed.toString();
    }

    private static String symbol(FormatCapabilities.Support support) {
        switch (support) {
            case FULL:
                return "yes";
            case DEGRADED:
                return "~";
            case PRESERVED_OPAQUE:
                return "opaque";
            case DROPPED:
            default:
                return "no";
        }
    }
}
