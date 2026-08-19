package com.coreintra.documents.service;

import java.util.Locale;

import com.coreintra.documents.entity.FontEntity;
import com.coreintra.documents.font.FontRecord;

/**
 * One font row, seen by each of its three consumers (brief 6.9).
 *
 * <h2>One store, three consumers, one record</h2>
 *
 * <p>An uploaded font has to reach the conversion worker's fontconfig path, the browser
 * editor as a webfont, and mdv's {@code pdf.fonts} config - mdv will not pick up a CJK
 * face implicitly. If any two of the three disagree about what a font looks like, the
 * feature is broken, because WYSIWYG is the whole point.
 *
 * <p>Three stores would make that disagreement possible and eventually certain. So this
 * is one row with three views of it, and the three cannot drift because there is nothing
 * for them to drift from.
 */
public final class InstalledFont {

    private final FontEntity font;

    InstalledFont(FontEntity font) {
        this.font = font;
    }

    public String id() {
        return font.id();
    }

    public String family() {
        return font.family();
    }

    public String style() {
        return font.style();
    }

    /** The content hash. The same bytes for all three consumers, by construction. */
    public String blobSha256() {
        return font.blobSha256();
    }

    public boolean isEnabled() {
        return font.isEnabled();
    }

    /** The pure-logic view the resolver consumes. */
    public FontRecord record() {
        return font.toRecord();
    }

    /**
     * Consumer 1: the filename the conversion worker materialises into its fontconfig path.
     *
     * <p>Derived from family and style rather than from the uploaded filename, which is
     * attacker-controlled and frequently {@code font(1).ttf}. Deterministic, so a worker
     * restarting onto the same volume finds the same file rather than a second copy.
     */
    public String fontconfigFileName() {
        return sanitise(font.family()) + "-" + sanitise(font.style()) + "."
                + font.fileFormat().toLowerCase(Locale.ROOT);
    }

    /**
     * Consumer 2: the {@code @font-face} the browser editor loads.
     *
     * <p>Served from the content-addressed route, so the URL changes when the bytes do and
     * a replaced font cannot be served from a stale cache while the PDF uses the new one -
     * which is exactly the two-of-three disagreement this class exists to prevent.
     */
    public String webfontFaceCss() {
        return "@font-face{font-family:\"" + font.family() + "\";font-style:normal;"
                + "font-display:swap;src:url(\"/api/fonts/" + font.blobSha256() + "\") format(\""
                + cssFormat() + "\")}";
    }

    /**
     * Consumer 3: mdv's {@code pdf.fonts} entry.
     *
     * <p>Subsetting is on: a full CJK face is 5-20 MB and mdv embeds what it is given.
     * The path is the worker's fontconfig path, so mdv and LibreOffice read the same file.
     */
    public String mdvFontConfigEntry(String workerFontDirectory) {
        String directory = workerFontDirectory.endsWith("/")
                ? workerFontDirectory
                : workerFontDirectory + "/";
        return "{ \"family\": \"" + font.family() + "\", \"src\": \"" + directory
                + fontconfigFileName() + "\", \"subset\": true }";
    }

    private String cssFormat() {
        String format = font.fileFormat().toLowerCase(Locale.ROOT);
        if ("otf".equals(format)) {
            return "opentype";
        }
        if ("woff2".equals(format)) {
            return "woff2";
        }
        if ("ttc".equals(format) || "otc".equals(format)) {
            return "collection";
        }
        return "truetype";
    }

    /** Filenames are ours, not the uploader's: a path separator in a family name is an escape. */
    private static String sanitise(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return out.toString();
    }
}
