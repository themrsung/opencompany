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
