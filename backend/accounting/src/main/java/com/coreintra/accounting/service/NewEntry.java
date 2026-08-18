package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.Posting;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import java.util.ArrayList;
import java.util.List;

/**
 * An entry somebody wants written: what happened, when in business time, and the lines.
 *
 * <p>Deliberately not an {@link com.coreintra.accounting.domain.Entry}. An {@code Entry} exists
 * only if it balances, so a request that might not balance cannot be one - which is what makes
 * "debits equal credits or the entry does not exist" true of every object in the system rather
 * than of the ones that got as far as being saved. The id and the batch are assigned by the
 * service, not by the caller, so nothing outside can choose where an entry lands.
 *
 * <h2>The 거래처 lives on the posting, and the entry only defaults it</h2>
 *
 * <p>§9 asks for the sub-ledger dimension "on entries and overridable per posting". It is
 * implemented here as a default applied at construction, not as a stored field on the entry, and
 * that is a deliberate decision worth recording.
 *
 * <p>A stored entry-level 거래처 would be a second copy of a fact the postings already carry, and
 * the two can legitimately differ: a receivable factored to a bank has the customer on the
 * receivable leg and the bank on the cash leg, and an intercompany recharge has a different
 * counterparty on each side. Once they can differ, every report has to decide which one it
 * believes, and a correction that rewrites the postings without rewriting the header leaves the
 * two disagreeing with nothing to say so. Ageing and prepaid schedules read the posting, because
 * the posting is where the money is.
 *
 * <p>So {@link #withClient} fills in the lines that did not name one and leaves the lines that did
 * alone. The convenience of typing the counterparty once survives; the duplicate state does not.
 */
public final class NewEntry {

    private final BusinessInstant postedAt;
    private final String description;
    private final List<Posting> postings;

    public NewEntry(BusinessInstant postedAt, String description, List<Posting> postings) {
        this.postedAt = postedAt;
        this.description = description;
        this.postings = postings == null ? Immutables.<Posting>listOf() : Immutables.copyOf(postings);
    }

    /**
     * An entry whose lines all belong to one 거래처 unless they say otherwise.
     *
     * @param clientId the entry-level default; null or blank leaves every posting as it is
     */
    public static NewEntry withClient(BusinessInstant postedAt, String description,
            List<Posting> postings, String clientId) {
        if (Texts.isBlank(clientId) || postings == null) {
            return new NewEntry(postedAt, description, postings);
        }
        String client = Texts.strip(clientId);
        List<Posting> tagged = new ArrayList<Posting>(postings.size());
        for (Posting posting : postings) {
            // A posting that named its own counterparty keeps it. The header is a default, and a
            // default that overwrote an explicit value would not be one.
            tagged.add(posting.clientId() == null ? posting.withClient(client) : posting);
        }
        return new NewEntry(postedAt, description, tagged);
    }

    public BusinessInstant postedAt() {
        return postedAt;
    }

    public String description() {
        return description;
    }

    public List<Posting> postings() {
        return postings;
    }
}
