package com.coreintra.app.api.documents;

import com.coreintra.compat.Texts;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentVersionEntity;
import com.coreintra.documents.entity.RenderFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The identity of an export <em>request</em>, and the URLs that answer it.
 *
 * <h2>Why this is not {@code RenderFingerprint}</h2>
 *
 * <p>{@code RenderFingerprint} identifies an <em>output</em>: it includes the
 * renderer version and the resolved font set, which only the worker knows once
 * it has run. What the queue needs is the identity of the <em>ask</em>, so that
 * two people pressing Export on the same version, format and locale join one job
 * instead of starting two LibreOffices. That is decidable before anything runs,
 * and it is what this computes.
 *
 * <p>The two are not expected to match, and nothing should be written that
 * assumes they do: the archived render carries its own fingerprint, and the job
 * carries this one.
 *
 * <p>Shared by the REST controller and the MCP tool so that an export asked for
 * over MCP joins the job an export asked for in the browser already started.
 * Two fingerprint implementations would mean two jobs for one document, which is
 * the exact cost the idempotency key exists to avoid.
 */
public final class ExportRequests {

    private ExportRequests() {
    }

    public static String fingerprint(DocumentEntity document, DocumentVersionEntity version,
            RenderFormat target, String locale) {
        String material = "request/v1\nsource=" + version.blobSha256()
                + "\ntemplate=" + document.templateId() + "@" + document.templateVersionNo()
                + "\nlocale=" + (Texts.isBlank(locale) ? "ko" : Texts.strip(locale))
                + "\ntarget=" + target.name();
        return sha256Hex(material);
    }

    /** Where the stored bytes of a version are downloaded from. */
    public static String nativeContentUrl(String documentId, int versionNo) {
        return "/api/v1/documents/" + documentId + "/versions/" + versionNo + "/content";
    }

    /** Where an archived export is downloaded from. */
    public static String exportContentUrl(String documentId, int versionNo, RenderFormat target) {
        return "/api/v1/documents/" + documentId + "/versions/" + versionNo
                + "/export/content?format=" + target.name();
    }

    /** Where a queued job is polled. */
    public static String pollUrl(String documentId, String jobId) {
        return "/api/v1/documents/" + documentId + "/export/jobs/" + jobId;
    }

    private static String sha256Hex(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (int i = 0; i < hash.length; i++) {
                int value = hash[i] & 0xff;
                if (value < 0x10) {
                    hex.append('0');
                }
                hex.append(Integer.toHexString(value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE this runs on", e);
        }
    }
}
