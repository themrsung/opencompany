package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.Posting;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import java.util.List;

/**
 * An entry somebody wants written: what happened, when in business time, and the lines.
 *
 * <p>Deliberately not an {@link com.coreintra.accounting.domain.Entry}. An {@code Entry} exists
 * only if it balances, so a request that might not balance cannot be one - which is what makes
 * "debits equal credits or the entry does not exist" true of every object in the system rather
 * than of the ones that got as far as being saved. The id and the batch are assigned by the
 * service, not by the caller, so nothing outside can choose where an entry lands.
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
