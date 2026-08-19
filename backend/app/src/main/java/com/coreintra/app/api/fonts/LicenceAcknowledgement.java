package com.coreintra.app.api.fonts;

/**
 * The words a client agrees to before a font is installed (§6.9).
 *
 * <h2>Why the text is server-side and versioned</h2>
 *
 * <p>The brief asks for "a checkbox with real words, not fine print", and for a
 * record of who agreed and to what. A record of an agreement whose wording the
 * client chose is not evidence of anything: an uploader could send
 * {@code "ok"} and the audit trail would say they acknowledged "ok". So the
 * wording lives here, the client fetches it, and the upload must echo it back
 * exactly. What is recorded is then provably the text that was displayed.
 *
 * <p>{@link #VERSION} exists so the wording can be improved without making
 * historical records ambiguous: an old font row carries the words that were on
 * the screen the day it was installed, and the version says which they were.
 *
 * <h2>What it does not say</h2>
 *
 * <p>It does not say the font is safe to use, and it does not promise any check.
 * We do not read the licence, we do not verify the claim, and the embedding bits
 * in the font's own metadata are shown read-only beside it precisely so the
 * client can see what they are agreeing about.
 */
public final class LicenceAcknowledgement {

    /** Bump when the wording changes. Never edit a released version's text. */
    public static final String VERSION = "2026-08-fonts-1";

    /** Korean is the default locale, so the Korean text is the one most users read. */
    public static final String TEXT_KO =
            "이 글꼴을 설치하고 문서에 포함(embedding)할 권리를 보유하고 있음을 확인합니다. "
            + "당사는 글꼴 라이선스를 확인하지 않으며 이에 대해 어떠한 보증이나 면책도 제공하지 않습니다. "
            + "글꼴 자체에 기록된 포함 제한(fsType)을 준수할 책임은 전적으로 설치자에게 있습니다.";

    public static final String TEXT_EN =
            "I confirm that this organisation holds the rights to install this font and to embed "
            + "it in documents. We do not verify font licences and provide no warranty or "
            + "indemnity for them. Honouring the embedding restrictions recorded in the font's "
            + "own metadata (fsType) is entirely the installer's responsibility.";

    private LicenceAcknowledgement() {
    }

    /**
     * The exact text recorded against a font installed under this version.
     *
     * <p>Both languages, joined, because the record must not depend on which
     * language the uploader happened to be reading: the agreement is to the
     * statement, and the statement is the same in both.
     */
    public static String recordedText() {
        return "[" + VERSION + "] " + TEXT_KO + " / " + TEXT_EN;
    }

    /** True when the echoed text is one this server actually displays. */
    public static boolean matches(String echoed) {
        if (echoed == null) {
            return false;
        }
        String trimmed = echoed.trim();
        return TEXT_KO.equals(trimmed) || TEXT_EN.equals(trimmed)
                || recordedText().equals(trimmed);
    }
}
