package com.coreintra.app.api.documents;

import com.coreintra.documents.internal.FidelityMatrix;
import com.coreintra.documents.internal.FormatRegistry;
import com.coreintra.documents.internal.adapter.DocxAdapter;
import com.coreintra.documents.internal.adapter.HwpLegacyAdapter;
import com.coreintra.documents.internal.adapter.HwpxAdapter;
import com.coreintra.documents.internal.adapter.MdvAdapter;

/**
 * The fidelity matrix, built once from the adapters this installation has.
 *
 * <h2>Why the list is here and should not be</h2>
 *
 * <p>{@link FormatRegistry} is a plain object and nothing in the documents
 * module publishes an assembled one, so the API has to assemble it to answer
 * "what does this export cost?". That means the adapter list is written down in
 * two places, and the day a fifth adapter is added to the module this file will
 * quietly keep answering for four. It is reported rather than worked around: the
 * documents module should expose the assembled registry as a bean, and this
 * class should then be four lines shorter and impossible to get wrong.
 *
 * <p>Only {@code capabilities()} is called on the adapters, which touches no
 * binaries and no I/O, so building the matrix once at class-init costs nothing
 * and keeps the export path free of per-request setup.
 */
final class FidelityMatrixSource {

    private static final FormatRegistry REGISTRY = new FormatRegistry()
            .register(new DocxAdapter())
            .register(new HwpxAdapter())
            .register(new HwpLegacyAdapter())
            .register(new MdvAdapter());

    private static final FidelityMatrix MATRIX = REGISTRY.fidelityMatrix();

    private FidelityMatrixSource() {
    }

    static FidelityMatrix matrix() {
        return MATRIX;
    }

    /**
     * The row for one direction, or an honest "there is no row" when either end
     * is a format nothing in this system reads back.
     *
     * @param fromFormatId the stored format, lower case: docx, hwpx, hwp, mdv
     * @param toFormatId   the export target, or null for PDF, DOC and HTML
     */
    static FidelityRowView row(String fromFormatId, String toFormatId, String targetName) {
        if (toFormatId == null) {
            return FidelityRowView.unavailable(fromFormatId, targetName,
                    targetName + " is an export target only: nothing in this system reads it back, "
                            + "so there is no round-trip to describe. What it looks like is "
                            + "decided by the renderer and its pinned version, which the render "
                            + "metadata records.");
        }
        try {
            return FidelityRowView.of(MATRIX.pair(fromFormatId, toFormatId));
        } catch (IllegalArgumentException unregistered) {
            // A format with no adapter cannot be described honestly, and guessing
            // "no losses" is the failure the matrix exists to prevent.
            return FidelityRowView.unavailable(fromFormatId, toFormatId,
                    unregistered.getMessage());
        }
    }
}
