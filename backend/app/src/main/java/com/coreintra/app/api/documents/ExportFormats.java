package com.coreintra.app.api.documents;

import com.coreintra.compat.Texts;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.RenderFormat;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * What each export target is: its media type, its filename, whether it needs a
 * conversion worker at all, and whether it is honest to call it lossless.
 *
 * <h2>DOC is labelled, not hidden</h2>
 *
 * <p>§6.4 says {@code .doc} goes through LibreOffice and is labelled legacy and
 * lossy in the UI. The label has to come from the API, because the UI cannot
 * know which targets are lossy without being told, and a UI that hardcodes the
 * list will be wrong the day a format is added. Every export response therefore
 * carries the labelling with it rather than expecting the client to remember.
 */
final class ExportFormats {

    private ExportFormats() {
    }

    /** @throws IllegalArgumentException naming every target, rather than "no enum constant" */
    static RenderFormat renderFormat(String requested) {
        if (!Texts.isBlank(requested)) {
            String wanted = Texts.strip(requested).toUpperCase(Locale.ROOT);
            for (RenderFormat candidate : RenderFormat.values()) {
                if (candidate.name().equals(wanted)) {
                    return candidate;
                }
            }
        }
        StringBuilder targets = new StringBuilder();
        for (RenderFormat candidate : RenderFormat.values()) {
            if (targets.length() > 0) {
                targets.append(", ");
            }
            targets.append(candidate.name());
        }
        throw new IllegalArgumentException(
                "\"" + requested + "\" is not an export target. Available: " + targets + ".");
    }

    static String mediaTypeOf(RenderFormat format) {
        if (format == RenderFormat.PDF) {
            return "application/pdf";
        }
        if (format == RenderFormat.DOCX) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (format == RenderFormat.DOC) {
            return "application/msword";
        }
        if (format == RenderFormat.HWPX) {
            return "application/hwp+zip";
        }
        if (format == RenderFormat.HWP) {
            return "application/x-hwp";
        }
        if (format == RenderFormat.HTML) {
            return "text/html";
        }
        return "text/vnd.mdv";
    }

    static String mediaTypeOf(DocumentFormat format) {
        if (format == DocumentFormat.DOCX) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (format == DocumentFormat.HWPX) {
            return "application/hwp+zip";
        }
        if (format == DocumentFormat.HWP) {
            return "application/x-hwp";
        }
        return "text/vnd.mdv";
    }

    /**
     * True when the stored bytes already are the export.
     *
     * <p>A DOCX document exported as DOCX is a download, not a conversion. Sending
     * it round LibreOffice anyway would burn a worker slot and — worse — return
     * bytes that differ from what was approved.
     */
    static boolean isNativeDownload(DocumentFormat stored, RenderFormat target) {
        return stored.name().equals(target.name());
    }

    /** The pivot format id the fidelity matrix knows this target by, or null. */
    static String pivotFormatId(RenderFormat target) {
        if (target == RenderFormat.DOCX || target == RenderFormat.HWPX
                || target == RenderFormat.HWP || target == RenderFormat.MDV) {
            return target.name().toLowerCase(Locale.ROOT);
        }
        // PDF, DOC and HTML have no adapter: nothing in this system reads them
        // back, so there is no pair to describe. The export response says so in
        // words rather than returning an empty row that looks like "no losses".
        return null;
    }

    static String pivotFormatId(DocumentFormat stored) {
        return stored.name().toLowerCase(Locale.ROOT);
    }

    /** §6.4: DOC is legacy and lossy, and the UI has to be able to say so. */
    static boolean isLegacy(RenderFormat target) {
        return target == RenderFormat.DOC;
    }

    static String legacyNoteEn(RenderFormat target) {
        if (!isLegacy(target)) {
            return null;
        }
        return "Legacy format. .doc is written by LibreOffice from the .docx and loses content "
                + "controls, and may reflow. Send .docx unless the recipient cannot open it.";
    }

    static String legacyNoteKo(RenderFormat target) {
        if (!isLegacy(target)) {
            return null;
        }
        return "구형 형식입니다. .doc는 LibreOffice가 .docx에서 변환하며 내용 컨트롤이 사라지고 "
                + "레이아웃이 달라질 수 있습니다. 받는 쪽에서 열 수 없는 경우가 아니라면 .docx를 보내십시오.";
    }

    /**
     * A {@code Content-Disposition} that survives a Korean title.
     *
     * <p>Both forms are sent: {@code filename} in ASCII for anything that does
     * not implement RFC 5987, and {@code filename*} percent-encoded UTF-8 for
     * everything that does. Sending only the first turns 지출결의서.pdf into
     * ____.pdf; sending only the second loses the download on older clients.
     */
    static String attachment(String title, String formatName) {
        String extension = formatName.toLowerCase(Locale.ROOT);
        String base = Texts.isBlank(title) ? "document" : Texts.strip(title);
        String filename = base + "." + extension;
        return "attachment; filename=\"" + ascii(filename) + "\"; filename*=UTF-8''"
                + percent(filename);
    }

    private static String ascii(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // A quote or a backslash would end the quoted-string early, which is
            // a header injection rather than a cosmetic problem.
            out.append(c < 32 || c > 126 || c == '"' || c == '\\' ? '_' : c);
        }
        return out.toString();
    }

    private static String percent(String value) {
        try {
            // URLEncoder is form encoding, so a space becomes '+' where RFC 5987
            // wants %20. One replacement is cheaper than a second encoder.
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is mandatory in every JRE", e);
        }
    }
}
