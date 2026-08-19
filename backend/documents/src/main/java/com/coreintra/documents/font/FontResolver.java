package com.coreintra.documents.font;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a requested font family against what is actually installed, and
 * reports every substitution it had to make.
 *
 * <h2>Why this cannot be left to fontconfig</h2>
 *
 * <p>fontconfig always answers. Asked for a family it does not have, it returns
 * its best guess and says nothing — measured on this very build:
 *
 * <pre>
 *   fc-match "Pretendard"   -&gt;  DejaVu Sans     (no Korean coverage at all)
 *   fc-match "함초롬바탕"     -&gt;  DejaVu Sans
 *   fc-match ":lang=ko"     -&gt;  WenQuanYi Zen Hei  (a *Chinese* face)
 * </pre>
 *
 * <p>A 지출결의서 rendered that way is not obviously broken. It is subtly wrong —
 * Korean set in a Chinese face, or in a font with no Korean at all — and nobody
 * finds out until a 대표이사 signs something that looks unprofessional.
 *
 * <p>So resolution goes through the font store, which knows what is genuinely
 * installed, and every fallback is <b>named and recorded</b> in the render
 * metadata. A missing font produces a warning; it never produces a silent
 * substitution.
 */
public final class FontResolver {

    /** What a request resolved to, and whether that was what was asked for. */
    public static final class Resolution implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String requestedFamily;
        private final String resolvedFamily;
        private final boolean substituted;
        private final String reason;

        Resolution(String requestedFamily, String resolvedFamily, boolean substituted,
                String reason) {
            this.requestedFamily = requestedFamily;
            this.resolvedFamily = resolvedFamily;
            this.substituted = substituted;
            this.reason = reason;
        }

        public String requestedFamily() {
            return requestedFamily;
        }

        /** Null when nothing could serve the request at all. */
        public String resolvedFamily() {
            return resolvedFamily;
        }

        public boolean isSubstituted() {
            return substituted;
        }

        /** True when not even a fallback covers the request. */
        public boolean isUnresolved() {
            return resolvedFamily == null;
        }

        /** Plain language, shown at edit time and at export time. */
        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            if (isUnresolved()) {
                return requestedFamily + " → (nothing available)";
            }
            return substituted
                    ? requestedFamily + " → " + resolvedFamily + " (substituted)"
                    : requestedFamily + " (installed)";
        }
    }

    private final Map<String, FontRecord> installedByFamily = new LinkedHashMap<String, FontRecord>();
    private final FontSubstitutionMap substitutions;

    public FontResolver(List<FontRecord> installed, FontSubstitutionMap substitutions) {
        for (FontRecord record : installed) {
            if (record.isEnabled()) {
                installedByFamily.put(normalise(record.family()), record);
            }
        }
        this.substitutions = substitutions;
    }

    /**
     * Resolves one family.
     *
     * @param requestedFamily the family the document names
     * @param scriptCode ISO 15924 script the text is in, or null if unknown
     */
    public Resolution resolve(String requestedFamily, String scriptCode) {
        FontRecord exact = installedByFamily.get(normalise(requestedFamily));
        if (exact != null) {
            if (scriptCode == null || exact.covers(scriptCode)) {
                return new Resolution(requestedFamily, exact.family(), false,
                        "installed and covers the required script");
            }
            // Installed, but cannot render this text. Falling through to the
            // chain is right; pretending it worked would produce tofu boxes.
            Resolution fallback = viaChain(requestedFamily, scriptCode,
                    "\"" + requestedFamily + "\" is installed but does not cover "
                            + scriptCode + " text");
            if (fallback != null) {
                return fallback;
            }
            return new Resolution(requestedFamily, null, true,
                    "\"" + requestedFamily + "\" is installed but does not cover " + scriptCode
                            + ", and no fallback does either. Text in this script will not render.");
        }

        Resolution fallback = viaChain(requestedFamily, scriptCode,
                "\"" + requestedFamily + "\" is not installed");
        if (fallback != null) {
            return fallback;
        }
        return new Resolution(requestedFamily, null, true,
                "\"" + requestedFamily + "\" is not installed and no fallback covers "
                        + (scriptCode == null ? "it" : scriptCode)
                        + ". Install the font, or add a substitution for it.");
    }

    private Resolution viaChain(String requestedFamily, String scriptCode, String why) {
        for (String candidate : substitutions.chainFor(requestedFamily, scriptCode)) {
            FontRecord record = installedByFamily.get(normalise(candidate));
            if (record != null && (scriptCode == null || record.covers(scriptCode))) {
                return new Resolution(requestedFamily, record.family(), true,
                        why + "; substituting \"" + record.family() + "\". The exported document "
                                + "will not look as the template intended.");
            }
        }
        return null;
    }

    /**
     * Resolves every family a document references.
     *
     * <p>Returns all of them, substitutions included, so the render metadata can
     * record exactly what was used — which is what makes a re-render comparable
     * to an archived one.
     */
    public List<Resolution> resolveAll(Set<String> requestedFamilies, String scriptCode) {
        List<Resolution> resolutions = new ArrayList<Resolution>();
        for (String family : requestedFamilies) {
            resolutions.add(resolve(family, scriptCode));
        }
        return Immutables.copyOf(resolutions);
    }

    /** Only the resolutions worth warning about. Empty means a clean render. */
    public List<Resolution> warnings(List<Resolution> resolutions) {
        List<Resolution> warnings = new ArrayList<Resolution>();
        for (Resolution resolution : resolutions) {
            if (resolution.isSubstituted() || resolution.isUnresolved()) {
                warnings.add(resolution);
            }
        }
        return Immutables.copyOf(warnings);
    }

    public Set<String> installedFamilies() {
        Set<String> families = new LinkedHashSet<String>();
        for (FontRecord record : installedByFamily.values()) {
            families.add(record.family());
        }
        return families;
    }

    /**
     * Font family matching is case- and space-insensitive.
     *
     * <p>Documents in the wild name the same family as "Noto Sans CJK KR",
     * "NotoSansCJKkr" and "noto sans cjk kr". Treating those as three different
     * fonts would report substitutions that are not really happening, and users
     * would learn to ignore the warnings.
     */
    private static String normalise(String family) {
        if (family == null) {
            return "";
        }
        return family.toLowerCase(java.util.Locale.ROOT).replace(" ", "").replace("-", "");
    }
}
