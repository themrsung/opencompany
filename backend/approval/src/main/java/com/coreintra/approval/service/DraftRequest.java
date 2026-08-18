package com.coreintra.approval.service;

import com.coreintra.compat.Texts;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the drafter is asking for.
 *
 * <h2>The business date is the drafter's, not the server's</h2>
 *
 * <p>Someone filing a 지출결의서 at 01:00 on the 31st for the shift that began on
 * the 30th is drafting a document that belongs to the 30th. That date decides
 * which template applies, which org chart the line resolves against, and which
 * permissions are checked — so it is an input, never {@code LocalDate.now()}.
 *
 * <p>The amount is {@link BigDecimal} and is built from a string by the caller.
 * It drives threshold rules, so a rounding error changes who is required to sign
 * (ADR 0004).
 */
public final class DraftRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String companyId;
    private final String documentType;
    private final String title;
    private final BigDecimal amount;
    private final String currencyCode;
    private final LocalDate businessDate;

    public DraftRequest(String companyId, String documentType, String title, BigDecimal amount,
            String currencyCode, LocalDate businessDate) {
        if (Texts.isBlank(companyId)) {
            throw new IllegalArgumentException("companyId");
        }
        if (Texts.isBlank(documentType)) {
            throw new IllegalArgumentException(
                    "documentType decides which 결재선 applies; a document without one cannot be "
                            + "routed");
        }
        if (Texts.isBlank(title)) {
            throw new IllegalArgumentException(
                    "제목을 입력해 주십시오. (A document needs a title: it is what an approver sees "
                            + "in their inbox before opening anything.)");
        }
        if (businessDate == null) {
            throw new NullPointerException("businessDate");
        }
        if (amount != null && Texts.isBlank(currencyCode)) {
            throw new IllegalArgumentException(
                    "an amount without a currency is not money. Give the currency code beside it.");
        }
        this.companyId = companyId;
        this.documentType = documentType;
        this.title = title;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.businessDate = businessDate;
    }

    public String companyId() {
        return companyId;
    }

    public String documentType() {
        return documentType;
    }

    public String title() {
        return title;
    }

    /** Null for a document with no money on it. */
    public BigDecimal amount() {
        return amount;
    }

    public String currencyCode() {
        return currencyCode;
    }

    public LocalDate businessDate() {
        return businessDate;
    }
}
