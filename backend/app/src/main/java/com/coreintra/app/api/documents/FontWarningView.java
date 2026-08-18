package com.coreintra.app.api.documents;

import com.coreintra.documents.font.FontResolver;

/**
 * What a requested font family actually resolved to.
 *
 * <p>§6.9 is explicit that a missing font must never fall back silently.
 * fontconfig always answers — asked for Pretendard it will hand back DejaVu Sans
 * and say nothing — and a 지출결의서 set in a Chinese face is not obviously
 * broken, it is subtly wrong, and nobody finds out until it has been signed.
 *
 * <p>So every resolution is reportable and every substitution is named: what was
 * asked for, what was used instead, and why. {@link #isSubstituted()} and
 * {@link #isUnresolved()} are separate because they need different words in the
 * interface — one is "it will look different", the other is "it will not render".
 */
public class FontWarningView {

    private final String requestedFamily;
    private final String resolvedFamily;
    private final boolean substituted;
    private final boolean unresolved;
    private final String reason;

    public FontWarningView(FontResolver.Resolution resolution) {
        this.requestedFamily = resolution.requestedFamily();
        this.resolvedFamily = resolution.resolvedFamily();
        this.substituted = resolution.isSubstituted();
        this.unresolved = resolution.isUnresolved();
        this.reason = resolution.reason();
    }

    public String getRequestedFamily() {
        return requestedFamily;
    }

    /** Null when nothing installed could serve the request at all. */
    public String getResolvedFamily() {
        return resolvedFamily;
    }

    public boolean isSubstituted() {
        return substituted;
    }

    /** True when not even a fallback chain covers it: expect tofu boxes. */
    public boolean isUnresolved() {
        return unresolved;
    }

    /** Plain language, shown at edit time and at export time. */
    public String getReason() {
        return reason;
    }
}
