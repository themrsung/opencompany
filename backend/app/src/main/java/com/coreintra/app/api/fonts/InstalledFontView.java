package com.coreintra.app.api.fonts;

import com.coreintra.app.api.documents.DocumentPages;
import com.coreintra.compat.Immutables;
import com.coreintra.documents.font.FontRecord;
import com.coreintra.documents.service.InstalledFont;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * An installed font, as the font manager shows it (§6.9).
 *
 * <h2>Everything the manager UI is required to show</h2>
 *
 * <p>Family, style, script coverage, source, uploader and licence metadata — all
 * of it in one row, because the question the screen answers is "should this font
 * be here?", and answering it means seeing who put it there, what they agreed
 * to, and what the font itself declares about embedding.
 *
 * <h2>Why the three consumer views are on the row</h2>
 *
 * <p>An uploaded font must reach the conversion worker's fontconfig path, the
 * browser editor as a webfont, and mdv's {@code pdf.fonts} config. If any two of
 * the three disagree about what a font looks like, WYSIWYG is broken. They are
 * derived from the same record here for the same reason they are derived from
 * the same row in the store: so that they cannot drift.
 */
public class InstalledFontView {

    static final Function<InstalledFont, InstalledFontView> MAPPER =
            new Function<InstalledFont, InstalledFontView>() {
                @Override
                public InstalledFontView apply(InstalledFont font) {
                    return new InstalledFontView(font, null);
                }
            };

    /** Ordered by family then style, which is how a font list is read. */
    static final DocumentPages.Keys<InstalledFont> KEYS = new DocumentPages.Keys<InstalledFont>() {
        @Override
        public String sortKey(InstalledFont font) {
            return font.family() + " " + font.style();
        }

        @Override
        public String id(InstalledFont font) {
            return font.id();
        }
    };

    private final String id;
    private final String family;
    private final String style;
    private final String fileFormat;
    private final String blobSha256;
    private final List<String> scriptCoverage;
    private final String source;
    private final String embeddingPermission;
    private final String uploadedByAccountId;
    private final String uploadedAt;
    private final String licenceAcknowledgementText;
    private final boolean enabled;
    private final String fontconfigFileName;
    private final String webfontFaceCss;
    private final String mdvFontConfigEntry;

    InstalledFontView(InstalledFont font, String workerFontDirectory) {
        FontRecord record = font.record();
        this.id = font.id();
        this.family = font.family();
        this.style = font.style();
        this.fileFormat = record.fileFormat();
        this.blobSha256 = font.blobSha256();
        this.scriptCoverage = sorted(record.scriptCoverage());
        this.source = String.valueOf(record.source());
        this.embeddingPermission = String.valueOf(record.embeddingPermission());
        this.uploadedByAccountId = record.uploadedByAccountId();
        this.uploadedAt = record.uploadedAt() == null ? null : String.valueOf(record.uploadedAt());
        this.licenceAcknowledgementText = record.licenceAcknowledgementText();
        this.enabled = font.isEnabled();
        this.fontconfigFileName = font.fontconfigFileName();
        this.webfontFaceCss = font.webfontFaceCss();
        this.mdvFontConfigEntry = workerFontDirectory == null
                ? null : font.mdvFontConfigEntry(workerFontDirectory);
    }

    public String getId() {
        return id;
    }

    public String getFamily() {
        return family;
    }

    public String getStyle() {
        return style;
    }

    /** ttf, otf, ttc, otc, woff2 — no allowlist, no curation (§6.9). */
    public String getFileFormat() {
        return fileFormat;
    }

    /** The content address. The same bytes for all three consumers, by construction. */
    public String getBlobSha256() {
        return blobSha256;
    }

    /** ISO 15924 script codes this face covers. Empty means nobody has said. */
    public List<String> getScriptCoverage() {
        return scriptCoverage;
    }

    /** BUNDLED, CLIENT_UPLOADED or HOST_PROVIDED. */
    public String getSource() {
        return source;
    }

    /**
     * What the font's own OS/2 table declares.
     *
     * <p>Reported, never enforced by us. Honouring it is the client's obligation
     * and showing it read-only is how they can see what they agreed about.
     */
    public String getEmbeddingPermission() {
        return embeddingPermission;
    }

    /** Who installed it. Null for a bundled face, which nobody uploaded. */
    public String getUploadedByAccountId() {
        return uploadedByAccountId;
    }

    public String getUploadedAt() {
        return uploadedAt;
    }

    /** The exact words the uploader agreed to, as they were shown on the day. */
    public String getLicenceAcknowledgementText() {
        return licenceAcknowledgementText;
    }

    /** A disabled font stays installed and stops being resolved to. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Consumer 1: the filename the conversion worker materialises. */
    public String getFontconfigFileName() {
        return fontconfigFileName;
    }

    /** Consumer 2: the @font-face the browser editor loads. */
    public String getWebfontFaceCss() {
        return webfontFaceCss;
    }

    /** Consumer 3: mdv's pdf.fonts entry. Null unless the worker path was asked for. */
    public String getMdvFontConfigEntry() {
        return mdvFontConfigEntry;
    }

    private static List<String> sorted(Set<String> scripts) {
        List<String> codes = new ArrayList<String>(scripts);
        java.util.Collections.sort(codes);
        return Immutables.copyOf(codes);
    }
}
