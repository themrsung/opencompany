package com.coreintra.accounting.domain;

/**
 * Why a batch of entries was written together.
 *
 * <h2>Only CLOSING changes what a report says</h2>
 *
 * <p>The rest are provenance: they tell a reader where a hundred entries came from without
 * making the reader open them. {@link #CLOSING} is different. Closing entries move the year's
 * income and expense into equity, so an income statement that included them would net to zero
 * and report that a profitable year had done nothing at all. A closed year must still report
 * what it earned, so the reports filter this kind out and only this one.
 *
 * <p>Spelt {@code AMORTIZATION} rather than the British form used in the prose here, because
 * the name travels: it is the value stored in {@code accounting_batch.kind} and the one the
 * API and MCP tools already name.
 */
public enum BatchKind {

    /** A person wrote these entries and meant each one. */
    MANUAL,

    /** Loaded from somewhere else - a bank file, a previous system. */
    IMPORT,

    /** Moves a period's income and expense into equity. Excluded from income statements. */
    CLOSING,

    /** A repeating charge posted for this period. */
    RECURRING,

    /** Posted from an amortisation schedule that a human previewed and accepted. */
    AMORTIZATION,

    /** Restates foreign-currency balances at a rate the business supplied. */
    FX_REVALUATION;

    /** True for the one kind the income statement leaves out. */
    public boolean isClosing() {
        return this == CLOSING;
    }

    /**
     * Reads a stored kind.
     *
     * @throws IllegalArgumentException naming the value, because an unreadable batch kind means
     *     a report is about to be silently wrong about which entries it should have skipped
     */
    public static BatchKind fromStored(String value) {
        if (value == null) {
            throw new IllegalArgumentException("a batch needs a kind");
        }
        for (BatchKind kind : values()) {
            if (kind.name().equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("\"" + value + "\" is not a batch kind. Known kinds are "
                + "MANUAL, IMPORT, CLOSING, RECURRING, AMORTIZATION and FX_REVALUATION.");
    }
}
