package com.coreintra.documents.font;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-editable family → fallback chain, per script.
 *
 * <p>A client whose templates reference 함초롬바탕 — which we may never bundle —
 * maps it to whatever they do have. A client running a CJK + Arabic +
 * Devanagari document set maps each script to a face that covers it, without
 * asking us.
 *
 * <p>Chains are ordered and consulted in order. The first installed family that
 * covers the script wins; the substitution is then named in the warning and
 * recorded in the render metadata.
 */
public final class FontSubstitutionMap implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Map<String, List<String>> byFamily;
    private final Map<String, List<String>> byScript;

    private FontSubstitutionMap(Map<String, List<String>> byFamily,
            Map<String, List<String>> byScript) {
        this.byFamily = byFamily;
        this.byScript = byScript;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The chain to try for a request: family-specific entries first, then the
     * script-level default.
     */
    public List<String> chainFor(String requestedFamily, String scriptCode) {
        List<String> chain = new ArrayList<String>();
        List<String> familyChain = byFamily.get(key(requestedFamily));
        if (familyChain != null) {
            chain.addAll(familyChain);
        }
        if (scriptCode != null) {
            List<String> scriptChain = byScript.get(scriptCode);
            if (scriptChain != null) {
                for (String candidate : scriptChain) {
                    if (!chain.contains(candidate)) {
                        chain.add(candidate);
                    }
                }
            }
        }
        return Immutables.copyOf(chain);
    }

    public Map<String, List<String>> familyChains() {
        return Immutables.mapCopyOf(byFamily);
    }

    public Map<String, List<String>> scriptChains() {
        return Immutables.mapCopyOf(byScript);
    }

    private static String key(String family) {
        return family == null
                ? ""
                : family.toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
    }

    public static final class Builder {
        private final Map<String, List<String>> byFamily = new LinkedHashMap<String, List<String>>();
        private final Map<String, List<String>> byScript = new LinkedHashMap<String, List<String>>();

        public Builder mapFamily(String requestedFamily, String... fallbacks) {
            byFamily.put(key(requestedFamily), Immutables.listOfArray(fallbacks));
            return this;
        }

        public Builder mapScript(String scriptCode, String... fallbacks) {
            byScript.put(scriptCode, Immutables.listOfArray(fallbacks));
            return this;
        }

        public FontSubstitutionMap build() {
            return new FontSubstitutionMap(
                    Immutables.mapCopyOf(byFamily), Immutables.mapCopyOf(byScript));
        }
    }

    /**
     * The shipped default chain, leading with Pretendard.
     *
     * <p>Pretendard (SIL OFL 1.1) is free, redistributable and commercially
     * usable, so it can be bundled in both the managed and on-prem builds with
     * no per-client licence conversation. Its Korean and Latin are designed
     * together, so mixed 한/영 lines do not ransom-note the way Noto plus a
     * separate Latin face does.
     *
     * <p>Noto CJK and Noto's broad coverage set sit behind it for scripts
     * Pretendard does not cover. 함초롬바탕/함초롬돋움 are mapped rather than
     * bundled — they are Hancom-licensed, and a client who owns 한글 installs
     * them themselves.
     */
    public static FontSubstitutionMap shippedDefault() {
        return builder()
                .mapFamily("함초롬바탕", "Pretendard", "Noto Serif CJK KR", "Noto Sans CJK KR")
                .mapFamily("함초롬돋움", "Pretendard", "Noto Sans CJK KR")
                .mapFamily("맑은 고딕", "Pretendard", "Noto Sans CJK KR")
                .mapFamily("굴림", "Pretendard", "Noto Sans CJK KR")
                .mapFamily("바탕", "Noto Serif CJK KR", "Pretendard")
                .mapFamily("Malgun Gothic", "Pretendard", "Noto Sans CJK KR")
                .mapScript("Hang", "Pretendard", "Noto Sans CJK KR", "Noto Serif CJK KR")
                .mapScript("Latn", "Pretendard", "Noto Sans", "DejaVu Sans")
                .mapScript("Hani", "Noto Sans CJK KR", "Noto Sans CJK SC")
                .mapScript("Jpan", "Pretendard JP", "Noto Sans CJK JP")
                .build();
    }
}
