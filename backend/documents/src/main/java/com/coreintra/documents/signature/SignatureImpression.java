package com.coreintra.documents.signature;

/**
 * A 도장 on its way into a render, and nowhere else.
 *
 * <h2>Why this type exists instead of a byte array</h2>
 *
 * <p>A person's seal is a forgery kit. Brief 6.3 requires that it be composited
 * server-side at render time and <b>never served to the browser as a reusable asset</b>,
 * and the reliable way to hold that line is not a rule in a document - it is having
 * nothing to return.
 *
 * <p>So this class has no public accessor for its content. {@link SignatureService}
 * returns it; {@link SignatureCompositor} - in this package, and therefore the only code
 * that can reach the bytes - writes them into a render workspace. A controller can hold
 * one of these and can do exactly two things with it: pass it to the compositor, or drop
 * it. There is no third option to write by mistake during a refactor, which is when this
 * kind of mistake actually gets made.
 *
 * <p><b>What this does not do.</b> Java 8 has no modules, so a determined caller inside
 * {@code com.coreintra.documents.signature} could add a class that reads the field. The
 * guarantee is against accident and drift, not against a developer who has decided to
 * leak a seal; that one is caught in review, and {@code signature_impression_use} records
 * every impression either way.
 */
public final class SignatureImpression {

    private final String signatureImageId;

    private final String contentType;

    private final byte[] image;

    SignatureImpression(String signatureImageId, String contentType, byte[] image) {
        this.signatureImageId = signatureImageId;
        this.contentType = contentType;
        this.image = image;
    }

    /** Which enrolled seal this is. Safe to log: it names a row, not a person's signature. */
    public String signatureImageId() {
        return signatureImageId;
    }

    public String contentType() {
        return contentType;
    }

    /** Size only. Enough to log or to size a layout box; not enough to reconstruct anything. */
    public int byteLength() {
        return image.length;
    }

    /** Package-private, and the only door. See the class javadoc for why it stays shut. */
    byte[] image() {
        return image;
    }

    @Override
    public String toString() {
        return "SignatureImpression(" + signatureImageId + ", " + contentType + ", "
                + image.length + " bytes)";
    }
}
