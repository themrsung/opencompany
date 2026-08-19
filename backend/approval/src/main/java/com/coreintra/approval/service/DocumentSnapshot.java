package com.coreintra.approval.service;

import com.coreintra.approval.entity.ApprovalDocumentEntity;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The hash that makes the trail mean something.
 *
 * <p>Every recorded action carries the digest of the document as it stood at
 * that moment. Without it the trail proves only that somebody approved
 * something; with it, it proves <em>what</em> they approved. A 지출결의서 whose
 * amount changed after the 부장 signed is then visibly a different document, not
 * a matter of anyone's recollection.
 *
 * <h2>What goes into it</h2>
 *
 * <p>The fields the approver was deciding about — company, type, title, amount
 * and currency, drafter, and the submission instant — plus a digest of the
 * document body supplied by the caller. The body lives in the {@code documents}
 * module and this module deliberately does not know its format; passing the
 * body's own digest in keeps the coupling to a string.
 *
 * <p>Field values are joined with an ASCII unit separator (U+001F), which cannot
 * occur in any of them, so no combination of fields can collide by running
 * together: a title ending in "1" beside an amount of "0" must not hash the same
 * as a title ending in "10" beside nothing.
 */
public final class DocumentSnapshot {

    private static final char UNIT_SEPARATOR = '\u001f';
    private static final String PREFIX = "sha256:";

    private DocumentSnapshot() {
    }

    /**
     * Digest of a document plus its body, as at a submission instant.
     *
     * @param submittedAt passed in rather than read off the document, because
     *        the digest has to exist before {@code markSubmitted} can be given
     *        it — and stamping the instant first would mean hashing a document
     *        that had already been changed
     * @param bodyDigest the documents module's own digest of the body, or null
     *        for a document that is only its header fields
     */
    public static String of(ApprovalDocumentEntity document, BusinessInstant submittedAt,
            String bodyDigest) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, document.id());
        append(canonical, document.companyId());
        append(canonical, document.documentType());
        append(canonical, document.title());
        append(canonical, document.drafterAccountId());
        append(canonical, document.drafterOrgUnitId());
        append(canonical, amountText(document.amount()));
        append(canonical, document.currencyCode());
        append(canonical, submittedAt == null ? null : submittedAt.toWireString());
        append(canonical, bodyDigest);
        return PREFIX + hex(sha256(canonical.toString()));
    }

    /** Digest of a document already stamped with its submission instant. */
    public static String of(ApprovalDocumentEntity document, String bodyDigest) {
        return of(document, document.submittedAt(), bodyDigest);
    }

    /**
     * Digest of an ordered list of field values — a document body, as a caller
     * that has one computes it.
     *
     * <p>Same separator and same algorithm as {@link #of}, so a body digest
     * computed here and folded into a document digest there behave consistently.
     * Nulls and empties are distinct positions rather than absent ones, which is
     * what makes "section 3 was deleted" hash differently from "section 3 was
     * emptied".
     */
    public static String ofParts(java.util.List<String> parts) {
        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            append(canonical, parts.get(i));
        }
        return PREFIX + hex(sha256(canonical.toString()));
    }

    /**
     * Amounts are compared by value, not by text.
     *
     * <p>{@code 5000000} and {@code 5000000.00} are the same money and must
     * produce the same digest, or re-saving a document through a UI that
     * normalises trailing zeros would look like tampering.
     */
    private static String amountText(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }

    private static void append(StringBuilder canonical, String value) {
        canonical.append(Texts.hasText(value) ? value : "").append(UNIT_SEPARATOR);
    }

    private static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(Texts.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JRE ships SHA-256. If this ever fires the platform is broken
            // in a way that hiding behind a fallback digest would only obscure.
            throw new IllegalStateException("SHA-256 is unavailable on this JRE", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            if (value < 0x10) {
                text.append('0');
            }
            text.append(Integer.toHexString(value));
        }
        return text.toString();
    }
}
